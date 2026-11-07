package dev.local.commentcollector;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Captures pages returned by XHS's native comment panel while an XHS comment task is running. */
final class XhsComments {
    private static final String TAG = "CommentCollector";
    private final XposedInterface xposed;
    private final ClassLoader loader;
    private final Supplier<Context> context;
    private final Supplier<String> currentPost;
    private final WeakIdentityMap<String> seen = new WeakIdentityMap<>();

    XhsComments(XposedInterface xposed, ClassLoader loader, Supplier<Context> context, Supplier<String> currentPost) {
        this.xposed = xposed; this.loader = loader; this.context = context; this.currentPost = currentPost;
    }

    void install() {
        try {
            Class<?> response = loader.loadClass("com.xingin.entities.q");
            Method getter = response.getDeclaredMethod("f"); // comments: ArrayList<CommentCommentInfo>
            xposed.hook(getter).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self != null) observe(self);
                return result;
            });
            Log.i(TAG, "xhs_comment_hook_installed model=com.xingin.entities.q getter=f");
        } catch (Throwable error) { Log.w(TAG, "xhs_comment_hook_failed", error); }
    }

    private void observe(Object response) {
        try {
            Context app = context.get();
            if (app == null) return;
            JSONObject page = XhsCommentMapper.page(response, currentPost.get());
            if (page == null) return;
            JSONObject task = CollectorClient.call(app, "status", new JSONObject());
            String status = task.optString("status");
            if (!"comment".equals(task.optString("kind")) || !status.startsWith("running")) {
                Log.i(TAG, "xhs_comment_skipped task_not_running kind=" + task.optString("kind") + " status=" + status);
                return;
            }
            String wanted = task.optString("photoId");
            if (!wanted.isEmpty() && !wanted.equals(page.optString("photoId"))) {
                Log.i(TAG, "xhs_comment_skipped other_post task=" + wanted + " page=" + page.optString("photoId"));
                return;
            }
            JSONArray rows = page.getJSONArray("rows");
            String fingerprint = page.optString("cursor") + ":" + page.optString("photoId") + ":" + rows.length()
                + ":" + firstId(rows) + ":" + task.optLong("taskId") + ":" + task.optLong("generation");
            synchronized (seen) { if (fingerprint.equals(seen.put(response, fingerprint))) return; }
            Log.i(TAG, "xhs_comment_list rows=" + rows.length() + " post=" + page.optString("photoId")
                + " more=" + page.opt("hasMore") + " cursor=" + page.optString("cursor"));
            Bundle args = new Bundle(); args.putString("page", page.toString());
            args.putLong("taskId", task.optLong("taskId")); args.putLong("generation", task.optLong("generation"));
            Bundle result = CollectorClient.callRaw(app, "append", args);
            if (result != null && result.containsKey("error")) Log.w(TAG, "xhs_page_storage_failed " + result.getString("error"));
        } catch (Throwable error) { Log.w(TAG, "xhs_comment_mapping_failed", error); }
    }

    private static String firstId(JSONArray rows) throws Exception {
        return rows.length() == 0 ? "" : rows.getJSONObject(0).optString("commentId");
    }
}
