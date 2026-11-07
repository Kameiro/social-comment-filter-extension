package dev.local.commentcollector;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

final class PostInfoPanel {
    private static Dialog open;

    private PostInfoPanel() {}

    static void show(Activity activity, JSONObject source) {
        show(activity, source, VideoInfoSettings.defaults());
    }

    static void show(Activity activity, JSONObject source, JSONObject settings) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || source == null) return;
        if (open != null && open.isShowing()) return;
        JSONObject post;
        try { post = new JSONObject(source.toString()); }
        catch (Exception error) { return; }
        int dialogStyle = dark(activity) ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar;
        Dialog dialog = new Dialog(activity, dialogStyle);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 18));
        boolean centered = VideoInfoSettings.centered(settings);
        sheet.setBackground(round(dark(activity) ? Color.rgb(37, 37, 40) : Color.WHITE, dp(activity, 24), !centered));

        TextView handle = text(activity, "—", 28, false, dark(activity) ? Color.rgb(130, 130, 135) : Color.LTGRAY);
        handle.setGravity(Gravity.CENTER); sheet.addView(handle, new LinearLayout.LayoutParams(-1, dp(activity, 24)));
        TextView title = text(activity, "视频信息", 22, true, dark(activity) ? Color.WHITE : Color.rgb(28, 28, 30));
        title.setPadding(0, dp(activity, 4), 0, dp(activity, 12)); sheet.addView(title);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        String author = first(post, "authorName", "authorId");
        String location = first(post, "location", "publishLocation", "locationName", "cityName", "city");
        String created = first(post, "created", "publishedAt");
        String duration = first(post, "duration", "durationMs", "videoDuration");
        String music = first(post, "musicName");
        addRow(activity, content, "发布账号", empty(author) ? "未知" : author);
        if (VideoInfoSettings.enabled(settings, "location")) addRow(activity, content, "发布地点", empty(location) ? "未提供" : location);
        if (VideoInfoSettings.enabled(settings, "publish_time")) addRow(activity, content, "发布时间", formatDate(created, settings));
        if (VideoInfoSettings.enabled(settings, "duration")) addRow(activity, content, "视频时长", formatDuration(duration));
        if (VideoInfoSettings.enabled(settings, "music") && !music.isEmpty()) addRow(activity, content, "音乐信息", music);

        if (VideoInfoSettings.enabled(settings, "statistics")) {
            LinearLayout metrics = new LinearLayout(activity); metrics.setGravity(Gravity.CENTER); metrics.setPadding(0, dp(activity, 8), 0, dp(activity, 12));
            metric(activity, metrics, formatCount(post.opt("likes")), "点赞");
            metric(activity, metrics, formatCount(post.opt("commentTotal")), "评论");
            metric(activity, metrics, formatCount(post.opt("favorites")), "收藏");
            metric(activity, metrics, formatCount(post.opt("shares")), "分享");
            content.addView(metrics, new LinearLayout.LayoutParams(-1, -2));
        }

        String coverUrl = first(post, "coverUrl", "cover", "coverImageUrl");
        if (VideoInfoSettings.enabled(settings, "cover") && coverUrl.startsWith("http")) {
            ImageView cover = new ImageView(activity); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(round(dark(activity) ? Color.rgb(55, 55, 60) : Color.rgb(240, 240, 243), dp(activity, 14), false));
            content.addView(cover, new LinearLayout.LayoutParams(-1, dp(activity, 170)));
            loadImage(cover, coverUrl);
        }
        if (VideoInfoSettings.enabled(settings, "description")) addRow(activity, content, "文案", empty(post.optString("title")) ? "暂无文案" : post.optString("title"));
        addRow(activity, content, "作品 ID", first(post, "postId"));
        scroll.addView(content);
        int maxHeight = Math.min(dp(activity, 520), (int) (activity.getResources().getDisplayMetrics().heightPixels * .62f));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));

        LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(activity, 12), 0, 0);
        if (VideoInfoSettings.enabled(settings, "copy_description")) {
            Button copy = button(activity, "复制文案", dark(activity) ? Color.rgb(255, 90, 31) : Color.rgb(255, 73, 6), Color.WHITE);
            copy.setOnClickListener(view -> { copy(activity, post.optString("title")); toast(activity, "文案已复制"); });
            actions.addView(copy, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        }
        if (VideoInfoSettings.enabled(settings, "video")) {
            Button save = button(activity, "保存信息", dark(activity) ? Color.rgb(72, 72, 78) : Color.rgb(240, 240, 243), dark(activity) ? Color.WHITE : Color.rgb(30, 30, 32));
            save.setOnClickListener(view -> { try { CollectorClient.call(activity, "record_post", new JSONObject().put("post", post)); toast(activity, "作品信息已保存"); } catch (Exception error) { toast(activity, "保存失败：" + error.getMessage()); } });
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(activity, 44), 1); saveParams.setMargins(dp(activity, 8), 0, 0, 0); actions.addView(save, saveParams);
        }
        if (VideoInfoSettings.enabled(settings, "cover") && coverUrl.startsWith("http")) {
            Button coverButton = button(activity, "保存封面", dark(activity) ? Color.rgb(72, 72, 78) : Color.rgb(240, 240, 243), dark(activity) ? Color.WHITE : Color.rgb(30, 30, 32));
            coverButton.setOnClickListener(view -> saveCover(activity, coverUrl));
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(0, dp(activity, 44), 1); coverParams.setMargins(dp(activity, 8), 0, 0, 0); actions.addView(coverButton, coverParams);
        }
        sheet.addView(actions);
        dialog.setContentView(sheet); dialog.setOnDismissListener(value -> { if (open == dialog) open = null; });
        dialog.show(); open = dialog;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(.45f); window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(centered ? Gravity.CENTER : Gravity.BOTTOM);
            window.setLayout(centered ? (int) (activity.getResources().getDisplayMetrics().widthPixels * .94f) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void addRow(Activity activity, LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, dp(activity, 5), 0, dp(activity, 5));
        TextView key = text(activity, label, 13, false, Color.rgb(155, 155, 160)); row.addView(key);
        TextView val = text(activity, value, 16, false, dark(activity) ? Color.WHITE : Color.rgb(38, 38, 40)); val.setTextIsSelectable(true); row.addView(val);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void metric(Activity activity, LinearLayout parent, String value, String label) {
        LinearLayout item = new LinearLayout(activity); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        item.addView(text(activity, value, 17, true, dark(activity) ? Color.WHITE : Color.rgb(48, 48, 52)), new LinearLayout.LayoutParams(-1, dp(activity, 28)));
        TextView caption = text(activity, label, 12, false, Color.rgb(155, 155, 160)); caption.setGravity(Gravity.CENTER); item.addView(caption);
        parent.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private static Button button(Activity activity, String label, int background, int foreground) {
        Button button = new Button(activity);
        button.setText(label); button.setTextSize(13); button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(foreground); button.setStateListAnimator(null); button.setPadding(dp(activity, 8), 0, dp(activity, 8), 0); button.setBackground(round(background, dp(activity, 22), false));
        return button;
    }

    private static TextView text(Activity activity, String value, int size, boolean bold, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(0, 1.15f); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }

    private static GradientDrawable round(int color, int radius, boolean topOnly) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadii(topOnly ? new float[]{radius, radius, radius, radius, 0, 0, 0, 0} : new float[]{radius, radius, radius, radius, radius, radius, radius, radius}); return shape;
    }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) { String value = object.optString(key, ""); if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value; }
        return "";
    }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private static String formatDate(String value, JSONObject settings) { if (empty(value)) return "未知"; try { long time = Long.parseLong(value); if (time > 0 && time < 100000000000L) time *= 1000; String pattern = settings == null ? "yyyy-MM-dd HH:mm:ss" : settings.optString("time_format", "yyyy-MM-dd HH:mm:ss"); return new java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA).format(new java.util.Date(time)); } catch (Exception ignored) { return value; } }
    private static String formatDuration(String value) { if (empty(value)) return "未知"; try { long ms = Long.parseLong(value); if (ms > 1000) ms /= 1000; return ms / 60 + "分" + ms % 60 + "秒"; } catch (Exception ignored) { return value; } }
    private static String formatCount(Object value) { if (value == null || JSONObject.NULL.equals(value)) return "未知"; try { long n = Long.parseLong(String.valueOf(value)); if (n >= 10000) return String.format(java.util.Locale.CHINA, "%.1f万", n / 10000d); return String.valueOf(n); } catch (Exception ignored) { return String.valueOf(value); } }
    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static boolean dark(Activity activity) { int mode = activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK; return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES; }
    private static void copy(Activity activity, String value) { ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE); if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("作品文案", value == null ? "" : value)); }
    private static void toast(Activity activity, String value) { Toast.makeText(activity, value, Toast.LENGTH_SHORT).show(); }

    private static void loadImage(ImageView target, String url) {
        new Thread(() -> { try { byte[] bytes = download(url); Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length); if (bitmap != null) new Handler(Looper.getMainLooper()).post(() -> target.setImageBitmap(bitmap)); } catch (Exception ignored) {} }, "post-info-image").start();
    }
    private static void saveCover(Activity activity, String url) {
        toast(activity, "正在保存封面…");
        new Thread(() -> { try {
            byte[] bytes = download(url); ContentResolver resolver = activity.getContentResolver(); ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "kuaishou-" + System.currentTimeMillis() + ".jpg"); values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CommentCollector");
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); if (uri == null) throw new IllegalStateException("无法创建图片文件");
            try (OutputStream output = resolver.openOutputStream(uri)) { if (output == null) throw new IllegalStateException("无法写入图片"); output.write(bytes); }
            new Handler(Looper.getMainLooper()).post(() -> toast(activity, "封面已保存到相册"));
        } catch (Exception error) { new Handler(Looper.getMainLooper()).post(() -> toast(activity, "保存封面失败：" + error.getMessage())); } }, "post-info-save-cover").start();
    }
    private static byte[] download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(); connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new IllegalStateException("HTTP " + connection.getResponseCode());
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) { byte[] buffer = new byte[8192]; int total = 0, count; while ((count = input.read(buffer)) != -1) { total += count; if (total > 12 * 1024 * 1024) throw new IllegalStateException("图片过大"); output.write(buffer, 0, count); } return output.toByteArray(); }
        finally { connection.disconnect(); }
    }
}
