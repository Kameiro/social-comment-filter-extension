package dev.local.commentcollector;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class NativeCommentPager {
    private final List<WeakReference<Object>> pagers = new ArrayList<>();
    private final Map<String, WeakReference<Object>> roots = new LinkedHashMap<>();
    private final NativePageGate gate = new NativePageGate();
    private final NativeEofGuard eofGuard = new NativeEofGuard();
    private String rootsPhoto = "";
    private Context context;
    private String version;
    private java.util.function.Consumer<JSONObject> completeRoot;
    private java.util.function.BiConsumer<Object, JSONObject> captureReply;
    private String state = "not_attached";
    private long busySince;
    private long busyTask, busyGeneration;
    private Object idleReply;
    private long idleSince;
    private long replyTask, replyGeneration;
    /** pump() runs on the host main thread, so collector IPC (which can block for seconds) goes here. */
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ks-native-io"); thread.setDaemon(true); return thread;
    });
    private volatile JSONArray pendingIds;
    private volatile String pendingKey = "";
    private volatile boolean pendingLoading;

    void attach(Context context, String version, java.util.function.Consumer<JSONObject> completeRoot,
                java.util.function.BiConsumer<Object, JSONObject> captureReply) {
        this.context = context; this.version = version; this.completeRoot = completeRoot; this.captureReply = captureReply;
    }

    synchronized JSONObject diagnostics() throws Exception {
        int bound = 0;
        for (WeakReference<Object> ref : pagers) if (ref.get() != null) bound++;
        return new JSONObject().put("nativeState", state).put("boundPagers", bound).put("cachedRoots", roots.size());
    }

    synchronized void bind(Object pager) {
        pagers.removeIf(ref -> ref.get() == null || ref.get() == pager);
        pagers.add(new WeakReference<>(pager));
        while (pagers.size() > 8) pagers.remove(0);
    }

    synchronized void observeRoots(String photo, Object comments) {
        if (!(comments instanceof List<?>)) return;
        if (!rootsPhoto.equals(photo)) { roots.clear(); rootsPhoto = photo; }
        for (Object comment : (List<?>) comments) {
            Object id = SnapshotMapper.field(comment, "mId");
            if (id == null) id = SnapshotMapper.invoke(comment, "getKId", new Class<?>[0]);
            if (id != null) roots.put(String.valueOf(id), new WeakReference<>(comment));
        }
        while (roots.size() > 2000) roots.remove(roots.keySet().iterator().next());
    }

    /** The user is not on the task's post: forget its pagers (they go stale) and all timing so nothing pauses meanwhile. */
    synchronized void leftTarget(String photo) {
        pagers.removeIf(ref -> {
            Object candidate = ref.get();
            if (candidate == null) return true;
            try {
                Object id = SnapshotMapper.invoke(SnapshotMapper.field(candidate, "b"), "getKId", new Class<?>[0]);
                return id != null && photo.equals(String.valueOf(id));
            } catch (Throwable ignored) { return false; }
        });
        gate.reset(); eofGuard.reset(); busySince = 0; idleReply = null;
        state = "native_waiting_target_post";
    }
    /** On the post but the comment list is not shown yet: stop the timers without discarding the pager. */
    synchronized void suspend() { gate.reset(); eofGuard.reset(); busySince = 0; state = "native_waiting_comment_list"; }

    /** Called when no task is running (paused/finished): drop timing state so a resume starts clean. */
    synchronized void idle() {
        gate.reset(); eofGuard.reset(); busySince = 0; idleReply = null;
    }

    /** @param postForeground the resumed activity is the post detail page (not a pendant/webview/settings page on top of it) */
    synchronized boolean pump(JSONObject task, boolean postForeground) throws Exception {
        if (context == null || !"14.8.40.50567".equals(version)) { state = "unsupported_native_version"; return false; }
        if (!"comment".equals(task.optString("kind")) || !task.optBoolean("auto") ||
            !task.optString("status").startsWith("running") || task.optBoolean("platformBlocked")) return false;
        if (replyTask != task.optLong("taskId") || replyGeneration != task.optLong("generation")) {
            replyTask = task.optLong("taskId"); replyGeneration = task.optLong("generation"); idleReply = null;
        }
        if ("ui".equals(task.getJSONObject("params").optString("pagingMode"))) { state = "ui_requested"; return false; }
        if (!postForeground) {
            // The post is covered: its pager reports "no more" and loads would go nowhere. Wait, do not pause.
            state = "native_waiting_post_foreground"; gate.reset(); eofGuard.reset(); busySince = 0; return true;
        }
        Object pager = null;
        for (int i = pagers.size() - 1; i >= 0; i--) {
            Object candidate = pagers.get(i).get();
            Object photo = SnapshotMapper.field(candidate, "b");
            Object id = SnapshotMapper.invoke(photo, "getKId", new Class<?>[0]);
            if (id != null && task.optString("photoId").equals(String.valueOf(id))) { pager = candidate; break; }
        }
        if (pager == null) { state = "native_pager_not_bound"; return false; }
        if (Boolean.TRUE.equals(call(pager, "h0"))) { pause(task, "manual_login_required"); return true; }
        boolean reply = task.optBoolean("rootComplete");
        Object root = null;
        Object receiver = pager;
        String stream = reply ? "reply" : "root";
        Method load;
        if (!reply) {
            Object core = SnapshotMapper.field(pager, "c1");
            if (core == null) { state = "native_core_not_bound"; return false; }
            if (busy(task, core)) return true;
            if (task.optInt("rootPages") == 0) {
                // Refresh through the App when its first page predates the task.
                receiver = core;
                load = core.getClass().getMethod("refresh");
                state = "native_first_page_refresh";
            } else {
                if (!Boolean.TRUE.equals(call(pager, "k0"))) {
                    if (expandFolded(pager)) { eofGuard.reset(); return true; }
                    Object response = call(core, "j0");
                    if (Boolean.FALSE.equals(SnapshotMapper.invoke(response, "hasMore", new Class<?>[0]))) {
                        markRootEof(task, "native_response_has_more_false"); eofGuard.reset();
                    } else if (eofGuard.persistent(SystemClock.elapsedRealtime(), task.optLong("taskId"), task.optLong("generation"), System.identityHashCode(response))) {
                        pause(task, "native_eof_unconfirmed"); eofGuard.reset(); gate.reset();
                    } else state = "native_eof_pending";
                    return true;
                }
                eofGuard.reset();
                load = pager.getClass().getMethod("k");
                state = "native_root_load";
            }
        } else {
            JSONArray ids = pendingReplyIds(task);
            if (ids == null) { state = "native_pending_ids_loading"; return true; }
            observeRoots(task.optString("photoId"), call(pager, "r0"));
            for (int i = 0; i < ids.length(); i++) {
                WeakReference<Object> ref = roots.get(ids.getString(i));
                if (ref != null && ref.get() != null) { root = ref.get(); break; }
            }
            if (root == null) {
                Object core = SnapshotMapper.field(pager, "c1");
                if (core == null) { state = "native_core_not_bound"; return false; }
                if (busy(task, core)) return true;
                if (call(core, "j0") == null) {
                    receiver = core; load = core.getClass().getMethod("refresh");
                } else if (Boolean.TRUE.equals(call(pager, "k0"))) load = pager.getClass().getMethod("k");
                else if (expandFolded(pager)) return true;
                else { state = "native_pending_root_unavailable"; return false; }
                stream = "restore-replies"; state = "native_restore_reply_roots";
            } else {
                load = null;
                for (Method method : pager.getClass().getDeclaredMethods()) if (method.getName().equals("z") && method.getParameterCount() == 3 &&
                    method.getParameterTypes()[0].getName().equals("vnb.d") && method.getParameterTypes()[1].getName().equals("vnb.q")) { load = method; break; }
                if (load == null || !load.getParameterTypes()[0].isInstance(root) || !load.getParameterTypes()[1].isInstance(root)) { state = "native_reply_signature_mismatch"; return false; }
                state = "native_reply_load";
            }
        }
        long now = SystemClock.elapsedRealtime();
        int progress;
        if (root != null) {
            Object sub = call(root, "getKSubComment");
            Object cached = SnapshotMapper.field(sub, "mComments");
            progress = java.util.Objects.hash(call(root, "getKId"), SnapshotMapper.field(sub, "mCursor"),
                cached instanceof List<?> ? ((List<?>) cached).size() : 0);
        } else {
            // Restored pages may already exist in SQLite; native responses still advance.
            Object core = SnapshotMapper.field(pager, "c1");
            progress = System.identityHashCode(call(core, "j0"));
        }
        NativePageGate.Decision decision = gate.check(now, task.optLong("taskId"), task.optLong("generation"), stream, progress);
        if (decision == NativePageGate.Decision.TIMEOUT) { pause(task, "native_page_timeout"); return true; }
        if (decision == NativePageGate.Decision.WAIT) return true;
        gate.requested(now, progress);
        JSONObject driver = bound(task).put("driver", reply ? "native-replies" : "native-root").put("nativeLoad", true);
        io.execute(() -> { try { CollectorClient.call(context, "paging_driver", driver); } catch (Exception ignored) {} });
        Log.i("CommentCollector", reply ? "native_reply_load" : "native_root_load");
        if (reply && root != null) {
            Object action = load.invoke(pager, root, root, null);
            String type = action == null ? "" : action.getClass().getName();
            Log.i("CommentCollector", "native_reply_action=" + type);
            Object sub = call(root, "getKSubComment");
            if (sub != null) captureReply.accept(sub, new JSONObject().put("photoId", task.optString("photoId"))
                .put("rootId", String.valueOf(call(root, "getKId"))).put("appVersion", version)
                .put("expectedReplies", call(root, "getKSubCommentCount")));
            // Cached expansion has no response and must not arm a network timeout.
            if (type.equals("com.kwai.kmp.comment.mvi.panel.pagelist.a$a$a") ||
                type.equals("com.kwai.kmp.comment.mvi.panel.pagelist.a$a$b")) {
                gate.localCompleted();
                state = "native_reply_cache_expanded";
                idleReply = null;
            } else if (type.equals("com.kwai.kmp.comment.mvi.panel.pagelist.a$a$c")) {
                // No request was issued: allow initialization/another in-flight load to finish.
                gate.localCompleted();
                state = "native_reply_waiting_ready";
                if (idleReply != root) { idleReply = root; idleSince = now; }
                Object cached = sub == null ? null : SnapshotMapper.field(sub, "mComments");
                Log.i("CommentCollector", "native_reply_idle sub=" + (sub != null) + " cached=" +
                    (cached instanceof List<?> ? ((List<?>) cached).size() : 0) + " expected=" + call(root, "getKSubCommentCount"));
                if (now - idleSince >= 45000) pause(task, "native_reply_unconfirmed");
            } else {
                idleReply = null;
            }
        } else load.invoke(receiver);
        return true;
    }

    private boolean expandFolded(Object pager) throws Exception {
        if (!Boolean.TRUE.equals(call(pager, "X0"))) return false;
        state = "native_fold_expanded";
        Log.i("CommentCollector", "native_fold_expanded");
        return true;
    }

    private boolean busy(JSONObject task, Object core) throws Exception {
        if (busyTask != task.optLong("taskId") || busyGeneration != task.optLong("generation")) {
            busyTask = task.optLong("taskId"); busyGeneration = task.optLong("generation"); busySince = 0;
        }
        if (!Boolean.TRUE.equals(call(core, "j"))) { busySince = 0; return false; }
        state = "native_waiting_response";
        long now = SystemClock.elapsedRealtime();
        if (busySince == 0) busySince = now;
        if (now - busySince >= 45000) pause(task, "native_page_timeout");
        return true;
    }

    private static Object call(Object value, String name) throws Exception { return value.getClass().getMethod(name).invoke(value); }
    private static JSONObject bound(JSONObject task) throws Exception {
        return new JSONObject().put("taskId", task.optLong("taskId")).put("generation", task.optLong("generation"));
    }
    private void markRootEof(JSONObject task, String evidence) throws Exception {
        // Keep EOF behind all captured pages on the same storage queue.
        completeRoot.accept(bound(task).put("stream", "root").put("evidence", evidence));
        Log.i("CommentCollector", "native_root_eof evidence=" + evidence);
    }
    private void pause(JSONObject task, String code) throws Exception {
        JSONObject request = bound(task).put("code", code);
        io.execute(() -> { try { CollectorClient.call(context, "pause", request); } catch (Exception ignored) {} });
    }
    /**
     * Returns reply roots fetched off the main thread since the last call, or null while a fetch is in
     * flight. Each result is used once, so a group finished in the meantime is never reloaded from stale ids.
     */
    private JSONArray pendingReplyIds(JSONObject task) throws Exception {
        String key = task.optLong("taskId") + ":" + task.optLong("generation");
        JSONArray ready = pendingIds;
        if (ready != null && key.equals(pendingKey)) { pendingIds = null; return ready; }
        fetchPendingIds(task, key);
        return null;
    }
    private void fetchPendingIds(JSONObject task, String key) throws Exception {
        if (pendingLoading) return;
        pendingLoading = true;
        JSONObject request = bound(task);
        io.execute(() -> {
            try {
                JSONArray items = CollectorClient.call(context, "pending_reply_ids", request).getJSONArray("items");
                pendingKey = key; pendingIds = items;
            } catch (Exception ignored) { pendingIds = null; }
            finally { pendingLoading = false; }
        });
    }
}
