package dev.local.commentcollector;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Opens a post author's profile or direct-message screen inside the host app. Nothing is ever sent automatically. */
final class UserLinks {
    private static final String TAG = "CommentCollector";
    private UserLinks() {}

    static boolean canOpen(JSONObject post) { return !post.optString("authorId").isEmpty(); }

    static void profile(Activity activity, JSONObject post) {
        String id = post.optString("authorId"), sec = post.optString("authorSecUid");
        String uri;
        switch (post.optString("platform")) {
            case "douyin": uri = "snssdk1128://user/profile/" + id + (sec.isEmpty() ? "" : "?sec_user_id=" + Uri.encode(sec)); break;
            case "xiaohongshu": case "xhs": uri = "xhsdiscover://user/" + id; break;
            default: uri = "kwai://profile/" + id;
        }
        if (!view(activity, uri)) toast(activity, "无法打开主页");
    }

    static void message(Activity activity, JSONObject post) {
        String id = post.optString("authorId"), sec = post.optString("authorSecUid");
        if ("douyin".equals(post.optString("platform")) && startDouyinChat(activity, id, sec)) return;
        if (("xhs".equals(post.optString("platform")) || "xiaohongshu".equals(post.optString("platform")))
            && startXhsChat(activity, id, post.optString("authorName"))) return;
        profile(activity, post);
        toast(activity, "已打开主页，请点右上角私信");
    }

    /** Opens XHS's native one-to-one chat route. It only navigates; it never sends a message. */
    private static boolean startXhsChat(Activity activity, String userId, String nickname) {
        if (userId == null || userId.isEmpty()) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("xhsdiscover://message/chat"))
                .setPackage(activity.getPackageName());
            intent.putExtra("userId", userId);
            if (nickname != null && !nickname.isEmpty()) intent.putExtra("nickname", nickname);
            intent.putExtra("source", "comso");
            activity.startActivity(intent);
            Log.i(TAG, "xhs_chat_started userId=" + userId);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "xhs_chat_failed", error);
            return false;
        }
    }

    private static boolean startDouyinChat(Activity activity, String uid, String sec) {
        try {
            Object proxy = activity.getClassLoader().loadClass("com.ss.android.ugc.aweme.im.IMProxy").getMethod("get").invoke(null);
            Object service = proxy.getClass().getMethod("getConversationService").invoke(proxy);
            for (Method method : service.getClass().getMethods()) {
                Class<?>[] t = method.getParameterTypes();
                if (t.length != 7 || t[0] != Activity.class || t[1] != String.class || t[2] != String.class || t[3] != String.class || t[4] != String.class || t[6] != long.class) continue;
                method.invoke(service, activity, uid, sec, null, "homepage_hot", null, 0L);
                Log.i(TAG, "douyin_chat_started via " + method.getName());
                return true;
            }
            Log.w(TAG, "douyin_chat_method_not_found");
        } catch (Throwable error) { Log.w(TAG, "douyin_chat_failed", error); }
        return false;
    }

    private static boolean view(Activity activity, String uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(activity.getPackageName());
            activity.startActivity(intent);
            return true;
        } catch (Throwable error) { Log.w(TAG, "open_link_failed " + uri, error); return false; }
    }

    private static void toast(Activity activity, String text) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show(); }
}
