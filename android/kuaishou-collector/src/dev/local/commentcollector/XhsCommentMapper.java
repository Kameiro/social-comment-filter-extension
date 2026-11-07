package dev.local.commentcollector;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Maps the XHS 9.51 comment response (CommentNoteCommentListData) into the shared page format. */
final class XhsCommentMapper {
    private XhsCommentMapper() {}

    static JSONObject page(Object response, String fallbackPost) throws Exception {
        if (response == null) return null;
        Object comments = ModelReader.get(response, "comments");
        if (!(comments instanceof List<?>)) return null;
        String post = fallbackPost == null ? "" : fallbackPost;
        JSONArray rows = new JSONArray();
        for (Object comment : (List<?>) comments) {
            String note = ModelReader.text(comment, "noteId");
            if (!note.isEmpty()) post = note;
        }
        for (Object comment : (List<?>) comments) append(rows, comment, post, "");
        if (rows.length() == 0 || post.isEmpty()) return null;
        JSONObject out = new JSONObject().put("photoId", post).put("postId", post)
            .put("rootId", "").put("rows", rows).put("groups", new JSONArray())
            .put("source", "xhs_android.CommentNoteCommentListData").put("requestCursorKnown", false);
        Object more = ModelReader.get(response, "hasMore");
        if (more instanceof Boolean) {
            out.put("hasMore", more);
            if (Boolean.FALSE.equals(more)) out.put("eofEvidence", "response.hasMore:false");
        }
        Object cursor = ModelReader.get(response, "cursor");
        if (cursor != null) out.put("cursor", String.valueOf(cursor));
        Object total = ModelReader.get(response, "commentCount");
        if (total instanceof Number) out.put("total", ((Number) total).longValue());
        return out;
    }

    private static void append(JSONArray rows, Object comment, String post, String root) throws Exception {
        String id = ModelReader.text(comment, "id");
        if (id.isEmpty()) return;
        String note = ModelReader.text(comment, "noteId");
        if (!note.isEmpty()) post = note;
        JSONObject row = new JSONObject().put("platform", "xhs").put("commentId", id)
            .put("photoId", post).put("postId", post).put("rootId", root)
            .put("level", root.isEmpty() ? 1 : 2);
        String text = ModelReader.text(comment, "content");
        if (!text.isEmpty()) row.put("text", text);
        Object user = ModelReader.get(comment, "user");
        put(row, "userId", ModelReader.text(user, "userid", "userId", "id"));
        put(row, "nickname", ModelReader.text(user, "nickname", "name"));
        put(row, "ipRegion", ModelReader.text(comment, "ipLocation"));
        putNumber(row, "likes", ModelReader.get(comment, "likeCount"));
        putNumber(row, "replies", ModelReader.get(comment, "subCommentCount"));
        String time = ModelReader.text(comment, "time");
        long created = number(time);
        if (created > 0) {
            if (created < 100000000000L) created *= 1000;
            row.put("created", created);
        } else if (!time.isEmpty()) row.put("createdText", time);
        row.put("ipRegionSource", "xhs_android.CommentCommentInfo.ipLocation")
            .put("gender", "unknown").put("capturedAt", System.currentTimeMillis());
        rows.put(row);
        Object replies = ModelReader.get(comment, "subComments");
        if (root.isEmpty() && replies instanceof List<?>) {
            for (Object reply : (List<?>) replies) append(rows, reply, post, id);
        }
    }

    private static void put(JSONObject out, String key, String value) throws Exception {
        if (value != null && !value.isEmpty()) out.put(key, value);
    }

    private static void putNumber(JSONObject out, String key, Object value) throws Exception {
        long n = number(value);
        if (value != null && (n > 0 || value instanceof Number)) out.put(key, n);
    }

    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value).trim()); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
