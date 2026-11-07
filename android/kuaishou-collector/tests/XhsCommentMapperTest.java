package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class XhsCommentMapperTest {
    public static class User { public String userid = "u1", nickname = "小红"; }
    public static class Comment {
        public String id = "c1", content = "正文评论", noteId = "n1", ipLocation = "上海", time = "1760000000";
        public Integer likeCount = 7, subCommentCount = 1;
        public User user = new User();
        public List<Comment> subComments = new ArrayList<>();
    }
    public static class Page {
        public List<Comment> comments = new ArrayList<>();
        public boolean hasMore = true;
        public String cursor = "cursor-1";
        public int commentCount = 99;
    }

    public static void main(String[] args) throws Exception {
        Page page = new Page();
        Comment root = new Comment();
        Comment reply = new Comment(); reply.id = "c2"; reply.content = "回复"; reply.noteId = "n1";
        root.subComments.add(reply); page.comments.add(root);
        JSONObject mapped = XhsCommentMapper.page(page, "");
        check("xhs".equals(mapped.getJSONArray("rows").getJSONObject(0).getString("platform")), "platform");
        check("n1".equals(mapped.getString("photoId")), "post");
        check(mapped.getJSONArray("rows").length() == 2, "nested reply");
        check("上海".equals(mapped.getJSONArray("rows").getJSONObject(0).getString("ipRegion")), "ip");
        check(mapped.getJSONArray("rows").getJSONObject(1).getInt("level") == 2, "reply level");
        check(mapped.getBoolean("hasMore") && "cursor-1".equals(mapped.getString("cursor")), "cursor");
        System.out.println("xhs comment mapper ok");
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
