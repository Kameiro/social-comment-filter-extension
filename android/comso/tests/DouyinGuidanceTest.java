package dev.local.commentcollector;

import org.json.JSONObject;

public class DouyinGuidanceTest {
    public static void main(String[] args) throws Exception {
        long start = 50_000;
        JSONObject task = new JSONObject().put("taskId", 92).put("generation", 1).put("kind", "comment")
            .put("platform", "douyin").put("status", "running-replies").put("pagingDriver", "native-waiting");
        JSONObject guide = new JSONObject().put("taskId", 92).put("generation", 1).put("startedAt", start)
            .put("attempted", true);

        assert DouyinGuidance.active(task, guide, start);
        assert !DouyinGuidance.active(task, new JSONObject(), start);
        assert !DouyinGuidance.active(task, guide, start - 1);
        assert !DouyinGuidance.canScroll(guide, start + 3_999, 4_000);
        assert !DouyinGuidance.canScroll(guide, start + 4_000, 3_999);
        assert DouyinGuidance.canScroll(guide, start + 4_000, 4_000);
        assert DouyinGuidance.canScroll(new JSONObject(guide.toString()).put("recoveryScrolled", true), start + 8_000, 4_000);
        assert !DouyinGuidance.canScroll(new JSONObject(guide.toString()).put("recoveryScrolls", 3), start + 8_000, 4_000);

        long deadline = start + DouyinGuidance.TIMEOUT_MS;
        assert DouyinGuidance.canScroll(guide, deadline, 4_000);
        assert !DouyinGuidance.canScroll(guide, start + DouyinGuidance.OPEN_DRAWER_RECOVERY_WINDOW_MS, 4_000);
        assert DouyinGuidance.active(task, guide, deadline - 1);
        assert !DouyinGuidance.active(task, guide, deadline);
        assert !DouyinGuidance.shouldPause(task, guide, start, deadline - 1);
        assert DouyinGuidance.shouldPause(task, guide, start, deadline);
        assert !DouyinGuidance.shouldPause(task, guide, start - 1, deadline);
        assert !DouyinGuidance.shouldPause(task, new JSONObject(guide.toString()).put("startedAt", start + 1), start, deadline + 1);
        assert !DouyinGuidance.shouldPause(new JSONObject(task.toString()).put("generation", 2), guide, start, deadline);
        assert !DouyinGuidance.shouldPause(new JSONObject(task.toString()).put("status", "running-profiles"), guide, start, deadline);
        assert !DouyinGuidance.shouldPause(new JSONObject(task.toString()).put("status", "complete"), guide, start, deadline);
        assert !DouyinGuidance.shouldPause(new JSONObject(task.toString()).put("pagingDriver", "native-replies"), guide, start, deadline);
        assert !DouyinGuidance.shouldPause(new JSONObject(task.toString()).put("platformBlocked", true), guide, start, deadline);
        assert !DouyinGuidance.shouldRebindNative(2);
        assert DouyinGuidance.shouldRebindNative(3);
        assert DouyinGuidance.shouldRebindNative(4);
        assert DouyinGuidance.shouldPromoteRoot(0, true, true);
        assert DouyinGuidance.shouldPromoteRoot(92, true, true);
        assert !DouyinGuidance.shouldPromoteRoot(0, false, true);
        assert !DouyinGuidance.shouldPromoteRoot(0, true, false);
        assert DouyinGuidance.keepNativeRootAfterProgress("native-root", 27, 7_999);
        assert !DouyinGuidance.keepNativeRootAfterProgress("native-root", 27, 8_000);
        assert !DouyinGuidance.keepNativeRootAfterProgress("native-root", 0, 1_000);
        assert !DouyinGuidance.keepNativeRootAfterProgress("native-waiting", 27, 1_000);
        assert !DouyinGuidance.shouldEnableUiFallback(2);
        assert DouyinGuidance.shouldEnableUiFallback(3);
        System.out.println("Douyin guidance recovery passed");
    }
}
