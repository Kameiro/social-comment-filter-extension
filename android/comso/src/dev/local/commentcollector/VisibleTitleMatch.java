package dev.local.commentcollector;

final class VisibleTitleMatch {
    static boolean matches(String title, String visible) {
        if (title == null || visible == null) return false;
        String a = title.replaceAll("\\s+", "").trim();
        String b = visible.replaceAll("\\s+", "").trim();
        return a.length() >= 8 && b.length() >= 8 && (a.startsWith(b) || b.startsWith(a));
    }
}
