package dev.local.commentcollector;

import org.json.JSONObject;

/** Pure task progress labels shared by the collector card and its tests. */
final class TaskProgress {
    private TaskProgress() {}
    static String state(JSONObject task) {
        String status = task.optString("status");
        if ("paused".equals(status)) return "已暂停";
        if ("stopped".equals(status)) return "已停止";
        if ("queued".equals(status)) return "排队中";
        if ("complete".equals(status) && allComplete(task)) return "全部完成";
        if ("comment".equals(task.optString("kind"))) {
            if (task.optBoolean("rootComplete")) {
                if (pendingReplies(task) > 0 || incompleteReplyGroups(task)) return "补充回复中";
                if (pendingProfiles(task)) return "补充资料中";
            }
            if ("native-waiting".equals(task.optString("pagingDriver")) && status.startsWith("running")) return "等待评论加载";
        }
        if ("running-replies".equals(status)) return "补充回复中";
        if ("running-profiles".equals(status)) return "补充资料中";
        if ("running".equals(status)) return "采集中";
        return "待命";
    }
    static boolean allComplete(JSONObject task) {
        if (!"comment".equals(task.optString("kind"))) return "complete".equals(task.optString("status"));
        return task.optBoolean("rootComplete") && pendingReplies(task) == 0 && !incompleteReplyGroups(task) && !pendingProfiles(task);
    }
    static String rootLine(JSONObject task) {
        int count = task.optInt("rootComments");
        String tail = task.optBoolean("rootComplete") ? "已到末页" : "仍在读取";
        return "一级评论 " + count + " 条 · 二级评论 " + task.optInt("replyComments") + " 条 · " + tail;
    }
    static String replyLine(JSONObject task) {
        int complete = task.optInt("replyGroupsComplete"), total = task.optInt("replyGroups"), pending = pendingReplies(task);
        return "回复组 " + complete + " / " + total + " 组 · 剩余 " + pending + " 组";
    }
    static String profileLine(JSONObject task) {
        int done = task.optInt("profilesDone"), total = task.optInt("profilesTotal"), failed = task.optInt("profilesFailed");
        return "资料采集 " + done + " / " + total + " 位" + (failed > 0 ? " · 失败 " + failed + " 位" : "");
    }
    static String commentCountLine(JSONObject task) {
        return "平台显示总数 " + number(task, "total") + " · 符合条件已保存 " + task.optInt("comments") + " 条";
    }
    private static int pendingReplies(JSONObject task) {
        int value = task.optInt("pendingReplies", -1);
        return value >= 0 ? value : Math.max(0, task.optInt("replyGroups") - task.optInt("replyGroupsComplete"));
    }
    private static boolean incompleteReplyGroups(JSONObject task) {
        int total = task.optInt("replyGroups");
        return total > 0 && task.optInt("replyGroupsComplete") < total;
    }
    private static boolean pendingProfiles(JSONObject task) {
        return task.optInt("profilesTotal") > task.optInt("profilesDone") + task.optInt("profilesFailed");
    }
    private static String number(JSONObject task, String key) {
        return task.has(key) && !task.isNull(key) ? task.optString(key) : "未知";
    }
}
