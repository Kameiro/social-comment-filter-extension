package dev.local.commentcollector;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Small, platform-neutral chat runner used after the user explicitly confirms a batch.
 * It opens each native chat, fills the visible composer and presses the platform's send
 * control. Platform hooks notify it when a new Activity is resumed; no accessibility
 * service or background broadcast is used.
 */
final class DirectMessageRunner {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static DirectMessageRunner active;

    private final Activity origin;
    private final String platform;
    private final String template;
    private final String action;
    private final ArrayDeque<JSONObject> queue = new ArrayDeque<>();
    private JSONObject current;
    private long waitingSince;
    private boolean filling;

    private DirectMessageRunner(Activity origin, String platform, String template, String action) {
        this.origin = origin; this.platform = platform; this.template = template; this.action = action;
    }

    static boolean running() { synchronized (LOCK) { return active != null; } }

    static void start(Activity origin, String platform, JSONArray ids, String template) {
        if (origin == null || ids == null || ids.length() == 0 || template == null || template.trim().isEmpty()) return;
        synchronized (LOCK) {
            if (active != null) {
                Toast.makeText(origin, "已有私信任务正在进行", Toast.LENGTH_SHORT).show();
                return;
            }
            active = new DirectMessageRunner(origin, platform, template.trim(), "message");
        }
        new Thread(() -> {
            DirectMessageRunner runner;
            synchronized (LOCK) { runner = active; }
            if (runner == null) return;
            try {
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i);
                    if (id.isEmpty()) continue;
                    JSONObject listing = CollectorClient.call(origin, "users", new JSONObject()
                        .put("userId", id).put("limit", 1));
                    JSONArray items = listing.optJSONArray("items");
                    JSONObject found = items == null || items.length() == 0 ? null : items.optJSONObject(0);
                    if (found == null || !id.equals(found.optString("userId")) || !platform.equals(Platforms.normalize(found.optString("platform")))) {
                        MAIN.post(() -> runner.finish("批量私信仅支持当前 App 的用户。跨平台请从用户详情打开「私信」。"));
                        return;
                    }
                    runner.queue.add(found);
                }
                MAIN.post(runner::next);
            } catch (Throwable error) {
                MAIN.post(() -> runner.finish("读取用户失败：" + (error.getMessage() == null ? "请稍后重试" : error.getMessage())));
            }
        }, "collector-message-queue").start();
    }

    static void startBlock(Activity origin, String platform, JSONArray ids) {
        if (origin == null || ids == null || ids.length() == 0) return;
        synchronized (LOCK) {
            if (active != null) { Toast.makeText(origin, "已有用户操作正在进行", Toast.LENGTH_SHORT).show(); return; }
            active = new DirectMessageRunner(origin, platform, "", "block");
        }
        new Thread(() -> {
            DirectMessageRunner runner;
            synchronized (LOCK) { runner = active; }
            if (runner == null) return;
            try {
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i); if (id.isEmpty()) continue;
                    JSONObject listing = CollectorClient.call(origin, "users", new JSONObject().put("search", id).put("limit", 1).put("platform", platform));
                    JSONArray items = listing.optJSONArray("items");
                    JSONObject found = items == null || items.length() == 0 ? null : items.optJSONObject(0);
                    if (found == null) found = new JSONObject().put("userId", id).put("nickname", id).put("platform", platform);
                    if (!found.has("userId")) found.put("userId", id);
                    runner.queue.add(found);
                }
                MAIN.post(runner::next);
            } catch (Throwable error) { MAIN.post(() -> runner.finish("读取用户失败")); }
        }, "collector-block-queue").start();
    }

    static void onActivityResumed(Activity activity) {
        DirectMessageRunner runner;
        synchronized (LOCK) { runner = active; }
        if (runner == null || activity == null) return;
        MAIN.postDelayed(() -> runner.trySend(activity), 700);
    }

    private void next() {
        if (queue.isEmpty()) { finish("私信任务已完成"); return; }
        current = queue.removeFirst(); filling = false; waitingSince = android.os.SystemClock.uptimeMillis();
        try {
            Activity host = origin;
            JSONObject target = new JSONObject(current.toString())
                .put("authorId", current.optString("userId"))
                .put("authorName", current.optString("nickname", current.optString("userId")))
                .put("authorSecUid", current.optString("secUid"))
                .put("platform", current.optString("platform", platform));
            boolean opened = "block".equals(action) ? UserLinks.profile(host, target) : UserLinks.message(host, target);
            if (!opened) { finish("无法打开用户页面"); return; }
            MAIN.postDelayed(() -> trySend(host), 1600);
        } catch (Throwable error) { finish("无法打开聊天页面"); }
    }

    private void trySend(Activity activity) {
        if (current == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (filling) return;
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return;
        if ("block".equals(action)) { tryBlock(activity, root); return; }
        if (!looksLikeChat(activity)) {
            View privateButton = findPrivateButton(root);
            if (privateButton != null && privateButton.isClickable()) {
                privateButton.performClick();
                MAIN.postDelayed(() -> trySend(activity), 1200);
                return;
            }
            if (android.os.SystemClock.uptimeMillis() - waitingSince < 10000) {
                MAIN.postDelayed(() -> trySend(activity), 1000); return;
            }
            finish("没有进入私信页面，已停止发送"); return;
        }
        EditText input = findComposer(root, activity.getResources().getDisplayMetrics().heightPixels);
        if (input == null) {
            View privateButton = findPrivateButton(root);
            if (privateButton != null && privateButton.isClickable()) {
                privateButton.performClick();
                MAIN.postDelayed(() -> trySend(activity), 1200);
                return;
            }
            if (android.os.SystemClock.uptimeMillis() - waitingSince < 10000) {
                MAIN.postDelayed(() -> trySend(activity), 1000); return;
            }
            finish("没有找到私信输入框，请确认已登录"); return;
        }
        filling = true;
        String text = render(template, current, platform);
        input.setText(text);
        input.setSelection(input.length());
        MAIN.postDelayed(() -> {
            View send = findSendButton(root);
            if (send == null) { filling = false; finish("没有找到发送按钮，请检查聊天页面"); return; }
            send.performClick();
            String name = current.optString("nickname", current.optString("userId"));
            Toast.makeText(activity, "已发送给 " + name, Toast.LENGTH_SHORT).show();
            filling = false;
            MAIN.postDelayed(this::next, 1000);
        }, 250);
    }

    private void tryBlock(Activity activity, View root) {
        View block = findAction(root, "拉黑", "block");
        if (block != null) {
            filling = true; block.performClick();
            MAIN.postDelayed(() -> {
                View confirm = findAction(root, "确定", "确认", "拉黑", "block");
                if (confirm != null) confirm.performClick();
                Toast.makeText(activity, "已处理 " + current.optString("nickname", current.optString("userId")), Toast.LENGTH_SHORT).show();
                filling = false; MAIN.postDelayed(this::next, 900);
            }, 350);
            return;
        }
        View more = findAction(root, "更多", "more", "设置");
        if (more != null && !filling) { filling = true; more.performClick(); MAIN.postDelayed(() -> { filling = false; trySend(activity); }, 500); return; }
        if (android.os.SystemClock.uptimeMillis() - waitingSince < 10000) { MAIN.postDelayed(() -> trySend(activity), 900); return; }
        finish("没有找到拉黑入口，请确认已登录");
    }

    private void finish(String message) {
        synchronized (LOCK) { if (active == this) active = null; }
        if (origin != null && message != null) Toast.makeText(origin, message, Toast.LENGTH_SHORT).show();
    }

    private static String render(String template, JSONObject user, String platform) {
        return template.replace("{昵称}", user.optString("nickname", user.optString("userId")))
            .replace("{用户}", user.optString("nickname", user.optString("userId")))
            .replace("{平台}", Platforms.label(platform));
    }

    private static EditText findComposer(View root, int height) {
        List<View> all = new ArrayList<>(); flatten(root, all);
        EditText best = null;
        for (View view : all) if (view instanceof EditText && view.isShown() && view.isEnabled()) {
            int[] at = new int[2]; view.getLocationOnScreen(at);
            if (at[1] < height * .45f || view.getWidth() < 120) continue;
            if (best == null || view.getWidth() > best.getWidth()) best = (EditText) view;
        }
        return best;
    }

    private static View findSendButton(View root) {
        List<View> all = new ArrayList<>(); flatten(root, all);
        for (View view : all) {
            if (!view.isShown() || !view.isClickable()) continue;
            String text = text(view), desc = view.getContentDescription() == null ? "" : view.getContentDescription().toString();
            String value = (text + " " + desc).toLowerCase(java.util.Locale.ROOT);
            if ((value.contains("发送") || value.contains("send")) && !value.contains("验证码")) return view;
        }
        return null;
    }

    private static View findPrivateButton(View root) {
        List<View> all = new ArrayList<>(); flatten(root, all);
        for (View view : all) {
            if (!view.isShown() || !view.isClickable()) continue;
            String value = (text(view) + " " + (view.getContentDescription() == null ? "" : view.getContentDescription())).toLowerCase(java.util.Locale.ROOT);
            if (value.contains("私信") || value.contains("message")) return view;
        }
        return null;
    }

    private static View findAction(View root, String... labels) {
        List<View> all = new ArrayList<>(); flatten(root, all);
        for (View view : all) {
            if (!view.isShown() || !view.isClickable()) continue;
            String value = (text(view) + " " + (view.getContentDescription() == null ? "" : view.getContentDescription())).toLowerCase(java.util.Locale.ROOT);
            for (String label : labels) if (value.contains(label.toLowerCase(java.util.Locale.ROOT))) return view;
        }
        return null;
    }

    private static String text(View view) {
        if (view instanceof android.widget.TextView) return ((android.widget.TextView) view).getText().toString();
        CharSequence desc = view.getContentDescription(); return desc == null ? "" : desc.toString();
    }

    private static boolean looksLikeChat(Activity activity) {
        String name = activity.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("chat") || name.contains("conversation") || name.contains("message") || name.contains("im.");
    }

    private static void flatten(View view, List<View> out) {
        if (view == null || out.size() >= 2500) return;
        out.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) flatten(((ViewGroup) view).getChildAt(i), out);
    }
}
