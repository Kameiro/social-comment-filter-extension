package dev.local.commentcollector;

import org.json.JSONArray;
import org.json.JSONObject;

/** Maps Douyin's {@code com.ss.android.ugc.aweme.feed.model.Aweme} to the shared post JSON. Public fields only. */
final class DouyinMapper {
    private DouyinMapper() {}

    static JSONObject post(Object aweme) throws Exception {
        if (aweme == null) return null;
        String id = ModelReader.text(aweme, "aid", "aweme_id");
        if (id.isEmpty()) return null;
        JSONObject out = new JSONObject().put("platform", "douyin").put("postId", id)
            .put("url", "https://www.douyin.com/video/" + id).put("snapshotAt", System.currentTimeMillis()).put("source", "douyin_android.Aweme");
        out.put("title", ModelReader.text(aweme, "desc"));
        long created = number(ModelReader.get(aweme, "createTime", "create_time"));
        if (created > 0) { out.put("created", created); out.put("publishedAt", created); }
        Object author = ModelReader.get(aweme, "author");
        put(out, "authorId", ModelReader.text(author, "uid"));
        put(out, "authorSecUid", ModelReader.text(author, "secUid", "sec_uid"));
        put(out, "authorName", ModelReader.text(author, "nickname"));
        put(out, "authorAvatarUrl", firstUrl(author, "avatarThumb", "avatarMedium", "avatarLarger", "avatarUrl"));

        Object stats = ModelReader.get(aweme, "statistics");
        putNumber(out, "likes", ModelReader.get(stats, "digg_count", "diggCount"));
        putNumber(out, "commentTotal", ModelReader.get(stats, "comment_count", "commentCount"));
        putNumber(out, "favorites", ModelReader.get(stats, "collect_count", "collectCount"));
        putNumber(out, "shares", ModelReader.get(stats, "share_count", "shareCount"));

        // Location: tagged POI first, then the post's city (code → name), then the IP region label.
        Object poi = ModelReader.get(aweme, "poiStruct", "poi_info");
        String poiName = ModelReader.text(poi, "poiName", "poi_name");
        Object address = ModelReader.get(poi, "address");
        String poiCity = ModelReader.text(address, "city", "province");
        String cityCode = ModelReader.text(aweme, "city");
        String cityName = ChinaCities.name(cityCode);
        String ip = ModelReader.text(aweme, "ipAttribution", "ip_attribution").replaceFirst("^\\s*IP\\s*属地\\s*[:：]?\\s*", "").trim();
        if (!ip.isEmpty()) out.put("ipRegion", ip);
        if (!cityCode.isEmpty()) out.put("cityCode", cityCode);
        if (!poiName.isEmpty()) out.put("location", poiName + (poiCity.isEmpty() ? "" : " · " + poiCity)).put("locationSource", "douyin_android.Aweme.poiStruct");
        else if (!cityName.isEmpty()) out.put("location", cityName).put("locationSource", "douyin_android.Aweme.city");
        else if (!ip.isEmpty()) out.put("location", "IP属地：" + ip).put("locationSource", "douyin_android.Aweme.ipAttribution");

        Object video = ModelReader.get(aweme, "video");
        long duration = number(ModelReader.get(aweme, "duration"));
        if (duration <= 0) duration = number(ModelReader.get(video, "duration"));
        if (duration > 0) out.put("duration", duration);
        String cover = ModelReader.url(ModelReader.get(video, "cover", "origin_cover", "originCover"));
        if (!cover.isEmpty()) out.put("coverUrl", cover);
        String play = ModelReader.url(ModelReader.get(video, "play_addr", "playAddr", "play_addr_h264", "download_addr", "downloadAddr"));
        if (!play.isEmpty()) out.put("videoUrl", play);

        Object music = ModelReader.get(aweme, "music");
        put(out, "musicName", ModelReader.text(music, "musicName", "musicTitle", "title"));
        put(out, "musicArtist", ModelReader.text(music, "authorName", "author", "ownerNickname", "owner_nickname"));
        String musicUrl = ModelReader.url(ModelReader.get(music, "play_url", "playUrl"));
        if (!musicUrl.isEmpty()) out.put("musicUrl", musicUrl);

        Object images = ModelReader.get(aweme, "images", "imageInfos");
        if (images instanceof Iterable) {
            JSONArray urls = new JSONArray();
            for (Object image : (Iterable<?>) images) {
                String url = ModelReader.url(ModelReader.get(image, "url_list", "urlList", "labelLarge", "label_large", "display_image", "displayImage"));
                if (url.isEmpty()) url = ModelReader.url(image);
                if (!url.isEmpty()) urls.put(url);
            }
            if (urls.length() > 0) { out.put("imageUrls", urls); out.remove("videoUrl"); }
        }
        return out;
    }

    private static void put(JSONObject out, String key, String value) throws Exception { if (value != null && !value.isEmpty()) out.put(key, value); }
    private static void putNumber(JSONObject out, String key, Object value) throws Exception { if (value instanceof Number) out.put(key, ((Number) value).longValue()); }
    private static String firstUrl(Object object, String... keys) {
        for (String key : keys) {
            String url = ModelReader.url(ModelReader.get(object, key));
            if (!url.isEmpty()) return url;
        }
        return "";
    }
    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) { return 0; }
    }
}
