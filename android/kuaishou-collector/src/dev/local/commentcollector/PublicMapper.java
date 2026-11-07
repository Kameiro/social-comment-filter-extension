package dev.local.commentcollector;

import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Whitelists public model fields; never serializes entire platform objects. */
public final class PublicMapper {
    private PublicMapper() {}
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static void put(JSONObject out, String key, Object value) throws Exception { out.put(key, value == null ? JSONObject.NULL : value); }
    private static Object first(Object object, String... names) {
        for (String name : names) {
            Object value = SnapshotMapper.field(object, name);
            if (value == null && name.startsWith("get")) value = SnapshotMapper.invoke(object, name, new Class<?>[0]);
            if (value != null) return value;
        }
        return null;
    }
    private static String mediaText(Object value, String... names) {
        if (value == null) return "";
        if (value instanceof CharSequence || value instanceof Number) return text(value);
        return text(first(value, names));
    }
    private static String urlText(Object value) {
        if (value == null) return "";
        if (value instanceof CharSequence) return nonEmpty(value);
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                String url = urlText(java.lang.reflect.Array.get(value, i));
                if (!url.isEmpty()) return url;
            }
            return "";
        }
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                String url = urlText(item);
                if (!url.isEmpty()) return url;
            }
            return "";
        }
        String direct = nonEmpty(first(value, "mUrl", "url", "getUrl", "getKUrl", "mVideoUrl", "getVideoUrl"));
        if (direct.startsWith("http")) return direct;
        Object nested = first(value, "mCdnList", "mVideoUrls", "mUrls", "getUrls");
        return nested == value ? "" : urlText(nested);
    }

    private static Object meta(Object entity, Object photo, String className) {
        if (entity == null || photo == null) return null;
        try {
            Class<?> type = photo.getClass().getClassLoader().loadClass(className);
            return SnapshotMapper.invoke(entity, "b", new Class<?>[]{Class.class}, type);
        } catch (Exception ignored) { return null; }
    }
    private static String nonEmpty(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        String result = String.valueOf(value).trim();
        return result.isEmpty() || "null".equalsIgnoreCase(result) || "0".equals(result) ? "" : result;
    }
    private static String locationPart(Object object, String... names) {
        return object == null ? "" : nonEmpty(first(object, names));
    }
    private static String locationFromObject(Object object, Set<Object> visited, int depth) {
        if (object == null || depth > 2 || visited.contains(object)) return "";
        if (object instanceof CharSequence) return nonEmpty(object);
        visited.add(object);
        String direct = locationPart(object, "mLocationName", "mCityName", "mPublishLocation", "getLocationName", "getCityName");
        if (!direct.isEmpty()) return direct;
        Object nested = first(object, "mLocation", "mLocationInfo", "mPoi", "mPoiInfo", "mPoiBriefInfo", "mAddressInfo", "mCityInfo",
            "mVideoMeta", "mImageMeta", "mPhotoMeta", "mCommonMeta", "mMeta", "getLocation", "getLocationInfo", "getPoi", "getPoiInfo",
            "getVideoMeta", "getImageMeta", "getPhotoMeta", "getCommonMeta", "getMeta");
        if (nested != null && nested != object) {
            String value = locationFromObject(nested, visited, depth + 1);
            if (!value.isEmpty()) return value;
        }
        String title = locationPart(object, "mTitle", "mName", "getTitle", "getName");
        String address = locationPart(object, "mAddress", "getAddress");
        String city = locationPart(object, "mCity", "mCityName", "getCity", "getCityName");
        StringBuilder result = new StringBuilder();
        for (String part : new String[]{title, address, city}) {
            if (part.isEmpty() || result.toString().contains(part)) continue;
            if (result.length() > 0) result.append(" ");
            result.append(part);
        }
        return result.toString();
    }
    private static String publishLocation(Object photo, Object common, Object entity) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object object : new Object[]{photo, common, entity}) {
            String value = locationFromObject(object, visited, 0);
            if (!value.isEmpty()) return value;
        }
        return "";
    }
    public static String gender(Object value) {
        return "F".equals(value) ? "female" : "M".equals(value) ? "male" : "unknown";
    }
    public static JSONObject profile(Object response) throws Exception {
        if (response == null || !(response.getClass().getName().endsWith("UserProfileResponse") || response.getClass().getName().endsWith(".UserProfile"))) return null;
        Object profile = response.getClass().getName().endsWith(".UserProfile") ? response : SnapshotMapper.field(response, "mUserProfile");
        if (profile == null || Boolean.TRUE.equals(SnapshotMapper.field(profile, "mIsBlockedByOwner"))) return null;
        Object user = SnapshotMapper.field(profile, "mProfile");
        String id = text(SnapshotMapper.field(user, "mId"));
        if (id.isEmpty()) return null;
        JSONObject out = new JSONObject().put("platform", "kuaishou").put("userId", id)
            .put("gender", gender(SnapshotMapper.field(user, "mSex"))).put("profileCapturedAt", System.currentTimeMillis())
            .put("profileSource", response.getClass().getName());
        put(out, "nickname", SnapshotMapper.field(user, "mName"));
        put(out, "bio", SnapshotMapper.field(user, "mText"));
        put(out, "location", SnapshotMapper.field(profile, "mCityName"));
        String privacy = text(SnapshotMapper.field(profile, "mAgePrivacy"));
        String age = text(SnapshotMapper.field(profile, "mAge"));
        // Unknown privacy codes are not interpreted as permission to reveal an age.
        out.put("age", privacy.isEmpty() && age.matches("[1-9][0-9]{0,2}") && Integer.parseInt(age) <= 120 ? Integer.parseInt(age) : JSONObject.NULL);
        out.put("ageVisibility", privacy.isEmpty() ? "public_or_unset" : "privacy_unspecified");
        Object meta = SnapshotMapper.invoke(profile, "getKUserProfileMeta", new Class<?>[0]);
        Object ip = SnapshotMapper.field(meta, "mIpLocation");
        put(out, "profileIpRegion", SnapshotMapper.field(ip, "ipLocationName"));
        JSONObject sources = new JSONObject().put("gender", "profile.user_sex").put("age", "userProfile.age")
            .put("location", "userProfile.cityName").put("profileIpRegion", "userProfileMeta.ipLocation.ipLocationName");
        out.put("fieldSources", sources);
        return out;
    }
    public static JSONObject post(Object photo) throws Exception {
        if (photo == null || !photo.getClass().getName().endsWith("QPhoto")) return null;
        String id = text(SnapshotMapper.invoke(photo, "getPhotoId", new Class<?>[0]));
        if (id.isEmpty()) return null;
        Object common = SnapshotMapper.invoke(photo, "getCommonMeta", new Class<?>[0]);
        Object entity = SnapshotMapper.field(photo, "mEntity");
        Object videoMeta = meta(entity, photo, "com.kuaishou.android.model.mix.VideoMeta");
        Object imageMeta = meta(entity, photo, "com.kuaishou.android.model.mix.ImageMeta");
        Class<?> metaType;
        try { metaType = photo.getClass().getClassLoader().loadClass("com.kuaishou.android.model.mix.PhotoMeta"); }
        catch (ClassNotFoundException error) { metaType = null; }
        Object metrics = metaType == null ? null : SnapshotMapper.invoke(entity, "b", new Class<?>[]{Class.class}, metaType);
        Object user = SnapshotMapper.invoke(photo, "getUser", new Class<?>[0]);
        JSONObject out = new JSONObject().put("platform", "kuaishou").put("postId", id).put("url", "kwai://work/" + id)
            .put("snapshotAt", System.currentTimeMillis()).put("source", "kuaishou_android.QPhoto");
        Object created = SnapshotMapper.field(common, "mCreated");
        put(out, "title", SnapshotMapper.field(common, "mCaption")); put(out, "created", created); put(out, "publishedAt", created);
        String location = publishLocation(photo, common, entity);
        if (!location.isEmpty()) out.put("location", location).put("locationSource", "kuaishou_android.QPhoto.location");
        put(out, "authorId", SnapshotMapper.field(user, "mId")); put(out, "authorName", SnapshotMapper.field(user, "mName"));
        put(out, "likes", SnapshotMapper.field(metrics, "mLikeCount")); put(out, "commentTotal", SnapshotMapper.field(metrics, "mCommentCount"));
        put(out, "shares", SnapshotMapper.field(metrics, "mShareCount")); put(out, "favorites", SnapshotMapper.field(metrics, "mCollectCount"));
        Object duration = first(photo, "mDuration", "mDurationMs", "mVideoDuration", "getDuration", "getDurationMs");
        if (duration == null) duration = first(common, "mDuration", "mDurationMs", "getDuration", "getDurationMs");
        if (duration == null) duration = first(videoMeta, "mDuration", "getDuration", "getDurationMs");
        put(out, "duration", duration);
        Object cover = first(photo, "mCoverUrl", "mCoverImageUrl", "mCover", "getCoverUrl", "getCover");
        if (cover == null) cover = first(videoMeta, "mCoverUrl", "mCoverImageUrl", "mCover", "getCoverUrl", "getCover");
        if (cover == null) cover = first(imageMeta, "mCoverUrl", "mCoverImageUrl", "mCover", "getCoverUrl", "getCover");
        String coverUrl = mediaText(cover, "mUrl", "mUrlString", "url", "getUrl", "getUrlString");
        if (!coverUrl.startsWith("http")) coverUrl = urlText(cover);
        if (!coverUrl.isEmpty()) out.put("coverUrl", coverUrl);
        Object music = first(photo, "mMusic", "mSound", "mMusicInfo", "getMusic", "getMusicInfo");
        if (music == null) music = first(videoMeta, "mMusic", "mMusicInfo", "getMusic", "getMusicInfo");
        String musicName = mediaText(music, "mName", "mTitle", "name", "title", "getName", "getTitle");
        if (musicName.isEmpty()) musicName = nonEmpty(first(videoMeta, "mMusicFeedName", "getMusicFeedName"));
        if (!musicName.isEmpty()) out.put("musicName", musicName);
        String videoUrl = urlText(first(videoMeta, "mVideoUrl", "mLocalUrl", "getVideoUrl", "getLocalUrl", "mVideoUrls", "mSdUrls", "mH265Urls"));
        if (!videoUrl.isEmpty()) out.put("videoUrl", videoUrl);
        String musicUrl = urlText(first(videoMeta, "mMusicUrl", "mMusicCdn", "mMusicCdnList", "getMusicUrl"));
        if (musicUrl.isEmpty()) musicUrl = urlText(first(imageMeta, "mMusicUrl", "mMusicCdn", "mMusicCdnList", "getMusicUrl"));
        if (!musicUrl.isEmpty()) out.put("musicUrl", musicUrl);
        return out;
    }
    public static JSONObject search(Object response, JSONObject request) throws Exception {
        if (response == null || !response.getClass().getName().endsWith("SearchResultResponse")) return null;
        Object items = SnapshotMapper.field(response, "mNormalItems");
        if (!(items instanceof List<?>)) return null;
        String keyword = request.optString("keyword");
        if (keyword.isEmpty()) return null;
        JSONArray posts = new JSONArray();
        for (Object item : (List<?>) items) {
            JSONObject single = post(SnapshotMapper.field(item, "mPhoto")); if (single != null) posts.put(single);
            Object list = SnapshotMapper.field(item, "mFeeds");
            if (list instanceof List<?>) for (Object photo : (List<?>) list) { JSONObject row = post(photo); if (row != null) posts.put(row); }
        }
        Object cursor = SnapshotMapper.field(response, "mCursor");
        return new JSONObject().put("keyword", keyword).put("posts", posts).put("cursor", cursor == null ? JSONObject.NULL : cursor)
            .put("hasMore", "no_more".equals(text(cursor)) ? false : JSONObject.NULL).put("source", response.getClass().getName());
    }
}
