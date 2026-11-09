package dev.local.commentcollector;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;
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
        }, "xhs-profile-driver").start();
    }

    private JSONObject buildProfile(Object userInfo, String uid) throws Exception {
        Object gObj = ModelReader.get(userInfo, "gender");
        if (gObj == null) {
            try {
                Method gm = userInfo.getClass().getMethod("getGender");
                gObj = gm.invoke(userInfo);
            } catch (Throwable ignored) {}
        }
        String gender = "unknown";
        if (gObj instanceof Number) {
            int g = ((Number) gObj).intValue();
            if (g == 1) gender = "male";
            else if (g == 2) gender = "female";
        } else if (gObj != null) {
            String gs = String.valueOf(gObj).trim();
            if ("1".equals(gs) || "male".equalsIgnoreCase(gs) || "男".equals(gs)) gender = "male";
            else if ("2".equals(gs) || "female".equalsIgnoreCase(gs) || "女".equals(gs)) gender = "female";
        }

        String rawIp = text(userInfo, "ipLocation", "ip_location");
        if (rawIp.isEmpty()) {
            try {
                Method ipm = userInfo.getClass().getMethod("getIpLocation");
                Object ipo = ipm.invoke(userInfo);
                if (ipo != null) rawIp = String.valueOf(ipo).trim();
            } catch (Throwable ignored) {}
        }
        String ip = rawIp.replaceFirst("^\\s*IP\\s*属地\\s*[:：]?\\s*", "").trim();

        JSONObject out = new JSONObject().put("platform", "xhs").put("userId", uid).put("gender", gender)
            .put("profileCapturedAt", System.currentTimeMillis()).put("profileSource", "xhs_android.UserInfo").put("profileIpRegion", ip);

        put(out, "nickname", text(userInfo, "nickname", "name"));
        String avatar = firstNonEmpty(text(userInfo, "avatar", "imageb", "images"), ModelReader.url(ModelReader.get(userInfo, "avatar", "imageb", "images")));
        put(out, "avatarUrl", avatar);
        put(out, "bio", text(userInfo, "desc", "userDescInfo", "user_desc"));
        put(out, "redId", text(userInfo, "redId", "red_id"));

        String city = text(userInfo, "location", "city");
        put(out, "location", city);

        Object age = JSONObject.NULL;
        Object ageObj = ModelReader.get(userInfo, "age");
        if (ageObj instanceof Number) {
            int a = ((Number) ageObj).intValue();
            if (a > 0 && a <= 120) age = a;
        }
        if (age == JSONObject.NULL) {
            String birthday = text(userInfo, "birthday");
            if (birthday.isEmpty()) {
                try {
                    Method bm = userInfo.getClass().getMethod("getBirthday");
                    Object bo = bm.invoke(userInfo);
                    if (bo != null) birthday = String.valueOf(bo).trim();
                } catch (Throwable ignored) {}
            }
            if (birthday.matches("(19|20)\\d\\d-\\d\\d-\\d\\d")) {
                int years = Calendar.getInstance().get(Calendar.YEAR) - Integer.parseInt(birthday.substring(0, 4));
                if (years > 0 && years <= 120) age = years;
            }
        }
        out.put("age", age).put("ageVisibility", age == JSONObject.NULL ? "privacy_unspecified" : "public_or_unset");
        out.put("fieldSources", new JSONObject().put("gender", "user.gender").put("age", ageObj != null ? "user.age" : "user.birthday").put("location", "user.location")
            .put("profileIpRegion", "user.ip_location"));
        return out;
    }

    private static String text(Object object, String... keys) {
        String t = ModelReader.text(object, keys);
        if (!t.isEmpty()) return t;
        if (object == null) return "";
        for (String key : keys) {
            String getter = "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            try {
                Method m = object.getClass().getMethod(getter);
                Object v = m.invoke(object);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static void put(JSONObject out, String key, String value) throws Exception {
        if (value != null && !value.isEmpty()) out.put(key, value);
    }
}
