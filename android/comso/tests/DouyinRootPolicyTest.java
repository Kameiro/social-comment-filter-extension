package dev.local.commentcollector;

import org.json.JSONArray;
import org.json.JSONObject;

public class DouyinRootPolicyTest {
    public static void main(String[] args) throws Exception {
        JSONObject page = new JSONObject().put("photoId", "post").put("rootId", "").put("cursor", "1").put("total", 19)
            .put("rows", new JSONArray().put(new JSONObject().put("photoId", "post")));
        assert !DouyinRootPolicy.validPage(null, "post");
        assert !DouyinRootPolicy.validPage(new JSONObject(page.toString()).put("rows", new JSONArray()), "post");
        assert DouyinRootPolicy.validPage(page, "post"); // missing hasMore can be saved, never EOF
        assert DouyinRootPolicy.validPage(new JSONObject(page.toString()).put("hasMore", false), "post");
        assert !DouyinRootPolicy.validPage(new JSONObject(page.toString()).put("rootId", "reply"), "post");
        assert !DouyinRootPolicy.validPage(new JSONObject(page.toString()).put("photoId", "other"), "post");
        assert !DouyinRootPolicy.validPage(new JSONObject(page.toString()).put("rows", new JSONArray().put(new JSONObject().put("photoId", "other"))), "post");
        assert !DouyinRootPolicy.validPage(new JSONObject(page.toString()).put("hasMore", false).put("cursor", "0").put("total", 0), "post");
        assert !DouyinRootPolicy.advance(false, false, true); // rejected or failed write
        assert !DouyinRootPolicy.advance(true, null, true); // missing hasMore
        assert !DouyinRootPolicy.advance(true, false, false); // reply page
        assert DouyinRootPolicy.advance(true, false, true); // committed final page
        assert DouyinRootPolicy.advance(true, true, true);
        System.out.println("Douyin root policy passed");
    }
}
