package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public final class MapperTest {
    public static class CommonCommentResponse {
        public List<Comment> mComments = new ArrayList<>();
        public String mCursor;
        public Boolean mHasMore;
        public Integer mCommentCount;
        public Map<String, QSubComment> mSubCommentMap;
    }
    public static class QSubComment { public List<Comment> mComments = new ArrayList<>(); public String mCursor; }
    public static class Comment {
        public String mId, mPhotoId, mRootCommentId, mComment, mAuthorName, mAuthorId, mAuthorArea;
        public Long mCreated, mLikedCount;
        public Integer mSubCommentCount;
        public QSubComment mSubComment;
    }
    public static class LegacyCommentResponse {
        public List<Comment> mComments = new ArrayList<>(); public Integer mHasMore; public String mCursor;
    }
    public static class CursorCommentResponse {
        public List<Comment> mComments = new ArrayList<>(); public String mCursor; public Boolean more;
        public String getCursor() { return mCursor; } public Boolean hasMore() { return more; }
    }
    public static class NativeCommentResponse {
        public List<Comment> mComments = new ArrayList<>(); public Boolean mHasMore = true; public Map<String,Object> mSubCommentMap;
    }
    public static class UserInfo { public String mId = "large-user-9223372036854775808", mSex = "F", mName = "public", mText = "public bio"; public String token = "never-export"; }
    public static class IpLocation { public String ipLocationName = "北京"; }
    public static class Meta { public IpLocation mIpLocation = new IpLocation(); }
    public static class Profile {
        public UserInfo mProfile = new UserInfo(); public String mAge = "27", mAgePrivacy = "", mCityName = "北京";
        public boolean mIsBlockedByOwner; public Meta getKUserProfileMeta() { return new Meta(); }
    }
    public static class UserProfileResponse { public Profile mUserProfile = new Profile(); }
    private static int assertions;
    private static void check(boolean condition, String name) { assertions++; if (!condition) throw new AssertionError(name); }
    public static void main(String[] args) throws Exception {
        WeakIdentityMap<String> identity = new WeakIdentityMap<>();
        Object key = new Object() { @Override public int hashCode() { throw new AssertionError("Do not call vendor hashCode"); } @Override public boolean equals(Object value) { throw new AssertionError("Do not call vendor equals"); } };
        identity.put(key, "value"); check("value".equals(identity.get(key)), "Cache keys use identity without vendor methods");
        check(identity.get(new Object()) == null, "Different objects never alias");
        check(identity.get(null) == null, "Null parser results are harmless");
        CommonCommentResponse response = new CommonCommentResponse();
        Comment row = new Comment(); row.mId = "9223372036854775808"; row.mPhotoId = "post-A";
        row.mComment = "public comment"; row.mLikedCount = 7L; row.mSubCommentCount = 1;
        response.mComments.add(row); response.mCommentCount = 42; response.mCursor = "next"; response.mHasMore = true;
        JSONObject page = SnapshotMapper.page(response, new JSONObject());
        check(page.getString("photoId").equals("post-A"), "Bind actual post");
        check(page.getJSONArray("rows").getJSONObject(0).getString("commentId").equals(row.mId), "64-bit ids stay strings");
        check(page.getJSONArray("rows").getJSONObject(0).getLong("likes") == 7, "Real likes preserved");
        check(page.getBoolean("hasMore"), "Has-more page is not EOF");
        row.mRootCommentId = "0"; check(SnapshotMapper.page(response, new JSONObject()).getJSONArray("rows").getJSONObject(0).getInt("level") == 1, "Zero parent sentinel stays a root comment"); row.mRootCommentId = null;
        response.mHasMore = null; response.mCursor = "";
        check(SnapshotMapper.page(response, new JSONObject()).isNull("hasMore"), "Empty cursor is unknown, not EOF");
        response.mCursor = "no_more";
        check(!SnapshotMapper.page(response, new JSONObject()).getBoolean("hasMore"), "Explicit cursor EOF");
        row.mLikedCount = null;
        check(SnapshotMapper.page(response, new JSONObject()).getJSONArray("rows").getJSONObject(0).isNull("likes"), "Missing counts are null, not fake zero");
        Comment reply = new Comment(); reply.mId = "reply"; reply.mComment = "reply text";
        row.mSubComment = new QSubComment(); row.mSubComment.mComments.add(reply); row.mSubComment.mCursor = "no_more";
        page = SnapshotMapper.page(response, new JSONObject());
        check(page.getJSONArray("rows").length() == 2, "Embedded replies captured");
        check(page.getJSONArray("rows").getJSONObject(1).getInt("level") == 2, "Reply level retained");
        check(page.getJSONArray("rows").getJSONObject(1).getString("rootId").equals(row.mId), "Reply parent retained");
        reply.mPhotoId = "wrong-post";
        check(SnapshotMapper.page(response, new JSONObject()) == null, "Mixed post replies rejected");
        row.mSubComment = null; response.mComments.clear();
        check(SnapshotMapper.page(response, new JSONObject()) == null, "Uncorrelated empty response rejected");
        check(!SnapshotMapper.page(response, new JSONObject().put("photoId", "post-A")).getBoolean("hasMore"), "Correlated empty EOF retained");
        check(SnapshotMapper.page(new Object(), new JSONObject()) == null, "Unrelated network data ignored");
        LegacyCommentResponse legacy = new LegacyCommentResponse(); legacy.mComments.add(row); legacy.mHasMore = 0;
        check(!SnapshotMapper.page(legacy, new JSONObject()).getBoolean("hasMore"), "Integer zero is explicit EOF");
        legacy.mHasMore = 1; check(SnapshotMapper.page(legacy, new JSONObject()).getBoolean("hasMore"), "Integer one is has-more");
        legacy.mHasMore = 2; check(SnapshotMapper.page(legacy, new JSONObject()).isNull("hasMore"), "Unknown integer is not EOF");
        CursorCommentResponse actual = new CursorCommentResponse(); actual.mComments.add(row); actual.mCursor = "no_more"; actual.more = false;
        check(!SnapshotMapper.page(actual, new JSONObject()).getBoolean("hasMore"), "Native cursor getter terminal evidence");
        actual.mCursor = "cursor-end"; check(SnapshotMapper.page(actual, new JSONObject()).getString("eofEvidence").equals("response.hasMore:false"), "Native hasMore getter evidence");
        actual.mCursor = ""; check(SnapshotMapper.page(actual, new JSONObject()).isNull("hasMore"), "Empty getter cursor remains unknown");
        QSubComment sub = new QSubComment(); reply.mPhotoId = null; reply.mRootCommentId = null; sub.mComments.add(reply); sub.mCursor = "no_more";
        check(SnapshotMapper.page(sub, new JSONObject().put("photoId", "post-A")) == null, "Unbound reply EOF cannot terminate root stream");
        reply.mRootCommentId = "parent";
        check(SnapshotMapper.page(sub, new JSONObject().put("photoId", "post-A")).getString("rootId").equals("parent"), "Cached replies bind explicit parent");
        com.kuaishou.android.model.mix.QSubComment nativeSub = new com.kuaishou.android.model.mix.QSubComment(); nativeSub.mComments.add(reply);
        JSONObject bound = new JSONObject().put("photoId", "post-A").put("rootId", "parent").put("expectedReplies", 2);
        check(SnapshotMapper.page(nativeSub, bound).isNull("hasMore"), "Empty SDK preview is not all replies");
        bound.put("expectedReplies", 1);
        check(!SnapshotMapper.page(nativeSub, bound).getBoolean("hasMore"), "Native no-more and full parent snapshot prove cache EOF");
        check(SnapshotMapper.page(nativeSub, bound).getString("eofEvidence").equals("reply.sdk_cursor:false"), "SDK evidence recorded explicitly");
        check(j6a.a.received == null, "Native predicate receives original cursor");
        j6a.a.next = true; check(SnapshotMapper.page(nativeSub, bound).getBoolean("hasMore"), "Native has-more signal retained"); j6a.a.next = false;
        NativeCommentResponse nativeRoot = new NativeCommentResponse(); row.mSubCommentCount = 2; nativeRoot.mComments.add(row); nativeRoot.mSubCommentMap = Map.of(row.mId, nativeSub);
        check(!SnapshotMapper.page(nativeRoot, new JSONObject()).getJSONArray("groups").getJSONObject(0).getBoolean("complete"), "Short preview cannot finish parent group");
        Comment second = new Comment(); second.mId = "second-reply"; nativeSub.mComments.add(second);
        check(SnapshotMapper.page(nativeRoot, new JSONObject()).getJSONArray("groups").getJSONObject(0).getBoolean("complete"), "Full native cache group completes with terminal SDK proof");
        UserProfileResponse profile = new UserProfileResponse(); JSONObject user = PublicMapper.profile(profile);
        check(user.getString("gender").equals("female"), "Explicit profile gender");
        check(user.getInt("age") == 27, "Public numeric age");
        check(user.getString("profileIpRegion").equals("北京") && user.getString("location").equals("北京"), "Profile location and IP are separate fields");
        check(!user.toString().contains("never-export"), "Credentials never serialized");
        profile.mUserProfile.mAgePrivacy = "hidden"; check(PublicMapper.profile(profile).isNull("age"), "Private age withheld");
        profile.mUserProfile.mProfile.mSex = "unknown"; check(PublicMapper.profile(profile).getString("gender").equals("unknown"), "Do not infer gender");
        profile.mUserProfile.mIsBlockedByOwner = true; check(PublicMapper.profile(profile) == null, "Blocked profile not bypassed");
        JSONObject analysis = Analysis.run(new JSONObject().put("excludePhrase", "exclude"), query -> {
            org.json.JSONArray items = new org.json.JSONArray();
            if (query.optInt("offset") == 0) {
                items.put(new JSONObject().put("userId", "u").put("photoId", "p1").put("text", "public").put("likes", 3).put("replies", 2).put("created", 1000));
                items.put(new JSONObject().put("userId", "u").put("photoId", "p2").put("text", "exclude").put("likes", JSONObject.NULL).put("replies", JSONObject.NULL).put("created", 2000));
            }
            return new JSONObject().put("items", items).put("total", 2);
        });
        JSONObject stats = analysis.getJSONArray("items").getJSONObject(0);
        check(stats.getInt("commentCount") == 2 && stats.getInt("postCount") == 2, "Cross-post comment statistics");
        check(stats.getInt("likesReceived") == 3 && stats.getInt("replyCount") == 2, "Actual interactions summed");
        check(stats.getInt("unknownLikes") == 1 && stats.getInt("unknownReplies") == 1, "Missing interactions not made up");
        check(stats.getBoolean("excluded") && analysis.getString("basis").equals("stored_public_comments_only"), "Explicit exclusion and honest scope");
        System.out.println("PASS: " + assertions + " mapper/analysis assertions");
    }
}
