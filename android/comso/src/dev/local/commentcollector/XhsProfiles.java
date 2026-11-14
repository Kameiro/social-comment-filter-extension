package dev.local.commentcollector;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Calendar;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * Xiaohongshu profile data driver:
 * While a comment task with profile filtering or a profile task is active,
 * consumes profile_queue_next, fetches UserInfo natively via XhsNativePager,
 * and records profile attributes (gender, age, location, profileIpRegion) to the database.
 */
final class XhsProfiles {
    private static final String TAG = "CommentCollector";
    private final Supplier<Context> context;
    private final Supplier<Activity> activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xhs-profile-driver");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean ticking;

    XhsProfiles(Supplier<Context> context, Supplier<Activity> activity) {
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
        worker.execute(() -> {
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
                boolean isXhs = "xhs".equals(plat) || photo.matches("[0-9a-fA-F]{24}");
                if (!isXhs) {
                    main.postDelayed(this::tick, 3000);
                    return;
                }
                String kind = task.optString("kind");
                String status = task.optString("status");
                boolean running = status.startsWith("running");
                if (("comment".equals(kind) || "profile".equals(kind)) && running) {
                    if (!XhsNativePager.profileReady()) {
                        Log.d(TAG, "xhs_profile_native_not_ready");
                        main.postDelayed(this::tick, 1500);
                        return;
                    }
                    long taskId = task.optLong("taskId");
                    JSONObject next = CollectorClient.call(app, "profile_queue_next", new JSONObject().put("taskId", taskId));
                    if (!next.optBoolean("empty")) {
                        String uid = next.optString("userId");
                        if (!uid.isEmpty()) {
                            Object resp = XhsNativePager.fetchProfile(uid);
                            if (resp != null) {
                                JSONObject prof = buildProfile(resp, uid);
                                CollectorClient.call(app, "record_profile", new JSONObject().put("taskId", taskId).put("user", prof));
                                Log.i(TAG, "xhs_profile_recorded uid=" + uid + " ip=" + prof.optString("profileIpRegion") + " gender=" + prof.optString("gender"));
                                delay = 350;
                            } else {
                                CollectorClient.call(app, "profile_queue_fail", new JSONObject().put("taskId", taskId).put("userId", uid).put("issue", "profile_fetch_failed"));
                                delay = 500;
                            }
                        }
                    } else if ("running-profiles".equals(status)) {
                        delay = 1500;
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "xhs_profile_tick_failed", error);
            }
            main.postDelayed(this::tick, delay);
        });
    }

    private JSONObject buildProfile(Object userInfo, String uid) throws Exception {
        return XhsProfileMapper.buildProfile(userInfo, uid);
    }
}
