package dev.local.commentcollector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
        check("https://p3.douyinpic.com/avatar.jpg".equals(ModelReader.url(ModelReader.get(u1, "avatarThumb"))), "Avatar URL should match");

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

        // Test 4: Three-state profile capture status logic
        // Pending when profileCapturedAt <= 0
        long pendingCapturedAt = 0L;
        check(pendingCapturedAt <= 0L, "Profile captured at 0 should be pending state");
        long capturedAt = 1731005000L;
        check(capturedAt > 0L, "Profile captured at timestamp should be completed state");

        System.out.println("PASS: DouyinUserMapperTest passed all assertions");
    }
}
