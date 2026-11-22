package dev.local.commentcollector;

import org.json.JSONArray;
import org.json.JSONObject;

final class DouyinRootPolicy {
    static boolean validPage(JSONObject page, String post) {
        if (page == null || post == null || post.isEmpty() || !post.equals(page.optString("photoId")) || !page.optString("rootId").isEmpty()) return false;
        JSONArray rows = page.optJSONArray("rows");
        if (rows == null || rows.length() == 0) return false;
        if (Boolean.FALSE.equals(page.opt("hasMore")) && "0".equals(page.optString("cursor", "0")) && page.optLong("total") == 0) return false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !post.equals(row.optString("photoId"))) return false;
        }
        return true;
    }
    static boolean advance(boolean appended, Object more, boolean root) {
        return appended && root && more instanceof Boolean;
    }

    /** Stored page fingerprints have the form root:cursor:hasMore:commentId[:commentId...]. */
    static long savedNextCursor(String fingerprint) {
        if (!fingerprint.startsWith(":")) return 0;
        int cursorEnd = fingerprint.indexOf(':', 1);
        if (cursorEnd < 0 || !fingerprint.startsWith(":true:", cursorEnd) || fingerprint.length() <= cursorEnd + 6) return 0;
        try {
            return Math.max(0L, Long.parseLong(fingerprint.substring(1, cursorEnd)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
