package dev.local.commentcollector;

public final class PostLinkTargetTest {
    public static void main(String[] args) {
        assert "kuaishou".equals(PostLinkTarget.platform("1234567890123456789", "kuaishou", "douyin"));
        assert "douyin".equals(PostLinkTarget.platform("1234567890123456789", "douyin", "kuaishou"));
        assert "xhs".equals(PostLinkTarget.platform("0123456789abcdef01234567", "", ""));
        assert "kuaishou".equals(PostLinkTarget.platform("1234567890123456789", "", "kuaishou"));
        System.out.println("Post link target passed");
    }
}
