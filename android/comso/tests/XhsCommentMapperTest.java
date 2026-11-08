package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class XhsCommentMapperTest {
    public static class User { public String userid = "u1", nickname = "小红", image = "https://ci.xiaohongshu.com/comment-avatar.jpg"; }
    public static class Comment {
        public String id = "c1", content = "正文评论", noteId = "n1", ipLocation = "IP属地：上海", time = "1760000000";
        public Integer likeCount = 7, subCommentCount = 1;
        public User user = new User();
        public List<Comment> subComments = new ArrayList<>();
        public List<String> pictures = new ArrayList<>();
        public Object memeInfo = null;
        public String rootCommentId = "";
    }
    public static class PageQ {
        public List<Comment> comments = new ArrayList<>();
        public boolean hasMore = true;
        public String cursor = "cursor-1";
        public int commentCount = 99;
    }
    public static class PageV {
        public Comment rootComment;
        public List<Comment> subComments = new ArrayList<>();
        public boolean hasMore = false;
        public String cursor = "cursor-v";
    }
    public static class PageW {
        public List<Comment> comments = new ArrayList<>();
        public boolean hasMore = false;
        public String cursor = "cursor-w";
        public int commentCount = 5;
    }

    public static void main(String[] args) throws Exception {
        // 1. Test Model Q (L1 comment page with inline replies and groups completion)
        PageQ pageQ = new PageQ();
        Comment root = new Comment();
        Comment reply = new Comment(); reply.id = "c2"; reply.content = "回复"; reply.noteId = "n1";
        root.subComments.add(reply);
        pageQ.comments.add(root);

        // Add picture comment
        Comment picComment = new Comment();
        picComment.id = "c3"; picComment.content = ""; picComment.pictures.add("https://ci.xiaohongshu.com/pic.jpg");
        pageQ.comments.add(picComment);

        // Add meme comment
        Comment memeComment = new Comment();
        memeComment.id = "c4"; memeComment.content = ""; memeComment.memeInfo = new Object();
        pageQ.comments.add(memeComment);

        JSONObject mapped = XhsCommentMapper.page(pageQ, "");
        check("xhs".equals(mapped.getJSONArray("rows").getJSONObject(0).getString("platform")), "platform");
        check("n1".equals(mapped.getString("photoId")), "post");
        check(mapped.getJSONArray("rows").length() == 4, "rows count (root + inline reply + pic + meme)");
        check("上海".equals(mapped.getJSONArray("rows").getJSONObject(0).getString("ipRegion")), "ip stripped");
        check("https://ci.xiaohongshu.com/comment-avatar.jpg".equals(mapped.getJSONArray("rows").getJSONObject(0).getString("avatarUrl")), "comment avatar");
        check(mapped.getJSONArray("rows").getJSONObject(1).getInt("level") == 2, "reply level");
        check("[图片]".equals(mapped.getJSONArray("rows").getJSONObject(2).getString("text")), "pic fallback");
        check("[表情]".equals(mapped.getJSONArray("rows").getJSONObject(3).getString("text")), "meme fallback");
        check(mapped.getBoolean("hasMore") && "cursor-1".equals(mapped.getString("cursor")), "cursor");
        check(mapped.getLong("total") == 99, "total on root page");

        // Groups: root c1 has subComments.size() == 1 >= subCommentCount == 1 -> complete: true
        check(mapped.getJSONArray("groups").length() >= 1, "groups populated");
        check("c1".equals(mapped.getJSONArray("groups").getJSONObject(0).getString("rootId")), "group rootId");
        check(mapped.getJSONArray("groups").getJSONObject(0).getBoolean("complete"), "group complete");

        // 2. Test Model V (sub-comment response with rootComment + subComments)
        PageV pageV = new PageV();
        Comment vRoot = new Comment(); vRoot.id = "c10"; vRoot.noteId = "n1"; vRoot.content = "主评";
        Comment vSub = new Comment(); vSub.id = "c11"; vSub.noteId = "n1"; vSub.content = "子评"; vSub.rootCommentId = "c10";
        pageV.rootComment = vRoot;
        pageV.subComments.add(vSub);
        JSONObject mappedV = XhsCommentMapper.page(pageV, "");
        check("c10".equals(mappedV.getString("rootId")), "pageV rootId");
        check("n1".equals(mappedV.getString("photoId")), "pageV photoId");
        check(mappedV.getJSONArray("rows").length() == 2, "pageV rows count");
        check(mappedV.getJSONArray("rows").getJSONObject(0).getInt("level") == 1, "pageV root level");
        check(mappedV.getJSONArray("rows").getJSONObject(1).getInt("level") == 2, "pageV sub level");
        check(!mappedV.getBoolean("hasMore"), "pageV hasMore false");
        check(mappedV.getJSONArray("groups").getJSONObject(0).getBoolean("complete"), "pageV group complete");

        // 3. Test Model W (sub-comment page response with comments pointing to rootCommentId)
        PageW pageW = new PageW();
        Comment w1 = new Comment(); w1.id = "c20"; w1.noteId = "n1"; w1.rootCommentId = "c10"; w1.content = "回1";
        Comment w2 = new Comment(); w2.id = "c21"; w2.noteId = "n1"; w2.rootCommentId = "c10"; w2.content = "回2";
        pageW.comments.add(w1);
        pageW.comments.add(w2);
        JSONObject mappedW = XhsCommentMapper.page(pageW, "");
        check("c10".equals(mappedW.getString("rootId")), "pageW rootId");
        check(mappedW.getJSONArray("rows").length() == 2, "pageW rows count");
        check(mappedW.getJSONArray("rows").getJSONObject(0).getInt("level") == 2, "pageW sub level");
        check(!mappedW.has("total"), "pageW total not set (so it doesn't overwrite post comment total)");
        check(!mappedW.getBoolean("hasMore"), "pageW hasMore false");
        check(mappedW.getJSONArray("groups").getJSONObject(0).getBoolean("complete"), "pageW group complete");

        System.out.println("xhs comment mapper ok");
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
