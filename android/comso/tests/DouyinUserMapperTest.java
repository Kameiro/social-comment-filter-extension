package dev.local.commentcollector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.json.JSONObject;

public final class DouyinUserMapperTest {
    public static class FakeUrlModel {
        public List<String> urlList = new ArrayList<>();
        public FakeUrlModel(String url) { urlList.add(url); }
    }

    public static class FakeUser {
        public String uid = "user_123456";
        public String secUid = "sec_user_abcdef";
        public String nickname = "测试用户昵称";
        public FakeUrlModel avatarThumb = new FakeUrlModel("https://p3.douyinpic.com/avatar.jpg");
        public int gender = 1; // 1 = male, 2 = female, 0 = unknown
        public String ipLocation = "IP属地：浙江";
        public int userAge = 25;
        public String birthday = "1998-05-12";
    }

    public static class FakeCommentWithMethod {
        public String cid = "comment_999";
        public String text = "这是一条测试评论";
        public Long createTime = 1731000000L;
        public Long diggCount = 42L;
        public String ipLabel = "IP属地：浙江";
        private FakeUser mUser = new FakeUser();
        public FakeUser LLLLZ() { return mUser; }
    }

    public static class FakeCommentWithField {
        public String cid = "comment_888";
        public String text = "第二条测试评论";
        public FakeUser commentUser = new FakeUser();
    }

    private static Object findUser(Object comment) {
        if (comment == null) return null;
        try {
            Method m = comment.getClass().getMethod("LLLLZ");
            Object u = m.invoke(comment);
            if (u != null) return u;
        } catch (Throwable ignored) {}
        try {
            Method m = comment.getClass().getMethod("getUser");
            Object u = m.invoke(comment);
            if (u != null) return u;
        } catch (Throwable ignored) {}
        Object user = ModelReader.get(comment, "user", "commentUser", "mUser", "author", "creator");
        if (user != null) return user;
        return null;
    }

    private static String mapGender(Object g) {
        if (g instanceof Number) {
            int gv = ((Number) g).intValue();
            return gv == 1 ? "male" : gv == 2 ? "female" : "unknown";
        } else if (g != null) {
            String gs = String.valueOf(g).trim();
            return "1".equals(gs) ? "male" : "2".equals(gs) ? "female" : "unknown";
        }
        return "unknown";
    }

    private static String firstUrl(Object object, String... keys) {
        for (String key : keys) {
            String url = ModelReader.url(ModelReader.get(object, key));
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private static List<String> terms(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String term : value.split("[,，、;；|]+")) {
            term = term.trim();
            if (!term.isEmpty() && out.size() < 20) out.add(term.length() > 50 ? term.substring(0, 50) : term);
        }
        return out;
    }

    private static boolean matchesCommentStage(JSONObject params, JSONObject comment) {
        if (params == null || comment == null) return true;
        String text = comment.optString("text", "");

        // 1. 排除关键词：最高优先级一票否决
        // 只要出现排除关键词，即使同时出现了包含关键词，该评论也必须被排除！
        for (String term : terms(params.optString("excludeKeyword"))) {
            if (!term.isEmpty() && text.contains(term)) return false;
        }

        // 2. 评论时间范围：满足近 N 天内
        int recentDays = params.optInt("recentDays");
        if (recentDays > 0) {
            long created = comment.optLong("created");
            if (created < 100000000000L) created *= 1000L;
            if (created < System.currentTimeMillis() - recentDays * 86400000L) return false;
        }

        // 3. 包含关键词（多词逗号/空格分隔，满足任意一个即算命中包含）
        List<String> kwTerms = terms(params.optString("keyword"));
        boolean hasKeyword = !kwTerms.isEmpty();
        boolean keywordMatch = false;
        if (hasKeyword) {
            for (String term : kwTerms) {
                if (!term.isEmpty() && text.contains(term)) { keywordMatch = true; break; }
            }
        }

        // 4. 评论 IP 属地（初筛，直接取自评论自身的 ipRegion）
        String commentRegionVal = params.optString("commentIpRegion").trim();
        if (commentRegionVal.isEmpty() && "comment".equals(params.optString("ipSource"))) {
            commentRegionVal = params.optString("ipRegion").trim();
        }
        List<String> commentRegionTerms = terms(commentRegionVal);
        boolean hasCommentRegion = !commentRegionTerms.isEmpty();
        boolean commentRegionMatch = false;
        if (hasCommentRegion) {
            String commentIp = comment.optString("ipRegion", "");
            for (String reg : commentRegionTerms) {
                if (!reg.isEmpty() && commentIp.contains(reg)) { commentRegionMatch = true; break; }
            }
        }

        // 5. 结合关系（AND 或 OR，但排除词始终一票否决）
        String relation = params.optString("relation", "and");
        if ("or".equals(relation) && hasKeyword && hasCommentRegion) {
            return keywordMatch || commentRegionMatch;
        }
        if (hasKeyword && !keywordMatch) return false;
        if (hasCommentRegion && !commentRegionMatch) return false;
        return true;
    }

    private static void check(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Failed: " + msg);
    }

    public static void main(String[] args) throws Exception {
        // Test 1: User extraction via LLLLZ()
        FakeCommentWithMethod c1 = new FakeCommentWithMethod();
        Object u1 = findUser(c1);
        check(u1 != null, "findUser should find user via LLLLZ()");
        check("user_123456".equals(ModelReader.text(u1, "uid")), "User uid should match");
        check("sec_user_abcdef".equals(ModelReader.text(u1, "secUid")), "secUid should match");
        check("测试用户昵称".equals(ModelReader.text(u1, "nickname")), "Nickname should match");
        check("https://p3.douyinpic.com/avatar.jpg".equals(firstUrl(u1, "avatarThumb")), "Avatar URL via firstUrl should match");

        // Test 2: User extraction via field
        FakeCommentWithField c2 = new FakeCommentWithField();
        c2.commentUser.gender = 2; // female
        c2.commentUser.nickname = "女性用户";
        Object u2 = findUser(c2);
        check(u2 != null, "findUser should find user via field commentUser");
        check("女性用户".equals(ModelReader.text(u2, "nickname")), "Nickname should match for field");

        // Test 3: Gender mapping logic
        check("male".equals(mapGender(ModelReader.get(u1, "gender"))), "Gender 1 should map to male");
        check("female".equals(mapGender(ModelReader.get(u2, "gender"))), "Gender 2 should map to female");
        check("unknown".equals(mapGender(0)), "Gender 0 should map to unknown");
        check("unknown".equals(mapGender(null)), "Null gender should map to unknown");
        check("male".equals(mapGender("1")), "String '1' should map to male");
        check("female".equals(mapGender("2")), "String '2' should map to female");

        // Test 4: Age extraction priority (userAge > birthday)
        Object ageObj = ModelReader.get(u1, "userAge", "age");
        check(ageObj instanceof Number && ((Number) ageObj).intValue() == 25, "userAge should be 25");

        // Test 5: Location formatting (ip + city)
        String ip = "广东";
        String city = "深圳";
        String location = (!city.contains(ip)) ? ip + " " + city : city;
        check("广东 深圳".equals(location), "Location should combine 省 + 市");
        ip = "北京";
        city = "北京市";
        location = (!city.contains(ip)) ? ip + " " + city : city;
        check("北京市".equals(location), "Location should not duplicate city if already containing ip");

        // Test 6: Two-stage matchesCommentStage tests
        JSONObject comment = new JSONObject()
            .put("text", "咨询价格是多少，想买一台")
            .put("ipRegion", "广东")
            .put("created", System.currentTimeMillis() - 3600000L); // 1 hour ago

        // 6a. Null params
        check(matchesCommentStage(null, comment), "Null params matches all");

        // 6b. Exclude keyword & Conflict rule (一票否决)
        JSONObject pExclude = new JSONObject().put("excludeKeyword", "广告, 报价, 价格");
        check(!matchesCommentStage(pExclude, comment), "Exclude keyword '价格' should reject comment");

        // Conflict test: comment matches BOTH include keyword AND exclude keyword -> MUST REJECT!
        JSONObject pConflict = new JSONObject().put("keyword", "买, 价格").put("excludeKeyword", "广告, 价格");
        check(!matchesCommentStage(pConflict, comment), "Conflict: exclude keyword must reject comment even when include keyword matches");

        // 6c. Recent days
        JSONObject pRecentOk = new JSONObject().put("recentDays", 3);
        check(matchesCommentStage(pRecentOk, comment), "Created 1 hr ago should pass 3 recent days");
        JSONObject pRecentExpired = new JSONObject().put("recentDays", 1);
        JSONObject oldComment = new JSONObject(comment.toString()).put("created", System.currentTimeMillis() - 200000000L); // ~2.3 days ago
        check(!matchesCommentStage(pRecentExpired, oldComment), "Created 2 days ago should fail 1 recent day");

        // 6d. Keyword matching
        JSONObject pKwMatch = new JSONObject().put("keyword", "买, 多少");
        check(matchesCommentStage(pKwMatch, comment), "Comment contains '买' should match");
        JSONObject pKwFail = new JSONObject().put("keyword", "投诉, 质量");
        check(!matchesCommentStage(pKwFail, comment), "Comment does not contain keyword should fail (AND)");

        // 6e. Comment IP Region in 初次筛选 (commentIpRegion)
        JSONObject pCommentIpOk = new JSONObject().put("commentIpRegion", "广东, 浙江");
        check(matchesCommentStage(pCommentIpOk, comment), "Comment IP '广东' matches commentIpRegion '广东, 浙江'");
        JSONObject pCommentIpFail = new JSONObject().put("commentIpRegion", "北京, 上海");
        check(!matchesCommentStage(pCommentIpFail, comment), "Comment IP '广东' does not match commentIpRegion '北京, 上海'");

        // 6f. Legacy IP Region & ipSource = comment
        JSONObject pLegacyCommentOk = new JSONObject().put("ipRegion", "广东, 浙江").put("ipSource", "comment");
        check(matchesCommentStage(pLegacyCommentOk, comment), "Legacy comment IP matches");
        JSONObject pLegacyCommentFail = new JSONObject().put("ipRegion", "北京, 上海").put("ipSource", "comment");
        check(!matchesCommentStage(pLegacyCommentFail, comment), "Legacy comment IP does not match");

        // 6g. Profile IP & Post IP (二次筛选, not rejected at comment stage)
        JSONObject pProfileOnly = new JSONObject().put("profileIpRegion", "北京, 上海");
        check(matchesCommentStage(pProfileOnly, comment), "profileIpRegion cannot be rejected at comment stage");
        JSONObject pPostOnly = new JSONObject().put("postIpRegion", "杭州");
        check(matchesCommentStage(pPostOnly, comment), "postIpRegion cannot be rejected at comment stage");

        // 6h. Relation = OR (keyword OR commentIpRegion)
        JSONObject pOrPassKw = new JSONObject().put("keyword", "想买").put("commentIpRegion", "北京").put("relation", "or");
        check(matchesCommentStage(pOrPassKw, comment), "Relation OR passes when keyword matches even if comment region misses");
        JSONObject pOrPassRegion = new JSONObject().put("keyword", "不存在的词").put("commentIpRegion", "广东").put("relation", "or");
        check(matchesCommentStage(pOrPassRegion, comment), "Relation OR passes when comment region matches even if keyword misses");
        JSONObject pOrFailBoth = new JSONObject().put("keyword", "不存在的词").put("commentIpRegion", "北京").put("relation", "or");
        check(!matchesCommentStage(pOrFailBoth, comment), "Relation OR fails when both keyword and comment region miss");

        // 6i. Conflict with Relation = OR (exclude keyword still vetoes everything)
        JSONObject pOrConflict = new JSONObject().put("keyword", "想买").put("commentIpRegion", "广东").put("excludeKeyword", "价格").put("relation", "or");
        check(!matchesCommentStage(pOrConflict, comment), "Relation OR must still reject if exclude keyword matches");

        System.out.println("PASS: DouyinUserMapperTest passed all assertions");
    }
}
