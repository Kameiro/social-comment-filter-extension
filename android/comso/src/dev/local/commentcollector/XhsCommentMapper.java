package dev.local.commentcollector;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Maps Xiaohongshu 9.51.0 comment responses into the shared page format:
 * - com.xingin.entities.q: L1 root comment list
 * - com.xingin.entities.v: sub-comment response (rootComment + subComments)
 * - com.xingin.entities.w: sub-comment page (comments with rootCommentId)
 */
final class XhsCommentMapper {
    private XhsCommentMapper() {}

    static JSONObject page(Object response, String fallbackPost) throws Exception {
        if (response == null) return null;
        String post = fallbackPost == null ? "" : fallbackPost;
        JSONArray rows = new JSONArray();
        JSONArray groups = new JSONArray();
        String pageRoot = "";
        String source = "xhs_android.CommentNoteCommentListData";

        Object rootComment = ModelReader.get(response, "rootComment");
        Object subCommentsObj = ModelReader.get(response, "subComments");
        Object commentsObj = ModelReader.get(response, "comments");

        if (rootComment != null || (subCommentsObj instanceof List<?> && commentsObj == null)) {
            // Model v: sub-comment response with rootComment + subComments
            source = "xhs_android.CommentSubCommentList";
            String rootId = ModelReader.text(rootComment, "id");
            String note = ModelReader.text(rootComment, "noteId");
            if (!note.isEmpty()) post = note;
            if (subCommentsObj instanceof List<?>) {
                for (Object sub : (List<?>) subCommentsObj) {
                    if (post.isEmpty()) { String n = ModelReader.text(sub, "noteId"); if (!n.isEmpty()) post = n; }
                    if (rootId.isEmpty()) {
                        String r = ModelReader.text(sub, "rootCommentId");
                        if (!r.isEmpty() && !"0".equals(r)) rootId = r;
                    }
                }
            }
            pageRoot = rootId;
            if (rootComment != null && !rootId.isEmpty()) {
                append(rows, rootComment, post, "");
            }
            if (subCommentsObj instanceof List<?>) {
                for (Object sub : (List<?>) subCommentsObj) {
                    append(rows, sub, post, pageRoot);
                }
            }
        } else if (commentsObj instanceof List<?>) {
            List<?> comments = (List<?>) commentsObj;
            for (Object comment : comments) {
                String note = ModelReader.text(comment, "noteId");
                if (!note.isEmpty()) post = note;
            }

            // Detect if this is a sub-comment page (Model w): comments pointing to a rootCommentId
            String detectedRoot = "";
            for (Object comment : comments) {
                String cId = ModelReader.text(comment, "id");
                String rId = ModelReader.text(comment, "rootCommentId");
                if (rId.isEmpty() || "0".equals(rId) || rId.equals(cId)) {
                    Object target = ModelReader.get(comment, "targetComment");
                    rId = ModelReader.text(target, "id", "rootCommentId");
                }
                if (!rId.isEmpty() && !rId.equals(cId) && !"0".equals(rId)) {
                    detectedRoot = rId;
                    break;
                }
            }

            if (!detectedRoot.isEmpty()) {
                // Model w: sub-comment page
                source = "xhs_android.CommentSubCommentPage";
                pageRoot = detectedRoot;
                for (Object comment : comments) {
                    append(rows, comment, post, pageRoot);
                }
            } else {
                // Model q: L1 root comment page
                source = "xhs_android.CommentNoteCommentListData";
                pageRoot = "";
                for (Object comment : comments) {
                    String cId = ModelReader.text(comment, "id");
                    append(rows, comment, post, "");

                    long subCount = number(ModelReader.get(comment, "subCommentCount"));
                    if (subCount > 0 && !cId.isEmpty()) {
                        Object inlineSubs = ModelReader.get(comment, "subComments");
                        int inlineSize = (inlineSubs instanceof List<?>) ? ((List<?>) inlineSubs).size() : 0;
                        Object subHasMore = ModelReader.get(comment, "subCommentHasMore");
                        String subCursor = ModelReader.text(comment, "subCommentCursor");
                        boolean hasMoreTrue = Boolean.TRUE.equals(subHasMore);
                        if (inlineSize >= subCount || Boolean.FALSE.equals(subHasMore) || (!hasMoreTrue && subCursor.isEmpty())) {
                            groups.put(new JSONObject().put("rootId", cId).put("complete", true));
                        }
                    }
                }
            }
        } else {
            return null;
        }

        if (rows.length() == 0 || post.isEmpty()) return null;

        JSONObject out = new JSONObject().put("platform", "xhs").put("photoId", post).put("postId", post)
            .put("rootId", pageRoot).put("rows", rows).put("groups", groups)
            .put("source", source).put("requestCursorKnown", false);

        Object more = ModelReader.get(response, "hasMore");
        if (more instanceof Boolean) {
            out.put("hasMore", more);
            if (Boolean.FALSE.equals(more)) {
                out.put("eofEvidence", "response.hasMore:false");
                if (!pageRoot.isEmpty()) {
                    groups.put(new JSONObject().put("rootId", pageRoot).put("complete", true));
                }
            }
        }
        Object cursor = ModelReader.get(response, "cursor");
        if (cursor != null) out.put("cursor", String.valueOf(cursor));

        if (pageRoot.isEmpty()) {
            Object total = ModelReader.get(response, "commentCount");
            if (total instanceof Number) out.put("total", ((Number) total).longValue());
        }

        return out;
    }

    private static void append(JSONArray rows, Object comment, String post, String root) throws Exception {
        String id = ModelReader.text(comment, "id");
        if (id.isEmpty()) return;
        String note = ModelReader.text(comment, "noteId");
        if (!note.isEmpty()) post = note;

        if (root.isEmpty()) {
            String parent = ModelReader.text(comment, "rootCommentId");
            if (parent.isEmpty() || "0".equals(parent) || parent.equals(id)) {
                Object target = ModelReader.get(comment, "targetComment");
                parent = ModelReader.text(target, "id", "rootCommentId");
            }
            if (!parent.isEmpty() && !parent.equals(id) && !"0".equals(parent)) root = parent;
        }

        JSONObject row = new JSONObject().put("platform", "xhs").put("commentId", id)
            .put("photoId", post).put("postId", post).put("rootId", root)
            .put("level", root.isEmpty() ? 1 : 2);

        String text = ModelReader.text(comment, "content");
        if (text.isEmpty()) {
            Object pics = ModelReader.get(comment, "pictures", "pictureInfo", "picList");
            if (pics instanceof Iterable && ((Iterable<?>) pics).iterator().hasNext()) {
                text = "[图片]";
            } else if (pics instanceof Object[] && ((Object[]) pics).length > 0) {
                text = "[图片]";
            } else if (ModelReader.get(comment, "memeInfo", "meme", "emoji") != null) {
                text = "[表情]";
            }
        }
        if (!text.isEmpty()) row.put("text", text);

        Object user = ModelReader.get(comment, "user");
        put(row, "userId", ModelReader.text(user, "userid", "userId", "id"));
        put(row, "nickname", ModelReader.text(user, "nickname", "name"));
        String avatar = firstUrl(user, "image", "avatar", "images", "imageSizeLarge", "avatarHolder");
        if (avatar.isEmpty()) avatar = firstUrl(comment, "userAvatar", "userImage", "avatar");
        put(row, "avatarUrl", avatar);

        String ip = ModelReader.text(comment, "ipLocation").replaceFirst("^\\s*(IP\\s*)?属地\\s*[:：]?\\s*", "").trim();
        put(row, "ipRegion", ip);

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

    private static String firstUrl(Object object, String... keys) {
        for (String key : keys) {
            String url = ModelReader.url(ModelReader.get(object, key));
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value).trim()); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
