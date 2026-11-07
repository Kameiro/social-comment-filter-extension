package dev.local.commentcollector;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Captures Douyin comment pages as the app loads them and hands them to the collector while a comment task runs. */
final class DouyinComments {
    private static final String TAG = "CommentCollector";
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final WeakIdentityMap<String> seen = new WeakIdentityMap<>();
    private final Context context;
    private final java.util.function.Supplier<android.app.Activity> activity;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean paging;
    private volatile long lastPageAt;
    private volatile int idleTicks;
    private volatile long nextCursor = -1;
    private volatile boolean nativeMode;
    // Furthest top-level cursor loaded per task+post, so a restart (app reloads page 1) resumes there instead of at the top.
    private final java.util.Map<String, Long> furthest = new java.util.concurrent.ConcurrentHashMap<>();
    static final boolean DEBUG_FORCE_UI = false;


    DouyinComments(Context context, java.util.function.Supplier<android.app.Activity> activity) { this.context = context; this.activity = activity; }

    /** Keeps scrolling the open comment list while a comment task is running and more pages remain. */
    private void startPaging() {
        lastPageAt = System.currentTimeMillis(); idleTicks = 0;
        if (paging) return;
        paging = true;
        main.postDelayed(this::tick, 900);
    }

    private void stopPaging(String why) { if (paging) { paging = false; Log.i(TAG, "douyin_auto_page_stop " + why); } }

    private void tick() {
        if (!paging) return;
        new Thread(() -> {
            boolean running = false, unknown = false;
            try { JSONObject task = CollectorClient.call(context, "status", new JSONObject()); JSONObject params = task.optJSONObject("params"); nativeMode = !DEBUG_FORCE_UI && params != null && !"ui".equals(params.optString("pagingMode", "auto")); running = "comment".equals(task.optString("kind")) && task.optString("status").startsWith("running"); } catch (Throwable ignored) { unknown = true; }
            final boolean go = running, failed = unknown;
            main.post(() -> {
                if (!paging) return;
                if (failed) { main.postDelayed(this::tick, 2000); return; } // collector briefly unreachable: keep going
                if (!go) { stopPaging("task_not_running"); return; }
                // Yield to the user: don't inject swipes within 4s of a real touch.
                if (android.os.SystemClock.uptimeMillis() - DoubleTapDetector.lastUserTouch < 4000) { lastPageAt = System.currentTimeMillis(); main.postDelayed(this::tick, 800); return; }
                idleTicks = System.currentTimeMillis() - lastPageAt > 1500 ? idleTicks + 1 : 0;
                if (idleTicks > 8) { stopPaging("no_new_page"); return; }
                if (nativeMode && DouyinNativePager.ready() && nextCursor >= 0) {
                    long cursor = nextCursor; nextCursor = -1;
                    new Thread(() -> { Object result = DouyinNativePager.fetch(cursor, DouyinNativePager.post()); if (result != null) observe(result, DouyinNativePager.post()); }, "douyin-native-page").start();
                } else if (!nativeMode || !DouyinNativePager.ready()) scrollComments();
                main.postDelayed(this::tick, nativeMode ? 1500 : 1100);
            });
        }, "douyin-auto-page").start();
    }

    /** Scrolls the lowest tall scrollable list on screen (the comment drawer) by almost one page. */
    private void scrollComments() {
        android.app.Activity host = activity.get();
        if (host == null) return;
        android.view.View best = findList(host.getWindow().getDecorView(), host.getResources().getDisplayMetrics().heightPixels, null);
        if (best == null) return;
        // A real finger drag: the list only requests the next page from a user-driven scroll, not from scrollBy.
        int[] at = new int[2]; best.getLocationOnScreen(at);
        float x = at[0] + best.getWidth() * .5f;
        float from = at[1] + best.getHeight() * .85f, to = at[1] + best.getHeight() * .2f;
        if (!best.canScrollVertically(1)) { float swap = from; from = to + (swap - to) * .3f; to = swap; } // at the end: pull back down, then the next tick pushes up
        long down = android.os.SystemClock.uptimeMillis();
        final int steps = 10;
        final float startY = from, endY = to;
        android.view.View root = host.getWindow().getDecorView();
        dispatch(root, down, down, android.view.MotionEvent.ACTION_DOWN, x, startY);
        for (int i = 1; i <= steps; i++) {
            final int n = i;
            main.postDelayed(() -> dispatch(root, down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * n / steps), 18L * i);
        }
        main.postDelayed(() -> dispatch(root, down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, endY), 18L * (steps + 1));
    }

    private static void dispatch(android.view.View root, long down, long time, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, time, action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { root.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private static android.view.View findList(android.view.View view, int screenHeight, android.view.View best) {
        if (view.getVisibility() != android.view.View.VISIBLE) return best;
        if (view.getClass().getName().contains("RecyclerView")) {
            int[] at = new int[2]; view.getLocationOnScreen(at);
            boolean drawer = at[1] > screenHeight * .2f && view.getHeight() > screenHeight * .25f && view.canScrollVertically(1);
            if (drawer && (best == null || view.getHeight() > best.getHeight())) best = view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) best = findList(group.getChildAt(i), screenHeight, best);
        }
        return best;
    }

    /** Maps a {@code CommentItemList} to the shared page JSON, or null when it holds no comments. */
    static JSONObject page(Object list, String fallbackPost) throws Exception { return page(list, fallbackPost, ""); }

    static JSONObject page(Object list, String fallbackPost, String knownRoot) throws Exception {
        Object items = ModelReader.get(list, "items");
        if (!(items instanceof List<?>)) return null;
        JSONArray rows = new JSONArray();
        String post = fallbackPost == null ? "" : fallbackPost;
        String root = knownRoot == null ? "" : knownRoot;
        for (Object comment : (List<?>) items) {
            String awemeId = ModelReader.text(comment, "awemeId");
            if (!awemeId.isEmpty()) post = awemeId; // the comment's own post id beats the last-seen one
            // A reply page's comments all point at their parent through replyId.
            String parent = ModelReader.text(comment, "replyId");
            if (root.isEmpty() && !parent.isEmpty() && !parent.matches("0+|-1|null")) root = parent;
        }
        for (Object comment : (List<?>) items) append(rows, comment, post, root);
        if (rows.length() == 0 || post.isEmpty()) return null;
        JSONObject page = new JSONObject().put("photoId", post).put("rootId", root).put("rows", rows).put("groups", new JSONArray());
        page.put("requestCursorKnown", false);
        Object more = ModelReader.get(list, "hasMore");
        Boolean hasMore = more instanceof Boolean ? (Boolean) more : null;
        if (hasMore != null) page.put("hasMore", hasMore);
        page.put("eofEvidence", Boolean.FALSE.equals(hasMore) ? "response.hasMore:false" : "");
        Object cursor = ModelReader.get(list, "cursor");
        if (cursor != null) page.put("cursor", String.valueOf(cursor));
        Object total = ModelReader.get(list, "total");
        // A reply page's total is that group's reply count, not the post's comment total.
        if (total instanceof Number && root.isEmpty()) page.put("total", ((Number) total).longValue());
        page.put("source", "douyin_android.CommentItemList");
        return page;
    }

    private static void append(JSONArray rows, Object comment, String post, String root) throws Exception {
        String id = ModelReader.text(comment, "cid");
        if (id.isEmpty()) return;
        Object user = ModelReader.get(comment, "commentUser");
        if (!root.isEmpty() && id.equals(root)) return;
        JSONObject row = new JSONObject().put("platform", "douyin").put("commentId", id).put("photoId", post).put("postId", post)
            .put("rootId", root).put("level", root.isEmpty() ? 1 : 2);
        put(row, "text", ModelReader.text(comment, "text"));
        put(row, "userId", ModelReader.text(user, "uid"));
        put(row, "secUid", ModelReader.text(user, "secUid", "sec_uid"));
        put(row, "nickname", ModelReader.text(user, "nickname"));
        Object created = ModelReader.get(comment, "createTime");
        if (created instanceof Number) row.put("created", ((Number) created).longValue());
        Object likes = ModelReader.get(comment, "diggCount");
        if (likes instanceof Number) row.put("likes", ((Number) likes).longValue());
        Object replies = ModelReader.get(comment, "replyCommentTotal");
        if (replies instanceof Number) row.put("replies", ((Number) replies).longValue());
        String ip = ModelReader.text(comment, "ipLabel").replaceFirst("^\\s*IP\\s*属地\\s*[:：]?\\s*", "").trim();
        put(row, "ipRegion", ip);
        row.put("ipRegionSource", "douyin_android.ipLabel").put("gender", "unknown").put("capturedAt", System.currentTimeMillis());
        rows.put(row);
        Object sub = ModelReader.get(comment, "replyComments");
        if (root.isEmpty() && sub instanceof List<?>) for (Object reply : (List<?>) sub) append(rows, reply, post, id);
    }

    private static void put(JSONObject row, String key, String value) throws Exception { if (value != null && !value.isEmpty()) row.put(key, value); }


    // ---- reply expansion (running-replies phase), driven natively through Douyin's own reply-list API
    private volatile boolean replyDriver;
    private volatile long lastReplyNote;
    private final java.util.Set<String> replySkipped = java.util.concurrent.ConcurrentHashMap.newKeySet();

    void startReplyDriver() {
        if (replyDriver) return;
        replyDriver = true;
        new Thread(() -> {
            while (true) {
                long sleep = 3000;
                try { sleep = replyStep(); } catch (Throwable error) { Log.w(TAG, "douyin_reply_step_failed", error); }
                try { Thread.sleep(sleep); } catch (InterruptedException interrupted) { return; }
            }
        }, "douyin-replies").start();
    }

    private long replyStep() throws Exception {
        JSONObject task;
        try { task = CollectorClient.call(context, "status", new JSONObject()); } catch (Throwable ignored) { return 3000; }
        if ("comment".equals(task.optString("kind")) && task.optString("status").startsWith("running")) maybeOpenPost(task);
        if (!"comment".equals(task.optString("kind")) || !"running-replies".equals(task.optString("status"))) return 3000;
        String photo = task.optString("photoId");
        if (!DouyinNativePager.ready() || !photo.equals(DouyinNativePager.post())) {
            if (System.currentTimeMillis() - lastReplyNote > 30000) { lastReplyNote = System.currentTimeMillis(); Log.i(TAG, "douyin_reply_waiting open_comments_of=" + photo + " ready=" + DouyinNativePager.ready()); }
            return 3000;
        }
        if (android.os.SystemClock.uptimeMillis() - DoubleTapDetector.lastUserTouch < 4000) return 1000;
        long taskId = task.optLong("taskId"), generation = task.optLong("generation");
        JSONObject listing = CollectorClient.call(context, "pending_reply_ids", new JSONObject().put("taskId", taskId).put("generation", generation));
        JSONArray ids = listing.optJSONArray("items");
        String root = null;
        for (int i = 0; ids != null && i < ids.length(); i++) {
            if (!replySkipped.contains(taskId + ":" + generation + ":" + ids.getString(i))) { root = ids.getString(i); break; }
        }
        if (root == null) {
            if (System.currentTimeMillis() - lastReplyNote > 30000) { lastReplyNote = System.currentTimeMillis(); Log.i(TAG, "douyin_reply_unfinished skipped=" + replySkipped.size()); }
            return 5000;
        }
        if (!fetchReplyGroup(root, photo, taskId, generation)) replySkipped.add(taskId + ":" + generation + ":" + root);
        return 500;
    }


    // ---- auto-open: bring the task's post (and its comment drawer) up when Douyin is in front but showing something else
    private final java.util.Map<String, int[]> openAttempts = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastOpenAt;

    private void maybeOpenPost(JSONObject task) {
        String photo = task.optString("photoId");
        if (photo.isEmpty() || !photo.matches("\\d+")) return;
        if (DouyinNativePager.ready() && photo.equals(DouyinNativePager.post())) return;           // already bound
        if (System.currentTimeMillis() - lastPageAt < 20000) return;                               // pages are flowing
        if (android.os.SystemClock.uptimeMillis() - DoubleTapDetector.lastUserTouch < 8000) return; // the user is busy
        if (System.currentTimeMillis() - lastOpenAt < 45000) return;
        android.app.Activity host = activity.get();
        if (host == null || host.isFinishing()) return;                                            // never pull Douyin to the front
        String key = task.optLong("taskId") + ":" + task.optLong("generation");
        int[] tries = openAttempts.computeIfAbsent(key, k -> new int[1]);
        if (tries[0] >= 3) { if (tries[0]++ == 3) Log.i(TAG, "douyin_open_post_gave_up post=" + photo); return; }
        tries[0]++; lastOpenAt = System.currentTimeMillis();
        Log.i(TAG, "douyin_open_post post=" + photo + " attempt=" + tries[0]);
        main.post(() -> {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.iesdouyin.com/share/video/" + photo + "/"));
                intent.setPackage(host.getPackageName());
                host.startActivity(intent);
                main.postDelayed(() -> openCommentDrawer(photo, 0), 3500);
            } catch (Throwable error) { Log.w(TAG, "douyin_open_post_failed", error); }
        });
    }

    private void openCommentDrawer(String photo, int attempt) {
        if (attempt > 10 || (DouyinNativePager.ready() && photo.equals(DouyinNativePager.post()))) return;
        android.app.Activity host = activity.get();
        if (host != null) {
            android.view.View root = host.getWindow().getDecorView();
            android.view.View drawer = findList(root, host.getResources().getDisplayMetrics().heightPixels, null);
            android.view.View button = drawer == null ? findCommentButton(root) : null;
            if (button != null) { Log.i(TAG, "douyin_open_comments click"); button.performClick(); }
        }
        main.postDelayed(() -> openCommentDrawer(photo, attempt + 1), 1500);
    }

    private static android.view.View findCommentButton(android.view.View view) {
        if (view.getVisibility() != android.view.View.VISIBLE) return null;
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().contains("评论") && !description.toString().contains("发表") && view.isClickable()) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { android.view.View found = findCommentButton(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }

    private boolean appendPage(JSONObject page, long taskId, long generation) {
        try {
            Bundle args = new Bundle(); args.putString("page", page.toString()); args.putLong("taskId", taskId); args.putLong("generation", generation);
            Bundle result = CollectorClient.callRaw(context, "append", args);
            if (result != null && result.containsKey("error")) { Log.w(TAG, "douyin_reply_storage_failed " + result.getString("error")); return false; }
            return true;
        } catch (Exception error) { Log.w(TAG, "douyin_reply_storage_unavailable"); return false; }
    }

    private boolean fetchReplyGroup(String root, String photo, long taskId, long generation) throws Exception {
        long cursor = 0;
        for (int pageNumber = 0; pageNumber < 300; pageNumber++) {
            while (android.os.SystemClock.uptimeMillis() - DoubleTapDetector.lastUserTouch < 4000) { try { Thread.sleep(800); } catch (InterruptedException e) { return false; } }
            Object result = null;
            for (int attempt = 0; attempt < 3 && result == null; attempt++) {
                result = DouyinNativePager.fetchReplies(root, cursor, photo);
                if (result == null) { try { Thread.sleep(1500); } catch (InterruptedException e) { return false; } }
            }
            if (result == null) { Log.w(TAG, "douyin_reply_group_failed root=" + root); return false; }
            JSONObject page = page(result, photo, root);
            Object more = ModelReader.get(result, "hasMore");
            boolean hasMore = Boolean.TRUE.equals(more);
            if (page == null) { // no replies came back: close the group explicitly instead of retrying it forever
                JSONObject empty = new JSONObject().put("photoId", photo).put("rootId", root).put("rows", new JSONArray()).put("groups", new JSONArray())
                    .put("hasMore", false).put("eofEvidence", "reply.empty").put("source", "douyin_android.CommentItemList");
                return appendPage(empty, taskId, generation);
            }
            if (!hasMore) page.put("hasMore", false);
            if (!appendPage(page, taskId, generation)) return false;
            if (!hasMore) return true;
            long next = cursor;
            try { next = Long.parseLong(page.optString("cursor")); } catch (NumberFormatException ignored) {}
            cursor = next > cursor ? next : cursor + 20;
            try { Thread.sleep(700); } catch (InterruptedException e) { return false; }
        }
        return true;
    }

    /** Called with each loaded comment list; stores it only while a comment task for this post is running. */
    void observe(Object list, String currentPost) {
        try {
            JSONObject page = page(list, currentPost);
            if (page == null) return;
            // Douyin hands the same page over a second time as an empty-state copy (cursor 0, total 0, hasMore false);
            // it must not count as the end of the list.
            if (page.optString("rootId").isEmpty() && Boolean.FALSE.equals(page.opt("hasMore")) && "0".equals(page.optString("cursor", "0")) && page.optLong("total") == 0) return;
            Log.i(TAG, "douyin_comment_list rows=" + page.getJSONArray("rows").length() + " post=" + page.optString("photoId") + " more=" + page.opt("hasMore") + " cursor=" + page.opt("cursor") + " total=" + page.opt("total") + " first=" + page.getJSONArray("rows").getJSONObject(0).optString("commentId"));
            JSONObject task = CollectorClient.call(context, "status", new JSONObject());
            if (!"comment".equals(task.optString("kind")) || !task.optString("status").startsWith("running")) { Log.i(TAG, "douyin_comment_skipped task_not_running kind=" + task.optString("kind") + " status=" + task.optString("status")); return; }
            if (!task.optString("photoId").isEmpty() && !task.optString("photoId").equals(page.optString("photoId"))) { Log.i(TAG, "douyin_comment_skipped other_post task=" + task.optString("photoId") + " page=" + page.optString("photoId")); return; }
            String fingerprint = page.optString("cursor") + ":" + page.getJSONArray("rows").length() + ":" + task.optLong("taskId") + ":" + task.optLong("generation");
            synchronized (seen) { if (fingerprint.equals(seen.put(list, fingerprint))) return; }
            lastPageAt = System.currentTimeMillis();
            if (page.optString("rootId").isEmpty()) {
                try {
                    long loaded = Long.parseLong(page.optString("cursor"));
                    String key = task.optLong("taskId") + ":" + task.optLong("generation") + ":" + page.optString("photoId");
                    long best = furthest.merge(key, loaded, Math::max);
                    nextCursor = best; // resume from the furthest page ever read, not from where the app happens to be
                } catch (NumberFormatException ignored) {}
            }
            if (Boolean.FALSE.equals(page.opt("hasMore"))) main.post(() -> stopPaging("eof")); else if (page.optString("rootId").isEmpty()) main.post(this::startPaging);
            Log.i(TAG, "douyin_comment_page rows=" + page.getJSONArray("rows").length() + " more=" + page.opt("hasMore"));
            long taskId = task.optLong("taskId"), generation = task.optLong("generation");
            writer.execute(() -> {
                try {
                    Bundle args = new Bundle(); args.putString("page", page.toString()); args.putLong("taskId", taskId); args.putLong("generation", generation);
                    Bundle result = CollectorClient.callRaw(context, "append", args);
                    if (result != null && result.containsKey("error")) Log.w(TAG, "douyin_page_storage_failed " + result.getString("error"));
                } catch (Exception error) { Log.w(TAG, "douyin_storage_unavailable"); }
            });
        } catch (Throwable error) { Log.w(TAG, "douyin_comment_mapping_failed", error); }
    }

    static void hook(io.github.libxposed.api.XposedInterface xposed, ClassLoader loader, java.util.function.Consumer<Object> sink) {
        try {
            Class<?> model = loader.loadClass("com.ss.android.ugc.aweme.comment.presenter.CommentFetchModel");
            Class<?> list = loader.loadClass("com.ss.android.ugc.aweme.comment.model.CommentItemList");
            int hooked = 0;
            // The comment panel is obfuscated Kotlin-multiplatform code whose consumers move between versions, so
            // watch the response model itself: any method call on a CommentItemList reveals the live instance.
            final WeakIdentityMap<Boolean> handled = new WeakIdentityMap<>();
            for (Method method : list.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()) continue;
                try {
                    xposed.hook(method).intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self != null) {
                            boolean first;
                            synchronized (handled) { first = handled.put(self, Boolean.TRUE) == null; }
                            if (first) { Log.i(TAG, "douyin_comment_via " + method.getName()); try { sink.accept(self); } catch (Throwable ignored) {} }
                        }
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "douyin_comment_methods_hooked=" + hooked);
            Log.i(TAG, "douyin_comment_hook_installed");
        } catch (Throwable error) { Log.w(TAG, "douyin_comment_hook_failed", error); }
    }
}
