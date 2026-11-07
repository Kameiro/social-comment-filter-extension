package dev.local.commentcollector;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the QPhoto shown under a touch point on demand.
 *
 * Kuaishou 14.8 no longer calls QPhoto.getCaption() reliably when a feed page becomes visible, so a
 * "last seen" getter cache is often empty or points at a preloaded neighbour. Instead we track resumed
 * androidx fragments (cheap: only on lifecycle changes) and, only when a double tap happens, walk the
 * innermost resumed fragment that contains the touch point to find its QPhoto field.
 */
final class CurrentPostResolver {
    private static final String TAG = "CommentCollector";
    private static final int MAX_FRAGMENTS = 48;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_NODES = 600;

    private final List<WeakReference<Object>> fragments = new ArrayList<>();
    private final Map<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();
    private volatile Class<?> photoType;
    private volatile Class<?> fragmentType;

    void bind(ClassLoader loader) {
        try { photoType = loader.loadClass("com.yxcorp.gifshow.entity.QPhoto"); } catch (Exception ignored) {}
        try { fragmentType = loader.loadClass("androidx.fragment.app.Fragment"); } catch (Exception ignored) {}
    }

    Class<?> fragmentType() { return fragmentType; }

    void onFragmentResumed(Object fragment) {
        if (fragment == null) return;
        synchronized (fragments) {
            for (Iterator<WeakReference<Object>> it = fragments.iterator(); it.hasNext(); ) {
                Object value = it.next().get();
                if (value == null || value == fragment) it.remove();
            }
            fragments.add(new WeakReference<>(fragment));
            while (fragments.size() > MAX_FRAGMENTS) fragments.remove(0);
        }
    }

    /** Returns the QPhoto instance for the post under (rawX, rawY), or null. Main thread only. */
    Object resolve(Activity activity, float rawX, float rawY, View touched) {
        Class<?> photo = photoType;
        if (activity == null || photo == null || activity.getWindow() == null) return null;
        View decor = activity.getWindow().getDecorView();
        List<Object[]> candidates = new ArrayList<>();
        List<Object> snapshot = new ArrayList<>();
        synchronized (fragments) {
            for (WeakReference<Object> ref : fragments) { Object value = ref.get(); if (value != null) snapshot.add(value); }
        }
        Rect bounds = new Rect();
        for (Object fragment : snapshot) {
            Object resumed = SnapshotMapper.invoke(fragment, "isResumed", new Class<?>[0]);
            if (!Boolean.TRUE.equals(resumed)) continue;
            Object hidden = SnapshotMapper.invoke(fragment, "isHidden", new Class<?>[0]);
            if (Boolean.TRUE.equals(hidden)) continue;
            Object value = SnapshotMapper.field(fragment, "mView");
            if (!(value instanceof View)) continue;
            View view = (View) value;
            if (view.getRootView() != decor || !view.isShown()) continue;
            if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(Math.round(rawX), Math.round(rawY))) continue;
            candidates.add(new Object[]{fragment, depth(view)});
        }
        // Innermost fragment first: that is the page actually under the finger (not the tab host).
        Collections.sort(candidates, (a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        for (Object[] candidate : candidates) {
            Object found = find(candidate[0], photo);
            if (found != null) {
                Log.i(TAG, "current_post_resolved source=fragment fragment=" + candidate[0].getClass().getName());
                return found;
            }
        }
        // Fallback: objects attached to the touched view hierarchy through View tags.
        for (View current = touched; current != null; ) {
            Object tag = current.getTag();
            if (tag != null && !(tag instanceof CharSequence)) {
                Object found = photo.isInstance(tag) ? tag : find(tag, photo);
                if (found != null) {
                    Log.i(TAG, "current_post_resolved source=view_tag view=" + current.getClass().getName());
                    return found;
                }
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        StringBuilder names = new StringBuilder();
        for (Object[] candidate : candidates) names.append(candidate[0].getClass().getName()).append(',');
        Log.i(TAG, "current_post_unresolved fragments=" + snapshot.size() + " hit=" + names);
        return null;
    }

    private static int depth(View view) {
        int depth = 0;
        for (Object parent = view.getParent(); parent instanceof View; parent = ((View) parent).getParent()) depth++;
        return depth;
    }

    private Object find(Object root, Class<?> photo) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root, 0});
        visited.add(root);
        int nodes = 0;
        while (!queue.isEmpty() && nodes++ < MAX_NODES) {
            Object[] item = queue.poll();
            Object object = item[0];
            int level = (Integer) item[1];
            for (Field field : fields(object.getClass())) {
                Object value;
                try { value = field.get(object); } catch (Throwable ignored) { continue; }
                if (value == null) continue;
                if (photo.isInstance(value)) return value;
                if (level + 1 >= MAX_DEPTH || visited.contains(value) || !traversable(value)) continue;
                visited.add(value);
                queue.add(new Object[]{value, level + 1});
            }
        }
        return null;
    }

    private boolean traversable(Object value) {
        if (value instanceof View || value instanceof Context || value instanceof CharSequence || value instanceof Number) return false;
        Class<?> fragment = fragmentType;
        // Do not hop into sibling/parent fragments: they belong to other pages.
        if (fragment != null && fragment.isInstance(value)) return false;
        if (value.getClass().isArray() || value instanceof Iterable || value instanceof Map) return false;
        Class<?> photo = photoType;
        return photo != null && value.getClass().getClassLoader() == photo.getClassLoader() && appClass(value.getClass().getName());
    }

    private static boolean appClass(String name) {
        // Kuaishou classes are often obfuscated into short packages, so exclude libraries instead of
        // whitelisting app packages.
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.") || name.startsWith("androidx.")
            || name.startsWith("kotlin.") || name.startsWith("kotlinx.") || name.startsWith("com.google.") || name.startsWith("okhttp3.")
            || name.startsWith("io.reactivex.") || name.startsWith("retrofit2.") || name.startsWith("dev.local."));
    }

    private Field[] fields(Class<?> type) {
        Field[] cached = fieldCache.get(type);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            String name = current.getName();
            if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("kotlin.")) continue;
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try { field.setAccessible(true); out.add(field); } catch (Throwable ignored) {}
            }
        }
        Field[] result = out.toArray(new Field[0]);
        fieldCache.put(type, result);
        return result;
    }
}
