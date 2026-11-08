package dev.local.commentcollector;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Captures Xiaohongshu comment pages as the app loads them and auto-scrolls/expands
 * while a comment task is running.
 */
final class XhsComments {
    private static final String TAG = "CommentCollector";
    private final XposedInterface xposed;
    private final ClassLoader loader;
    private final Supplier<Context> context;
    private final Supplier<Activity> activity;
    private final Supplier<View> activeRootView;
    private final Supplier<String> currentPost;
    private final Consumer<String> onPostObserved;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final WeakIdentityMap<String> seen = new WeakIdentityMap<>();
    private final WeakIdentityMap<Long> clickedExpands = new WeakIdentityMap<>();
    private final Set<String> savedFingerprints = ConcurrentHashMap.newKeySet();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, JSONObject> recentPages = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) { return size() > 10; }
    };
    private volatile boolean paging;
    private volatile long lastPageAt;
    private volatile int idleTicks;

    XhsComments(XposedInterface xposed, ClassLoader loader, Supplier<Context> context, Supplier<Activity> activity,
                Supplier<View> activeRootView, Supplier<String> currentPost, Consumer<String> onPostObserved) {
        this.xposed = xposed;
        this.loader = loader;
        this.context = context;
        this.activity = activity;
        this.activeRootView = activeRootView;
        this.currentPost = currentPost;
        this.onPostObserved = onPostObserved;
    }

    XhsComments(XposedInterface xposed, ClassLoader loader, Supplier<Context> context, Supplier<Activity> activity,
                Supplier<String> currentPost, Consumer<String> onPostObserved) {
        this(xposed, loader, context, activity, () -> {
            Activity act = activity.get();
            return (act != null && act.getWindow() != null) ? act.getWindow().getDecorView() : null;
        }, currentPost, onPostObserved);
    }

    XhsComments(XposedInterface xposed, ClassLoader loader, Supplier<Context> context, Supplier<String> currentPost) {
        this(xposed, loader, context, () -> null, () -> null, currentPost, null);
    }

    void install() {
        String[] modelClasses = {"com.xingin.entities.q", "com.xingin.entities.v", "com.xingin.entities.w"};
        for (String className : modelClasses) {
            try {
                Class<?> clazz = loader.loadClass(className);
                final WeakIdentityMap<Boolean> handled = new WeakIdentityMap<>();
                int hooked = 0;
                for (Method m : clazz.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic()) continue;
                    try {
                        xposed.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            Object self = chain.getThisObject();
                            if (self != null) {
                                boolean first;
                                synchronized (handled) { first = handled.put(self, Boolean.TRUE) == null; }
                                if (first) observe(self);
                            }
                            return result;
                        });
                        hooked++;
                    } catch (Throwable ignored) {}
                }
                Log.i(TAG, "xhs_comment_hook_installed class=" + className + " methods=" + hooked);
            } catch (Throwable e) {
                Log.w(TAG, "xhs_comment_hook_failed class=" + className, e);
            }
        }

        // Lightweight background watcher ensuring auto-paging runs while an XHS comment task is active
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2500);
                    Context app = context.get();
                    if (app != null && !paging) {
                        JSONObject task = CollectorClient.call(app, "status", new JSONObject());
                        String plat = task.optString("platform");
                        if ((plat.isEmpty() || "xhs".equals(plat))
                            && "comment".equals(task.optString("kind"))
                            && task.optString("status").startsWith("running")
                            && task.optBoolean("auto", true)) {
                            main.post(this::ensurePaging);
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {}
            }
        }, "xhs-task-watcher").start();
    }

    void ensurePaging() { startPaging(); }

    private void startPaging() {
        lastPageAt = System.currentTimeMillis();
        idleTicks = 0;
        if (paging) return;
        paging = true;
        main.postDelayed(this::tick, 900);
    }

    private void stopPaging(String why) {
        if (paging) {
            paging = false;
            Log.i(TAG, "xhs_auto_page_stop " + why);
        }
    }

    private void tick() {
        if (!paging) return;
        new Thread(() -> {
            Context app = context.get();
            if (app == null) { main.postDelayed(this::tick, 2000); return; }
            JSONObject task = null;
            try {
                task = CollectorClient.call(app, "status", new JSONObject());
            } catch (Throwable ignored) {}
            final JSONObject activeTask = task;
            main.post(() -> {
                if (!paging) return;
                if (activeTask == null) { main.postDelayed(this::tick, 2000); return; }
                String plat = activeTask.optString("platform");
                boolean go = (plat.isEmpty() || "xhs".equals(plat))
                    && "comment".equals(activeTask.optString("kind"))
                    && activeTask.optString("status").startsWith("running")
                    && activeTask.optBoolean("auto", true);
                if (!go) { stopPaging("task_not_running"); return; }

                String wanted = activeTask.optString("photoId");
                List<JSONObject> toReplay = new ArrayList<>();
                synchronized (recentPages) {
                    for (Map.Entry<String, JSONObject> entry : recentPages.entrySet()) {
                        if (entry.getKey().startsWith(wanted + ":")) {
                            toReplay.add(entry.getValue());
                        }
                    }
                }
                for (JSONObject cached : toReplay) {
                    savePage(app, activeTask, cached, null);
                }

                if (SystemClock.uptimeMillis() - DoubleTapDetector.lastUserTouch < 4000) {
                    lastPageAt = System.currentTimeMillis();
                    main.postDelayed(this::tick, 800);
                    return;
                }

                idleTicks = System.currentTimeMillis() - lastPageAt > 1500 ? idleTicks + 1 : 0;
                scrollComments(app, activeTask);
                main.postDelayed(this::tick, 1200);
            });
        }, "xhs-auto-page").start();
    }

    private void scrollComments(Context app, JSONObject task) {
        Activity host = activity.get();
        if (host == null || host.isFinishing()) return;
        View decor = activeRootView != null ? activeRootView.get() : null;
        if (decor == null && host.getWindow() != null) decor = host.getWindow().getDecorView();
        if (decor == null) return;

        // 1. Check if there is an unexpanded reply button visible
        View expandBtn = findExpandButton(decor);
        if (expandBtn != null) {
            clickedExpands.put(expandBtn, System.currentTimeMillis());
            expandBtn.performClick();
            lastPageAt = System.currentTimeMillis();
            idleTicks = 0;
            return;
        }

        // 2. Find comment list RecyclerView
        int screenHeight = host.getResources().getDisplayMetrics().heightPixels;
        View best = findCommentList(decor, screenHeight, null);
        if (best == null) {
            View commentBtn = findCommentButton(decor);
            if (commentBtn != null) commentBtn.performClick();
            return;
        }

        boolean canScrollDown = best.canScrollVertically(1);
        if (!canScrollDown) {
            // At bottom of list
            if (idleTicks >= 4 && ("running-replies".equals(task.optString("status")) || task.optBoolean("rootComplete"))) {
                completePendingReplies(app, task);
            }
            if (idleTicks > 10) {
                // Synthetic EOF if stuck at bottom
                JSONObject eof = new JSONObject();
                try {
                    String photo = task.optString("photoId");
                    eof.put("platform", "xhs").put("photoId", photo).put("postId", photo)
                        .put("rootId", "").put("rows", new JSONArray()).put("groups", new JSONArray())
                        .put("hasMore", false).put("eofEvidence", "scroll.at_bottom_timeout").put("source", "xhs_android.synthetic_eof");
                    savePage(app, task, eof, null);
                } catch (Throwable ignored) {}
                completePendingReplies(app, task);
                stopPaging("at_bottom_timeout");
                return;
            }
        }

        // 3. Dispatch touch drag upward
        int[] at = new int[2];
        best.getLocationOnScreen(at);
        float x = at[0] + best.getWidth() * 0.5f;
        float from = Math.max(at[1] + best.getHeight() * 0.82f, screenHeight * 0.78f);
        float to = Math.min(at[1] + best.getHeight() * 0.22f, screenHeight * 0.25f);
        if (!canScrollDown) {
            // Pull slightly down then push back up to trigger reload
            float swap = from;
            from = to + (swap - to) * 0.3f;
            to = swap;
        }
        long down = SystemClock.uptimeMillis();
        final int steps = 10;
        final float startY = from, endY = to;
        final View targetDecor = decor;
        dispatch(targetDecor, down, down, MotionEvent.ACTION_DOWN, x, startY);
        for (int i = 1; i <= steps; i++) {
            final int n = i;
            main.postDelayed(() -> dispatch(targetDecor, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * n / steps), 18L * i);
        }
        main.postDelayed(() -> {
            dispatch(targetDecor, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, endY);
            if (best.canScrollVertically(1)) {
                best.scrollBy(0, Math.max(200, (int) (best.getHeight() * 0.55f)));
            }
        }, 18L * (steps + 1));
        Log.i(TAG, "xhs_auto_scroll view=" + best.getClass().getSimpleName() + " canScrollDown=" + canScrollDown + " idle=" + idleTicks);
    }

    private void completePendingReplies(Context app, JSONObject task) {
        new Thread(() -> {
            try {
                long taskId = task.optLong("taskId");
                long generation = task.optLong("generation");
                String photo = task.optString("photoId");
                JSONObject listing = CollectorClient.call(app, "pending_reply_ids", new JSONObject().put("taskId", taskId).put("generation", generation));
                JSONArray ids = listing.optJSONArray("items");
                if (ids != null && ids.length() > 0) {
                    JSONArray groups = new JSONArray();
                    for (int i = 0; i < ids.length(); i++) {
                        groups.put(new JSONObject().put("rootId", ids.getString(i)).put("complete", true));
                    }
                    JSONObject page = new JSONObject().put("platform", "xhs").put("photoId", photo).put("postId", photo)
                        .put("rootId", ids.getString(0)).put("rows", new JSONArray()).put("groups", groups)
                        .put("hasMore", false).put("eofEvidence", "xhs.replies_unreachable").put("source", "xhs_android.synthetic_replies");
                    Bundle args = new Bundle();
                    args.putString("page", page.toString());
                    args.putLong("taskId", taskId);
                    args.putLong("generation", generation);
                    CollectorClient.callRaw(app, "append", args);
                }
            } catch (Throwable error) {
                Log.w(TAG, "xhs_complete_replies_failed", error);
            }
        }, "xhs-complete-replies").start();
    }

    private static void dispatch(View root, long down, long time, int action, float x, float y) {
        if (root == null) return;
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        DoubleTapDetector.injecting = true;
        try { root.dispatchTouchEvent(event); } catch (Throwable ignored) {}
        finally { DoubleTapDetector.injecting = false; event.recycle(); }
    }

    private View findExpandButton(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (root.isClickable()) {
            CharSequence text = null;
            if (root instanceof TextView) text = ((TextView) root).getText();
            else text = root.getContentDescription();
            if (text != null) {
                String str = text.toString().trim();
                if (str.matches(".*(展开.*回复|展开更多.*|查看.*折叠.*|查看全部.*回复).*")) {
                    Long last = clickedExpands.get(root);
                    if (last == null || System.currentTimeMillis() - last > 3000) {
                        return root;
                    }
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findExpandButton(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findCommentList(View view, int screenHeight, View best) {
        if (view == null || view.getVisibility() != View.VISIBLE) return best;
        boolean canScrollVertical = view.canScrollVertically(1) || view.canScrollVertically(-1);
        boolean isRecyclerView = view.getClass().getName().contains("RecyclerView");
        boolean isHorizontalOnly = view.canScrollHorizontally(1) || view.canScrollHorizontally(-1);

        if (canScrollVertical || (isRecyclerView && !isHorizontalOnly)) {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            int h = view.getHeight();
            if (h > screenHeight * 0.20f) {
                if (best == null) {
                    best = view;
                } else {
                    int[] bestAt = new int[2];
                    best.getLocationOnScreen(bestAt);
                    boolean bestCanScroll = best.canScrollVertically(1) || best.canScrollVertically(-1);
                    if (canScrollVertical && !bestCanScroll) {
                        best = view;
                    } else if (!canScrollVertical && bestCanScroll) {
                        // keep best
                    } else {
                        int bottom = at[1] + h;
                        int bestBottom = bestAt[1] + best.getHeight();
                        if (at[1] > bestAt[1] || bottom > bestBottom) {
                            best = view;
                        } else if (at[1] == bestAt[1] && h > best.getHeight()) {
                            best = view;
                        }
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                best = findCommentList(group.getChildAt(i), screenHeight, best);
            }
        }
        return best;
    }

    private static View findCommentButton(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        CharSequence desc = view.getContentDescription();
        if (desc != null && desc.toString().contains("评论") && !desc.toString().contains("发表") && view.isClickable()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findCommentButton(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    private void observe(Object response) {
        try {
            Context app = context.get();
            if (app == null) return;
            JSONObject page = XhsCommentMapper.page(response, currentPost.get());
            if (page == null) return;
            String post = page.optString("photoId");
            if (!post.isEmpty() && onPostObserved != null) {
                onPostObserved.accept(post);
            }
            String cacheKey = post + ":" + page.optString("rootId") + ":" + page.optString("cursor");
            synchronized (recentPages) { recentPages.put(cacheKey, page); }

            JSONObject task = CollectorClient.call(app, "status", new JSONObject());
            String status = task.optString("status");
            if (!"comment".equals(task.optString("kind")) || !status.startsWith("running")) {
                Log.i(TAG, "xhs_comment_cached_before_task post=" + post + " root=" + page.optString("rootId"));
                return;
            }
            String wanted = task.optString("photoId");
            if (!wanted.isEmpty() && !wanted.equals(post)) {
                Log.i(TAG, "xhs_comment_skipped other_post wanted=" + wanted + " got=" + post);
                return;
            }
            savePage(app, task, page, response);
        } catch (Throwable error) {
            Log.w(TAG, "xhs_comment_mapping_failed", error);
        }
    }

    private void savePage(Context app, JSONObject task, JSONObject page, Object responseIdentity) {
        JSONArray rows = page.optJSONArray("rows");
        if (rows == null || rows.length() == 0) {
            if (!page.has("hasMore") || page.optBoolean("hasMore")) return;
        }
        long taskId = task.optLong("taskId");
        long generation = task.optLong("generation");
        String fingerprint = page.optString("cursor") + ":" + page.optString("photoId") + ":" + page.optString("rootId")
            + ":" + (rows == null ? 0 : rows.length()) + ":" + firstId(rows) + ":" + taskId + ":" + generation;
        if (!savedFingerprints.add(fingerprint)) return;
        if (responseIdentity != null) {
            synchronized (seen) { if (fingerprint.equals(seen.put(responseIdentity, fingerprint))) return; }
        }
        lastPageAt = System.currentTimeMillis();
        idleTicks = 0;
        if (page.optString("rootId").isEmpty()) {
            if (!Boolean.FALSE.equals(page.opt("hasMore"))) {
                main.post(this::startPaging);
            }
        }
        writer.execute(() -> {
            try {
                Bundle args = new Bundle();
                args.putString("page", page.toString());
                args.putLong("taskId", taskId);
                args.putLong("generation", generation);
                Bundle result = CollectorClient.callRaw(app, "append", args);
                if (result != null && result.containsKey("error")) {
                    Log.w(TAG, "xhs_page_storage_failed " + result.getString("error"));
                }
            } catch (Throwable error) {
                Log.w(TAG, "xhs_storage_unavailable", error);
            }
        });
    }

    private static String firstId(JSONArray rows) {
        if (rows == null || rows.length() == 0) return "";
        try { return rows.getJSONObject(0).optString("commentId"); } catch (Throwable ignored) { return ""; }
    }
}
