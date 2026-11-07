package dev.local.commentcollector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * Douyin profile data: records a user's profile (IP location, city, gender, age) whenever the app loads one, and while a
 * profile task is running walks the pending users by opening their profile pages.
 */
final class DouyinProfiles {
    private static final String TAG = "CommentCollector";
    private final Context context;
    private final Supplier<Activity> activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, Long> recent = new ConcurrentHashMap<>();
    private Field ipField;
    private volatile boolean ticking;
    private volatile String openedUser = "";
    private volatile long openedAt;

    DouyinProfiles(Context context, Supplier<Activity> activity) { this.context = context; this.activity = activity; }

    /** Hooks the {@code User} model's identity getters; a loaded profile has {@code ipLocation} filled in. */
    static void hook(io.github.libxposed.api.XposedInterface xposed, ClassLoader loader, java.util.function.Consumer<Object> sink) {
        try {
            Class<?> user = loader.loadClass("com.ss.android.ugc.aweme.profile.model.User");
            int hooked = 0;
            for (String name : new String[] {"getUid", "getSecUid", "getNickname"}) {
                try {
                    Method method = user.getDeclaredMethod(name);
                    xposed.hook(method).intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self != null) try { sink.accept(self); } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "douyin_profile_hooks=" + hooked);
        } catch (Throwable error) { Log.w(TAG, "douyin_profile_hook_failed", error); }
    }

    /** Hooks cheap Aweme getters so every post the app loads (including a profile's grid) reaches {@code sink} once. */
    static void hookPosts(io.github.libxposed.api.XposedInterface xposed, ClassLoader loader, java.util.function.Consumer<Object> sink) {
        try {
            Class<?> aweme = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
            final WeakIdentityMap<Boolean> seen = new WeakIdentityMap<>();
            int hooked = 0;
            for (String name : new String[] {"getAid", "getAuthorUid", "isTop", "getAwemeType", "getStatus", "isLike", "isCollected", "hasViewed", "isProhibited", "isStory", "getGroupId", "getIsAdAweme", "isEcomAweme", "getFollowStatus", "getProfileUid", "getDownloadStatus", "getCreatedTimeInMillSec"}) {
                try {
                    Method method = aweme.getDeclaredMethod(name);
                    xposed.hook(method).intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self != null) {
                            boolean first;
                            synchronized (seen) { first = seen.put(self, Boolean.TRUE) == null; }
                            if (first) try { sink.accept(self); } catch (Throwable ignored) {}
                        }
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "douyin_post_hooks=" + hooked);
        } catch (Throwable error) { Log.w(TAG, "douyin_post_hook_failed", error); }
    }

    /** Post-list responses (feed pages and a profile's post grid): every item in the list is observed once, when first touched. */
    static void hookPostLists(io.github.libxposed.api.XposedInterface xposed, ClassLoader loader, java.util.function.Consumer<Object> sink) {
        try {
            Class<?> list = loader.loadClass("com.ss.android.ugc.aweme.feed.model.FeedItemList");
            final WeakIdentityMap<Boolean> seen = new WeakIdentityMap<>();
            int hooked = 0;
            for (Method method : list.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()) continue;
                try {
                    xposed.hook(method).intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self != null) {
                            boolean first;
                            synchronized (seen) { first = seen.put(self, Boolean.TRUE) == null; }
                            if (first) {
                                Object items = ModelReader.get(self, "items");
                                if (items instanceof Iterable) for (Object item : (Iterable<?>) items) try { sink.accept(item); } catch (Throwable ignored) {}
                            }
                        }
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "douyin_post_list_hooks=" + hooked);
        } catch (Throwable error) { Log.w(TAG, "douyin_post_list_hook_failed", error); }
    }

    private static final class Latest { long created; String location = ""; String city = ""; }
    private final ConcurrentHashMap<String, Latest> latest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recorded = new ConcurrentHashMap<>();

    /** Remembers each author's newest unpinned post location; pushes an update when a profile was just recorded. */
    void observePost(Object aweme) {
        try {
            Object author = ModelReader.get(aweme, "author");
            String uid = ModelReader.text(author, "uid");
            if (uid.isEmpty()) uid = ModelReader.text(aweme, "authorUserId");
            if (uid.isEmpty()) { reject("no_author", aweme); return; }
            Object top = ModelReader.get(aweme, "isTop");
            if (top instanceof Number && ((Number) top).intValue() != 0) return; // pinned posts are not the latest
            Object time = ModelReader.get(aweme, "createTime");
            long created = time instanceof Number ? ((Number) time).longValue() : 0;
            if (created <= 0) { reject("no_time", aweme); return; }
            String cityName = ChinaCities.name(ModelReader.text(aweme, "city"));
            Object poi = ModelReader.get(aweme, "poiStruct", "poi_info");
            String poiCity = ModelReader.text(ModelReader.get(poi, "address"), "city", "province");
            String ip = ModelReader.text(aweme, "ipAttribution", "ip_attribution").replaceFirst("^\\s*IP\\s*属地\\s*[:：]?\\s*", "").trim();
            String city = !cityName.isEmpty() ? cityName : poiCity;
            String location = !city.isEmpty() ? city : ip;
            if (location.isEmpty()) { reject("no_location uid=" + uid + " city=" + ModelReader.text(aweme, "city") + " ip=" + ip, aweme); return; }
            Latest current = latest.get(uid);
            if (current != null && current.created >= created) return;
            Latest next = new Latest(); next.created = created; next.location = location; next.city = city;
            latest.put(uid, next);
            Log.i(TAG, "douyin_post_seen uid=" + uid + " location=" + location + " city=" + city + " created=" + created);
            Long when = recorded.get(uid);
            if (when != null && SystemClock.elapsedRealtime() - when < 60000) sendPostLocation(uid, next);
        } catch (Throwable error) { Log.w(TAG, "douyin_post_observe_failed", error); }
    }

    private int rejected;
    private void reject(String why, Object aweme) {
        if (rejected++ < 12) Log.i(TAG, "douyin_post_rejected " + why + " aid=" + ModelReader.text(aweme, "aid"));
    }

    private void sendPostLocation(String uid, Latest post) {
        try {
            JSONObject user = new JSONObject().put("platform", "douyin").put("userId", uid).put("latestPostLocation", post.location)
                .put("latestPostAt", post.created);
            if (!post.city.isEmpty()) user.put("latestPostCity", post.city);
            JSONObject data = new JSONObject().put("user", user);
            writer.execute(() -> { try { CollectorClient.call(context, "record_profile", data); } catch (Exception ignored) {} });
            Log.i(TAG, "douyin_latest_post uid=" + uid + " location=" + post.location);
            if (uid.equals(openedUser)) main.postDelayed(this::closeProfilePage, 500);
        } catch (Throwable error) { Log.w(TAG, "douyin_post_send_failed", error); }
    }

    private String text(Object target, String name) {
        try {
            Field f = target.getClass().getField(name);
            Object value = f.get(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) { return ""; }
    }

    /** Called for any User object the app touches; only fully loaded profiles (with an IP location) are recorded. */
    void observe(Object user) {
        String ip = text(user, "ipLocation").replaceFirst("^\\s*IP\\s*属地\\s*[:：]?\\s*", "").trim();
        if (ip.isEmpty()) return;
        String uid = text(user, "uid");
        if (uid.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        Long last = recent.get(uid);
        if (last != null && now - last < 60000) return;
        recent.put(uid, now);
        Log.i(TAG, "douyin_profile_seen uid=" + uid + " ip=" + ip + " city=" + text(user, "cityName") + "/" + text(user, "city") + " province=" + text(user, "province") + " gender=" + text(user, "gender") + " birthday=" + text(user, "birthday"));
        final String region = ip;
        new Thread(() -> {
            try {
                JSONObject task = CollectorClient.call(context, "status", new JSONObject());
                if (!"profile".equals(task.optString("kind")) || !task.optString("status").startsWith("running")) return;
                JSONObject profile = profile(user, uid, region);
                JSONObject data = new JSONObject().put("taskId", task.optLong("taskId")).put("generation", task.optLong("generation")).put("user", profile);
                writer.execute(() -> { try { CollectorClient.call(context, "record_profile", data); } catch (Exception error) { Log.w(TAG, "douyin_profile_storage_failed"); } });
                recorded.put(uid, SystemClock.elapsedRealtime());
                Log.i(TAG, "douyin_profile_observed uid=" + uid + " ip=" + region + " post=" + (latest.get(uid) == null ? "pending" : latest.get(uid).location));
                // Stay on the page until the posts have loaded (or a few seconds pass) so the latest post location is captured too.
                if (uid.equals(openedUser)) main.postDelayed(this::closeProfilePage, latest.get(uid) == null ? 7000 : 700);
            } catch (Throwable error) { Log.w(TAG, "douyin_profile_failed", error); }
        }, "douyin-profile").start();
    }

    private JSONObject profile(Object user, String uid, String region) throws Exception {
        String gender = switch (text(user, "gender")) { case "1" -> "male"; case "2" -> "female"; default -> "unknown"; };
        JSONObject out = new JSONObject().put("platform", "douyin").put("userId", uid).put("gender", gender)
            .put("profileCapturedAt", System.currentTimeMillis()).put("profileSource", "douyin_android.User").put("profileIpRegion", region);
        put(out, "nickname", text(user, "nickname"));
        put(out, "bio", text(user, "signature"));
        String sec = text(user, "secUid"); put(out, "secUid", sec);
        String city = firstNonEmpty(text(user, "cityName"), text(user, "city"));
        String province = text(user, "province");
        String district = text(user, "district");
        String place = city.isEmpty() ? province : province.isEmpty() || province.equals(city) ? city : province + " " + city;
        put(out, "location", district.isEmpty() || place.isEmpty() ? place : place + "·" + district);
        Object age = JSONObject.NULL;
        String birthday = text(user, "birthday");
        if (birthday.matches("(19|20)\\d\\d-\\d\\d-\\d\\d")) {
            int years = Calendar.getInstance().get(Calendar.YEAR) - Integer.parseInt(birthday.substring(0, 4));
            if (years > 0 && years <= 120) age = years;
        }
        out.put("age", age).put("ageVisibility", age == JSONObject.NULL ? "privacy_unspecified" : "public_or_unset");
        Latest post = latest.get(uid);
        if (post != null) { out.put("latestPostLocation", post.location).put("latestPostAt", post.created); if (!post.city.isEmpty()) out.put("latestPostCity", post.city); }
        out.put("fieldSources", new JSONObject().put("gender", "user.gender").put("age", "user.birthday").put("location", "user.city")
            .put("profileIpRegion", "user.ip_location"));
        return out;
    }

    private static String firstNonEmpty(String... values) { for (String v : values) if (v != null && !v.isEmpty()) return v; return ""; }
    private static void put(JSONObject out, String key, String value) throws Exception { if (value != null && !value.isEmpty()) out.put(key, value); }

    private void closeProfilePage() {
        Activity current = activity.get();
        if (current != null && current.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("profile")) current.finish();
    }

    /** While a profile task runs, open each pending user's profile in turn. */
    void startDriver() {
        if (ticking) return;
        ticking = true;
        main.postDelayed(this::tick, 3000);
    }

    private void tick() {
        new Thread(() -> {
            try {
                JSONObject task = CollectorClient.call(context, "status", new JSONObject());
                if ("profile".equals(task.optString("kind")) && task.optString("status").startsWith("running")) {
                    Activity host = activity.get();
                    if (host != null && !host.isFinishing()) drive(task.optLong("taskId"), host);
                }
            } catch (Throwable error) { Log.w(TAG, "douyin_profile_tick_failed", error); }
            main.postDelayed(this::tick, 3000);
        }, "douyin-profile-driver").start();
    }

    private void drive(long taskId, Activity host) throws Exception {
        JSONObject input = new JSONObject().put("taskId", taskId);
        String uid = CollectorClient.call(context, "profile_next", input).optString("userId");
        if (uid.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!uid.equals(openedUser)) {
            if (now - openedAt < 4000) return;
            openedUser = uid; openedAt = now;
            main.post(() -> {
                try { host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("snssdk1128://user/profile/" + Uri.encode(uid))).setPackage(host.getPackageName())); }
                catch (Throwable error) { Log.w(TAG, "douyin_profile_open_failed", error); }
            });
            Log.i(TAG, "douyin_profile_open uid=" + uid);
        } else if (now - openedAt > 25000) {
            CollectorClient.call(context, "profile_next", input.put("failedUserId", uid));
            openedUser = "";
            main.post(this::closeProfilePage);
        }
    }
}
