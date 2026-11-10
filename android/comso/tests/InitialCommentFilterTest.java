package dev.local.commentcollector;

import org.json.JSONObject;

public final class InitialCommentFilterTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        long now = 2_000_000_000_000L;
        JSONObject fresh = new JSONObject().put("text", "想买 广东").put("ipRegion", "广东").put("created", now - 3_600_000L);
        JSONObject old = new JSONObject(fresh.toString()).put("created", now - 4 * 86400000L);

        // Time is the highest-priority gate even when keyword/IP would pass.
        JSONObject bounded = new JSONObject().put("recentDays", 1).put("keyword", "想买").put("commentIpRegion", "广东").put("relation", "or");
        check(InitialCommentFilter.matches(bounded, fresh, now), "fresh comment passes time + OR conditions");
        check(!InitialCommentFilter.matches(bounded, old, now), "expired comment fails before keyword/IP evaluation");
        check(!InitialCommentFilter.matches(new JSONObject().put("recentDays", 1), new JSONObject().put("text", "无时间"), now), "unknown time does not satisfy bounded filter");

        JSONObject and = new JSONObject().put("keyword", "想买").put("commentIpRegion", "北京").put("relation", "and");
        check(!InitialCommentFilter.matches(and, fresh, now), "AND rejects when only keyword matches");
        JSONObject or = new JSONObject(and.toString()).put("relation", "or");
        check(InitialCommentFilter.matches(or, fresh, now), "OR accepts when keyword matches");
        check(InitialCommentFilter.matches(new JSONObject(and.toString()).put("keyword", "不存在" ).put("relation", "or"), fresh, now) == false, "OR rejects when neither matches");
        check(InitialCommentFilter.matches(new JSONObject().put("keyword", "想买").put("relation", "or"), fresh, now), "single keyword ignores stale OR relation");
        check(InitialCommentFilter.matches(new JSONObject().put("commentIpRegion", "广东").put("relation", "or"), fresh, now), "single comment IP ignores stale OR relation");

        JSONObject excluded = new JSONObject(bounded.toString()).put("excludeKeyword", "想买");
        check(!InitialCommentFilter.matches(excluded, fresh, now), "exclude keyword remains veto");
        System.out.println("PASS: " + assertions + " initial filter assertions");
    }
}
