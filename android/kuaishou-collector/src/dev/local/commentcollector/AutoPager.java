package dev.local.commentcollector;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Rect;
import android.widget.TextView;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.json.JSONObject;

final class AutoPager implements Application.ActivityLifecycleCallbacks {
    private final Context context;
    private final Supplier<String> lastPhoto;
    private final Consumer<JSONObject> bootstrap;
    private final NativeCommentPager nativePager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> activity = new WeakReference<>(null);
    volatile WeakReference<Dialog> dialog = new WeakReference<>(null);
    private long taskId;
    private long generation;
    private int pages = -1;
    private long lastProgress;
    private String lastClick = "";
    private int actions;
    private boolean running;
    private boolean repliesStarted;
    private boolean navigationRequested;
    private String profileUser = "";
    private long navigatedAt;
    private long lastHeartbeat;
    private String stage = "idle", errorType = "";
    private final List<WeakReference<View>> windows = new ArrayList<>();

    AutoPager(Context context, Supplier<String> lastPhoto, Consumer<JSONObject> bootstrap, NativeCommentPager nativePager) {
        this.context = context; this.lastPhoto = lastPhoto; this.bootstrap = bootstrap; this.nativePager = nativePager;
    }
    void start() { handler.postDelayed(this::tick, 3000); }
    void trackWindow(View view) {
        if (view == null) return;
        windows.removeIf(ref -> ref.get() == null || ref.get() == view);
        windows.add(new WeakReference<>(view));
        while (windows.size() > 20) windows.remove(0);
    }
    private void tick() {
        try {
            Bundle result = context.getContentResolver().call(CaptureProvider.URI, "status", null, null);
            if (result == null || result.containsKey("error")) return;
            JSONObject state = new JSONObject(result.getString("json"));
            if (!state.optString("status").startsWith("running")) { running = false; stage = state.optString("status"); return; }
            String taskPlatform = state.optString("platform");
            if ((!taskPlatform.isEmpty() && !"kuaishou".equals(taskPlatform))
                || state.optString("photoId").matches("[0-9a-fA-F]{24}")) {
                running = false;
                return;
            }
            if (!running) { actions = 0; lastProgress = SystemClock.elapsedRealtime(); running = true; }
            if (taskId != state.optLong("taskId") || generation != state.optLong("generation")) {
                taskId = state.optLong("taskId"); pages = -1; actions = 0; lastClick = ""; repliesStarted = false;
                generation = state.optLong("generation"); navigationRequested = false; profileUser = ""; navigatedAt = 0;
                if ("comment".equals(state.optString("kind"))) { bootstrap.accept(state); return; }
            }
            if (!state.optBoolean("auto")) { running = false; return; }
            if (pages != state.optInt("pages")) { pages = state.optInt("pages"); lastProgress = SystemClock.elapsedRealtime(); }
            Activity foreground = activity.get();
            if (foreground == null) { stage = "waiting_foreground"; lastProgress = SystemClock.elapsedRealtime(); return; }
            if (CollectorPanel.showing()) {
                stage = "database_open";
                if ("comment".equals(state.optString("kind")) && nativePager.pump(state)) {
                    lastProgress = SystemClock.elapsedRealtime(); return;
                }
                lastProgress = SystemClock.elapsedRealtime(); return;
            }
            if (SystemClock.elapsedRealtime() - lastProgress > 60000) {
                pause("discovery".equals(state.optString("kind")) ? "no_new_search_page" :
                    "comment".equals(state.optString("kind")) && state.optInt("rootPages") == 0 ? "root_page_not_observed" : "no_new_comment_page"); return;
            }
            if (foreground != null && foreground.hasWindowFocus() &&
                foreground.getClass().getName().matches(".*(?:\\.login\\.|LoginActivity).*")) {
                pause("manual_login_required"); return;
            }
            View root = window();
            if (root == null) return;
            List<View> nodes = new ArrayList<>(); flatten(root, nodes);
            for (View view : nodes) {
                if (!view.isShown() || resourceAncestor(view, "comment_frame")) continue;
                String text = textOf(view);
                if (text.contains("安全验证") || text.contains("请完成验证") || text.contains("拖动滑块") || text.contains("请先登录")) {
                    pause("manual_verification_required"); return;
                }
            }
            if ("profile".equals(state.optString("kind"))) { profiles(state, foreground); return; }
            if ("discovery".equals(state.optString("kind"))) {
                if (!navigationRequested) {
                    launch(foreground, "kwai://search?keyword=" + Uri.encode(state.getJSONObject("params").optString("keyword")));
                    navigationRequested = true; lastProgress = SystemClock.elapsedRealtime(); return;
                }
                if (SystemClock.elapsedRealtime() - lastProgress > 60000) { pause("no_new_search_page"); return; }
                for (View node : nodes) if (node.isShown() && node.getClass().getName().contains("RecyclerView") && node.canScrollVertically(1)) {
                    node.scrollBy(0, Math.max(100, (int) (node.getHeight() * .7f))); actions++; return;
                }
                return;
            }
            stage = "comment_loading";
            if ("comment".equals(state.optString("kind")) && nativePager.pump(state)) {
                lastProgress = SystemClock.elapsedRealtime(); return;
            }
            boolean hasComments = false;
            for (View node : nodes) if (node.getClass().getName().contains("RecyclerView") && commentList(node)) { hasComments = true; break; }
            if (!navigationRequested && !state.optString("photoId").isEmpty() &&
                (!state.optString("photoId").equals(lastPhoto.get()) || !hasComments || state.optInt("rootPages") == 0)) {
                stage = "opening_target_post";
                launch(foreground, "kwai://work/" + Uri.encode(state.optString("photoId"))); navigationRequested = true;
                navigatedAt = SystemClock.elapsedRealtime(); lastProgress = navigatedAt; return;
            }
            if (navigationRequested && !hasComments && SystemClock.elapsedRealtime() - navigatedAt > 4000) {
                stage = "opening_comments";
                for (View node : nodes) if (node.isShown() && node.getContentDescription() != null &&
                    node.getContentDescription().toString().matches("评论[0-9万,.]+")) {
                    if (node.performClick() || tap(root, node)) { navigatedAt = SystemClock.elapsedRealtime(); return; }
                }
            }
            if (state.optString("photoId").isEmpty() || !state.optString("photoId").equals(lastPhoto.get())) return;
            if ("native".equals(state.getJSONObject("params").optString("pagingMode")) && hasComments && state.optInt("rootPages") > 0) {
                pause("native_adapter_unavailable"); return;
            }
            if (SystemClock.elapsedRealtime() - lastProgress > 60000) { pause("no_new_comment_page"); return; }
            if (actions >= 300) { pause("auto_action_limit"); return; }
            View list = null;
            for (View node : nodes) {
                if (!node.isShown() || node.getHeight() < 150 || !node.getClass().getName().contains("RecyclerView")) continue;
                if (commentList(node) && (list == null || node.getHeight() > list.getHeight())) list = node;
            }
            if (list == null) return;
            if (!state.optBoolean("rootComplete") && !list.canScrollVertically(1)) {
                for (View view : itemsOf(root)) {
                    if (!view.isShown() || !(view instanceof TextView)) continue;
                    String label = ((TextView) view).getText().toString();
                    if (!label.contains("已折叠") || !label.contains("展开")) continue;
                    View clickable = view;
                    for (int i = 0; i < 3 && !clickable.isClickable() && clickable.getParent() instanceof View; i++) clickable = (View) clickable.getParent();
                    if (clickable.isClickable() && !lastClick.equals("fold:" + pages)) { clickable.performClick(); lastClick = "fold:" + pages; actions++; return; }
                }
            }
            if (state.optBoolean("rootComplete") && !repliesStarted) {
                list.getClass().getMethod("scrollToPosition", int.class).invoke(list, 0);
                repliesStarted = true; actions++; return;
            }
            List<View> items = new ArrayList<>(); flatten(list, items);
            if (state.optBoolean("rootComplete")) for (View view : items) {
                if (!view.isShown()) continue;
                String text = textOf(view);
                if (!text.matches(".*(?:展开.*回复|查看更多回复|更多回复).*")) continue;
                if (!resourceAncestor(view, "sub_comment_more")) continue;
                String key = pages + ":" + text + ":" + view.getTop();
                if (key.equals(lastClick)) continue;
                if (tap(root, view)) { Log.i("CommentCollector", "reply_control_tapped"); lastClick = key; actions++; return; }
            }
            CollectorClient.call(context, "paging_driver", new JSONObject().put("taskId", taskId).put("generation", generation).put("driver", "ui").put("uiScroll", true));
            list.scrollBy(0, Math.max(100, (int) (list.getHeight() * .75f))); actions++;
        } catch (Exception error) {
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            errorType = cause.getClass().getSimpleName(); stage = "adapter_error";
            Log.w("CommentCollector", "adapter_error:" + errorType);
            pause("adapter_error:" + errorType.toLowerCase(java.util.Locale.ROOT));
        } finally { heartbeat(); handler.postDelayed(this::tick, 3000); }
    }
    private void heartbeat() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastHeartbeat < 10000) return;
        lastHeartbeat = now;
        try {
            JSONObject report = nativePager.diagnostics().put("loadedBuild", BuildInfo.ID).put("stage", stage)
                .put("taskId", taskId).put("generation", generation).put("pid", android.os.Process.myPid()).put("errorType", errorType)
                .put("appVersion", context.getPackageManager().getPackageInfo("com.smile.gifmaker", 0).versionName);
            CollectorClient.call(context, "runtime_update", report);
        } catch (Exception ignored) { Log.w("CommentCollector", "runtime_diagnostic_unavailable"); }
    }
    private View window() {
        for (int i = windows.size() - 1; i >= 0; i--) {
            View view = windows.get(i).get();
            if (view != null && view.isShown() && view.isAttachedToWindow()) {
                List<View> nodes = new ArrayList<>(); flatten(view, nodes);
                for (View node : nodes) if (node.getClass().getName().contains("RecyclerView") && commentList(node)) return view;
            }
        }
        Dialog current = dialog.get();
        if (current != null && current.isShowing() && current.getWindow() != null && current.getWindow().getDecorView().hasWindowFocus()) return current.getWindow().getDecorView();
        Activity currentActivity = activity.get();
        return currentActivity != null && currentActivity.hasWindowFocus() ? currentActivity.getWindow().getDecorView() : null;
    }
    private void launch(Activity activity, String uri) { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.smile.gifmaker")); }
    private void profiles(JSONObject task, Activity activity) throws Exception {
        JSONObject input = new JSONObject().put("taskId", taskId);
        JSONObject next = CollectorClient.call(context, "profile_next", input); String uid = next.optString("userId");
        if (uid.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!uid.equals(profileUser)) {
            if (now - navigatedAt < 6000) return;
            profileUser = uid; navigatedAt = now; launch(activity, "kwai://profile/" + Uri.encode(uid));
        } else if (now - navigatedAt > 25000) {
            CollectorClient.call(context, "profile_next", input.put("failedUserId", uid)); profileUser = "";
        }
    }
    private boolean commentList(View view) {
        if (commentAncestor(view)) return true;
        List<View> children = new ArrayList<>(); flatten(view, children);
        for (View child : children) {
            try {
                String id = child.getResources().getResourceEntryName(child.getId());
                if ("comment_frame".equals(id) || "sub_comment_more".equals(id)) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }
    private boolean commentAncestor(View view) {
        for (int i = 0; view != null && i < 8; i++) {
            String name = view.getClass().getName();
            try { if (view.getId() != View.NO_ID) name += view.getResources().getResourceEntryName(view.getId()); }
            catch (Exception ignored) {}
            if (name.toLowerCase(java.util.Locale.ROOT).contains("comment")) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }
    private static boolean resourceAncestor(View view, String name) {
        for (int i = 0; view != null && i < 12; i++) {
            try { if (view.getResources().getResourceEntryName(view.getId()).startsWith(name)) return true; }
            catch (Exception ignored) {}
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }
    private static void flatten(View view, List<View> result) {
        if (result.size() >= 5000) return;
        result.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) flatten(((ViewGroup) view).getChildAt(i), result);
    }
    private static List<View> itemsOf(View root) { List<View> result = new ArrayList<>(); flatten(root, result); return result; }
    private static String textOf(View view) {
        Object value = view instanceof TextView ? ((TextView) view).getText() :
            view.getClass().getSimpleName().contains("Text") ? SnapshotMapper.invoke(view, "getText", new Class<?>[0]) : null;
        return value == null ? "" : value.toString();
    }
    private static boolean tap(View root, View target) {
        Rect bounds = new Rect(); if (!target.getLocalVisibleRect(bounds) || bounds.width() < 8 || bounds.height() < 8) return false;
        int[] origin = new int[2], position = new int[2]; root.getLocationOnScreen(origin); target.getLocationOnScreen(position);
        long now = SystemClock.uptimeMillis();
        float x = position[0] + bounds.exactCenterX() - origin[0], y = position[1] + bounds.exactCenterY() - origin[1];
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { boolean handled = root.dispatchTouchEvent(down);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                MotionEvent up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
                up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try { if (root.isAttachedToWindow()) root.dispatchTouchEvent(up); } finally { up.recycle(); }
            }, 100); return handled;
        } finally { down.recycle(); }
    }
    private void pause(String code) {
        try { CollectorClient.call(context, "pause", new JSONObject().put("taskId", taskId).put("generation", generation).put("code", code)); }
        catch (Exception ignored) {}
    }
    @Override public void onActivityResumed(Activity value) { activity = new WeakReference<>(value); CollectorPanel.attach(value, lastPhoto); }
    @Override public void onActivityCreated(Activity value, Bundle state) {}
    @Override public void onActivityStarted(Activity value) {}
    @Override public void onActivityPaused(Activity value) { if (activity.get() == value) activity.clear(); }
    @Override public void onActivityStopped(Activity value) {}
    @Override public void onActivitySaveInstanceState(Activity value, Bundle state) {}
    @Override public void onActivityDestroyed(Activity value) { CollectorPanel.detach(value); if (activity.get() == value) activity.clear(); }
}
