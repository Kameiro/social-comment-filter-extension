package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class XhsMapperTest {
    public static class User { public String userid = "u1", nickname = "小红"; }
    public static class Img { public String url = "https://ci.xiaohongshu.com/a.jpg"; }
    public static class ImgItem { public String originUrl = "https://ci.xiaohongshu.com/origin.jpg"; }
    public static class VideoInfo { public String originUrl = "https://ci.xiaohongshu.com/video.mp4"; public long duration = 15; }
    public static class Cover { public String url = "https://ci.xiaohongshu.com/cover.jpg"; }

    public static class Note {
        public String id = "n1", title = "标题", desc = "正文", ipLocation = "IP属地：浙江", userSetLocation = "";
        public long time = 1760000000000L;
        public int likedCount = 12, collectedCount = 3, commentsCount = 4, sharedCount = 1;
        public User user = new User();
        public List<Img> imageList = new ArrayList<>(List.of(new Img()));
    }

    public static class NoteItem {
        public String noteId = "n2", displayTitle = "标题2", description = "正文2", ipLocation = "江苏";
        public long createTime = 1761000000000L;
        public int likes = 50, favCount = 20, commentCount = 15, shareCount = 5;
        public User user = new User();
        public List<ImgItem> imagesList = new ArrayList<>(List.of(new ImgItem()));
        public VideoInfo videoInfo = new VideoInfo();
    }

    public static class NoteWithCover {
        public String id = "n3", desc = "仅封面";
        public Cover cover = new Cover();
    }

    public static void main(String[] args) throws Exception {
        // 1. NoteFeed mapping
        JSONObject p = XhsMapper.post(new Note());
        check("xhs".equals(p.getString("platform")), "platform");
        check("n1".equals(p.getString("postId")), "id");
        check("标题\n正文".equals(p.getString("title")), "title+desc");
        check("浙江".equals(p.getString("ipRegion")), "ipRegion");
        check("IP属地：浙江".equals(p.getString("location")), "ip fallback");
        check(p.getLong("likes") == 12 && p.getLong("commentTotal") == 4, "stats");
        check("小红".equals(p.getString("authorName")), "author");
        check(p.getJSONArray("imageUrls").length() == 1 && p.has("coverUrl"), "images");

        Note poi = new Note(); poi.userSetLocation = "杭州西湖";
        check("杭州西湖".equals(XhsMapper.post(poi).getString("location")), "user location wins");

        // 2. NoteItemBean mapping
        JSONObject p2 = XhsMapper.post(new NoteItem());
        check("xhs".equals(p2.getString("platform")), "p2 platform");
        check("n2".equals(p2.getString("postId")), "p2 postId");
        check("标题2\n正文2".equals(p2.getString("title")), "p2 title+desc");
        check(p2.getLong("created") == 1761000000000L, "p2 createTime");
        check(p2.getLong("likes") == 50 && p2.getLong("favorites") == 20 && p2.getLong("commentTotal") == 15 && p2.getLong("shares") == 5, "p2 stats");
        check("https://ci.xiaohongshu.com/origin.jpg".equals(p2.getJSONArray("imageUrls").getString(0)), "p2 image");
        check("https://ci.xiaohongshu.com/video.mp4".equals(p2.getString("videoUrl")), "p2 videoUrl");
        check(p2.getLong("duration") == 15000, "p2 duration");

        // 3. Cover fallback mapping
        JSONObject p3 = XhsMapper.post(new NoteWithCover());
        check("https://ci.xiaohongshu.com/cover.jpg".equals(p3.getString("coverUrl")), "cover fallback");

        System.out.println("xhs mapper ok");
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
