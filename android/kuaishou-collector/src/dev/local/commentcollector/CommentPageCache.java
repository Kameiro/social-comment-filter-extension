package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONObject;

/** Short-lived, normalized root pages observed before a task was created. */
final class CommentPageCache {
    private static final long TTL = 180000;
    private final LinkedHashMap<String, Buffer> posts = new LinkedHashMap<>();
    private static final class Buffer {
        final List<String> pages = new ArrayList<>();
        String next = "";
        long time;
        int bytes;
        boolean broken, ended;
    }
    synchronized void offer(JSONObject page, long now) throws Exception {
        if (!page.optString("rootId").isEmpty() || !page.optBoolean("requestCursorKnown")) return;
        String photo = page.optString("photoId"); if (photo.isEmpty()) return;
        String request = page.optString("requestCursor");
        boolean first = request.isEmpty() || "0".equals(request);
        Buffer buffer = posts.get(photo);
        if (first) { buffer = new Buffer(); posts.put(photo, buffer); }
        if (buffer == null || buffer.broken) return;
        if (!first && (buffer.ended || !request.equals(buffer.next))) { buffer.broken = true; return; }
        String data = page.toString(); int size = data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (buffer.pages.size() >= 16 || buffer.bytes + size > 1048576) { buffer.broken = true; buffer.pages.clear(); return; }
        buffer.pages.add(data); buffer.bytes += size; buffer.next = page.optString("cursor"); buffer.time = now;
        buffer.ended = Boolean.FALSE.equals(page.opt("hasMore"));
        while (posts.size() > 4) posts.remove(posts.keySet().iterator().next());
    }
    synchronized List<JSONObject> read(String photo, long now) throws Exception {
        Buffer buffer = posts.get(photo); List<JSONObject> result = new ArrayList<>();
        if (buffer == null || buffer.broken || now - buffer.time > TTL || now < buffer.time) return result;
        for (String page : buffer.pages) result.add(new JSONObject(page).put("fromNativeCache", true));
        return result;
    }
}
