package dev.local.commentcollector;

final class CurrentTargetPolicy {
    static boolean supportsActivity(String name) {
        return name != null && !name.contains("Search") && !name.contains("UserProfile") && !name.contains("Setting")
            && (name.contains("FlowPageActivity") || name.contains("DetailActivity") || name.contains("MainActivity") || name.contains("SplashActivity"));
    }
    static boolean sameTarget(String expected, String visible) {
        return expected != null && !expected.isEmpty() && expected.equals(visible);
    }
}
