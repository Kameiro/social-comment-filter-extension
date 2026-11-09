package dev.local.commentcollector;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.MotionEvent;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.WeakHashMap;
import org.json.JSONObject;

/**
 * Xiaohongshu (com.xingin.xhs) support. Note-detail pages are not androidx-fragment based, so the current note is the
 * most recent {@code NoteFeed} whose {@code getId()} was read, refined by walking the resumed activity's object graph
 * when possible. Double taps are detected by {@link DoubleTapDetector}. NOTE: needs a logged-in account to exercise.
 */
final class XhsHooks {
    private static final String TAG = "CommentCollector";
    private static final AtomicBoolean installed = new AtomicBoolean();
    private final XposedInterface xposed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Context context;
    private volatile Activity activity;
    private volatile WeakReference<Object> lastNote;
    private volatile long lastNoteAt;
    private volatile WeakReference<Object> detailNote;
    private volatile long detailNoteAt;
    private volatile String lastPostId = "";
    private final WeakIdentityMap<Boolean> recorded = new WeakIdentityMap<>();
    private final CurrentPostResolver resolver = new CurrentPostResolver();
    private DoubleTapDetector detector;
    private XhsComments comments;
    private final WeakHashMap<Dialog, DialogTapState> commentDialogTaps = new WeakHashMap<>();
    private volatile WeakReference<Dialog> activeCommentDialog;

    XhsHooks(XposedInterface xposed) { this.xposed = xposed; }

    void install(ClassLoader loader) {
        if (!installed.compareAndSet(false, true)) return;
        resolver.bind(loader, XhsMapper.NOTE_CLASS);
        try {
            Method start = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            xposed.hook(start).intercept(chain -> { Object result = chain.proceed(); attach((Application) chain.getArg(0)); return result; });
        } catch (Throwable error) { Log.w(TAG, "xhs_lifecycle_hook_failed", error); }
        try {
            Class<?> note = loader.loadClass(XhsMapper.NOTE_CLASS);
            // 9.51 uses obfuscated Kotlin accessors; F0() is NoteFeed.id in this build.
            int hooked = 0;
            try {
                Method id = note.getDeclaredMethod("F0");
                xposed.hook(id).intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self != null) { lastNote = new WeakReference<>(self); lastNoteAt = System.currentTimeMillis(); remember(self); }
                    return result;
                });
                hooked++;
            } catch (NoSuchMethodException ignored) {}
            Log.i(TAG, "xhs_note_getters_hooked=" + hooked);
        } catch (Throwable error) { Log.w(TAG, "xhs_note_hook_unavailable", error); }
        hookDetailModels(loader);
        detector = new DoubleTapDetector(xposed, new DoubleTapDetector.Handler() {
            @Override public Activity activity() { return activity; }
            @Override public boolean onDoubleTap(Activity host, float x, float y) { return showPost(host, x, y); }
            @Override public boolean holdSingleTap(Activity host, float x, float y) { return shouldHoldImageTap(host, x, y); }
        });
        detector.install();
        hookCommentDialogs();
        XhsNativePager.onPostCaptured = id -> { if (id != null && !id.isEmpty()) lastPostId = id; };
        new Thread(() -> {
            try { XhsNativePager.install(xposed, loader); }
            catch (Throwable t) { Log.w(TAG, "xhs_native_install_failed", t); }
        }, "xhs-native-install").start();
        comments = new XhsComments(xposed, loader, () -> context, () -> activity, this::activeRootView, this::currentPostId, id -> { if (id != null && !id.isEmpty()) lastPostId = id; });
        comments.install();
        Log.i(TAG, "xhs_hooks_installed");
    }

    private String currentPostId() {
        try {
            String nativePost = XhsNativePager.post();
            if (!nativePost.isEmpty() && !"null".equals(nativePost)) return nativePost;
            nativePost = XhsNativePager.lastCapturedPost();
            if (!nativePost.isEmpty() && !"null".equals(nativePost)) return nativePost;
            Activity act = activity;
            boolean inDetail = isDetailActivity(act);
            WeakReference<Object> ref = detailNote;
            Object note = (ref == null || (!inDetail && System.currentTimeMillis() - detailNoteAt > 30_000)) ? null : ref.get();
            JSONObject post = XhsMapper.post(note);
            if (post != null && !post.optString("postId").isEmpty()) return post.optString("postId");
            if (inDetail && act != null) {
                Object resolved = resolver.resolveActivity(act, 0, 0);
                JSONObject resolvedPost = XhsMapper.post(resolved);
                if (resolvedPost != null && !resolvedPost.optString("postId").isEmpty()) return resolvedPost.optString("postId");
            }
            return lastPostId;
        } catch (Throwable ignored) { return lastPostId; }
    }

    private boolean showPost(Activity host, float x, float y) {
        try {
            if (isCommentActivity(host)) {
                Log.i(TAG, "xhs_comment_double_tap_suppressed");
                return true;
            }
            if (!isDetailActivity(host)) { Log.i(TAG, "xhs_double_tap_ignored non_detail_activity"); return false; }
            if (isCommentLayer(host, x, y)) { Log.i(TAG, "xhs_double_tap_ignored comment_layer"); return true; }
            Object note = resolver.resolveActivity(host, x, y);
            if (note == null) {
                WeakReference<Object> ref = detailNote;
                note = ref == null || System.currentTimeMillis() - detailNoteAt > 2_000 ? null : ref.get();
            }
            if (note == null) { Log.i(TAG, "xhs_double_tap_no_note"); return false; }
            JSONObject post = XhsMapper.post(note);
            if (post == null) { Log.i(TAG, "xhs_post_map_empty id=" + ModelReader.text(note, "id", "noteId", "sourceNoteId")); return false; }
            Log.i(TAG, "post_info_triggered platform=xhs post=" + post.optString("postId"));
            JSONObject settings = VideoInfoSettings.current(host);
            main.post(() -> PostInfoPanel.show(host, post, settings));
            return true;
        } catch (Throwable error) { Log.w(TAG, "xhs_post_map_failed", error); return false; }
    }

    private void hookDetailModels(ClassLoader loader) {
        try {
            Class<?> repository = loader.loadClass("com.xingin.matrix.notedetail.v3.repo.NoteDetailRepository");
            Method current = repository.getDeclaredMethod("d");
            xposed.hook(current).intercept(chain -> {
                Object result = chain.proceed();
                rememberDetail(result, "repository.d");
                return result;
            });
            Log.i(TAG, "xhs_detail_hook_installed model=NoteDetailRepository method=d");
        } catch (Throwable error) { Log.w(TAG, "xhs_detail_hook_unavailable", error); }
        try {
            Class<?> args = loader.loadClass("pfa.c");
            Class<?> item = loader.loadClass("com.xingin.entities.NoteItemBean");
            Method current = args.getDeclaredMethod("f", item);
            xposed.hook(current).intercept(chain -> {
                rememberDetail(chain.getArg(0), "detail_args.arg0");
                Object result = chain.proceed();
                rememberDetail(result, "detail_args.f");
                return result;
            });
            Log.i(TAG, "xhs_detail_hook_installed model=pfa.c method=f");
        } catch (Throwable error) { Log.w(TAG, "xhs_detail_args_hook_unavailable", error); }
        try {
            Class<?> publisher = loader.loadClass("com.xingin.matrix.detail.page.ItemVisibilityStatePublisher");
            Method current = publisher.getDeclaredMethod("k", int.class);
            xposed.hook(current).intercept(chain -> {
                Object result = chain.proceed();
                rememberDetail(result, "visibility.k");
                return result;
            });
            Log.i(TAG, "xhs_detail_hook_installed model=ItemVisibilityStatePublisher method=k");
        } catch (Throwable error) { Log.w(TAG, "xhs_visibility_hook_unavailable", error); }
    }

    private void hookCommentDialogs() {
        try {
            Method dispatch = Dialog.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            xposed.hook(dispatch).intercept(chain -> {
                Dialog dialog = (Dialog) chain.getThisObject();
                MotionEvent event = (MotionEvent) chain.getArg(0);
                if (isCommentDialog(dialog)) {
                    activeCommentDialog = new WeakReference<>(dialog);
                    if (suppressCommentDoubleTap(dialog, event)) return true;
                }
                return chain.proceed();
            });
            Log.i(TAG, "xhs_comment_dialog_hook_installed");
        } catch (Throwable error) { Log.w(TAG, "xhs_comment_dialog_hook_unavailable", error); }
        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            xposed.hook(show).intercept(chain -> {
                Dialog dialog = (Dialog) chain.getThisObject();
                if (isCommentDialog(dialog)) activeCommentDialog = new WeakReference<>(dialog);
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }

    View activeRootView() {
        WeakReference<Dialog> ref = activeCommentDialog;
        Dialog d = ref != null ? ref.get() : null;
        if (d != null && d.isShowing() && d.getWindow() != null) {
            View decor = d.getWindow().getDecorView();
            if (decor != null && decor.isShown()) return decor;
        }
        Activity act = activity;
        return (act != null && act.getWindow() != null) ? act.getWindow().getDecorView() : null;
    }

    private boolean suppressCommentDoubleTap(Dialog dialog, MotionEvent event) {
        if (event == null) return false;
        long now = event.getEventTime();
        DialogTapState state;
        synchronized (commentDialogTaps) {
            state = commentDialogTaps.get(dialog);
            if (state == null) { state = new DialogTapState(); commentDialogTaps.put(dialog, state); }
        }
        int action = event.getActionMasked();
        if (state.consuming) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) state.consuming = false;
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            float density = dialog.getContext().getResources().getDisplayMetrics().density;
            boolean second = state.lastUp > 0 && now - state.lastUp < 320
                && Math.hypot(event.getX() - state.lastX, event.getY() - state.lastY) < 47 * density;
            state.downAt = now; state.downX = event.getX(); state.downY = event.getY();
            if (second) {
                state.lastUp = 0; state.consuming = true;
                Log.i(TAG, "xhs_comment_double_tap_suppressed");
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            boolean tap = now - state.downAt < 220 && Math.hypot(event.getX() - state.downX, event.getY() - state.downY) < 40;
            state.lastUp = tap ? now : 0; state.lastX = event.getX(); state.lastY = event.getY();
        }
        return false;
    }

    private static boolean isCommentDialog(Dialog dialog) {
        if (dialog == null) return false;
        String name = dialog.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("comment") && name.contains("dialog");
    }

    private static final class DialogTapState {
        long lastUp, downAt;
        float lastX, lastY, downX, downY;
        boolean consuming;
    }

    private void rememberDetail(Object note, String source) {
        if (note == null) return;
        try {
            JSONObject post = XhsMapper.post(note);
            if (post == null) return;
            String id = post.optString("postId");
            if (!id.isEmpty()) lastPostId = id;
            detailNote = new WeakReference<>(note);
            detailNoteAt = System.currentTimeMillis();
            Log.i(TAG, "xhs_current_note source=" + source + " id=" + id);
            remember(note);
        } catch (Throwable ignored) {}
    }

    private static boolean isDetailActivity(Activity host) {
        if (host == null) return false;
        String name = host.getClass().getName();
        return name.contains("com.xingin.matrix.notedetail.NoteDetailActivity")
            || name.contains("com.xingin.matrix.detail.activity.DetailFeedActivity");
    }

    private static boolean isCommentActivity(Activity host) {
        return host != null && host.getClass().getName().contains("com.xingin.comment.input.ui.NoteCommentActivity");
    }

    private static boolean shouldHoldImageTap(Activity host, float x, float y) {
        return false;
    }

    private static boolean isCommentLayer(Activity host, float x, float y) {
        if (host == null || host.getWindow() == null) return false;
        View view = deepestView(host.getWindow().getDecorView(), x, y);
        for (View current = view; current != null; ) {
            String name = current.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            CharSequence description = current.getContentDescription();
            String text = description == null ? "" : description.toString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("comment") || name.contains("reply") || name.contains("input") || name.contains("bottomsheet")
                || name.contains("dialog") || current instanceof android.widget.EditText
                || text.contains("评论") || text.contains("回复")) return true;
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static View deepestView(View root, float x, float y) {
        if (root == null || root.getVisibility() != View.VISIBLE || !root.isShown()) return null;
        int[] location = new int[2]; root.getLocationOnScreen(location);
        if (x < location[0] || y < location[1] || x >= location[0] + root.getWidth() || y >= location[1] + root.getHeight()) return null;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = deepestView(group.getChildAt(i), x, y);
                if (found != null) return found;
            }
        }
        return root;
    }

    private void remember(Object note) {
        Context app = context;
        if (app == null) return;
        synchronized (recorded) { if (recorded.put(note, Boolean.TRUE) != null) return; }
        new Thread(() -> {
            try {
                JSONObject post = XhsMapper.post(note);
                if (post != null) CollectorClient.call(app, "record_post", new JSONObject().put("post", post));
            } catch (Throwable ignored) {}
        }, "xhs-record-post").start();
    }

    private void attach(Application app) {
        if (context != null) return;
        context = app;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity value) {
                activity = value;
                CollectorPanel.attach(value, XhsHooks.this::currentPostId);
                CollectorPanel.attachDouyinSettingsEntry(value, XhsHooks.this::currentPostId);
                if (comments != null) comments.ensurePaging();
            }
            @Override public void onActivityPaused(Activity value) {
                CollectorPanel.detach(value);
                if (activity == value) activity = null;
            }
            @Override public void onActivityCreated(Activity value, Bundle state) { if (detector != null) detector.hook(value.getClass()); }
            @Override public void onActivityStarted(Activity value) {}
            @Override public void onActivityStopped(Activity value) {}
            @Override public void onActivitySaveInstanceState(Activity value, Bundle state) {}
            @Override public void onActivityDestroyed(Activity value) {}
        });
        Log.i(TAG, "xhs_application_attached");
    }
}
