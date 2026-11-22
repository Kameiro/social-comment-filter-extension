package dev.local.commentcollector;

import org.json.JSONObject;

public class UserLinkTargetTest {
    public static void main(String[] args) throws Exception {
        String xhsId = "0123456789abcdef01234567";
        JSONObject xhs = UserLinkTarget.fromUser(xhsId, "测试用户", new JSONObject().put("platform", "xiaohongshu"), "douyin");
        assert Platforms.XHS.equals(Platforms.packageFor(xhs.optString("platform")));
        assert xhsId.equals(xhs.optString("authorId"));
        JSONObject kuaishou = UserLinkTarget.fromUser("1234567", "测试用户",
            new JSONObject().put("user", new JSONObject().put("platform", "kuaishou")), "douyin");
        assert Platforms.KUAISHOU.equals(Platforms.packageFor(kuaishou.optString("platform")));
        JSONObject legacyXhs = UserLinkTarget.fromUser(xhsId, "旧数据", new JSONObject(), "douyin");
        assert "xhs".equals(legacyXhs.optString("platform"));
        JSONObject douyin = UserLinkTarget.fromUser("123", "测试用户",
            new JSONObject().put("platform", "douyin").put("user", new JSONObject().put("platform", "douyin").put("secUid", "test")), "kuaishou");
        assert Platforms.DOUYIN.equals(Platforms.packageFor(douyin.optString("platform")));
        assert "test".equals(douyin.optString("authorSecUid"));
        JSONObject explicitKuaishou = UserLinkTarget.fromUser("123", "测试用户",
            new JSONObject().put("platform", "kuaishou").put("photoId", "1234567890123456789"), "douyin");
        assert "kuaishou".equals(explicitKuaishou.optString("platform"));
        System.out.println("Cross-platform user link targets passed");
    }
}
