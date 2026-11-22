package dev.local.commentcollector;

import org.json.JSONObject;

public final class TaskProgressTest {
    public static void main(String[] args) throws Exception {
        JSONObject running = new JSONObject().put("kind", "comment").put("status", "running").put("rootComments", 12).put("comments", 12).put("total", 100);
        assert "采集中".equals(TaskProgress.state(running));
        assert "一级评论 12 条 · 二级评论 0 条 · 仍在读取".equals(TaskProgress.rootLine(running));
        JSONObject replies = new JSONObject().put("kind", "comment").put("status", "running-replies").put("rootComplete", true).put("rootComments", 208).put("replyComments", 138).put("comments", 346).put("total", 1433).put("replyGroups", 50).put("replyGroupsComplete", 29).put("pendingReplies", 22);
        assert "补充回复中".equals(TaskProgress.state(replies));
        assert "一级评论 208 条 · 二级评论 138 条 · 已到末页".equals(TaskProgress.rootLine(replies));
        assert "回复组 29 / 50 组 · 剩余 22 组".equals(TaskProgress.replyLine(replies));
        assert "平台显示总数 1433 · 符合条件已保存 346 条".equals(TaskProgress.commentCountLine(replies));
        JSONObject complete = new JSONObject().put("kind", "comment").put("status", "complete").put("rootComplete", true).put("replyGroups", 2).put("replyGroupsComplete", 2).put("pendingReplies", 0).put("profilesTotal", 3).put("profilesDone", 3);
        assert "全部完成".equals(TaskProgress.state(complete));
        JSONObject profiles = new JSONObject().put("kind", "comment").put("status", "running-profiles").put("rootComplete", true).put("replyGroups", 0).put("replyGroupsComplete", 0).put("pendingReplies", 0).put("profilesTotal", 4).put("profilesDone", 2);
        assert "补充资料中".equals(TaskProgress.state(profiles));
        assert "资料采集 2 / 4 位".equals(TaskProgress.profileLine(profiles));
    }
}
