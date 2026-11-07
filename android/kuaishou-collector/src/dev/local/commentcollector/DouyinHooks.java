package dev.local.commentcollector;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.json.JSONObject;

/**
 * Douyin (com.ss.android.ugc.aweme) support.
 *
 * The feed's {@code com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel} owns the double-tap handler — an
 * obfuscated method with signature {@code (MotionEvent, IFeedViewHolder, Aweme) void} — and exposes
 * {@code getCurrentAweme()}. Intercepting that handler gives the exact post and suppresses the like, with no
 * gesture heuristics. Methods are matched by signature, not name, so it survives Douyin's per-version renames.
 */
final class DouyinHooks {
    private static final String TAG = "CommentCollector";
    private final XposedInterface xposed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Context context;
    private volatile Activity activity;
    private final WeakIdentityMap<Boolean> recorded = new WeakIdentityMap<>();

    DouyinHooks(XposedInterface xposed) { this.xposed = xposed; }

    private static final java.util.concurrent.atomic.AtomicBoolean installed = new java.util.concurrent.atomic.AtomicBoolean();

    void install(ClassLoader loader) {
        // Douyin reports onPackageReady more than once for the same process; hook only once.
        if (!installed.compareAndSet(false, true)) return;
        try {
            Method applicationStart = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            xposed.hook(applicationStart).intercept(chain -> {
                Object result = chain.proceed();
                attach((Application) chain.getArg(0));
                return result;
            });
        } catch (Throwable error) { Log.w(TAG, "douyin_lifecycle_hook_failed", error); }

        Class<?> panel, aweme, holder;
        try {
            panel = loader.loadClass("com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel");
            aweme = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
            holder = loader.loadClass("com.ss.android.ugc.aweme.feed.adapter.IFeedViewHolder");
        } catch (Throwable error) { Log.w(TAG, "douyin_feed_classes_unavailable", error); return; }

        int doubleTapHooks = 0;
        for (Method method : panel.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            boolean touch = types.length > 0 && types[0] == MotionEvent.class;
            if (!touch) continue;
            boolean doubleTap = types.length == 3 && types[1] == holder && types[2] == aweme && method.getReturnType() == void.class;
            String signature = method.getName() + Arrays.toString(types);
            try {
                xposed.hook(method).intercept(chain -> {
                    MotionEvent event = (MotionEvent) chain.getArg(0);
                    Log.i(TAG, "douyin_panel_touch method=" + signature + " action=" + (event == null ? -1 : event.getActionMasked())
                        + " candidate=" + doubleTap);
                    if (doubleTap && chain.getArg(2) != null && showPost(chain.getArg(2))) return null; // handled: no like
                    return chain.proceed();
                });
                if (doubleTap) doubleTapHooks++;
            } catch (Throwable error) { Log.w(TAG, "douyin_touch_hook_failed " + signature, error); }
        }
        Log.i(TAG, "douyin_hooks_installed doubleTapHandlers=" + doubleTapHooks);
        // Capture the live panel instance from any of its methods, so we can ask it for the current Aweme later.
        int tracked = 0;
        for (Method method : panel.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()) continue;
            if (method.getName().equals("getCurrentAweme")) continue;
            try {
                xposed.hook(method).intercept(chain -> {
                    Object self = chain.getThisObject();
                    if (self != null) { Object old = livePanel == null ? null : livePanel.get(); if (old != self) { livePanel = new java.lang.ref.WeakReference<>(self); } }
                    return chain.proceed();
                });
                tracked++;
            } catch (Throwable ignored) {}
        }
        Log.i(TAG, "douyin_panel_methods_tracked=" + tracked);

        installDoubleTap();

        // Record every post that becomes current, so browsed posts show up in the database like on Kuaishou.
        try {
            Method current = panel.getDeclaredMethod("getCurrentAweme");
            xposed.hook(current).intercept(chain -> {
                Object result = chain.proceed();
                if (result != null) remember(result);
                return result;
            });
        } catch (Throwable error) { Log.w(TAG, "douyin_current_aweme_hook_failed", error); }
    }

    private static volatile java.lang.ref.WeakReference<Object> livePanel;

    private DoubleTapDetector detector;

    private void installDoubleTap() {
        detector = new DoubleTapDetector(xposed, new DoubleTapDetector.Handler() {
            @Override public Activity activity() { return activity; }
            @Override public boolean onDoubleTap(Activity host, float x, float y) {
                Object aweme = currentAweme();
                Log.i(TAG, "douyin_double_tap aweme=" + (aweme != null));
                return aweme != null && showPost(aweme);
            }
        });
        detector.install();
    }

    private static Object currentAweme() {
        try {
            Object panel = livePanel == null ? null : livePanel.get();
            if (panel == null) return null;
            Method m = panel.getClass().getMethod("getCurrentAweme");
            return m.invoke(panel);
        } catch (Throwable error) { Log.w(TAG, "douyin_current_aweme_failed", error); return null; }
    }

    /** Set to true once the double-tap candidate is confirmed in testing; until then only logs. */
    static volatile boolean interceptDoubleTap = true;

    private boolean showPost(Object aweme) {
        if (!interceptDoubleTap) return false;
        Activity current = activity;
        if (current == null || current.isFinishing()) return false;
        try {
            JSONObject post = DouyinMapper.post(aweme);
            if (post == null) return false;
            Log.i(TAG, "post_info_triggered platform=douyin post=" + post.optString("postId"));
            new Thread(() -> {
                JSONObject settings = VideoInfoSettings.load(current);
                main.post(() -> PostInfoPanel.show(current, post, settings));
            }, "post-info-settings").start();
            return true;
        } catch (Throwable error) { Log.w(TAG, "douyin_post_map_failed", error); return false; }
    }

    private void remember(Object aweme) {
        Context app = context;
        if (app == null) return;
        synchronized (recorded) { if (recorded.put(aweme, Boolean.TRUE) != null) return; }
        new Thread(() -> {
            try {
                JSONObject post = DouyinMapper.post(aweme);
                if (post != null) CollectorClient.call(app, "record_post", new JSONObject().put("post", post));
            } catch (Throwable ignored) {}
        }, "douyin-record-post").start();
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
        Log.i(TAG, "douyin_application_attached");
    }
}
