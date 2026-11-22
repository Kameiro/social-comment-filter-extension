package dev.local.commentcollector;

import org.json.JSONObject;

final class DouyinGuidance {
    static final String SETTING = "douyin_guidance";
    static final String TIMEOUT_ISSUE = "douyin_guidance_timeout";
    static final long RETRY_DELAY_MS = 4_000;
    static final long TIMEOUT_MS = 25_000;
    static final long OPEN_DRAWER_RECOVERY_WINDOW_MS = 60_000;
    static final int MAX_RECOVERY_SWIPES = 3;
    static final int NATIVE_REBIND_STALLS = 3;

    private DouyinGuidance() {}

    static boolean paging(String status) {
        return "running".equals(status) || "running-replies".equals(status);
    }

    static boolean matches(JSONObject task, JSONObject guide) {
        return "douyin".equals(task.optString("platform")) && "comment".equals(task.optString("kind"))
            && paging(task.optString("status")) && !task.optBoolean("platformBlocked")
            && guide.optLong("taskId") > 0 && guide.optLong("taskId") == task.optLong("taskId")
            && guide.optLong("generation") == task.optLong("generation") && guide.optLong("startedAt") > 0;
    }

    static boolean active(JSONObject task, JSONObject guide, long now) {
        long age = now - guide.optLong("startedAt");
        return matches(task, guide) && age >= 0 && age < TIMEOUT_MS;
    }

    static boolean shouldPause(JSONObject task, JSONObject guide, long startedAt, long now) {
        return matches(task, guide) && "native-waiting".equals(task.optString("pagingDriver"))
            && guide.optLong("startedAt") == startedAt && now - startedAt >= TIMEOUT_MS;
    }

    static boolean canScroll(JSONObject guide, long now, long userIdleMs) {
        long age = now - guide.optLong("startedAt");
        int attempts = guide.optInt("recoveryScrolls", guide.optBoolean("recoveryScrolled") ? 1 : 0);
        return attempts < MAX_RECOVERY_SWIPES && age >= RETRY_DELAY_MS && age < OPEN_DRAWER_RECOVERY_WINDOW_MS && userIdleMs >= RETRY_DELAY_MS;
    }

    static boolean canKeepWaitingWithDrawer(JSONObject guide, long now) {
        long age = now - guide.optLong("startedAt");
        return age >= 0 && age < OPEN_DRAWER_RECOVERY_WINDOW_MS;
    }

    static boolean shouldEnableUiFallback(int attempts) {
        return attempts >= MAX_RECOVERY_SWIPES;
    }

    static boolean shouldRebindNative(int stalls) {
        return stalls >= NATIVE_REBIND_STALLS;
    }

    /** Root pages observed from the normal response hook have no expected task id. */
    static boolean shouldPromoteRoot(long expectedTask, boolean root, boolean appended) {
        return root && appended;
    }

    static boolean keepNativeRootAfterProgress(String driver, int pages, long ageMs) {
        return "native-root".equals(driver) && pages > 0 && ageMs >= 0 && ageMs < 8_000;
    }
}
