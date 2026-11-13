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

        // Relative time tests
        check(InitialCommentFilter.parseRelativeTime("刚刚", now) == now, "刚刚 parses to now");
        check(InitialCommentFilter.parseRelativeTime("5分钟前", now) == now - 300_000L, "5分钟前 parses");
        check(InitialCommentFilter.parseRelativeTime("2小时前", now) == now - 7_200_000L, "2小时前 parses");
        check(InitialCommentFilter.parseRelativeTime("昨天", now) == now - 86400000L, "昨天 parses");
        check(InitialCommentFilter.parseRelativeTime("3天前", now) == now - 3 * 86400000L, "3天前 parses");

        JSONObject relJustNow = new JSONObject().put("text", "杭州西湖").put("time", "刚刚");
        check(InitialCommentFilter.matches(new JSONObject().put("recentDays", 30).put("keyword", "杭州"), relJustNow, now), "relative time '刚刚' passes recentDays=30");

        JSONObject relOld = new JSONObject().put("text", "杭州西湖").put("time", "40天前");
        check(!InitialCommentFilter.matches(new JSONObject().put("recentDays", 30).put("keyword", "杭州"), relOld, now), "relative time '40天前' fails recentDays=30");

        // User scenario tests: 近30天内杭州 (关键词 '杭州' OR 评论IP '浙江')
        JSONObject userScenario = new JSONObject()
            .put("recentDays", 30)
            .put("keyword", "杭州")
            .put("commentIpRegion", "浙江")
            .put("relation", "or")
            .put("excludeKeyword", "广告");

        // Case A: Contains "杭州", empty comment IP -> PASS
        JSONObject commentA = new JSONObject().put("text", "杭州哪里最好玩？").put("ipRegion", "").put("created", now - 86400000L);
        check(InitialCommentFilter.matches(userScenario, commentA, now), "User scenario: text='杭州', empty IP passes Stage 1");

        // Case B: No "杭州", comment IP is "浙江" -> PASS
        JSONObject commentB = new JSONObject().put("text", "风景太好看了").put("ipRegion", "浙江").put("created", now - 86400000L);
        check(InitialCommentFilter.matches(userScenario, commentB, now), "User scenario: no '杭州', IP='浙江' passes Stage 1");

        // Case C: No "杭州", comment IP is "江苏" -> FAIL
        JSONObject commentC = new JSONObject().put("text", "风景太好看了").put("ipRegion", "江苏").put("created", now - 86400000L);
        check(!InitialCommentFilter.matches(userScenario, commentC, now), "User scenario: no '杭州', IP='江苏' fails Stage 1");

        // Case D: Contains "杭州" AND contains "广告" -> FAIL (exclude veto)
        JSONObject commentD = new JSONObject().put("text", "杭州同城广告合作").put("ipRegion", "浙江").put("created", now - 86400000L);
        check(!InitialCommentFilter.matches(userScenario, commentD, now), "User scenario: contains exclude keyword fails Stage 1");

        // Case E: Case-insensitivity check (e.g. English keywords)
        JSONObject pCase = new JSONObject().put("keyword", "VIP").put("excludeKeyword", "SPAM");
        JSONObject cUpper = new JSONObject().put("text", "我是vip会员").put("created", now - 1000L);
        check(InitialCommentFilter.matches(pCase, cUpper, now), "Case-insensitive keyword match");
        JSONObject cSpam = new JSONObject().put("text", "我是VIP会员 spam").put("created", now - 1000L);
        check(!InitialCommentFilter.matches(pCase, cSpam, now), "Case-insensitive excludeKeyword match");

        System.out.println("PASS: " + assertions + " initial filter assertions");
    }
}
