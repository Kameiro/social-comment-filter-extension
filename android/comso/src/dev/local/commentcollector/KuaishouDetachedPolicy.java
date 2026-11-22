package dev.local.commentcollector;

import org.json.JSONObject;

/** Pure task/response gates for Kuaishou detached paging. */
final class KuaishouDetachedPolicy {
    private KuaishouDetachedPolicy() {}

    static boolean canDetach(JSONObject task, String boundPhoto) {
        if (task == null || !"comment".equals(task.optString("kind"))
            || !task.optBoolean("auto") || !task.optString("status").startsWith("running")
            || task.optBoolean("platformBlocked")) return false;
        String photo = task.optString("photoId");
        JSONObject params = task.optJSONObject("params");
        return !photo.isEmpty() && photo.equals(boundPhoto)
            && (params == null || !"ui".equals(params.optString("pagingMode")));
    }

    static boolean acceptsPage(JSONObject task, long generation, String responsePhoto) {
        return task != null && "comment".equals(task.optString("kind"))
            && task.optString("status").startsWith("running")
            && task.optLong("generation") == generation
            && task.optString("photoId").equals(responsePhoto);
    }

    static boolean shouldRebind(int failures) { return failures >= 3 && failures <= 5; }
}
