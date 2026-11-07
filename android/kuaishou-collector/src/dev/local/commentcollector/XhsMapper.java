package dev.local.commentcollector;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Maps Xiaohongshu's {@code com.xingin.entities.notedetail.NoteFeed} to the shared post JSON. Inner model classes are
 * obfuscated or renamed between versions, so every field is looked up by several plausible names.
 */
final class XhsMapper {
    static final String NOTE_CLASS = "com.xingin.entities.notedetail.NoteFeed";

    private XhsMapper() {}

    static JSONObject post(Object note) throws Exception {
        if (note == null) return null;
        String id = ModelReader.text(note, "id", "noteId");
        if (id.isEmpty()) return null;
        JSONObject out = new JSONObject().put("platform", "xhs").put("postId", id)
            .put("url", "https://www.xiaohongshu.com/explore/" + id).put("snapshotAt", System.currentTimeMillis()).put("source", "xhs_android.NoteFeed");
        String title = ModelReader.text(note, "title");
        String desc = ModelReader.text(note, "desc");
        out.put("title", title.isEmpty() ? desc : (desc.isEmpty() ? title : title + "\n" + desc));
        long created = number(ModelReader.get(note, "time"));
        if (created > 0) { if (created < 100000000000L) created *= 1000; out.put("created", created).put("publishedAt", created); }
        Object user = ModelReader.get(note, "user");
        put(out, "authorId", ModelReader.text(user, "userid", "userId", "id"));
        put(out, "authorName", ModelReader.text(user, "nickname", "name", "nickName"));

        putNumber(out, "likes", ModelReader.get(note, "likedCount"));
        putNumber(out, "favorites", ModelReader.get(note, "collectedCount"));
        putNumber(out, "commentTotal", ModelReader.get(note, "commentsCount"));
        putNumber(out, "shares", ModelReader.get(note, "sharedCount"));

        // Location: the author's tagged POI / user-set location first, else the note's IP属地 (province level).
        Object poi = ModelReader.get(note, "poi");
        String place = ModelReader.text(poi, "name", "poiName", "title");
        if (place.isEmpty()) place = ModelReader.text(note, "userSetLocation");
        String ip = ModelReader.text(note, "ipLocation").replaceFirst("^\\s*(IP\\s*)?属地\\s*[:：]?\\s*", "").trim();
        if (!ip.isEmpty()) out.put("ipRegion", ip);
        if (!place.isEmpty()) out.put("location", place).put("locationSource", "xhs_android.NoteFeed.poi");
        else if (!ip.isEmpty()) out.put("location", "IP属地：" + ip).put("locationSource", "xhs_android.NoteFeed.ipLocation");

        Object images = ModelReader.get(note, "imageList");
        JSONArray urls = new JSONArray();
        if (images instanceof Iterable) {
            for (Object image : (Iterable<?>) images) {
                String url = ModelReader.url(ModelReader.get(image, "urlSizeLarge", "originUrl", "url", "traceId"));
                if (url.isEmpty()) url = ModelReader.url(image);
                if (!url.isEmpty()) urls.put(url);
            }
        }
        if (urls.length() > 0) { out.put("imageUrls", urls); out.put("coverUrl", urls.getString(0)); }

        Object video = ModelReader.get(note, "video");
        if (video != null) {
            String play = ModelReader.url(ModelReader.get(video, "url", "videoUrl", "h264Url", "originUrl"));
            if (play.isEmpty()) play = ModelReader.url(video);
            if (!play.isEmpty()) out.put("videoUrl", play);
            long duration = number(ModelReader.get(video, "duration"));
            if (duration > 0) out.put("duration", duration < 1000 ? duration * 1000 : duration);
        }
        Object music = ModelReader.get(note, "music");
        put(out, "musicName", ModelReader.text(music, "name", "title", "musicName"));
        put(out, "musicArtist", ModelReader.text(music, "author", "artist", "nickname"));
        String musicUrl = ModelReader.url(ModelReader.get(music, "url", "playUrl", "musicUrl"));
        if (!musicUrl.isEmpty()) out.put("musicUrl", musicUrl);
        return out;
    }

    private static void put(JSONObject out, String key, String value) throws Exception { if (value != null && !value.isEmpty()) out.put(key, value); }
    private static void putNumber(JSONObject out, String key, Object value) throws Exception {
        long n = number(value);
        if (value != null && (n > 0 || value instanceof Number)) out.put(key, n);
    }
    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value).trim()); } catch (NumberFormatException ignored) { return 0; }
    }
}
