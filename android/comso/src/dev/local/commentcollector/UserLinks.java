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

    static boolean canOpen(JSONObject post) {
        String id = post.optString("authorId");
        return !id.isBlank() && !"null".equals(id);
    }

    static boolean profile(Activity activity, JSONObject post) {
        if (!canOpen(post)) {
            toast(activity, "该记录缺少用户 ID，无法打开主页");
            return false;
        }
        String id = Uri.encode(post.optString("authorId")), sec = post.optString("authorSecUid");
        String platform = platform(activity, post);
        String uri = switch (platform) {
            case "douyin" -> "snssdk1128://user/profile/" + id + (sec.isEmpty() ? "" : "?sec_user_id=" + Uri.encode(sec));
            case "xhs" -> "xhsdiscover://user/" + id;
            default -> "kwai://profile/" + id;
        };
        boolean opened = view(activity, uri, Platforms.packageFor(platform));
        if (!opened) toast(activity, "无法打开" + Platforms.label(platform) + "主页，请检查对应 App 是否可用");
        return opened;
    }

    static boolean message(Activity activity, JSONObject post) {
        String id = post.optString("authorId"), sec = post.optString("authorSecUid");
        String platform = platform(activity, post);
        if (canOpen(post) && activity.getPackageName().equals(Platforms.packageFor(platform))) {
            if ("douyin".equals(platform) && startDouyinChat(activity, id, sec)) return true;
            if ("xhs".equals(platform) && startXhsChat(activity, id, post.optString("authorName"))) return true;
        }
        if (!profile(activity, post)) return false;
        toast(activity, "请在" + Platforms.label(platform) + "主页点「私信」");
        return true;
    }

    private static String platform(Activity activity, JSONObject post) {
        String platform = Platforms.normalize(post.optString("platform"));
        return platform.isEmpty() ? Platforms.name(activity.getPackageName()) : platform;
    }

    /** Opens XHS's native one-to-one chat route. It only navigates; it never sends a message. */
    private static boolean startXhsChat(Activity activity, String userId, String nickname) {
        if (userId == null || userId.isEmpty()) return false;
        try {
            // Going through xhsdiscover://message/chat first opens RouterPageActivity. The
            // current XHS router drops the userId while forwarding that route, which leaves
            // ChatActivity on the generic "用户" screen and produces a transient network error.
            // Start the native activity explicitly so all chat extras survive the hand-off.
            Intent intent = new Intent()
                .setClassName(activity.getPackageName(), "com.xingin.im.ui.activity.ChatActivity");
            intent.putExtra("userId", userId);
            if (nickname != null && !nickname.isEmpty()) intent.putExtra("nickname", nickname);
            intent.putExtra("source", "comso");
            intent.putExtra("targetStoreId", -1);
            intent.putExtra("msgStoreId", "0");
            intent.putExtra("chat_type", 0);
            intent.putExtra("chat_unread_count", 0);
            intent.putExtra("chat_auto_show_keyboard", false);
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

    private static boolean view(Activity activity, String uri, String targetPackage) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(targetPackage);
            if (!activity.getPackageName().equals(targetPackage)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (Exception error) { Log.w(TAG, "open_user_link_failed platformPackage=" + targetPackage, error); return false; }
    }

    private static void toast(Activity activity, String text) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show(); }
}
