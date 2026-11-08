package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RecoveryTest {
    private static int assertions;
    private static void check(boolean value, String name) { assertions++; if (!value) throw new AssertionError(name); }
    private static JSONObject page(String post, String input, String output, boolean more) throws Exception {
        return new JSONObject().put("photoId", post).put("rootId", "").put("requestCursorKnown", true)
            .put("requestCursor", input).put("cursor", output).put("hasMore", more).put("rows", new JSONArray());
    }
    public static void main(String[] args) throws Exception {
        CommentPageCache cache = new CommentPageCache();
        cache.offer(page("a", "", "next", true), 10);
        check(cache.read("a", 20).size() == 1, "Pre-task root page retained");
        cache.offer(page("a", "next", "no_more", false), 30);
        check(cache.read("a", 40).size() == 2 && !cache.read("a", 40).get(1).getBoolean("hasMore"), "Contiguous native EOF retained");
        check(cache.read("b", 40).isEmpty(), "Different post never replays");
        JSONObject copy = cache.read("a", 40).get(0); copy.put("photoId", "changed");
        check(cache.read("a", 40).get(0).getString("photoId").equals("a"), "Replay is a deep copy");
        check(cache.read("a", 180031).isEmpty(), "Old snapshots expire");
        cache.offer(page("a", "", "n1", true), 50); cache.offer(page("a", "missing", "no_more", false), 60);
        check(cache.read("a", 70).isEmpty(), "Cursor gap cannot replay terminal pages");
        cache.offer(page("b", "next", "no_more", false), 80);
        check(cache.read("b", 90).isEmpty(), "Tail without first page cannot replay");
        cache.offer(page("c", "", "n1", true).put("rootId", "reply-parent"), 100);
        check(cache.read("c", 110).isEmpty(), "Reply snapshots never seed root stream");
        cache.offer(page("d", "", "no_more", false).put("requestCursorKnown", false), 120);
        check(cache.read("d", 130).isEmpty(), "Unknown request cursor cannot seed root stream");
        JSONObject regions = new JSONObject().put("ipRegion", "北京、河北,北京");
        check(RegionFilter.values(regions, "ipRegion", "ipRegions").equals(List.of("北京", "河北")), "Regions use OR and deduplicate");
        StringBuilder sql = new StringBuilder("1=1"); List<String> params = new ArrayList<>();
        RegionFilter.append(sql, params, regions, "ipRegion", "ipRegions", "region");
        check(sql.toString().contains(" OR ") && params.equals(List.of("%北京%", "%河北%")), "SQL regions are separate bound parameters");
        check(RegionFilter.values(new JSONObject().put("ipRegions", new JSONArray().put("北京").put("河北")), "ipRegion", "ipRegions").size() == 2, "Structured region arrays accepted");
        System.out.println("PASS: " + assertions + " recovery/region assertions");
    }
}
