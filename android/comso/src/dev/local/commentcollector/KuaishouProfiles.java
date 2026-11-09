package dev.local.commentcollector;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * Kuaishou profile background data driver:
 * While a comment task with profile filtering or a profile task is active,
 * consumes profile_queue_next, fetches UserProfileResponse natively via
 * com.yxcorp.gifshow.profile.util.m1.v(...) and PublicMapper.profile,
 * and records profile attributes (gender, age, location, profileIpRegion) to the database.
 * Completely eliminates UI hopping (0 Activity starts/finishes).
 */
final class KuaishouProfiles {
    private static final String TAG = "CommentCollector";
    private final Supplier<Context> context;
    private final Supplier<Activity> activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean ticking;
    private static volatile Method m1FetchMethod;
    private static volatile boolean m1LookupAttempted;

    KuaishouProfiles(Supplier<Context> context, Supplier<Activity> activity) {
        this.context = context;
        this.activity = activity;
    }

    void install() {
        startDriver();
    }

    void startDriver() {
        if (ticking) return;
        ticking = true;
        main.postDelayed(this::tick, 2000);
    }

    private void tick() {
        new Thread(() -> {
            long delay = 3000;
            try {
                Context app = context.get();
                if (app == null) {
                    main.postDelayed(this::tick, 2000);
                    return;
                }
                JSONObject task = CollectorClient.call(app, "status", new JSONObject());
                String plat = task.optString("platform");
                String photo = task.optString("photoId");
                boolean isKs = "kuaishou".equals(plat) || (photo.matches("\\d+") && photo.length() > 10);
                if (!isKs) {
                    main.postDelayed(this::tick, 3000);
                    return;
                }
                String kind = task.optString("kind");
                String status = task.optString("status");
                boolean running = status.startsWith("running");
                if (("comment".equals(kind) || "profile".equals(kind)) && running) {
                    long taskId = task.optLong("taskId");
                    JSONObject next = CollectorClient.call(app, "profile_queue_next", new JSONObject().put("taskId", taskId));
                    if (next != null && !next.optBoolean("empty")) {
                        String uid = next.optString("userId");
                        if (!uid.isEmpty()) {
                            Object profileResp = fetchNativeProfile(app.getClassLoader(), uid);
                            if (profileResp != null) {
                                JSONObject prof = PublicMapper.profile(profileResp);
                                if (prof != null) {
                                    CollectorClient.call(app, "record_profile", new JSONObject().put("taskId", taskId).put("user", prof));
                                    Log.i(TAG, "kuaishou_profile_recorded uid=" + uid + " ip=" + prof.optString("profileIpRegion") + " gender=" + prof.optString("gender") + " age=" + prof.opt("age"));
                                    delay = 350;
                                } else {
                                    Log.w(TAG, "kuaishou_profile_parse_null uid=" + uid);
                                    CollectorClient.call(app, "profile_queue_fail", new JSONObject().put("taskId", taskId).put("userId", uid).put("issue", "profile_parse_null"));
                                    delay = 500;
                                }
                            } else {
                                Log.w(TAG, "kuaishou_profile_fetch_null uid=" + uid);
                                CollectorClient.call(app, "profile_queue_fail", new JSONObject().put("taskId", taskId).put("userId", uid).put("issue", "profile_fetch_null"));
                                delay = 500;
                            }
                        }
                    } else if ("running-profiles".equals(status)) {
                        delay = 1500;
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "kuaishou_profile_tick_failed", error);
            }
            main.postDelayed(this::tick, delay);
        }, "ks-profile-driver").start();
    }

    static Object fetchNativeProfile(ClassLoader loader, String userId) {
        try {
            Method m = findFetchMethod(loader);
            if (m == null) {
                Log.w(TAG, "kuaishou_m1_v_not_found");
                return null;
            }
            // public static Observable<t4n.d<UserProfileResponse>> v(
            //     String userId, boolean pv, int scene, int photoTabId, int origin,
            //     boolean z23, boolean z24, RequestTiming timing, Map extraParams,
            //     String businessScene, String extraStr, NetworkTrace trace)
            Object observable = m.invoke(null, userId, false, 0, 0, 3, false, false, null, Collections.emptyMap(), "", "", null);
            if (observable == null) return null;
            Method blockingFirst = observable.getClass().getMethod("blockingFirst");
            Object wrapper = blockingFirst.invoke(observable);
            if (wrapper == null) return null;
            if (wrapper.getClass().getName().endsWith("UserProfileResponse") || wrapper.getClass().getName().endsWith(".UserProfile")) {
                return wrapper;
            }
            // t4n.d.a() returns UserProfileResponse
            try {
                Method aMethod = wrapper.getClass().getMethod("a");
                Object unwrapped = aMethod.invoke(wrapper);
                if (unwrapped != null) return unwrapped;
            } catch (Throwable ignored) {}
            return wrapper;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "kuaishou_profile_native_call_failed uid=" + userId + " " + cause.getMessage(), cause);
            return null;
        }
    }

    private static synchronized Method findFetchMethod(ClassLoader loader) {
        if (m1FetchMethod != null) return m1FetchMethod;
        if (m1LookupAttempted) return null;
        m1LookupAttempted = true;
        try {
            Class<?> m1Class = loader.loadClass("com.yxcorp.gifshow.profile.util.m1");
            for (Method m : m1Class.getDeclaredMethods()) {
                if ("v".equals(m.getName()) && m.getParameterCount() == 12) {
                    m.setAccessible(true);
                    m1FetchMethod = m;
                    Log.i(TAG, "kuaishou_found_m1_v method=" + m);
                    return m;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "kuaishou_find_m1_failed", error);
        }
        return null;
    }
}
