package dev.local.commentcollector;

public final class CurrentTargetPolicyTest {
    public static void main(String[] args) {
        assert CurrentTargetPolicy.supportsActivity("com.ss.splash.SplashActivity");
        assert CurrentTargetPolicy.supportsActivity("com.ss.FlowPageActivity");
        assert !CurrentTargetPolicy.supportsActivity("com.ss.SearchResultActivity");
        assert !CurrentTargetPolicy.supportsActivity("com.ss.UnknownActivity");
        assert CurrentTargetPolicy.sameTarget("A", "A");
        assert !CurrentTargetPolicy.sameTarget("A", "B");
        assert !CurrentTargetPolicy.sameTarget("A", "");
        assert !CurrentTargetPolicy.sameTarget("A", null);
        System.out.println("Current target policy passed");
    }
}
