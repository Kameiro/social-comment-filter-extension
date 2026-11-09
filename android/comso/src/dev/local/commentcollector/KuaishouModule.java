package dev.local.commentcollector;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Rect;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class KuaishouModule extends XposedModule {
    private static final String TARGET = "com.smile.gifmaker";
    private static final String TAG = "CommentCollector";
    private final ThreadLocal<JSONObject> request = new ThreadLocal<>();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), runnable -> new Thread(runnable, "comment-capture"));
    private String process;
    private volatile Context context;
    private volatile Activity activeActivity;
    private volatile String lastPhoto = "";
    private volatile JSONObject lastPost;
    private String version = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastTapAt;
    private float lastTapX, lastTapY;
    private WeakReference<View> lastTapTarget = new WeakReference<>(null);
    private WeakReference<View> downTarget = new WeakReference<>(null);
    private boolean downOnMedia;
    private boolean suppressSecondGesture;
    private long downAt;
    private float downX, downY;
    private AutoPager pager;
    private final CommentPageCache recentPages = new CommentPageCache();
    private final NativeCommentPager nativePager = new NativeCommentPager();
    private final WeakIdentityMap<Boolean> processed = new WeakIdentityMap<>();
    private final WeakIdentityMap<String> profileSeen = new WeakIdentityMap<>();
    private final WeakIdentityMap<JSONObject> replyContext = new WeakIdentityMap<>();
    private final WeakIdentityMap<String> commentSeen = new WeakIdentityMap<>();
    private final java.util.Set<String> commentSchemas = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Set<String> touchHooks = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private long lastTouchLogAt;
    private final CurrentPostResolver currentPost = new CurrentPostResolver();
    private JSONObject pendingPost;
    // HomeActivity.dispatchTouchEvent calls super, and both it and Activity.dispatchTouchEvent are hooked.
    // Without this guard every MotionEvent is observed twice and the second ACTION_UP clears the first tap.
    private int touchDepth;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName();
        Log.i(TAG, "module_loaded process=" + process + "");
        if (!Platforms.HOSTS.contains(process) && !"dev.local.commentcollector".equals(process)) detach();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        Log.i(TAG, "package_ready first=" + param.isFirstPackage() + " package=" + param.getPackageName());
        if (!param.isFirstPackage() || !param.getPackageName().equals(process)) return;
        ClassLoader loader = param.getClassLoader();
        if (Platforms.DOUYIN.equals(process)) { new DouyinHooks(this).install(loader); return; }
        if (Platforms.XHS.equals(process)) { new XhsHooks(this).install(loader); return; }
        if ("dev.local.commentcollector".equals(process)) {
            try {
                Method probe = loader.loadClass("dev.local.commentcollector.MainActivity").getDeclaredMethod("hookProbe");
                hook(probe).intercept(chain -> {
                    chain.proceed();
                    log(Log.INFO, TAG, "probe_executed api=" + getApiVersion());
                    Log.i(TAG, "probe_executed api=" + getApiVersion());
                    return "hook-active / API " + getApiVersion();
                });
            } catch (Exception error) { log(Log.ERROR, TAG, "probe_hook_failed", error); }
            return;
        }
        try {
            Method applicationStart = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            hook(applicationStart).intercept(chain -> {
                Object result = chain.proceed();
                attach((Application) chain.getArg(0));
                return result;
            });
            hook(Dialog.class.getDeclaredMethod("show")).intercept(chain -> {
                Object result = chain.proceed();
                if (pager != null) pager.dialog = new WeakReference<>((Dialog) chain.getThisObject());
                return result;
            });
        } catch (Exception error) { log(Log.ERROR, TAG, "lifecycle_hook_failed", error); }
        try {
            Method windowAdd = Class.forName("android.view.WindowManagerImpl")
                .getDeclaredMethod("addView", android.view.View.class, android.view.ViewGroup.LayoutParams.class);
            hook(windowAdd).intercept(chain -> {
                Object result = chain.proceed();
                if (pager != null) pager.trackWindow((android.view.View) chain.getArg(0));
                return result;
            });
        } catch (Exception error) { log(Log.WARN, TAG, "window_hook_unavailable", error); }
        currentPost.bind(loader);
        installFragmentTracking();
        installTouchHooks(loader);
        installRequests(loader);
        installNativePaging(loader);
        installNativeSettings(loader);
        installConverter(loader, "retrofit2.converter.gson.GsonResponseBodyConverter");
        installConverter(loader, "com.kwai.feature.api.corona.api.fast.CoronaProxyGsonResponseBodyConverter");
        try {
            Class<?> gson = loader.loadClass("com.google.gson.Gson");
            for (Method method : gson.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (method.getReturnType() != Object.class || args.length != 2 ||
                    (!java.lang.reflect.Type.class.isAssignableFrom(args[1]) && args[1] != Class.class)) continue;
                observe(method);
            }
        } catch (Exception error) { log(Log.WARN, TAG, "gson_hook_unavailable"); }
        try {
            Method getter = loader.loadClass("com.yxcorp.gifshow.comment.common.model.response.CommonCommentResponse")
                .getDeclaredMethod("getMComments");
            hook(getter).intercept(chain -> {
                Object result = chain.proceed();
                capture(chain.getThisObject());
                return result;
            });
        } catch (Exception error) { log(Log.WARN, TAG, "model_hook_unavailable"); }
        try {
            Method getter = loader.loadClass("com.kuaishou.android.model.mix.QSubComment").getDeclaredMethod("getKComments");
            hook(getter).intercept(chain -> { Object result = chain.proceed(); capture(chain.getThisObject()); return result; });
        } catch (Exception error) { log(Log.WARN, TAG, "reply_model_hook_unavailable"); }
        try {
            Method getter = loader.loadClass("com.kwai.framework.model.user.UserProfile").getDeclaredMethod("getKUserInfo");
            hook(getter).intercept(chain -> { Object result = chain.proceed(); capture(chain.getThisObject()); return result; });
            Class<?> qPhoto = loader.loadClass("com.yxcorp.gifshow.entity.QPhoto");
            // Keep this on the caption getter only. The other QPhoto getters are hot render-path methods;
            // reflecting on them causes measurable feed stalls on newer Kuaishou builds.
            Method caption = qPhoto.getDeclaredMethod("getCaption");
            hook(caption).intercept(chain -> {
                Object result = chain.proceed();
                rememberPost(chain.getThisObject());
                return result;
            });
        } catch (Exception error) { log(Log.WARN, TAG, "public_model_hook_unavailable"); }
        log(Log.INFO, TAG, "hooks_installed");
    }

    private void rememberPost(Object value) {
        try { if (context != null && value != null) synchronized (processed) {
            if (processed.containsKey(value)) return;
            JSONObject post = PublicMapper.post(value);
            if (post == null) return;
            lastPost = post;
            processed.put(value, Boolean.TRUE);
            writer.execute(() -> { try { CollectorClient.call(context, "record_post", new JSONObject().put("post", post)); } catch (Exception ignored) {} });
        } } catch (Exception ignored) { Log.w(TAG, "post_observation_failed"); }
    }

    private boolean observeDoubleTap(Activity activity, MotionEvent event) {
        if (event == null) return false;
        String name = activity.getClass().getName();
        if (!isFeedActivity(name) || CollectorPanel.showing() || (pager != null && pager.dialog.get() != null && pager.dialog.get().isShowing())) {
            resetTap(); return false;
        }
        long now = SystemClock.uptimeMillis();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            View target = mediaViewAt(activity, event.getRawX(), event.getRawY());
            if (now - lastTouchLogAt > 800L) {
                lastTouchLogAt = now;
                Log.i(TAG, "double_tap_down activity=" + name + " post=" + (lastPost == null ? "" : lastPost.optString("postId"))
                    + " target=" + (target == null ? "null" : target.getClass().getName())
                    + " x=" + event.getRawX() + " y=" + event.getRawY());
            }
            // Overlays (pause icon, like heart) appear between taps, so compare position, not view identity.
            boolean secondDown = target != null && lastTapAt > 0L && now - lastTapAt <= 360L
                && Math.abs(event.getRawX() - lastTapX) + Math.abs(event.getRawY() - lastTapY)
                    <= activity.getResources().getDisplayMetrics().density * 72f;
            downTarget = new WeakReference<>(target);
            downOnMedia = target != null;
            downAt = now;
            downX = event.getRawX();
            downY = event.getRawY();
            if (!downOnMedia) lastTapAt = 0;
            if (target == null && lastTapAt > 0L && now - lastTapAt <= 360L)
                Log.i(TAG, "double_tap_second_rejected reason=" + lastRejectReason + " x=" + event.getRawX() + " y=" + event.getRawY());
            if (secondDown) {
                // Resolve only on a real double tap so single taps and scrolling stay untouched.
                JSONObject post = postUnder(activity, event.getRawX(), event.getRawY(), target);
                Log.i(TAG, "double_tap_second_down post=" + (post == null ? "" : post.optString("postId")));
                if (post != null) {
                    pendingPost = post;
                    suppressSecondGesture = true;
                    return true;
                }
                lastTapAt = 0L;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            boolean consume = suppressSecondGesture;
            resetTap();
            return consume;
        }
        if (suppressSecondGesture) {
            if (action == MotionEvent.ACTION_UP) {
                View target = mediaViewAt(activity, event.getRawX(), event.getRawY());
                float drift = Math.abs(event.getRawX() - downX) + Math.abs(event.getRawY() - downY);
                boolean sameMedia = downOnMedia && now - downAt <= 500L
                    && drift <= activity.getResources().getDisplayMetrics().density * 72f;
                suppressSecondGesture = false;
                downOnMedia = false;
                JSONObject post = pendingPost;
                pendingPost = null;
                if (sameMedia && post != null && !post.optString("postId").isEmpty()) {
                    Log.i(TAG, "post_info_triggered post=" + post.optString("postId"));
                    lastTapAt = 0L;
                    JSONObject settings = VideoInfoSettings.current(activity);
                    mainHandler.post(() -> PostInfoPanel.show(activity, post, settings));
                } else {
                    lastTapAt = 0L;
                }
            }
            return true;
        }
        if (action != MotionEvent.ACTION_UP) return false;
        View down = downTarget.get();
        View up = mediaViewAt(activity, event.getRawX(), event.getRawY());
        boolean sameMedia = downOnMedia && down != null && up != null;
        float move = Math.abs(event.getRawX() - downX) + Math.abs(event.getRawY() - downY);
        if (!sameMedia || now - downAt > 500L || move > activity.getResources().getDisplayMetrics().density * 72f) {
            resetTap();
            return false;
        }
        lastTapAt = now;
        lastTapX = event.getRawX();
        lastTapY = event.getRawY();
        lastTapTarget = new WeakReference<>(up);
        downOnMedia = false;
        return false;
    }

    private JSONObject postUnder(Activity activity, float rawX, float rawY, View target) {
        try {
            Object photo = currentPost.resolve(activity, rawX, rawY, target);
            if (photo != null) {
                JSONObject post = PublicMapper.post(photo);
                if (post != null && !post.optString("postId").isEmpty()) {
                    lastPost = post;
                    rememberPost(photo);
                    return post;
                }
            }
        } catch (Throwable error) { Log.w(TAG, "current_post_resolve_failed", error); }
        return hasUsablePost() ? lastPost : null;
    }

    private void installFragmentTracking() {
        Class<?> fragment = currentPost.fragmentType();
        if (fragment == null) { Log.w(TAG, "fragment_tracking_unavailable"); return; }
        try {
            hook(fragment.getDeclaredMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                currentPost.onFragmentResumed(chain.getThisObject());
                return result;
            });
            Log.i(TAG, "fragment_tracking_installed");
        } catch (Exception error) { log(Log.WARN, TAG, "fragment_tracking_unavailable", error); }
    }

    private boolean hasUsablePost() {
        JSONObject post = lastPost;
        long capturedAt = post == null ? 0L : post.optLong("snapshotAt", 0L);
        return post != null && !post.optString("postId").isEmpty() && capturedAt > 0L && System.currentTimeMillis() - capturedAt < 300000L;
    }

    private void resetTap() {
        lastTapAt = 0;
        downAt = 0;
        downX = downY = 0f;
        downOnMedia = false;
        suppressSecondGesture = false;
        pendingPost = null;
        downTarget = new WeakReference<>(null);
        lastTapTarget = new WeakReference<>(null);
    }



    private static volatile String lastRejectReason = "";

    /**
     * Returns the view under the finger when the touch is on the post body, or null for controls.
     * Uses geometry instead of class-name keywords: Kuaishou's full-screen player containers carry
     * names like "...Tab..." or "...Action..." that the old keyword filter wrongly rejected.
     */
    private static View mediaViewAt(Activity activity, float rawX, float rawY) {
        if (activity == null || activity.getWindow() == null) return null;
        View root = activity.getWindow().getDecorView();
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        int height = root.getHeight();
        int width = root.getWidth();
        float y = rawY - origin[1];
        float x = rawX - origin[0];
        // Post body only: top bar/tabs, the right action rail, caption and bottom navigation are excluded.
        if (height <= 0 || width <= 0 || y < height * .12f || y > height * .74f || x < width * .04f || x > width * .82f) {
            lastRejectReason = "region"; return null;
        }
        View target = findViewAt(root, rawX, rawY);
        if (target == null) return root;
        long screen = (long) width * height;
        View current = target;
        for (int i = 0; current != null && i < 10; i++) {
            long area = (long) current.getWidth() * current.getHeight();
            // A small clickable view (button, avatar, link, follow chip) is a control, not the post body.
            if ((current.isClickable() || current.isLongClickable()) && area > 0 && area < screen / 6) {
                lastRejectReason = "control:" + current.getClass().getName() + " " + current.getWidth() + "x" + current.getHeight();
                return null;
            }
            if (area >= screen / 2) break;
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return target;
    }

    private static View findViewAt(View view, float rawX, float rawY) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.01f) return null;
        Rect bounds = new Rect();
        view.getGlobalVisibleRect(bounds);
        if (!bounds.contains(Math.round(rawX), Math.round(rawY))) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                View hit = findViewAt(child, rawX, rawY);
                if (hit != null) return hit;
            }
        }
        return view;
    }


    private static boolean isFeedActivity(String name) {
        return name.endsWith("HomeActivity") || name.contains("PhotoDetailActivity") || name.contains("FeedActivity") ||
            name.endsWith("KwaiRnActivity");
    }

    private void attach(Application app) {
        if (context != null) return;
        context = app;
        try { version = app.getPackageManager().getPackageInfo(TARGET, 0).versionName; }
        catch (Exception ignored) {}
        nativePager.attach(app, version, data -> writer.execute(() -> {
            try { CollectorClient.call(app, "native_eof", data); }
            catch (Exception error) { Log.w(TAG, "native_eof_storage_failed"); }
        }), (sub, metadata) -> {
            replyContext.put(sub, metadata);
            capture(sub);
        });
        pager = new AutoPager(app, () -> lastPhoto, this::replayCached, nativePager);
        app.registerActivityLifecycleCallbacks(pager);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity value) {
                activeActivity = value;
                if (isFeedActivity(value.getClass().getName())) {
                    resetTap();
                    Log.i(TAG, "current_post_reset activity=" + value.getClass().getName());
                }
            }
            @Override public void onActivityCreated(Activity value, Bundle state) {}
            @Override public void onActivityStarted(Activity value) {}
            @Override public void onActivityPaused(Activity value) {}
            @Override public void onActivityStopped(Activity value) {}
            @Override public void onActivitySaveInstanceState(Activity value, Bundle state) {}
            @Override public void onActivityDestroyed(Activity value) {}
        });
        pager.start();
        log(Log.INFO, TAG, "application_attached version=" + version);
    }

    private void installNativePaging(ClassLoader loader) {
        try {
            Class<?> type = loader.loadClass("com.kwai.kmp.comment.mvi.panel.pagelist.KmpCommentFeaturePageList");
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                nativePager.bind(chain.getThisObject());
                Log.i(TAG, "native_kmp_pager_created");
                return result;
            });
            hook(type.getDeclaredMethod("k")).intercept(chain -> {
                nativePager.bind(chain.getThisObject());
                Log.i(TAG, "native_kmp_load_observed");
                return chain.proceed();
            });
        } catch (Exception error) { Log.i(TAG, "native_kmp_probe_unavailable"); }
    }

    private void installTouchHooks(ClassLoader loader) {
        String[] names = {
            "android.app.Activity",
            "com.yxcorp.gifshow.HomeActivity",
            "com.yxcorp.gifshow.KwaiRnActivity",
            "com.yxcorp.gifshow.detail.KwaiRnActivity",
            "com.yxcorp.gifshow.detail.PhotoDetailActivity"
        };
        for (String name : names) {
            try {
                Class<?> type = name.startsWith("android.") ? Activity.class : loader.loadClass(name);
                Method method;
                try { method = type.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class); }
                catch (NoSuchMethodException ignored) { method = type.getMethod("dispatchTouchEvent", MotionEvent.class); }
                String key = method.getDeclaringClass().getName() + "#" + method.getName();
                if (!touchHooks.add(key)) continue;
                hook(method).intercept(chain -> {
                    if (touchDepth > 0) return chain.proceed();
                    touchDepth++;
                    try {
                    boolean consume = false;
                    try {
                        Object receiver = chain.getThisObject();
                        Activity activity = receiver instanceof Activity ? (Activity) receiver : activeActivity;
                        if (receiver instanceof Activity || (receiver instanceof View && activity != null &&
                            ((View) receiver) == activity.getWindow().getDecorView()) ||
                            (receiver instanceof View && activity != null &&
                            ((View) receiver) == findViewAt(activity.getWindow().getDecorView(),
                                ((MotionEvent) chain.getArg(0)).getRawX(), ((MotionEvent) chain.getArg(0)).getRawY()))) {
                            consume = observeDoubleTap(activity, (MotionEvent) chain.getArg(0));
                        }
                    }
                    catch (Exception ignored) { Log.w(TAG, "double_tap_observer_failed"); }
                    return consume ? true : chain.proceed();
                    } finally { touchDepth--; }
                });
                Log.i(TAG, "double_tap_hook_installed method=" + key);
            } catch (Exception error) { log(Log.WARN, TAG, "double_tap_hook_unavailable", error); }
        }
    }

    private void installNativeSettings(ClassLoader loader) {
        try {
            Class<?> type = loader.loadClass("com.yxcorp.gifshow.settings.holder.EntryListFragment");
            Method onResume = type.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                mainHandler.post(() -> addNativeSettingsEntry(chain.getThisObject()));
                return result;
            });
            Log.i(TAG, "native_settings_hook_installed");
        } catch (Exception error) { log(Log.WARN, TAG, "native_settings_hook_unavailable", error); }
        try {
            Class<?> settingsActivity = loader.loadClass("com.yxcorp.plugin.setting.activity.SettingsActivity");
            Method onResume = settingsActivity.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                mainHandler.post(() -> addSettingsActivityEntry((Activity) chain.getThisObject()));
                return result;
            });
            Log.i(TAG, "settings_activity_hook_installed");
        } catch (Exception error) { log(Log.WARN, TAG, "settings_activity_hook_unavailable", error); }
    }

    private void addSettingsActivityEntry(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            View list = activity.findViewById(activity.getResources().getIdentifier("item_list", "id", TARGET));
            if (list == null || !(list.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) list.getParent();
            String tag = "comment_collector_native_settings_activity";
            if (parent.findViewWithTag(tag) != null) return;
            float d = activity.getResources().getDisplayMetrics().density;
            View card = CollectorPanel.createSettingsCard(activity, () -> lastPhoto, tag);
            int insertIndex = Math.max(0, parent.indexOfChild(list));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(54 * d));
            int marginH = Math.round(16 * d);
            lp.setMargins(marginH, Math.round(12 * d), marginH, Math.round(4 * d));
            parent.addView(card, insertIndex, lp);
            Log.i(TAG, "settings_activity_entry_added");
        } catch (Exception error) { Log.w(TAG, "settings_activity_entry_failed", error); }
    }

    private void addNativeSettingsEntry(Object fragment) {
        try {
            Object value = SnapshotMapper.field(fragment, "f189091e");
            if (!(value instanceof LinearLayout)) return;
            LinearLayout list = (LinearLayout) value;
            String tag = "comment_collector_native_settings";
            if (list.findViewWithTag(tag) != null) return;
            Activity activity = (Activity) SnapshotMapper.invoke(fragment, "getActivity", new Class<?>[0]);
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            float d = activity.getResources().getDisplayMetrics().density;
            View card = CollectorPanel.createSettingsCard(activity, () -> lastPhoto, tag);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(54 * d));
            int marginH = Math.round(16 * d);
            lp.setMargins(marginH, Math.round(12 * d), marginH, Math.round(4 * d));
            list.addView(card, 0, lp);
            Log.i(TAG, "native_settings_entry_added");
        } catch (Exception error) { Log.w(TAG, "native_settings_entry_failed", error); }
    }

    private void installRequests(ClassLoader loader) {
        try {
            // 14.8.40.50567's Retrofit call class is obfuscated, but its response signature is stable.
            Class<?> call;
            try { call = loader.loadClass("retrofit2.OkHttpCall"); }
            catch (ClassNotFoundException ignored) { call = loader.loadClass("retrofit2.k"); }
            for (Method method : call.getDeclaredMethods()) {
                if (method.getParameterCount() != 1 ||
                    !"okhttp3.Response".equals(method.getParameterTypes()[0].getName())) continue;
                hook(method).intercept(chain -> {
                    JSONObject previous = request.get();
                    JSONObject metadata = requestInfo(chain.getArg(0));
                    request.set(metadata);
                    try {
                        Object result = chain.proceed();
                        Object body = SnapshotMapper.field(result, "body", "b");
                        if (body != null) capture(body);
                        try { if (metadata != null && context != null) {
                            Object code = SnapshotMapper.field(chain.getArg(0), "code");
                            JSONObject task = CollectorClient.call(context, "status", new JSONObject());
                            boolean matches = "comment".equals(task.optString("kind")) && !metadata.optString("photoId").isEmpty() && metadata.optString("photoId").equals(task.optString("photoId")) ||
                                "discovery".equals(task.optString("kind")) && !metadata.optString("keyword").isEmpty() && metadata.optString("keyword").equals(task.getJSONObject("params").optString("keyword"));
                            if (matches &&
                                code instanceof Number && ((Number) code).intValue() >= 400)
                                pause("http_" + ((Number) code).intValue());
                        } } catch (Exception ignored) { Log.w(TAG, "response_diagnostic_unavailable"); }
                        return result;
                    } finally { if (previous == null) request.remove(); else request.set(previous); }
                });
            }
        } catch (Exception error) { log(Log.WARN, TAG, "request_correlation_unavailable"); }
    }

    private JSONObject requestInfo(Object response) {
        try {
            Object req = SnapshotMapper.field(response, "request");
            Object url = SnapshotMapper.field(req, "url");
            if (url == null) return null;
            URI uri = URI.create(url.toString());
            if (!uri.getPath().toLowerCase(java.util.Locale.ROOT).matches(".*(?:comment|search|profile).*")) return null;
            JSONObject out = new JSONObject().put("appVersion", version);
            out.put("streamType", uri.getPath().toLowerCase(java.util.Locale.ROOT).matches(".*(?:sub|reply).*" ) ? "reply" : "root");
            String query = uri.getRawQuery();
            if (query != null) for (String term : query.split("&")) {
                String[] parts = term.split("=", 2);
                if (parts.length == 2) parameter(out, decode(parts[0]), decode(parts[1]));
            }
            Object body = SnapshotMapper.field(req, "body");
            if (body != null && "okhttp3.FormBody".equals(body.getClass().getName())) {
                Object names = SnapshotMapper.field(body, "encodedNames");
                Object values = SnapshotMapper.field(body, "encodedValues");
                if (names instanceof List<?> && values instanceof List<?>) {
                    for (int i = 0; i < ((List<?>) names).size(); i++) {
                        parameter(out, decode(String.valueOf(((List<?>) names).get(i))), decode(String.valueOf(((List<?>) values).get(i))));
                    }
                }
            }
            if (!out.optString("rootId").isEmpty()) out.put("streamType", "reply");
            else if ("reply".equals(out.optString("streamType")) && out.has("commentAnchorId")) out.put("rootId", out.getString("commentAnchorId"));
            out.remove("commentAnchorId");
            return out;
        } catch (Exception ignored) { return null; }
    }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private void parameter(JSONObject out, String name, String value) throws Exception {
        if ("photoId".equals(name) || "photo_id".equals(name)) out.put("photoId", value);
        boolean parent = !value.isEmpty() && !value.matches("0+|-1|null|undefined");
        if (parent && ("rootCommentId".equals(name) || "rootComment_id".equals(name) || "root_comment_id".equals(name) || "rootId".equals(name))) out.put("rootId", value);
        if (parent && ("commentId".equals(name) || "comment_id".equals(name))) out.put("commentAnchorId", value);
        if ("keyword".equals(name) || "query".equals(name)) out.put("keyword", value);
        if ("pcursor".equals(name) || "cursor".equals(name)) out.put("requestCursorKnown", true).put("requestCursor", value);
    }
    private void installConverter(ClassLoader loader, String name) {
        try {
            for (Method method : loader.loadClass(name).getDeclaredMethods()) {
                if ("convert".equals(method.getName()) && method.getParameterCount() == 1 && !method.isBridge()) observe(method);
            }
        } catch (Exception error) { log(Log.WARN, TAG, "converter_unavailable: " + name); }
    }
    private void observe(Method method) {
        hook(method).intercept(chain -> {
            Object result = chain.proceed();
            capture(result);
            return result;
        });
    }
    private void capture(Object response) {
        if (context == null) return;
        try {
            JSONObject metadata = request.get();
            if (metadata == null) metadata = new JSONObject().put("appVersion", version);
            JSONObject bound = replyContext.get(response);
            if (bound != null) metadata = bound;
            JSONObject profile = PublicMapper.profile(response);
            JSONObject search = PublicMapper.search(response, metadata);
            if (profile != null || search != null) {
                JSONObject state = CollectorClient.call(context, "status", new JSONObject());
                if (profile != null) {
                    JSONObject signature = new JSONObject(profile.toString()); signature.remove("profileCapturedAt"); String fingerprint = JsonContracts.canonical(signature);
                    fingerprint += ":" + state.optLong("taskId") + ":" + state.optLong("generation");
                    synchronized (profileSeen) { if (fingerprint.equals(profileSeen.put(response, fingerprint))) return; }
                    if (pager != null) pager.onProfileRecorded(profile.optString("userId"));
                } else synchronized (processed) { if (processed.put(response, Boolean.TRUE) != null) return; }
                JSONObject data = new JSONObject().put("taskId", state.optLong("taskId")).put("generation", state.optLong("generation"));
                if (profile != null) data.put("user", profile); else data.put("page", search);
                String operation = profile != null ? "record_profile" : "record_search";
                writer.execute(() -> { try { CollectorClient.call(context, operation, data); } catch (Exception ignored) { pause("storage_error"); } });
                Log.i(TAG, profile != null ? "public_profile_observed" : "search_page_observed posts=" + search.getJSONArray("posts").length());
                return;
            }
            JSONObject page = SnapshotMapper.page(response, metadata);
            if (response != null && "reply".equals(metadata.optString("streamType")) && commentSchemas.add("reply:" + response.getClass().getName()))
                Log.i(TAG, "reply_response_type=" + response.getClass().getName() + " bound_post=" + metadata.has("photoId") + " bound_parent=" + metadata.has("rootId"));
            if (page == null) return;
            recentPages.offer(page, android.os.SystemClock.elapsedRealtime());
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (int i = 0; i < page.getJSONArray("rows").length(); i++) {
                JSONObject row = page.getJSONArray("rows").getJSONObject(i);
                if (row.optInt("level") == 1 && !row.isNull("replies")) counts.put(row.getString("commentId"), row.optInt("replies"));
            }
            Object groups = SnapshotMapper.field(response, "mSubCommentMap");
            if (groups instanceof java.util.Map<?, ?>) for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) groups).entrySet())
                if (entry.getValue() != null) replyContext.put(entry.getValue(), new JSONObject().put("photoId", page.getString("photoId")).put("rootId", String.valueOf(entry.getKey())).put("appVersion", version)
                    .put("expectedReplies", counts.getOrDefault(String.valueOf(entry.getKey()), -1)));
            Object roots = SnapshotMapper.field(response, "mComments");
            nativePager.observeRoots(page.getString("photoId"), roots);
            if (roots instanceof List<?>) for (Object comment : (List<?>) roots) {
                Object sub = SnapshotMapper.field(comment, "mSubComment"), parent = SnapshotMapper.field(comment, "mId");
                if (sub != null && parent != null) replyContext.put(sub, new JSONObject().put("photoId", page.getString("photoId")).put("rootId", String.valueOf(parent)).put("appVersion", version)
                    .put("expectedReplies", counts.getOrDefault(String.valueOf(parent), -1)));
            }
            lastPhoto = page.getString("photoId");
            JSONObject task = CollectorClient.call(context, "status", new JSONObject());
            long taskId = task.optLong("taskId"), generation = task.optLong("generation");
            if (!"comment".equals(task.optString("kind")) || !task.optString("status").startsWith("running") ||
                (!task.optString("photoId").isEmpty() && !lastPhoto.equals(task.optString("photoId")))) return;
            org.json.JSONArray ids = new org.json.JSONArray();
            for (int i = 0; i < page.getJSONArray("rows").length(); i++) {
                JSONObject row = new JSONObject(page.getJSONArray("rows").getJSONObject(i).toString()); row.remove("capturedAt"); ids.put(row);
            }
            String fingerprint = new JSONObject().put("task", taskId).put("generation", generation).put("parent", page.optString("rootId"))
                .put("cursor", page.opt("cursor")).put("more", page.opt("hasMore")).put("rows", ids).put("groups", page.optJSONArray("groups")).toString();
            synchronized (commentSeen) { if (fingerprint.equals(commentSeen.put(response, fingerprint))) return; }
            Object more = SnapshotMapper.field(response, "mHasMore", "hasMore");
            Log.i(TAG, "comment_page_observed rows=" + page.getJSONArray("rows").length() + " more=" + page.opt("hasMore") +
                " more_type=" + (more == null ? "missing" : more.getClass().getSimpleName()) +
                " more_method=" + SnapshotMapper.invoke(response, "hasMore", new Class<?>[0]) + " eof=" + page.optString("eofEvidence"));
            enqueuePage(page, taskId, generation);
        } catch (java.util.concurrent.RejectedExecutionException error) { pause("storage_queue_full"); }
        catch (Exception error) { log(Log.WARN, TAG, "comment_mapping_failed: " + error.getClass().getSimpleName()); }
    }
    private void replayCached(JSONObject task) {
        try {
            if (task.optInt("rootPages") > 0 || task.optBoolean("rootComplete")) return;
            List<JSONObject> pages = recentPages.read(task.optString("photoId"), android.os.SystemClock.elapsedRealtime());
            for (JSONObject page : pages) enqueuePage(page, task.optLong("taskId"), task.optLong("generation"));
            Log.i(TAG, "root_cache_replay pages=" + pages.size());
        } catch (Exception error) { Log.w(TAG, "root_cache_replay_unavailable"); }
    }
    private void enqueuePage(JSONObject page, long taskId, long generation) throws Exception {
        String payload = page.toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > 500000) { pause("page_too_large"); return; }
        writer.execute(() -> {
            try {
                Bundle args = new Bundle(); args.putString("page", payload); args.putLong("taskId", taskId); args.putLong("generation", generation);
                Bundle result = CollectorClient.callRaw(context, "append", args);
                if (result != null && result.containsKey("error")) Log.w(TAG, "page_storage_failed");
            } catch (Exception error) { Log.w(TAG, "storage_unavailable"); }
        });
    }
    private void pause(String code) {
        if (context == null) return;
        try {
            Bundle args = new Bundle(); args.putString("code", code);
            CollectorClient.callRaw(context, "pause", args);
        } catch (Exception ignored) {}
    }
}
