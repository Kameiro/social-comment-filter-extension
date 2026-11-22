package dev.local.commentcollector;

import java.util.Arrays;
import java.util.List;

/** Host apps the module runs inside, and per-platform identifiers. */
final class Platforms {
    static final String KUAISHOU = "com.smile.gifmaker";
    static final String DOUYIN = "com.ss.android.ugc.aweme";
    static final String XHS = "com.xingin.xhs";
    static final List<String> HOSTS = Arrays.asList(KUAISHOU, DOUYIN, XHS);

    private Platforms() {}

    /** "kuaishou" / "douyin" / "xiaohongshu" for a host package. */
    static String name(String packageName) {
        if (DOUYIN.equals(packageName)) return "douyin";
        if (XHS.equals(packageName)) return "xhs";
        return "kuaishou";
    }

    static String normalize(String platform) {
        return switch (platform == null ? "" : platform.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "xiaohongshu", "xhs" -> "xhs";
            case "douyin" -> "douyin";
            case "kuaishou" -> "kuaishou";
            default -> "";
        };
    }

    static String packageFor(String platform) {
        return switch (normalize(platform)) {
            case "douyin" -> DOUYIN;
            case "xhs" -> XHS;
            case "kuaishou" -> KUAISHOU;
            default -> "";
        };
    }

    static String label(String platform) {
        switch (platform == null ? "" : platform) {
            case "douyin": return "抖音";
            case "xhs": case "xiaohongshu": return "小红书";
            default: return "快手";
        }
    }
}
