package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class XhsMapperTest {
    public static class User { public String userid = "u1", nickname = "小红"; }
    public static class Img { public String url = "https://ci.xiaohongshu.com/a.jpg"; }
    public static class Note {
        public String id = "n1", title = "标题", desc = "正文", ipLocation = "浙江", userSetLocation = "";
        public long time = 1760000000000L;
        public int likedCount = 12, collectedCount = 3, commentsCount = 4, sharedCount = 1;
        public User user = new User();
        public List<Img> imageList = new ArrayList<>(List.of(new Img()));
    }

    public static void main(String[] args) throws Exception {
        JSONObject p = XhsMapper.post(new Note());
        check("xhs".equals(p.getString("platform")), "platform");
        check("n1".equals(p.getString("postId")), "id");
        check("标题\n正文".equals(p.getString("title")), "title+desc");
        check("IP属地：浙江".equals(p.getString("location")), "ip fallback");
        check(p.getLong("likes") == 12 && p.getLong("commentTotal") == 4, "stats");
        check("小红".equals(p.getString("authorName")), "author");
        check(p.getJSONArray("imageUrls").length() == 1 && p.has("coverUrl"), "images");
        Note poi = new Note(); poi.userSetLocation = "杭州西湖";
        check("杭州西湖".equals(XhsMapper.post(poi).getString("location")), "user location wins");
        System.out.println("xhs mapper ok");
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
