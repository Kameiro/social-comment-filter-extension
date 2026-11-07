package dev.local.commentcollector;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Xiaohongshu (com.xingin.xhs) support. Note-detail pages are not androidx-fragment based, so the current note is the
 * most recent {@code NoteFeed} whose {@code getId()} was read, refined by walking the resumed activity's object graph
 * when possible. Double taps are detected by {@link DoubleTapDetector}. NOTE: needs a logged-in account to exercise.
 */
final class XhsHooks {
    private static final String TAG = "CommentCollector";
    private static final AtomicBoolean installed = new AtomicBoolean();
    private final XposedInterface xposed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Context context;
    private volatile Activity activity;
    private volatile WeakReference<Object> lastNote;
    private volatile long lastNoteAt;
    private final WeakIdentityMap<Boolean> recorded = new WeakIdentityMap<>();
    private final CurrentPostResolver resolver = new CurrentPostResolver();
    private DoubleTapDetector detector;

    XhsHooks(XposedInterface xposed) { this.xposed = xposed; }

    void install(ClassLoader loader) {
        if (!installed.compareAndSet(false, true)) return;
        resolver.bind(loader, XhsMapper.NOTE_CLASS);
        try {
            Method start = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            xposed.hook(start).intercept(chain -> { Object result = chain.proceed(); attach((Application) chain.getArg(0)); return result; });
        } catch (Throwable error) { Log.w(TAG, "xhs_lifecycle_hook_failed", error); }
        try {
            Class<?> note = loader.loadClass(XhsMapper.NOTE_CLASS);
            // NoteFeed's id is a public field in 9.51, so hook whichever String getters exist (no-arg, instance).
            int hooked = 0;
            for (Method method : note.getDeclaredMethods()) {
                if (hooked >= 6 || method.getParameterCount() != 0 || method.getReturnType() != String.class
                    || java.lang.reflect.Modifier.isStatic(method.getModifiers()) || !method.getName().startsWith("get")) continue;
                xposed.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self != null) { lastNote = new WeakReference<>(self); lastNoteAt = System.currentTimeMillis(); remember(self); }
                    return result;
                });
                hooked++;
            }
            Log.i(TAG, "xhs_note_getters_hooked=" + hooked);
        } catch (Throwable error) { Log.w(TAG, "xhs_note_hook_unavailable", error); }
        detector = new DoubleTapDetector(xposed, new DoubleTapDetector.Handler() {
            @Override public Activity activity() { return activity; }
            @Override public boolean onDoubleTap(Activity host, float x, float y) { return showPost(host, x, y); }
        });
        detector.install();
        Log.i(TAG, "xhs_hooks_installed");
    }

    private boolean showPost(Activity host, float x, float y) {
        try {
            Object note = resolver.resolve(host, x, y, null);
            if (note == null) { WeakReference<Object> ref = lastNote; note = ref == null || System.currentTimeMillis() - lastNoteAt > 60_000 ? null : ref.get(); }
            if (note == null) { Log.i(TAG, "xhs_double_tap_no_note"); return false; }
            JSONObject post = XhsMapper.post(note);
            if (post == null) return false;
            Log.i(TAG, "post_info_triggered platform=xhs post=" + post.optString("postId"));
            JSONObject settings = VideoInfoSettings.current(host);
            main.post(() -> PostInfoPanel.show(host, post, settings));
            return true;
        } catch (Throwable error) { Log.w(TAG, "xhs_post_map_failed", error); return false; }
    }

    private void remember(Object note) {
        Context app = context;
        if (app == null) return;
        synchronized (recorded) { if (recorded.put(note, Boolean.TRUE) != null) return; }
        new Thread(() -> {
            try {
                JSONObject post = XhsMapper.post(note);
                if (post != null) CollectorClient.call(app, "record_post", new JSONObject().put("post", post));
            } catch (Throwable ignored) {}
        }, "xhs-record-post").start();
    }

    private void attach(Application app) {
        if (context != null) return;
        context = app;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity value) { activity = value; }
            @Override public void onActivityPaused(Activity value) { if (activity == value) activity = null; }
            @Override public void onActivityCreated(Activity value, Bundle state) { if (detector != null) detector.hook(value.getClass()); }
            @Override public void onActivityStarted(Activity value) {}
            @Override public void onActivityStopped(Activity value) {}
            @Override public void onActivitySaveInstanceState(Activity value, Bundle state) {}
            @Override public void onActivityDestroyed(Activity value) {}
        });
        Log.i(TAG, "xhs_application_attached");
    }
}
