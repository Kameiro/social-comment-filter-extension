package dev.local.commentcollector;

/** Pure decisions for the first Kuaishou comment-drawer activation. */
final class KuaishouActivationPolicy {
    private KuaishouActivationPolicy() {}

    static boolean shouldActivate(String platform, String status, boolean auto, boolean onTarget,
                                  boolean listShown, boolean detachedReady, int attempts) {
        return "kuaishou".equals(Platforms.normalize(platform))
            && auto && status != null && status.startsWith("running")
            && onTarget && !listShown && !detachedReady && attempts < TargetPostGate.MAX_OPEN_ATTEMPTS;
    }

    static String label(boolean activating, boolean listShown, boolean detachedReady, boolean buttonFound) {
        if (detachedReady) return "已激活，可浏览其他内容";
        if (listShown) return "评论列表已打开，正在读取";
        if (activating) return "正在自动打开评论区";
        return buttonFound ? "需要首次打开评论区激活" : "请点击“打开评论区激活”";
    }

    static boolean shouldRebind(long now, long lastProgressAt, int pages, int comments) {
        return pages > 0 && comments >= 0 && lastProgressAt > 0 && now - lastProgressAt >= 15000L;
    }

    static boolean shouldUiFallback(String platform, String kind, String mode, boolean auto,
                                    boolean onTarget, boolean listShown, int rootPages) {
        return "kuaishou".equals(Platforms.normalize(platform)) && "comment".equals(kind)
            && "auto".equals(mode) && auto && onTarget && listShown && rootPages > 0;
    }
}
