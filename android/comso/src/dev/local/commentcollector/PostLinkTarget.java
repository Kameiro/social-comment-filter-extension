package dev.local.commentcollector;

final class PostLinkTarget {
    private PostLinkTarget() {}

    static String platform(String id, String explicit, String fallback) {
        String value = Platforms.normalize(explicit);
        if (!value.isEmpty()) return value;
        value = Platforms.normalize(fallback);
        if (!value.isEmpty()) return value;
        if (id != null && id.matches("[0-9a-fA-F]{24}")) return "xhs";
        if (id != null && id.matches("\\d{19}")) return "douyin";
        return "";
    }
}
