package dev.local.commentcollector;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SnapshotMapper {
    private SnapshotMapper() {}

    public static Object field(Object object, String... names) {
        if (object == null) return null;
        for (String name : names) {
            for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(object);
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        return null;
    }

    private static String text(Object object) { return object == null ? "" : String.valueOf(object); }
    private static void put(JSONObject json, String key, Object value) throws Exception {
        json.put(key, value == null ? JSONObject.NULL : value);
    }
    public static Object invoke(Object object, String method, Class<?>[] types, Object... arguments) {
        if (object == null) return null;
        try { return object.getClass().getMethod(method, types).invoke(object, arguments); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }
    private static Boolean replyMore(Object sub, Object cursor) {
        if (sub == null || !"com.kuaishou.android.model.mix.QSubComment".equals(sub.getClass().getName())) return null;
        try {
            Object value = sub.getClass().getClassLoader().loadClass("j6a.a").getMethod("a", String.class).invoke(null, cursor);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    public static JSONObject page(Object response, JSONObject request) throws Exception {
        if (response == null) return null;
        String type = response.getClass().getName();
        boolean correlatedReply = "reply".equals(request.optString("streamType")) && !request.optString("rootId").isEmpty() && !request.optString("photoId").isEmpty();
        if (!type.contains("CommentResponse") && !type.endsWith("QSubComment") &&
            !type.endsWith("CommentsResponse") && !(correlatedReply && type.endsWith("Response"))) return null;
        Object source = field(response, "mComments", "comments", "rootComments");
        if (!(source instanceof List<?>) && !request.optString("rootId").isEmpty()) source = field(response, "mSubComments", "subComments");
        if (!(source instanceof List<?>)) return null;
        JSONArray rows = new JSONArray();
        String requestPhoto = request.optString("photoId");
        String rootId = request.optString("rootId");
        for (Object comment : (List<?>) source) {
            append(rows, comment, requestPhoto, rootId);
        }
        String photo = requestPhoto;
        if (photo.isEmpty() && rows.length() > 0) photo = rows.getJSONObject(0).optString("photoId");
        if (photo.isEmpty()) return null;
        if (type.endsWith("QSubComment") && rootId.isEmpty()) {
            if (rows.length() == 0 || rows.getJSONObject(0).optString("rootId").isEmpty()) return null;
            rootId = rows.getJSONObject(0).optString("rootId");
        }
        for (int i = 0; i < rows.length(); i++) {
            if (!photo.equals(rows.getJSONObject(i).optString("photoId"))) return null;
        }
        JSONObject page = new JSONObject();
        page.put("photoId", photo).put("rootId", rootId).put("rows", rows);
        page.put("requestCursorKnown", request.optBoolean("requestCursorKnown"));
        if (request.optBoolean("requestCursorKnown")) page.put("requestCursor", request.optString("requestCursor"));
        Object more = field(response, "mHasMore", "hasMore");
        Object cursor = field(response, "mCursor", "pCursor", "mPcursor", "pcursor", "cursor");
        Object effectiveCursor = invoke(response, "getCursor", new Class<?>[0]);
        if (effectiveCursor instanceof String && !((String) effectiveCursor).isEmpty()) cursor = effectiveCursor;
        Object moreMethod = invoke(response, "hasMore", new Class<?>[0]);
        if (more == null && moreMethod instanceof Boolean && !text(cursor).isEmpty()) more = moreMethod;
        // Only explicit protocol evidence confirms EOF. Null/empty cursors do not.
        Boolean hasMore = more instanceof Boolean ? (Boolean) more : null;
        Boolean nativeReplyMore = type.endsWith("QSubComment") ? replyMore(response, cursor) : null;
        java.util.Set<String> unique = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) unique.add(rows.getJSONObject(i).getString("commentId"));
        boolean cacheExhausted = request.optInt("expectedReplies", -1) > 0 && unique.size() >= request.optInt("expectedReplies");
        if (hasMore == null && (Boolean.TRUE.equals(nativeReplyMore) || Boolean.FALSE.equals(nativeReplyMore) && cacheExhausted)) hasMore = nativeReplyMore;
        if (more instanceof Number && (((Number) more).intValue() == 0 || ((Number) more).intValue() == 1))
            hasMore = ((Number) more).intValue() == 1;
        if ("no_more".equals(text(cursor))) hasMore = false;
        put(page, "hasMore", hasMore);
        page.put("eofEvidence", Boolean.FALSE.equals(hasMore) ? "no_more".equals(text(cursor)) ? "cursor:no_more" : nativeReplyMore != null ? "reply.sdk_cursor:false" : moreMethod instanceof Boolean ? "response.hasMore:false" : "response.hasMoreField:false" : "");
        put(page, "cursor", cursor);
        put(page, "total", field(response, "mCommentCount", "commentCount"));
        page.put("source", type).put("appVersion", request.optString("appVersion"));
        JSONArray groups = new JSONArray();
        java.util.Map<String, Integer> expected = new java.util.HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.optInt("level") == 1 && !row.isNull("replies")) expected.put(row.getString("commentId"), row.optInt("replies"));
        }
        Object subMap = field(response, "mSubCommentMap", "subCommentsMap");
        if (subMap instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) subMap).entrySet()) {
                subGroup(groups, rows, photo, text(entry.getKey()), entry.getValue(), expected.get(text(entry.getKey())));
            }
        }
        for (Object comment : (List<?>) source) {
            Object sub = field(comment, "mSubComment");
            if (sub != null) subGroup(groups, rows, photo, text(field(comment, "mId")), sub, expected.get(text(field(comment, "mId"))));
        }
        Object additional = field(response, "mSubComments");
        if (additional instanceof List<?>) for (Object comment : (List<?>) additional) append(rows, comment, photo, rootId);
        for (int i = 0; i < rows.length(); i++) {
            if (!photo.equals(rows.getJSONObject(i).optString("photoId"))) return null;
        }
        page.put("groups", groups);
        return page;
    }

    private static void subGroup(JSONArray groups, JSONArray rows, String photo, String root, Object sub, Integer expected) throws Exception {
        Object list = field(sub, "mComments", "subComments");
        if (list instanceof List<?>) for (Object comment : (List<?>) list) append(rows, comment, photo, root);
        Object cursor = field(sub, "mCursor", "pcursor");
        java.util.Set<String> unique = new java.util.HashSet<>();
        if (list instanceof List<?>) for (Object comment : (List<?>) list) unique.add(text(field(comment, "mId", "comment_id")));
        unique.remove("");
        boolean complete = "no_more".equals(text(cursor)) || expected != null && expected > 0 && unique.size() >= expected && Boolean.FALSE.equals(replyMore(sub, cursor));
        groups.put(new JSONObject().put("rootId", root).put("complete", complete)
            .put("eofEvidence", complete ? "no_more".equals(text(cursor)) ? "cursor:no_more" : "reply.sdk_cursor:false" : ""));
    }

    private static void append(JSONArray rows, Object comment, String fallbackPhoto, String fallbackRoot) throws Exception {
        String id = text(field(comment, "mId", "comment_id"));
        if (id.isEmpty()) return;
        String photo = text(field(comment, "mPhotoId", "photo_id"));
        String root = text(field(comment, "mRootCommentId", "rootCommentId"));
        if (root.matches("0+|-1|null|undefined")) root = "";
        if (photo.isEmpty()) photo = fallbackPhoto;
        if (root.isEmpty()) root = fallbackRoot;
        Object user = field(comment, "mUser");
        JSONObject row = new JSONObject().put("platform", "kuaishou").put("commentId", id)
            .put("photoId", photo).put("postId", photo).put("rootId", root).put("level", root.isEmpty() || root.equals(id) ? 1 : 2);
        put(row, "text", field(comment, "mComment", "content"));
        Object author = field(comment, "mAuthorId", "author_id");
        if (author == null) author = field(user, "mId");
        put(row, "userId", author == null ? null : text(author));
        Object name = field(comment, "mAuthorName", "author_name");
        if (name == null) name = field(user, "mName");
        put(row, "nickname", name);
        row.put("gender", PublicMapper.gender(field(user, "mSex")));
        put(row, "created", field(comment, "mCreated", "created"));
        put(row, "likes", field(comment, "mLikedCount", "likedCount"));
        put(row, "replies", field(comment, "mSubCommentCount", "subCommentCount"));
        put(row, "ipRegion", field(comment, "mAuthorArea", "authorArea"));
        row.put("ipRegionSource", "kuaishou_android.authorArea");
        row.put("capturedAt", System.currentTimeMillis());
        rows.put(row);
    }
}
