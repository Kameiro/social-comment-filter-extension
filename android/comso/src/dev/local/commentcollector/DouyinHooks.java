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
    private final CurrentPostResolver resolver = new CurrentPostResolver();
    private volatile long lastPanelTouchAt;
    private volatile String lastCommentPostId = "";

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

        resolver.bind(loader, "com.ss.android.ugc.aweme.feed.model.Aweme");
        Class<?> fragment = resolver.fragmentType();
        if (fragment != null) {
            try {
                xposed.hook(fragment.getDeclaredMethod("onResume")).intercept(chain -> {
                    Object result = chain.proceed();
                    resolver.onFragmentResumed(chain.getThisObject());
                    return result;
                });
                Log.i(TAG, "douyin_fragment_tracking_installed");
            } catch (Throwable error) { Log.w(TAG, "douyin_fragment_tracking_unavailable", error); }
        }

        DouyinComments.onPostObserved = this::onCommentPostObserved;
        DouyinNativePager.onPostCaptured = this::onNativePostCaptured;

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
                    lastPanelTouchAt = System.currentTimeMillis();
                    if (doubleTap && chain.getArg(2) != null && showPost(chain.getArg(2))) return null; // handled: no like
                    return chain.proceed();
                });
                if (doubleTap) doubleTapHooks++;
            } catch (Throwable error) { Log.w(TAG, "douyin_touch_hook_failed " + signature, error); }
        }
        directDoubleTapAvailable = doubleTapHooks > 0;
        Log.i(TAG, "douyin_hooks_installed doubleTapHandlers=" + doubleTapHooks);
        // Capture the live panel instance from any of its methods, so we can ask it for the current Aweme later.
        int tracked = 0;
        for (Method method : panel.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()) continue;
            if (method.getName().equals("getCurrentAweme")) continue;
            try {
                xposed.hook(method).intercept(chain -> {
                    Object self = chain.getThisObject();
                    if (self != null && isCurrentPanel(self)) {
                        Object old = livePanel == null ? null : livePanel.get();
                        if (old != self) { livePanel = new java.lang.ref.WeakReference<>(self); }
                    }
                    return chain.proceed();
                });
                tracked++;
            } catch (Throwable ignored) {}
        }
        Log.i(TAG, "douyin_panel_methods_tracked=" + tracked);

        installDoubleTap();
        hostLoader = loader;
        awaitApplication(0);
        DouyinComments.hook(xposed, loader, list -> {
            Context app = context;
            if (app == null) return;
            synchronized (DouyinHooks.this) { if (comments == null) comments = new DouyinComments(app, () -> activity); }
            DouyinComments target = comments;
            String fallbackPost = resolveCurrentPostId();
            new Thread(() -> target.observe(list, fallbackPost), "douyin-comments").start();
        });

        DouyinProfiles.hook(xposed, loader, user -> {
            Context app = context;
            if (app == null) return;
            if (profiles == null) { profiles = new DouyinProfiles(app, () -> activity); profiles.startDriver(); }
            profiles.observe(user);
        });

        DouyinProfiles.hookPosts(xposed, loader, loaded -> {
            Context app = context;
            if (app == null) return;
            if (profiles == null) { profiles = new DouyinProfiles(app, () -> activity); profiles.startDriver(); }
            ensurePager();
            profiles.observePost(loaded);
        });

        DouyinProfiles.hookPostLists(xposed, loader, loaded -> {
            Context app = context;
            if (app == null) return;
            if (profiles == null) { profiles = new DouyinProfiles(app, () -> activity); profiles.startDriver(); }
            ensurePager();
            profiles.observePost(loaded);
        });

        // Record every post that becomes current, so browsed posts show up in the database like on Kuaishou.
        try {
            Method current = panel.getDeclaredMethod("getCurrentAweme");
            xposed.hook(current).intercept(chain -> {
                Object result = chain.proceed();
                if (result != null) {
                    Object self = chain.getThisObject();
                    if (isCurrentPanel(self)) remember(result); else recordOnly(result);
                }
                return result;
            });
        } catch (Throwable error) { Log.w(TAG, "douyin_current_aweme_hook_failed", error); }
    }

    private static volatile java.lang.ref.WeakReference<Object> livePanel;
    private volatile boolean directDoubleTapAvailable;
    private volatile ClassLoader hostLoader;
    private final java.util.concurrent.atomic.AtomicBoolean pagerStarted = new java.util.concurrent.atomic.AtomicBoolean();

    /** Hooks the Retrofit layer once the feed is loading, i.e. once Douyin's network stack is certainly up. */
    private void ensurePager() {
        if (hostLoader == null || !pagerStarted.compareAndSet(false, true)) return;
        new Thread(() -> { try { DouyinNativePager.install(xposed, hostLoader); } catch (Throwable t) { Log.w(TAG, "douyin_native_thread_failed", t); } }, "douyin-native-install").start();
    }

    private volatile DouyinComments comments;
    private volatile DouyinProfiles profiles;

    private DoubleTapDetector detector;

    private void installDoubleTap() {
        detector = new DoubleTapDetector(xposed, new DoubleTapDetector.Handler() {
            @Override public Activity activity() { return activity; }
            @Override public boolean onDoubleTap(Activity host, float x, float y) {
                // When Douyin exposes its holder+Aweme callback, it is the only source that knows
                // which feed cell was tapped. The generic Activity detector sees the panel's
                // preloaded current item and can otherwise open the neighbour's information.
                if (directDoubleTapAvailable && System.currentTimeMillis() - lastPanelTouchAt < 600) {
                    Log.i(TAG, "douyin_generic_double_tap_skipped direct_handler_available");
                    return false;
                }
                Object aweme = null;
                boolean isFlow = host != null && host.getClass().getName().contains("FlowPageActivity");
                if (!isFlow) {
                    Object panel = livePanel == null ? null : livePanel.get();
                    if (panel != null && isCurrentPanel(panel)) {
                        aweme = currentAweme();
                    }
                }
                if (aweme == null && host != null) {
                    android.view.View decor = host.getWindow() == null ? null : host.getWindow().getDecorView();
                    android.view.View touched = CurrentPostResolver.deepestView(decor, x, y);
                    aweme = resolver.resolve(host, x, y, touched);
                    if (aweme == null) aweme = resolver.resolveActivity(host, x, y);
                }
                if (aweme == null) aweme = currentAweme();
                Log.i(TAG, "douyin_double_tap aweme=" + (aweme != null));
                return aweme != null && showPost(aweme);
            }
        });
        detector.install();
    }

    private static Activity panelActivity(Object panel) {
        if (panel == null) return null;
        Object act = ModelReader.get(panel, "activity");
        if (act instanceof Activity) return (Activity) act;
        Object ctx = SnapshotMapper.invoke(panel, "getContext", new Class<?>[0]);
        if (ctx instanceof Activity) return (Activity) ctx;
        if (ctx instanceof android.content.ContextWrapper) {
            android.content.Context base = ((android.content.ContextWrapper) ctx).getBaseContext();
            if (base instanceof Activity) return (Activity) base;
        }
        Object act2 = SnapshotMapper.invoke(panel, "getActivity", new Class<?>[0]);
        if (act2 instanceof Activity) return (Activity) act2;
        return null;
    }

    private boolean isCurrentPanel(Object panel) {
        if (panel == null) return false;
        Activity act = activity;
        if (act == null) return true;
        Activity pAct = panelActivity(panel);
        return pAct == null || pAct == act;
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
            remember(aweme);
            JSONObject post = DouyinMapper.post(aweme);
            if (post == null) return false;
            Log.i(TAG, "post_info_triggered platform=douyin post=" + post.optString("postId"));
            JSONObject settings = VideoInfoSettings.current(current);
            main.post(() -> PostInfoPanel.show(current, post, settings));
            return true;
        } catch (Throwable error) { Log.w(TAG, "douyin_post_map_failed", error); return false; }
    }

    private volatile String lastPostId = "";

    private void onCommentPostObserved(String postId) {
        if (postId != null && !postId.isEmpty()) {
            lastCommentPostId = postId;
            lastPostId = postId;
        }
    }

    private void onNativePostCaptured(String postId) {
        if (postId != null && !postId.isEmpty()) {
            lastCommentPostId = postId;
            lastPostId = postId;
        }
    }

    /** Updates the post supplier synchronously; persistence remains off the host UI thread. */
    private void markCurrent(Object aweme) {
        if (aweme == null) return;
        String id = ModelReader.text(aweme, "aid", "aweme_id");
        if (!id.isEmpty()) lastPostId = id;
    }

    private void recordOnly(Object aweme) {
        if (aweme == null) return;
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

    private void remember(Object aweme) {
        markCurrent(aweme);
        recordOnly(aweme);
    }

    private static boolean isPostDetailActivity(Activity act) {
        if (act == null) return false;
        String name = act.getClass().getName();
        return (name.contains("FlowPageActivity") || name.contains("DetailActivity")) && !name.contains("Search");
    }

    private String resolveCurrentPostId() {
        Activity act = activity;
        if (act != null && DouyinComments.hasVisibleDrawer(act)) {
            String commentPost = lastCommentPostId;
            if (commentPost.isEmpty()) commentPost = DouyinNativePager.lastCapturedPost();
            if (commentPost.isEmpty()) commentPost = DouyinNativePager.post();
            if (!commentPost.isEmpty()) {
                lastPostId = commentPost;
                return commentPost;
            }
        }
        if (act != null) {
            String actName = act.getClass().getName();
            boolean isSearch = actName.contains("Search");
            boolean isFlow = actName.contains("FlowPageActivity");
            Object aweme = null;
            if (!isFlow) {
                Object panel = livePanel == null ? null : livePanel.get();
                if (panel != null && isCurrentPanel(panel)) {
                    aweme = currentAweme();
                }
            }
            if (aweme == null && !isSearch) {
                android.view.View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
                float midX = act.getResources().getDisplayMetrics().widthPixels * 0.5f;
                float midY = act.getResources().getDisplayMetrics().heightPixels * 0.5f;
                aweme = resolver.resolve(act, midX, midY, decor);
                if (aweme == null) aweme = resolver.resolveActivity(act, midX, midY);
                if (aweme == null) aweme = resolver.resolveActivity(act, -1, -1);
            }
            if (aweme != null) {
                remember(aweme);
                String aid = ModelReader.text(aweme, "aid", "aweme_id");
                if (!aid.isEmpty()) return aid;
            }
        }
        return lastPostId == null ? "" : lastPostId;
    }

    private void refreshCurrentPost(Activity act) {
        if (act == null || act.isFinishing() || act != activity || !isPostDetailActivity(act)) return;
        boolean isFlow = act.getClass().getName().contains("FlowPageActivity");
        Object aweme = null;
        if (!isFlow) {
            Object panel = livePanel == null ? null : livePanel.get();
            if (panel != null && isCurrentPanel(panel)) {
                aweme = currentAweme();
            }
        }
        if (aweme == null) {
            android.view.View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            float midX = act.getResources().getDisplayMetrics().widthPixels * 0.5f;
            float midY = act.getResources().getDisplayMetrics().heightPixels * 0.5f;
            aweme = resolver.resolve(act, midX, midY, decor);
            if (aweme == null) aweme = resolver.resolveActivity(act, midX, midY);
            if (aweme == null) aweme = resolver.resolveActivity(act, -1, -1);
        }
        if (aweme != null) {
            remember(aweme);
            Log.i(TAG, "douyin_post_refreshed activity=" + act.getClass().getSimpleName() + " post=" + ModelReader.text(aweme, "aid", "aweme_id"));
        }
    }

    /** Application.onCreate can finish before the module hooks it; poll for the live Application and attach to it. */
    private void awaitApplication(int attempt) {
        if (context != null || attempt > 60) return;
        try {
            Application live = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (live != null) { attach(live); return; }
        } catch (Throwable error) { Log.w(TAG, "douyin_attach_poll_failed", error); }
        main.postDelayed(() -> awaitApplication(attempt + 1), 500);
    }

    private void attach(Application app) {
        if (context != null) return;
        context = app;
        synchronized (this) { if (comments == null) comments = new DouyinComments(app, () -> activity); }
        comments.startReplyDriver();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity value) {
                activity = value;
                Log.i(TAG, "douyin_resumed " + value.getClass().getSimpleName() + " loader=" + (hostLoader != null));
                if (hostLoader != null) new Thread(() -> { try { DouyinNativePager.install(xposed, hostLoader); } catch (Throwable t) { Log.w(TAG, "douyin_native_thread_failed", t); } }, "douyin-native-install").start();
                if (comments != null) comments.ensurePaging();
                CollectorPanel.attach(value, DouyinHooks.this::resolveCurrentPostId);
                CollectorPanel.attachDouyinSettingsEntry(value, DouyinHooks.this::resolveCurrentPostId);
                if (isPostDetailActivity(value)) {
                    refreshCurrentPost(value);
                    main.postDelayed(() -> refreshCurrentPost(value), 400);
                    main.postDelayed(() -> refreshCurrentPost(value), 1000);
                }
            }
            @Override public void onActivityPaused(Activity value) { if (activity == value) activity = null; }
            @Override public void onActivityCreated(Activity value, Bundle state) { if (detector != null) detector.hook(value.getClass()); }
            @Override public void onActivityStarted(Activity value) {}
            @Override public void onActivityStopped(Activity value) {}
            @Override public void onActivitySaveInstanceState(Activity value, Bundle state) {}
            @Override public void onActivityDestroyed(Activity value) {}
        });
        Log.i(TAG, "douyin_application_attached");
        main.postDelayed(() -> new Thread(() -> { try { DouyinNativePager.install(xposed, hostLoader); } catch (Throwable t) { Log.w(TAG, "douyin_native_thread_failed", t); } }, "douyin-native-install").start(), 2500);
    }
}
