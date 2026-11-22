package dev.local.commentcollector;

public final class KuaishouActivationPolicyTest {
    public static void main(String[] args) {
        assert KuaishouActivationPolicy.shouldActivate("kuaishou", "running", true, true, false, false, 0);
        assert !KuaishouActivationPolicy.shouldActivate("douyin", "running", true, true, false, false, 0);
        assert !KuaishouActivationPolicy.shouldActivate("kuaishou", "running", true, false, false, false, 0);
        assert !KuaishouActivationPolicy.shouldActivate("kuaishou", "running", true, true, true, false, 0);
        assert !KuaishouActivationPolicy.shouldActivate("kuaishou", "running", true, true, false, true, 0);
        assert !KuaishouActivationPolicy.shouldActivate("kuaishou", "running", true, true, false, false, TargetPostGate.MAX_OPEN_ATTEMPTS);
        assert "正在自动打开评论区".equals(KuaishouActivationPolicy.label(true, false, false, true));
        assert "需要首次打开评论区激活".equals(KuaishouActivationPolicy.label(false, false, false, true));
        assert "请点击“打开评论区激活”".equals(KuaishouActivationPolicy.label(false, false, false, false));
        assert "已激活，可浏览其他内容".equals(KuaishouActivationPolicy.label(false, false, true, true));
        assert KuaishouActivationPolicy.shouldRebind(20000, 5000, 3, 20);
        assert KuaishouActivationPolicy.shouldRebind(25000, 5000, 40, 99);
        assert !KuaishouActivationPolicy.shouldRebind(19000, 5000, 0, 20);
        assert !KuaishouActivationPolicy.shouldRebind(19000, 5000, 3, 20);
        assert KuaishouActivationPolicy.shouldUiFallback("kuaishou", "comment", "auto", true, true, true, 37);
        assert !KuaishouActivationPolicy.shouldUiFallback("kuaishou", "comment", "native", true, true, true, 37);
        assert !KuaishouActivationPolicy.shouldUiFallback("kuaishou", "comment", "auto", true, false, true, 37);
        System.out.println("Kuaishou activation policy passed");
    }
}
