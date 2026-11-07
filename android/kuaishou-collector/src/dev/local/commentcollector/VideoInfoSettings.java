package dev.local.commentcollector;

import android.content.Context;
import org.json.JSONObject;

/** Settings for the double-tap post information panel. */
final class VideoInfoSettings {
    static final String NAME = "video_info";

    private VideoInfoSettings() {}

    static JSONObject defaults() {
        try {
            return new JSONObject()
                .put("location", true)
                .put("publish_time", true)
                .put("duration", true)
                .put("statistics", true)
                .put("video", true)
                .put("cover", true)
                .put("current_image", false)
                .put("music", true)
                .put("description", true)
                .put("copy_description", true)
                .put("presentation", "bottom")
                .put("time_format", "yyyy-MM-dd HH:mm:ss");
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    static JSONObject load(Context context) {
        JSONObject result = defaults();
        if (context == null) return result;
        try {
            JSONObject saved = CollectorClient.call(context, "settings_get", new JSONObject().put("name", NAME));
            if (saved != null) {
                java.util.Iterator<String> keys = saved.keys();
                while (keys.hasNext()) {
                    String key = keys.next(); result.put(key, saved.opt(key));
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static volatile JSONObject cache;
    private static volatile long cachedAt;
    private static final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Never blocks: returns the last loaded settings (or defaults) at once and refreshes them in the background,
     * so a double-tap does not wait on a cross-process provider call (which can take seconds if it must be woken).
     */
    static JSONObject current(Context context) {
        JSONObject value = cache;
        if ((value == null || System.currentTimeMillis() - cachedAt > 30000) && context != null && refreshing.compareAndSet(false, true)) {
            new Thread(() -> {
                try { JSONObject loaded = load(context); cache = loaded; cachedAt = System.currentTimeMillis(); }
                finally { refreshing.set(false); }
            }, "video-info-settings").start();
        }
        return value == null ? defaults() : value;
    }

    static void save(Context context, JSONObject settings) throws Exception {
        JSONObject merged = defaults(); java.util.Iterator<String> keys = settings.keys();
        while (keys.hasNext()) { String key = keys.next(); try { merged.put(key, settings.opt(key)); } catch (Exception ignored) {} }
        cache = merged; cachedAt = System.currentTimeMillis();
        CollectorClient.call(context, "settings_save", new JSONObject().put("name", NAME).put("value", settings));
    }

    static boolean enabled(JSONObject settings, String key) {
        return settings == null || !settings.has(key) || settings.optBoolean(key, true);
    }

    static boolean centered(JSONObject settings) {
        return settings != null && "center".equals(settings.optString("presentation", "bottom"));
    }
}
