package dev.local.commentcollector;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Whitelists public model fields; never serializes entire platform objects. */
public final class PublicMapper {
    private PublicMapper() {}
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static void put(JSONObject out, String key, Object value) throws Exception { out.put(key, value == null ? JSONObject.NULL : value); }
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
        Class<?> metaType;
        try { metaType = photo.getClass().getClassLoader().loadClass("com.kuaishou.android.model.mix.PhotoMeta"); }
        catch (ClassNotFoundException error) { metaType = null; }
        Object metrics = metaType == null ? null : SnapshotMapper.invoke(entity, "b", new Class<?>[]{Class.class}, metaType);
        Object user = SnapshotMapper.invoke(photo, "getUser", new Class<?>[0]);
        JSONObject out = new JSONObject().put("platform", "kuaishou").put("postId", id).put("url", "kwai://work/" + id)
            .put("snapshotAt", System.currentTimeMillis()).put("source", "kuaishou_android.QPhoto");
        put(out, "title", SnapshotMapper.field(common, "mCaption")); put(out, "created", SnapshotMapper.field(common, "mCreated"));
        put(out, "authorId", SnapshotMapper.field(user, "mId")); put(out, "authorName", SnapshotMapper.field(user, "mName"));
        put(out, "likes", SnapshotMapper.field(metrics, "mLikeCount")); put(out, "commentTotal", SnapshotMapper.field(metrics, "mCommentCount"));
        put(out, "shares", SnapshotMapper.field(metrics, "mShareCount")); put(out, "favorites", SnapshotMapper.field(metrics, "mCollectCount"));
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
