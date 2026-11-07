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
        boolean centered = VideoInfoSettings.centered(settings);
        LinearLayout sheet = new LinearLayout(activity);
        // Full-screen window with our own scrim view: dim and sheet animate in one surface, so there is
        // no per-frame window relayout (the old window-dim animation left ghost frames on dismiss).
        android.widget.FrameLayout root = new android.widget.FrameLayout(activity);
        android.view.View scrim = new android.view.View(activity);
        scrim.setBackgroundColor(Color.BLACK); scrim.setAlpha(0f);
        Dialog dialog = new Dialog(activity, dialogStyle) {
            private boolean closing;
            @Override public void dismiss() {
                // Animate out first; the real dismiss runs when the sheet has left the screen.
                if (closing || !isShowing() || sheet.getWindowToken() == null) { super.dismiss(); return; }
                closing = true;
                Motion.exit(sheet, scrim, centered, DIM, () -> { try { super.dismiss(); } catch (RuntimeException ignored) {} });
            }
        };
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 18));
        sheet.setBackground(round(dark(activity) ? Color.rgb(37, 37, 40) : Color.WHITE, dp(activity, 24), !centered));

        TextView handle = text(activity, "—", 28, false, dark(activity) ? Color.rgb(130, 130, 135) : Color.LTGRAY);
        handle.setGravity(Gravity.CENTER); sheet.addView(handle, new LinearLayout.LayoutParams(-1, dp(activity, 24)));
        TextView title = text(activity, "视频信息", 22, true, dark(activity) ? Color.WHITE : Color.rgb(28, 28, 30));
        title.setPadding(0, dp(activity, 4), 0, dp(activity, 12)); sheet.addView(title);
        if (!centered) { Motion.dragToDismiss(handle, sheet, scrim, DIM, dialog::dismiss); Motion.dragToDismiss(title, sheet, scrim, DIM, dialog::dismiss); }

        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        String author = first(post, "authorName", "authorId");
        String location = first(post, "location", "publishLocation", "locationName", "cityName", "city");
        String created = first(post, "created", "publishedAt");
        String duration = first(post, "duration", "durationMs", "videoDuration");
        String music = first(post, "musicName");
        String artist = first(post, "musicArtist");
        if (!music.isEmpty() && !artist.isEmpty() && !music.contains(artist)) music = music + " - " + artist;
        addRow(activity, content, "发布账号", empty(author) ? "未知" : author);
        if (VideoInfoSettings.enabled(settings, "location")) addRow(activity, content, "发布地点", empty(location) ? "未提供" : location);
        if (VideoInfoSettings.enabled(settings, "publish_time")) addRow(activity, content, "发布时间", formatDate(created, settings));
        if (VideoInfoSettings.enabled(settings, "duration")) addRow(activity, content, "视频时长", formatDuration(duration));
        if (VideoInfoSettings.enabled(settings, "music") && !music.isEmpty()) addRow(activity, content, "音乐信息", music);

        if (VideoInfoSettings.enabled(settings, "statistics")) {
            LinearLayout metrics = new LinearLayout(activity); metrics.setGravity(Gravity.CENTER); metrics.setPadding(0, dp(activity, 8), 0, dp(activity, 12));
            String[] keys = {"likes", "commentTotal", "favorites", "shares"};
            String[] labels = {"点赞", "评论", "收藏", "分享"};
            for (int i = 0; i < keys.length; i++) {
                TextView number = metric(activity, metrics, formatCount(post.opt(keys[i])), labels[i]);
                Motion.countUp(number, post.opt(keys[i]), PostInfoPanel::formatCount, 180 + i * 60L);
            }
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

        LinearLayout actions = new LinearLayout(activity); actions.setOrientation(LinearLayout.VERTICAL); actions.setPadding(0, dp(activity, 12), 0, 0);
        int neutral = dark(activity) ? Color.rgb(72, 72, 78) : Color.rgb(240, 240, 243);
        int onNeutral = dark(activity) ? Color.WHITE : Color.rgb(30, 30, 32);
        java.util.List<Button> buttons = new java.util.ArrayList<>();
        if (VideoInfoSettings.enabled(settings, "copy_description")) {
            Button copy = button(activity, "复制文案", dark(activity) ? Color.rgb(255, 90, 31) : Color.rgb(255, 73, 6), Color.WHITE);
            copy.setOnClickListener(view -> { copy(activity, post.optString("title")); Motion.flashLabel(copy, "已复制 ✓", 1200); });
            buttons.add(copy);
        }
        Button info = button(activity, "下载信息", neutral, onNeutral);
        info.setOnClickListener(view -> saveInfo(activity, post, info));
        buttons.add(info);
        String videoUrl = first(post, "videoUrl");
        org.json.JSONArray imageUrls = post.optJSONArray("imageUrls");
        if (VideoInfoSettings.enabled(settings, "video") && videoUrl.startsWith("http")) {
            Button video = button(activity, "下载视频", neutral, onNeutral);
            video.setOnClickListener(view -> saveMedia(activity, video, new String[]{videoUrl}, Kind.VIDEO, "kuaishou-" + post.optString("postId")));
            buttons.add(video);
        } else if (VideoInfoSettings.enabled(settings, "video") && imageUrls != null && imageUrls.length() > 0) {
            String[] urls = new String[imageUrls.length()];
            for (int i = 0; i < urls.length; i++) urls[i] = imageUrls.optString(i);
            Button images = button(activity, "下载图片", neutral, onNeutral);
            images.setOnClickListener(view -> saveMedia(activity, images, urls, Kind.IMAGE, "kuaishou-" + post.optString("postId")));
            buttons.add(images);
        }
        if (VideoInfoSettings.enabled(settings, "cover") && coverUrl.startsWith("http")) {
            Button coverButton = button(activity, "下载封面", neutral, onNeutral);
            coverButton.setOnClickListener(view -> saveMedia(activity, coverButton, new String[]{coverUrl}, Kind.IMAGE, "kuaishou-" + post.optString("postId") + "-cover"));
            buttons.add(coverButton);
        }
        String musicUrl = first(post, "musicUrl");
        if (VideoInfoSettings.enabled(settings, "music") && musicUrl.startsWith("http")) {
            Button musicButton = button(activity, "下载音乐", neutral, onNeutral);
            String musicFile = "kuaishou-" + post.optString("postId") + "-music" + (first(post, "musicName").isEmpty() ? "" : "-" + safeName(first(post, "musicName")));
            musicButton.setOnClickListener(view -> saveMedia(activity, musicButton, new String[]{musicUrl}, Kind.AUDIO, musicFile));
            buttons.add(musicButton);
        }
        grid(activity, actions, buttons, 1);
        // Buttons are stacked one per row, so give the info area only the height that is left
        // (sheet capped at ~90% of the screen), keeping a usable minimum.
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        int stacked = buttons.size() * dp(activity, 44) + Math.max(0, buttons.size() - 1) * dp(activity, 8) + dp(activity, 12);
        int chrome = dp(activity, 24 + 44 + 10 + 18 + 48); // handle, title, paddings, nav bar allowance
        int available = (int) (screen * .9f) - stacked - chrome;
        int scrollHeight = Math.max(dp(activity, 160), Math.min(Math.min(dp(activity, 520), (int) (screen * .62f)), available));
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, scrollHeight));
        sheet.addView(actions);
        scrim.setOnClickListener(view -> dialog.dismiss());
        root.addView(scrim, new android.widget.FrameLayout.LayoutParams(-1, -1));
        int sheetWidth = centered ? (int) (activity.getResources().getDisplayMetrics().widthPixels * .94f) : ViewGroup.LayoutParams.MATCH_PARENT;
        root.addView(sheet, new android.widget.FrameLayout.LayoutParams(sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT, centered ? Gravity.CENTER : Gravity.BOTTOM));
        int basePadding = sheet.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            if (!centered) sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(), sheet.getPaddingRight(), basePadding + bars.bottom);
            return insets;
        });
        dialog.setContentView(root); dialog.setOnDismissListener(value -> { if (open == dialog) open = null; });
        Motion.pressAll(actions);
        Motion.stagger(content, 120);
        dialog.show(); open = dialog;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setWindowAnimations(0);
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(Color.TRANSPARENT);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            Motion.highRefreshRate(activity, window);
        }
        Motion.enter(sheet, scrim, centered, DIM);
    }

    private static final float DIM = .45f;

    private static void addRow(Activity activity, LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, dp(activity, 5), 0, dp(activity, 5));
        TextView key = text(activity, label, 13, false, Color.rgb(155, 155, 160)); row.addView(key);
        TextView val = text(activity, value, 16, false, dark(activity) ? Color.WHITE : Color.rgb(38, 38, 40)); val.setTextIsSelectable(true); row.addView(val);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private static TextView metric(Activity activity, LinearLayout parent, String value, String label) {
        LinearLayout item = new LinearLayout(activity); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        TextView number = text(activity, value, 17, true, dark(activity) ? Color.WHITE : Color.rgb(48, 48, 52)); number.setGravity(Gravity.CENTER);
        item.addView(number, new LinearLayout.LayoutParams(-1, dp(activity, 28)));
        TextView caption = text(activity, label, 12, false, Color.rgb(155, 155, 160)); caption.setGravity(Gravity.CENTER); item.addView(caption);
        parent.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        return number;
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
    static String formatCount(long value) { return formatCount((Object) value); }
    static String formatCount(Object value) { if (value == null || JSONObject.NULL.equals(value)) return "未知"; try { long n = Long.parseLong(String.valueOf(value)); if (n >= 10000) return String.format(java.util.Locale.CHINA, "%.1f万", n / 10000d); return String.valueOf(n); } catch (Exception ignored) { return String.valueOf(value); } }
    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static boolean dark(Activity activity) { int mode = activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK; return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES; }
    private static void copy(Activity activity, String value) { ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE); if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("作品文案", value == null ? "" : value)); }
    private static void toast(Activity activity, String value) { Toast.makeText(activity, value, Toast.LENGTH_SHORT).show(); }

    private static void loadImage(ImageView target, String url) {
        new Thread(() -> { try { byte[] bytes = download(url); Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length); if (bitmap != null) new Handler(Looper.getMainLooper()).post(() -> { target.setImageBitmap(bitmap); Motion.fadeIn(target, 260); }); } catch (Exception ignored) {} }, "post-info-image").start();
    }
    private enum Kind { VIDEO, IMAGE, AUDIO, FILE }

    /** Lays buttons out in rows of {@code columns}, equal width, 8dp gaps. */
    private static void grid(Activity activity, LinearLayout parent, java.util.List<Button> buttons, int columns) {
        LinearLayout row = null;
        for (int i = 0; i < buttons.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rowParams.topMargin = dp(activity, 8);
                parent.addView(row, rowParams);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(activity, 44), 1);
            if (i % columns != 0) params.leftMargin = dp(activity, 8);
            row.addView(buttons.get(i), params);
        }
    }

    private static String safeName(String value) {
        String name = value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    /** Readable text file with the post's public fields, saved to Download/CommentCollector. */
    private static void saveInfo(Activity activity, JSONObject post, Button button) {
        StringBuilder text = new StringBuilder();
        line(text, "作品 ID", post.optString("postId"));
        line(text, "作品链接", post.optString("url"));
        line(text, "发布账号", first(post, "authorName") + (first(post, "authorId").isEmpty() ? "" : "（" + first(post, "authorId") + "）"));
        line(text, "发布时间", formatDate(first(post, "created", "publishedAt"), null));
        line(text, "发布地点", first(post, "location"));
        line(text, "视频时长", first(post, "duration").isEmpty() ? "" : formatDuration(first(post, "duration")));
        line(text, "点赞", formatCount(post.opt("likes"))); line(text, "评论", formatCount(post.opt("commentTotal")));
        line(text, "收藏", formatCount(post.opt("favorites"))); line(text, "分享", formatCount(post.opt("shares")));
        line(text, "音乐", first(post, "musicName") + (first(post, "musicArtist").isEmpty() ? "" : " - " + first(post, "musicArtist")));
        line(text, "文案", post.optString("title"));
        line(text, "视频地址", first(post, "videoUrl"));
        line(text, "封面地址", first(post, "coverUrl"));
        line(text, "音乐地址", first(post, "musicUrl"));
        org.json.JSONArray images = post.optJSONArray("imageUrls");
        if (images != null) for (int i = 0; i < images.length(); i++) line(text, "图片 " + (i + 1), images.optString(i));
        byte[] bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CharSequence label = button.getText();
        button.setEnabled(false);
        new Thread(() -> {
            try {
                try { CollectorClient.call(activity, "record_post", new JSONObject().put("post", post)); } catch (Exception ignored) {}
                try (OutputStream output = open(activity, Kind.FILE, "kuaishou-" + post.optString("postId") + "-info.txt", "text/plain")) { output.write(bytes); }
                finish(activity, button, label, "已保存 ✓", null);
            } catch (Exception error) { finish(activity, button, label, null, "保存信息失败：" + error.getMessage()); }
        }, "post-info-save-text").start();
    }

    private static void line(StringBuilder out, String label, String value) {
        if (value == null || value.isEmpty() || "null".equals(value) || "未知".equals(value)) return;
        out.append(label).append("：").append(value).append('\n');
    }

    /** Streams one or more URLs into the shared gallery/Music/Download folders with live progress on the button. */
    private static void saveMedia(Activity activity, Button button, String[] urls, Kind kind, String baseName) {
        if (!button.isEnabled()) return;
        CharSequence label = button.getText();
        button.setEnabled(false);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                for (int index = 0; index < urls.length; index++) {
                    final int item = index;
                    HttpURLConnection connection = (HttpURLConnection) new URL(urls[index]).openConnection();
                    connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) kwai-android");
                    try {
                        int code = connection.getResponseCode();
                        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                        String mime = mimeOf(connection.getContentType(), urls[index], kind);
                        String name = baseName + (urls.length > 1 ? "-" + (index + 1) : "") + extension(mime, urls[index], kind);
                        long total = connection.getContentLengthLong();
                        try (InputStream input = connection.getInputStream(); OutputStream output = open(activity, kind, name, mime)) {
                            byte[] buffer = new byte[64 * 1024];
                            long done = 0; int lastPercent = -1; int count;
                            while ((count = input.read(buffer)) != -1) {
                                output.write(buffer, 0, count); done += count;
                                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                                if (percent != lastPercent) {
                                    lastPercent = percent;
                                    String progress = urls.length > 1 ? "下载中 " + (item + 1) + "/" + urls.length
                                        : percent >= 0 ? "下载中 " + percent + "%" : "下载中…";
                                    main.post(() -> button.setText(progress));
                                }
                            }
                        }
                    } finally { connection.disconnect(); }
                }
                String where = kind == Kind.VIDEO ? "已保存到 Movies/CommentCollector" : kind == Kind.AUDIO ? "已保存到 Music/CommentCollector" : "已保存到相册";
                finish(activity, button, label, "已保存 ✓", where);
            } catch (Exception error) { finish(activity, button, label, null, "下载失败：" + error.getMessage()); }
        }, "post-info-download").start();
    }

    private static void finish(Activity activity, Button button, CharSequence label, String flash, String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            button.setText(label); button.setEnabled(true);
            if (flash != null) Motion.flashLabel(button, flash, 1500);
            if (message != null) toast(activity, message);
        });
    }

    /** Creates a MediaStore entry (pending until closed) and returns its output stream. */
    private static OutputStream open(Activity activity, Kind kind, String name, String mime) throws Exception {
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        Uri collection;
        switch (kind) {
            case VIDEO: collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CommentCollector"); break;
            case AUDIO: collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/CommentCollector"); break;
            case FILE: collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI; values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CommentCollector"); break;
            default: collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CommentCollector"); break;
        }
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IllegalStateException("无法创建文件");
        OutputStream stream = resolver.openOutputStream(uri);
        if (stream == null) { resolver.delete(uri, null, null); throw new IllegalStateException("无法写入文件"); }
        return new java.io.FilterOutputStream(stream) {
            private boolean ok = true;
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                try { out.write(b, off, len); } catch (java.io.IOException error) { ok = false; throw error; }
            }
            @Override public void close() throws java.io.IOException {
                super.close();
                ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (ok) resolver.update(uri, done, null, null); else resolver.delete(uri, null, null);
            }
        };
    }

    private static String mimeOf(String header, String url, Kind kind) {
        String type = header == null ? "" : header.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!type.isEmpty() && !type.equals("application/octet-stream") && !type.startsWith("text/")) return type;
        String path = url.toLowerCase(java.util.Locale.ROOT).split("\\?")[0];
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".m4a") || path.endsWith(".aac")) return "audio/mp4";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".heic") || path.endsWith(".heif")) return "image/heif";
        return kind == Kind.VIDEO ? "video/mp4" : kind == Kind.AUDIO ? "audio/mp4" : "image/jpeg";
    }

    private static String extension(String mime, String url, Kind kind) {
        switch (mime) {
            case "video/mp4": return ".mp4";
            case "audio/mpeg": return ".mp3";
            case "audio/mp4": case "audio/aac": case "audio/x-m4a": return ".m4a";
            case "image/webp": return ".webp";
            case "image/png": return ".png";
            case "image/heif": case "image/heic": return ".heic";
            case "image/jpeg": return ".jpg";
            default: return kind == Kind.VIDEO ? ".mp4" : kind == Kind.AUDIO ? ".m4a" : ".jpg";
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(); connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new IllegalStateException("HTTP " + connection.getResponseCode());
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) { byte[] buffer = new byte[8192]; int total = 0, count; while ((count = input.read(buffer)) != -1) { total += count; if (total > 12 * 1024 * 1024) throw new IllegalStateException("图片过大"); output.write(buffer, 0, count); } return output.toByteArray(); }
        finally { connection.disconnect(); }
    }
}
