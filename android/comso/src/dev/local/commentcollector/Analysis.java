package dev.local.commentcollector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Analysis {
    public interface Source { JSONObject stream(JSONObject query, java.util.function.Consumer<JSONObject> sink, int maxRows) throws Exception; }
    private static final int MAX_ROWS = 20000;
    private Analysis() {}
    public static JSONObject run(JSONObject input, Source source) throws Exception {
        JSONObject query = new JSONObject(input.toString());
        Map<String, JSONObject> users = new HashMap<>(); Map<String, Set<String>> posts = new HashMap<>();
        String exclude = input.optString("excludePhrase").trim();
        JSONObject done = source.stream(query, row -> accumulate(row, users, posts, exclude), MAX_ROWS);
        int read = done.optInt("read"); boolean more = done.optBoolean("more");
        JSONArray result = new JSONArray();
        java.util.List<JSONObject> ordered = new java.util.ArrayList<>(users.values());
        ordered.sort((a, b) -> Integer.compare(b.optInt("commentCount"), a.optInt("commentCount")));
        try { for (JSONObject user : ordered) result.put(user.put("postCount", posts.get(user.getString("userId")).size())); }
        catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        return new JSONObject().put("items", result).put("commentsAnalyzed", read).put("commentsAvailable", more ? read + 1 : read)
            .put("truncated", more).put("basis", "stored_public_comments_only");
    }
    private static void accumulate(JSONObject row, Map<String, JSONObject> users, Map<String, Set<String>> posts, String exclude) {
        try {
            String uid = row.optString("userId"); if (uid.isEmpty() || "null".equals(uid)) return;
            JSONObject user = users.get(uid);
            if (user == null) {
                JSONObject profile = row.optJSONObject("user");
                user = new JSONObject().put("userId", uid).put("nickname", row.opt("nickname")).put("commentCount", 0).put("likesReceived", 0)
                    .put("replyCount", 0).put("unknownLikes", 0).put("unknownReplies", 0).put("firstCommentAt", 0).put("lastCommentAt", 0)
                    .put("excluded", profile != null && !exclude.isEmpty() && profile.optString("bio").contains(exclude));
                users.put(uid, user); posts.put(uid, new HashSet<>());
            }
            user.put("commentCount", user.getInt("commentCount") + 1); posts.get(uid).add(row.optString("photoId"));
            for (String key : new String[]{"likes", "replies"}) {
                String target = "likes".equals(key) ? "likesReceived" : "replyCount", unknown = "likes".equals(key) ? "unknownLikes" : "unknownReplies";
                if (row.isNull(key)) user.put(unknown, user.getInt(unknown) + 1);
                else user.put(target, user.getLong(target) + row.optLong(key));
            }
            long time = row.optLong("created"); if (time > 0 && time < 100000000000L) time *= 1000;
            if (time > 0) { long first = user.getLong("firstCommentAt"); user.put("firstCommentAt", first == 0 ? time : Math.min(first, time)); user.put("lastCommentAt", Math.max(time, user.getLong("lastCommentAt"))); }
            if (!exclude.isEmpty() && row.optString("text").contains(exclude)) user.put("excluded", true);
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
}
