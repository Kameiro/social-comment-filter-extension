package dev.local.commentcollector;

import android.app.Activity;
import android.app.Dialog;
import android.app.UiModeManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.ContextThemeWrapper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CollectorPanel {
    private static WeakReference<Dialog> open = new WeakReference<>(null);
    private static final String FLOATING_TAG = "comment_collector_fab";
    private final Activity activity;
    private final Context uiContext;
    private final Supplier<String> photo;
    private final Runnable closePanel;
    private final LinearLayout root, body;
    private final TextView summary;
    private final boolean darkModeAtCreate;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject filters = new JSONObject();
    private int tab, offset;
    private String userFilter = "";
    private ScrollView currentScroll;
    private String lastTaskSnapshot = "";
    private long lastAutoSyncTaskId;
    private boolean autoSyncRunning;
    private volatile String cloudStatusHint = "";
    private final Set<String> selectedPosts = new LinkedHashSet<>(), selectedUsers = new LinkedHashSet<>();
    private final java.util.List<ImageButton> navIcons = new java.util.ArrayList<>();
    private final int surface, surfaceContainer, surfaceVariant, foreground, secondary, link, primary, onPrimaryContainer, errorColor;

    public static boolean showing() {
        Dialog value = open.get();
        return value != null && value.isShowing() && value.getOwnerActivity() != null && !value.getOwnerActivity().isDestroyed() &&
            value.getWindow() != null && value.getWindow().getDecorView().isAttachedToWindow();
    }
    public static void detach(Activity activity) {
        Dialog value = open.get();
        if (value != null && value.getOwnerActivity() == activity) { value.dismiss(); open.clear(); }
    }
    public static void attach(Activity activity, Supplier<String> currentPhoto) {
        if (activity.isFinishing() || activity.getClass().getName().contains(".login.")) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout) || decor.findViewWithTag(FLOATING_TAG) != null) return;
        ImageButton button = new ImageButton(activity); button.setTag(FLOATING_TAG);
        button.setContentDescription("打开评论数据库"); button.setImageResource(android.R.drawable.ic_menu_agenda);
        button.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        GradientDrawable background = new GradientDrawable(); background.setShape(GradientDrawable.OVAL); background.setColor(Color.rgb(11, 124, 89));
        button.setBackground(background); button.setElevation(dp(activity, 6));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(activity, 52), dp(activity, 52), Gravity.TOP | Gravity.START);
        params.leftMargin = dp(activity, 8); params.topMargin = dp(activity, 110);
        button.setOnClickListener(view -> show(activity, currentPhoto));
        button.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY; int left, top; boolean moving;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); left = params.leftMargin; top = params.topMargin; moving = false; return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float x = event.getRawX() - startX, y = event.getRawY() - startY;
                    if (Math.abs(x) + Math.abs(y) > dp(activity, 8)) moving = true;
                    if (moving) {
                        params.leftMargin = Math.max(0, Math.min(decor.getWidth() - params.width, left + (int) x));
                        params.topMargin = Math.max(dp(activity, 30), Math.min(decor.getHeight() - params.height - dp(activity, 40), top + (int) y));
                        button.setLayoutParams(params);
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) { if (!moving) button.performClick(); return true; }
                return event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });
        ((FrameLayout) decor).addView(button, params);
    }
    public static void show(Activity activity, Supplier<String> currentPhoto) {
        if (showing()) return;
        Dialog stale = open.get(); if (stale != null) stale.dismiss(); open.clear();
        Dialog dialog = new Dialog(activity, dark(activity) ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        dialog.setOwnerActivity(activity);
        CollectorPanel panel = new CollectorPanel(activity, currentPhoto, dialog::dismiss);
        dialog.setContentView(panel.root); dialog.setOnDismissListener(value -> { panel.handler.removeCallbacksAndMessages(null); if (open.get() == dialog) open.clear(); });
        dialog.show(); dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        open = new WeakReference<>(dialog); panel.poll();
    }
    public static View standalone(Activity activity, String probe) {
        CollectorPanel panel = new CollectorPanel(activity, () -> "", activity::finish);
        panel.summary.setContentDescription("Vector Hook " + probe); panel.poll(); return panel.root;
    }

    private CollectorPanel(Activity activity, Supplier<String> photo, Runnable close) {
        this.activity = activity; this.photo = photo; this.closePanel = close;
        boolean dark = dark(activity);
        darkModeAtCreate = dark;
        uiContext = new ContextThemeWrapper(activity, dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        surface = dynamicColor("system_neutral1_10", dark ? Color.rgb(28, 28, 30) : Color.rgb(250, 249, 253));
        surfaceContainer = dynamicColor("system_neutral1_50", dark ? Color.rgb(39, 39, 42) : Color.rgb(243, 242, 247));
        surfaceVariant = dynamicColor("system_neutral2_100", dark ? Color.rgb(70, 70, 74) : Color.rgb(226, 225, 233));
        foreground = dynamicColor("system_neutral1_900", dark ? Color.rgb(238, 238, 242) : Color.rgb(28, 27, 32));
        secondary = dynamicColor("system_neutral2_700", dark ? Color.rgb(202, 196, 208) : Color.rgb(73, 69, 79));
        primary = dynamicColor("system_accent1_600", dark ? Color.rgb(216, 190, 255) : Color.rgb(103, 80, 164));
        onPrimaryContainer = dynamicColor("system_accent1_900", dark ? Color.rgb(239, 219, 255) : Color.rgb(33, 0, 93));
        link = primary;
        errorColor = dynamicColor("system_accent2_600", dark ? Color.rgb(255, 180, 171) : Color.rgb(186, 26, 26));
        root = vertical(); root.setBackgroundColor(surface);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { handler.removeCallbacksAndMessages(null); }
        });
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        LinearLayout bar = horizontal(); bar.setPadding(dp(20), dp(8), dp(12), dp(8));
        TextView title = text("评论数据库", 22, true); bar.addView(title, new LinearLayout.LayoutParams(0, dp(64), 1));
        bar.addView(icon(android.R.drawable.ic_menu_myplaces, "共享账号", this::accountSettings));
        bar.addView(icon(android.R.drawable.ic_menu_preferences, "本机接口", this::apiSettings));
        bar.addView(icon(android.R.drawable.ic_menu_close_clear_cancel, "关闭评论数据库", close)); root.addView(bar);
        summary = text("", 13, false); summary.setPadding(dp(16), dp(12), dp(16), dp(14)); summary.setTextColor(secondary); summary.setBackgroundColor(surfaceContainer); root.addView(summary);
        body = vertical(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout navigation = horizontal();
        String[] names = {"评论", "帖子", "用户", "分析", "任务"};
        int[] icons = {android.R.drawable.ic_menu_agenda, android.R.drawable.ic_menu_gallery, android.R.drawable.ic_menu_myplaces, android.R.drawable.ic_menu_sort_by_size, android.R.drawable.ic_menu_recent_history};
        for (int i = 0; i < names.length; i++) {
            final int index = i; LinearLayout item = vertical(); item.setGravity(Gravity.CENTER); item.setMinimumHeight(dp(72)); item.setPadding(0, 0, 0, dp(4));
            ImageButton image = icon(icons[i], names[i], () -> select(index));
            navIcons.add(image); image.setImageTintList(ColorStateList.valueOf(i == tab ? onPrimaryContainer : secondary));
            if (i == tab) image.setBackground(round(primary, 20));
            item.addView(image, new LinearLayout.LayoutParams(dp(64), dp(36)));
            TextView name = text(names[i], 12, false); name.setGravity(Gravity.CENTER); item.addView(name); item.setOnClickListener(view -> select(index));
            navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        navigation.setPadding(dp(8), dp(6), dp(8), dp(10)); navigation.setBackgroundColor(surfaceContainer); divider(root); root.addView(navigation);
        try { filters = api("settings_get", new JSONObject().put("name", "filters")); } catch (Exception ignored) {}
        render();
    }
    private int dp(int value) { return dp(activity, value); }
    private static boolean dark(Activity activity) {
        UiModeManager mode = activity.getSystemService(UiModeManager.class);
        int configured = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (mode != null && mode.getNightMode() == UiModeManager.MODE_NIGHT_YES) return true;
        if (mode != null && mode.getNightMode() == UiModeManager.MODE_NIGHT_NO) return false;
        return configured == Configuration.UI_MODE_NIGHT_YES ||
            (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    private android.app.AlertDialog.Builder alert() { return new android.app.AlertDialog.Builder(activity, dark(activity) ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert); }
    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(foreground); view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private ImageButton icon(int drawable, String label, Runnable action) {
        ImageButton button = new ImageButton(activity); button.setImageResource(drawable); button.setContentDescription(label);
        button.setBackgroundColor(Color.TRANSPARENT); button.setImageTintList(ColorStateList.valueOf(foreground));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); button.setOnClickListener(view -> action.run()); return button;
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(uiContext); button.setText(label); button.setTextSize(13); button.setAllCaps(false); button.setMinHeight(dp(48));
        button.setTextColor(onPrimaryContainer); button.setBackground(round(surfaceVariant, 20));
        button.setStateListAnimator(null); button.setPadding(dp(16), 0, dp(16), 0);
        button.setOnClickListener(view -> action.run()); return button;
    }
    private void divider(LinearLayout parent) { View line = new View(activity); line.setBackgroundColor(Color.argb(45, 127, 127, 127)); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private GradientDrawable round(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private int dynamicColor(String name, int fallback) {
        int id = activity.getResources().getIdentifier(name, "color", "android");
        if (id == 0) id = Resources.getSystem().getIdentifier(name, "color", "android");
        try { return id == 0 ? fallback : activity.getColor(id); } catch (Exception ignored) { return fallback; }
    }
    private JSONObject api(String operation, JSONObject params) throws Exception { return CollectorClient.call(activity, operation, params); }
    private void error(Exception error) { alert().setMessage(error.getMessage()).setPositiveButton("确定", null).show(); }
    private void select(int index) { tab = index; offset = 0; userFilter = ""; currentScroll = null; render(); }
    private void render() {
        for (int i = 0; i < navIcons.size(); i++) {
            navIcons.get(i).setSelected(i == tab);
            navIcons.get(i).setImageTintList(ColorStateList.valueOf(i == tab ? onPrimaryContainer : secondary));
            navIcons.get(i).setBackground(i == tab ? round(primary, 20) : null);
        }
        int scrollY = currentScroll == null ? 0 : currentScroll.getScrollY(); currentScroll = null;
        body.removeAllViews();
        try {
            switch (tab) {
                case 0 -> comments(); case 1 -> posts(); case 2 -> users(); case 3 -> analysis(); case 4 -> tasks();
            }
        } catch (Exception error) { body.addView(text("读取失败：" + error.getMessage(), 14, false)); }
        if (currentScroll != null && scrollY > 0) currentScroll.post(() -> currentScroll.scrollTo(0, scrollY));
    }
    private LinearLayout scrolling() {
        ScrollView scroll = new ScrollView(activity); currentScroll = scroll; LinearLayout list = vertical(); list.setPadding(dp(16), 0, dp(16), dp(16)); scroll.addView(list);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); return list;
    }
    private void commands(Runnable refresh) {
        LinearLayout bar = horizontal(); bar.setPadding(dp(12), 0, dp(8), 0);
        bar.addView(button("新建评论任务", this::commentDialog), new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(icon(android.R.drawable.ic_menu_search, "筛选评论", this::filterDialog));
        bar.addView(icon(android.R.drawable.ic_menu_rotate, "刷新结果", refresh)); body.addView(bar);
    }
    private void comments() throws Exception {
        commands(this::render);
        JSONObject query = new JSONObject(filters.toString()).put("offset", offset).put("limit", 50);
        if (!userFilter.isEmpty()) query.put("userId", userFilter);
        JSONObject data = api("comments", query);
        TextView count = text((userFilter.isEmpty() ? "已保存评论" : "用户评论") + " · " + data.getInt("total") + " 条", 14, true);
        count.setPadding(dp(16), dp(12), dp(16), dp(12)); body.addView(count);
        LinearLayout list = scrolling(); JSONArray items = data.getJSONArray("items");
        if (items.length() == 0) list.addView(text("当前条件下暂无评论", 14, false));
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.getJSONObject(i), user = row.optJSONObject("user");
            String name = row.optString("nickname", row.optString("userId"));
            TextView author = text(name, 15, true); author.setTextColor(link); author.setMinHeight(dp(48));
            author.setOnClickListener(view -> openProfile(row.optString("userId"))); list.addView(author);
            String region = row.optString("ipRegion"); if (region.isEmpty() || "null".equals(region)) region = "未知";
            TextView meta = text((row.optInt("level") == 2 ? "二级评论" : "一级评论") + " · IP " + region + " · " + date(row.optLong("created")), 12, false);
            meta.setTextColor(secondary); list.addView(meta);
            TextView content = text(row.optString("text"), 15, false); content.setTextIsSelectable(true); content.setPadding(0, dp(8), 0, dp(8)); list.addView(content);
            TextView metrics = text("赞 " + number(row, "likes") + " · 回复 " + number(row, "replies") + " · 任务 #" + row.optLong("taskId"), 12, false);
            metrics.setTextColor(secondary); list.addView(metrics);
            LinearLayout actions = horizontal();
            actions.addView(button("打开帖子", () -> openPost(row.optString("photoId"))));
            actions.addView(button("用户评论", () -> { userFilter = row.optString("userId"); offset = 0; render(); })); list.addView(actions); divider(list);
        }
        pageControls(data);
    }
    private void posts() throws Exception {
        LinearLayout toolbar = horizontal(); toolbar.setPadding(dp(12), 0, dp(8), 0);
        toolbar.addView(button("发现帖子", this::discoveryDialog), new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbar.addView(icon(android.R.drawable.ic_menu_save, "抓取选中帖子", () -> {
            if (selectedPosts.isEmpty()) { error(new IllegalStateException("请先选择帖子")); return; }
            filterDialog(() -> { try { JSONArray tasks = new JSONArray(); for (String id : selectedPosts)
                tasks.put(new JSONObject(filters.toString()).put("photoId", id).put("kind", "comment").put("auto", true).put("enqueue", true));
                api("batch_start", new JSONObject().put("tasks", tasks)); closePanel.run(); } catch (Exception error) { error(error); } });
        }));
        toolbar.addView(icon(android.R.drawable.ic_menu_rotate, "刷新帖子", this::render)); body.addView(toolbar);
        JSONObject data = api("posts", new JSONObject().put("offset", offset).put("limit", 30));
        LinearLayout list = scrolling(); JSONArray items = data.getJSONArray("items");
        if (items.length() == 0) list.addView(text("暂无已保存作品", 14, false));
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.getJSONObject(i); String id = row.optString("postId");
            LinearLayout heading = horizontal(); CheckBox select = new CheckBox(uiContext); select.setContentDescription("选择作品 " + id); select.setChecked(selectedPosts.contains(id));
            select.setOnCheckedChangeListener((view, checked) -> { if (checked) selectedPosts.add(id); else selectedPosts.remove(id); }); heading.addView(select);
            TextView title = text(row.optString("title", "作品 " + id), 16, true); title.setPadding(0, dp(16), 0, dp(8)); heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); list.addView(heading);
            list.addView(text("赞 " + number(row, "likes") + " · 分享 " + number(row, "shares") + " · 收藏 " + number(row, "favorites"), 12, false));
            list.addView(text("平台评论 " + number(row, "commentTotal") + " · 快照 " + date(row.optLong("snapshotAt")), 12, false));
            LinearLayout actions = horizontal(); actions.addView(button("打开帖子", () -> openPost(id)));
            actions.addView(button("加入采集队列", () -> collect(id, true))); list.addView(actions); divider(list);
        }
        pageControls(data);
    }
    private void users() throws Exception {
        JSONObject data = api("users", new JSONObject(filters.toString()).put("offset", offset).put("limit", 30));
        LinearLayout toolbar = horizontal(); toolbar.setPadding(dp(12), 0, dp(8), 0);
        toolbar.addView(text("用户 · " + data.getInt("total"), 16, true), new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbar.addView(icon(android.R.drawable.ic_menu_edit, "补充选中用户资料", () -> profileTask(new JSONArray(selectedUsers))));
        toolbar.addView(icon(android.R.drawable.ic_menu_search, "筛选用户", this::filterDialog));
        toolbar.addView(icon(android.R.drawable.ic_menu_rotate, "刷新用户", this::render)); body.addView(toolbar);
        LinearLayout list = scrolling(); JSONArray items = data.getJSONArray("items");
        if (items.length() == 0) list.addView(text("暂无已保存用户", 14, false));
        for (int i = 0; i < items.length(); i++) {
            JSONObject user = items.getJSONObject(i); String id = user.optString("userId");
            LinearLayout heading = horizontal(); CheckBox select = new CheckBox(uiContext); select.setContentDescription("选择用户 " + id); select.setChecked(selectedUsers.contains(id));
            select.setOnCheckedChangeListener((view, checked) -> { if (checked) selectedUsers.add(id); else selectedUsers.remove(id); }); heading.addView(select);
            TextView name = text(user.optString("nickname", id), 15, true); name.setTextColor(link); name.setMinHeight(dp(48));
            name.setOnClickListener(view -> openProfile(id)); heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); list.addView(heading);
            list.addView(text("快手 · 性别 " + gender(user.optString("gender")) + " · 年龄 " + number(user, "age"), 12, false));
            list.addView(text("主页 IP " + number(user, "profileIpRegion") + " · 所在地 " + number(user, "location") + "\n评论 IP " + number(user, "lastCommentIpRegion"), 12, false));
            LinearLayout actions = horizontal();
            actions.addView(button("评论 " + user.optInt("commentCount") + " 条", () -> { tab = 0; userFilter = id; offset = 0; render(); }));
            actions.addView(button("补充资料", () -> profileTask(new JSONArray().put(id)))); list.addView(actions); divider(list);
        }
        pageControls(data);
    }
    private void tasks() throws Exception {
        JSONObject data = api("tasks", new JSONObject()); LinearLayout bar = horizontal(); bar.setPadding(dp(12), 0, dp(8), 0);
        bar.addView(button("新建评论任务", this::commentDialog), new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(icon(android.R.drawable.ic_menu_rotate, "刷新任务", this::render)); body.addView(bar);
        LinearLayout list = scrolling(); JSONArray items = data.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject task = items.getJSONObject(i); long id = task.getLong("taskId"); String state = task.optString("status");
            String kind = task.optString("kind"); boolean waitingForPage = showing() && task.optBoolean("auto") && state.startsWith("running") && !task.optString("pagingDriver").startsWith("native-");
            list.addView(text("任务 #" + id + " · " + ("profile".equals(kind) ? "主页资料" : "discovery".equals(kind) ? "发现帖子" : "评论采集") + " · " + (waitingForPage ? "等待返回快手" : state(state)), 16, true));
            if (state.startsWith("running") || "paused".equals(state)) {
                CheckBox auto = new CheckBox(uiContext); auto.setText("自动加载"); auto.setTextColor(foreground); auto.setChecked(task.optBoolean("auto"));
                auto.setOnCheckedChangeListener((view, enabled) -> { try { api("auto", new JSONObject().put("taskId", id).put("enabled", enabled)); } catch (Exception error) { error(error); } }); list.addView(auto);
            }
            if ("profile".equals(kind)) list.addView(text("成功 " + task.optInt("profilesDone") + " / " + task.optInt("profilesTotal") + " 位 · 失败 " + task.optInt("profilesFailed") + " 位", 13, false));
            else if ("discovery".equals(kind)) list.addView(text(task.getJSONObject("params").optString("keyword") + " · 已发现 " + task.optInt("discoveredPosts") + " 条", 13, false));
            else list.addView(text("作品 " + task.optString("photoId") + "\n已读 " + task.optInt("comments") + " 条 / 平台 " + number(task, "total") + " 条", 13, false));
            ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            long total = "profile".equals(kind) ? task.optLong("profilesTotal") : "discovery".equals(kind) ? task.getJSONObject("params").optLong("target", 20) : task.optLong("total");
            long read = "profile".equals(kind) ? task.optLong("profilesDone") : "discovery".equals(kind) ? task.optLong("discoveredPosts") : task.optLong("comments");
            progress.setMax(100); progress.setIndeterminate(total <= 0 && state.startsWith("running") && !waitingForPage);
            if (total > 0) progress.setProgress(Math.min("complete".equals(state) ? 100 : 99, (int) (100 * read / total)));
            list.addView(progress, new LinearLayout.LayoutParams(-1, dp(12)));
            if ("comment".equals(kind)) list.addView(text("主评论 " + task.optInt("rootComments") + " · 回复 " + task.optInt("replyComments") + "\n主评论末页 " + (task.optBoolean("rootComplete") ? "已确认" : "未确认") +
                " · 回复组 " + task.optInt("replyGroupsComplete") + "/" + task.optInt("replyGroups"), 12, false));
            if ("comment".equals(kind) && task.optLong("platformCountGap") > 0)
                list.addView(text("与平台数量相差 " + task.optLong("platformCountGap") + " 条", 12, false));
            if ("comment".equals(kind)) list.addView(text("意图 " + taskPagingMode(task) + " · 实际 " +
                (task.optString("pagingDriver").startsWith("native-") ? "原生接口加载" : "页面加载") +
                " · 原生调用 " + task.optInt("nativeLoads") + " 次 · 滑动 " + task.optInt("uiScrolls") + " 次", 12, false));
            if (state.startsWith("running") || "paused".equals(state)) {
                JSONObject runtime = api("runtime", new JSONObject());
                list.addView(text("模块 " + runtime.optString("installedVersion") + " · " + runtimeStage(runtime.optString("stage")) +
                    "\n" + runtimeStage(runtime.optString("nativeState")) + " · 分页器 " + runtime.optInt("boundPagers"), 12, false));
            }
            String issue = task.optString("issue"); if (!issue.isEmpty()) { TextView reason = text(issue(issue), 12, false); reason.setTextColor("complete".equals(state) ? primary : errorColor); list.addView(reason); }
            GridLayout actions = new GridLayout(activity); actions.setColumnCount(2);
            if (waitingForPage) action(actions, button("返回快手继续", closePanel));
            if ("paused".equals(state)) action(actions, button("继续", () -> taskAction("resume", id)));
            if (state.startsWith("running") || "paused".equals(state) || "queued".equals(state)) action(actions, button("停止", () -> taskAction("stop", id)));
            if ("stopped".equals(state)) action(actions, button("重试", () -> taskAction("retry", id)));
            action(actions, button("运行记录", () -> events(id))); if ("comment".equals(kind)) action(actions, button("打开帖子", () -> openPost(task.optString("photoId"))));
            action(actions, button("复制诊断", () -> copyDiagnostics(id)));
            list.addView(actions); divider(list);
        }
        if (items.length() == 0) list.addView(text("暂无任务", 14, false));
    }
    private void action(GridLayout parent, Button button) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(); params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.width = 0; params.height = dp(48); params.setMargins(dp(2), dp(2), dp(2), dp(2)); parent.addView(button, params);
    }
    private void taskAction(String operation, long id) {
        try { api(operation, new JSONObject().put("taskId", id));
            if (("resume".equals(operation) || "retry".equals(operation)) && showing()) open.get().dismiss(); else render(); }
        catch (Exception error) { error(error); }
    }
    private void collect(String id, boolean enqueue) {
        if (id == null || id.isEmpty()) { error(new IllegalStateException("未读取当前作品评论")); return; }
        filterDialog(() -> collectNow(id, enqueue));
    }
    private void commentDialog() {
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), 0);
        EditText id = input("作品 ID（上次读取）", photo.get(), false); fields.addView(id);
        alert().setTitle("新建评论任务").setView(fields).setNegativeButton("取消", null)
            .setPositiveButton("设置条件", (dialog, which) -> collect(id.getText().toString().trim(), true)).show();
    }
    private void profileTask(JSONArray users) {
        if (users.length() == 0) { error(new IllegalStateException("请先选择用户")); return; }
        try { api("start", new JSONObject().put("kind", "profile").put("userIds", users).put("auto", true).put("enqueue", true)); closePanel.run(); }
        catch (Exception error) { error(error); }
    }
    private void discoveryDialog() {
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), 0);
        EditText keyword = input("搜索关键词", "", false), count = input("目标数量", "20", true), pages = input("最多加载页数", "20", true);
        fields.addView(keyword); fields.addView(count); fields.addView(pages);
        alert().setTitle("发现帖子").setView(fields).setNegativeButton("取消", null).setPositiveButton("开始发现", (dialog, which) -> {
            try { api("start", new JSONObject().put("kind", "discovery").put("keyword", keyword.getText().toString().trim())
                .put("target", Integer.parseInt(count.getText().toString())).put("maxPages", Integer.parseInt(pages.getText().toString())).put("auto", true).put("enqueue", true)); closePanel.run(); }
            catch (Exception error) { error(error); }
        }).show();
    }
    private void analysis() {
        LinearLayout commands = horizontal(); commands.setPadding(dp(12), 0, dp(8), 0);
        commands.addView(button("重新计算", this::render), new LinearLayout.LayoutParams(0, dp(48), 1));
        commands.addView(icon(android.R.drawable.ic_menu_search, "分析条件", this::filterDialog)); body.addView(commands);
        LinearLayout list = scrolling(); TextView pending = text("正在计算", 14, false); list.addView(pending);
        new Thread(() -> {
            try { JSONObject data = api("analysis", new JSONObject(filters.toString())); handler.post(() -> {
                if (tab != 3 || activity.isFinishing()) return; list.removeAllViews();
                list.addView(text("已分析 " + data.optInt("commentsAnalyzed") + " 条公开评论" + (data.optBoolean("truncated") ? " · 部分结果" : ""), 14, true));
                JSONArray items = data.optJSONArray("items");
                if (items.length() == 0) list.addView(text("当前条件下暂无结果", 14, false));
                for (int i = 0; i < items.length(); i++) {
                    JSONObject user = items.optJSONObject(i); String uid = user.optString("userId");
                    TextView name = text(user.optString("nickname", uid), 15, true); name.setMinHeight(dp(48)); name.setTextColor(link); name.setOnClickListener(view -> openProfile(uid)); list.addView(name);
                    list.addView(text("评论 " + user.optInt("commentCount") + " 条 · 出现 " + user.optInt("postCount") + " 个帖子\n获赞 " + user.optLong("likesReceived") + " · 回复 " + user.optLong("replyCount"), 13, false));
                    list.addView(text(date(user.optLong("firstCommentAt")) + " 至 " + date(user.optLong("lastCommentAt")), 12, false));
                    list.addView(button("查看评论", () -> { tab = 0; userFilter = uid; offset = 0; render(); })); divider(list);
                }
            }); } catch (Exception error) { handler.post(() -> pending.setText("计算失败：" + error.getMessage())); }
        }, "collector-analysis").start();
    }
    private void collectNow(String id, boolean enqueue) {
        try {
            JSONObject params = new JSONObject(filters.toString()).put("photoId", id).put("kind", "comment").put("auto", true).put("enqueue", enqueue);
            api("start", params); closePanel.run();
        } catch (Exception error) { error(error); }
    }
    private void pageControls(JSONObject data) {
        LinearLayout controls = horizontal(); controls.setPadding(dp(12), 0, dp(12), 0);
        Button previous = button("上一页", () -> { offset = Math.max(0, offset - data.optInt("limit", 50)); render(); }); previous.setEnabled(offset > 0);
        Button next = button("下一页", () -> { offset += data.optInt("limit", 50); render(); }); next.setEnabled(offset + data.optInt("limit", 50) < data.optInt("total"));
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(48), 1)); controls.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1)); body.addView(controls);
    }
    private EditText input(String hint, String value, boolean numeric) {
        EditText view = new EditText(uiContext); view.setHint(hint); view.setText(value); view.setTextSize(14); view.setTextColor(foreground); view.setHintTextColor(secondary);
        view.setSingleLine(true); view.setMinHeight(dp(48)); if (numeric) view.setInputType(InputType.TYPE_CLASS_NUMBER); return view;
    }
    private void filterDialog() { filterDialog(null); }
    private void filterDialog(Runnable after) {
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), 0);
        EditText keyword = input("评论关键词", filters.optString("keyword"), false), region = input("评论 IP 属地（北京、河北）", filters.optString("ipRegion"), false);
        fields.addView(keyword); fields.addView(region);
        Spinner genders = new Spinner(uiContext); String[] genderKeys = {"all", "female", "male", "unknown", "female-or-unknown"};
        genders.setAdapter(new ArrayAdapter<>(uiContext, android.R.layout.simple_spinner_dropdown_item, new String[]{"全部性别", "女", "男", "未知", "女或未知"}));
        genders.setMinimumHeight(dp(48)); fields.addView(genders);
        for (int i = 0; i < genderKeys.length; i++) if (genderKeys[i].equals(filters.optString("gender", "all"))) genders.setSelection(i);
        LinearLayout ages = horizontal(); EditText min = input("年龄下限", filters.has("minAge") ? filters.optString("minAge") : "", true);
        EditText max = input("年龄上限", filters.has("maxAge") ? filters.optString("maxAge") : "", true);
        ages.addView(min, new LinearLayout.LayoutParams(0, dp(56), 1)); ages.addView(max, new LinearLayout.LayoutParams(0, dp(56), 1)); fields.addView(ages);
        Spinner sorts = new Spinner(uiContext); String[] sortKeys = {"captured_desc", "created_desc", "created_asc", "likes_desc", "captured_asc"};
        sorts.setAdapter(new ArrayAdapter<>(uiContext, android.R.layout.simple_spinner_dropdown_item, new String[]{"最新抓取", "最新评论", "最早评论", "点赞最多", "最早抓取"})); sorts.setMinimumHeight(dp(48)); fields.addView(sorts);
        for (int i = 0; i < sortKeys.length; i++) if (sortKeys[i].equals(filters.optString("sort", "captured_desc"))) sorts.setSelection(i);
        Spinner paging = new Spinner(uiContext); String[] pagingKeys = {"auto", "native", "ui"};
        paging.setAdapter(new ArrayAdapter<>(uiContext, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"自动（优先原生）", "原生接口", "页面滚动"})); paging.setMinimumHeight(dp(48)); fields.addView(paging);
        for (int i = 0; i < pagingKeys.length; i++) if (pagingKeys[i].equals(filters.optString("pagingMode", "auto"))) paging.setSelection(i);
        ScrollView scroll = new ScrollView(activity); scroll.addView(fields);
        alert().setTitle(after == null ? "结果条件" : "采集条件").setView(scroll).setNegativeButton("取消", null).setPositiveButton(after == null ? "应用" : "开始抓取", (dialog, which) -> {
            try {
                filters = new JSONObject().put("keyword", keyword.getText().toString().trim()).put("ipRegion", region.getText().toString().trim())
                    .put("gender", genderKeys[genders.getSelectedItemPosition()]).put("sort", sortKeys[sorts.getSelectedItemPosition()])
                    .put("pagingMode", pagingKeys[paging.getSelectedItemPosition()]);
                if (!min.getText().toString().isEmpty()) filters.put("minAge", Integer.parseInt(min.getText().toString()));
                if (!max.getText().toString().isEmpty()) filters.put("maxAge", Integer.parseInt(max.getText().toString()));
                api("settings_save", new JSONObject().put("name", "filters").put("value", filters)); offset = 0; if (after != null) after.run(); else render();
            } catch (Exception error) { error(error); }
        }).show();
    }
    private void accountSettings() {
        JSONObject config;
        try { config = CloudAccountClient.config(activity); } catch (Exception error) { error(error); return; }
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), dp(12));
        TextView note = text("电脑端和手机端使用同一个云端账户。密码只在登录请求中使用，不会写入手机数据库；平台 Cookie 和本机接口密钥也不会上传。", 13, false);
        note.setTextColor(secondary); note.setPadding(0, 0, 0, dp(12)); fields.addView(note);
        EditText server = input("账户服务地址", config.optString("apiBaseUrl", CloudAccountClient.DEFAULT_API_BASE_URL), false);
        EditText email = input("邮箱", config.optString("email"), false); email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = input("密码（至少 12 个字符）", "", false); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView state = text(config.optString("accessToken").isEmpty() ? "状态：未登录" : "状态：已保存登录状态", 12, false); state.setTextColor(secondary);
        TextView syncState = text(syncStateText(config), 12, false); syncState.setTextColor(secondary);
        fields.addView(server); fields.addView(email); fields.addView(password); fields.addView(state); fields.addView(syncState);
        LinearLayout actions = vertical(); actions.setPadding(0, dp(8), 0, 0);
        Button login = button(config.optString("accessToken").isEmpty() ? "登录并同步" : "重新登录并同步", () -> {});
        Button sync = button("立即同步", () -> {}); Button logout = button("退出登录", () -> {});
        actions.addView(login, new LinearLayout.LayoutParams(-1, dp(48))); actions.addView(sync, new LinearLayout.LayoutParams(-1, dp(48))); actions.addView(logout, new LinearLayout.LayoutParams(-1, dp(48))); fields.addView(actions);
        sync.setEnabled(!config.optString("accessToken").isEmpty()); logout.setEnabled(!config.optString("accessToken").isEmpty());
        Runnable syncNow = () -> {
            login.setEnabled(false); sync.setEnabled(false); logout.setEnabled(false); state.setText("状态：正在同步…");
            new Thread(() -> {
                try {
                    JSONObject result = CloudAccountClient.sync(activity); JSONObject counts = result.optJSONObject("counts");
                    handler.post(() -> { state.setText("状态：已登录"); syncState.setText(counts == null ? "已同步" : "已同步 · 评论 " + counts.optInt("comments") + " · 帖子 " + counts.optInt("posts") + " · 用户 " + counts.optInt("users")); login.setEnabled(true); sync.setEnabled(true); logout.setEnabled(true); });
                } catch (Exception error) {
                    handler.post(() -> { state.setText(error.getMessage() != null && error.getMessage().contains("重新登录") ? "状态：登录已过期" : "状态：已登录，云端暂时不可用"); syncState.setText("同步失败：" + error.getMessage()); login.setEnabled(true); sync.setEnabled(true); logout.setEnabled(true); });
                }
            }, "collector-cloud-sync").start();
        };
        login.setOnClickListener(view -> {
            String address = server.getText().toString().trim(), mail = email.getText().toString().trim(), secret = password.getText().toString();
            login.setEnabled(false); sync.setEnabled(false); logout.setEnabled(false); state.setText("状态：正在登录…");
            new Thread(() -> {
                try {
                    CloudAccountClient.login(activity, mail, secret, address); handler.post(() -> { state.setText("状态：已登录"); syncState.setText("账户已确认，正在上传本地数据…"); syncNow.run(); });
                } catch (Exception error) {
                    handler.post(() -> { state.setText("状态：登录失败"); syncState.setText(error.getMessage()); login.setEnabled(true); sync.setEnabled(false); logout.setEnabled(false); });
                }
            }, "collector-cloud-login").start();
        });
        sync.setOnClickListener(view -> syncNow.run());
        logout.setOnClickListener(view -> {
            try { CloudAccountClient.logout(activity); state.setText("状态：未登录"); syncState.setText("本机数据仍保留"); sync.setEnabled(false); logout.setEnabled(false); }
            catch (Exception error) { error(error); }
        });
        ScrollView scroll = new ScrollView(activity); scroll.addView(fields);
        alert().setTitle("共享账户").setView(scroll).setNegativeButton("关闭", null).show();
    }
    private String syncStateText(JSONObject config) {
        long last = config.optLong("lastSyncAt"); String error = config.optString("lastSyncError");
        if (!error.isEmpty()) return "上次同步失败：" + error;
        return last > 0 ? "上次同步：" + date(last) : "尚未同步本机数据";
    }
    private void apiSettings() {
        LinearLayout settings = vertical(); settings.setPadding(dp(20), 0, dp(20), dp(12));
        boolean enabled; try { enabled = api("api_control", new JSONObject()).optBoolean("running"); } catch (Exception error) { error(error); return; }
        TextView address = text("127.0.0.1:" + LocalApi.PORT + " · " + (enabled ? "已开启" : "已关闭"), 14, false); settings.addView(address);
        Button toggle = button(enabled ? "关闭接口" : "开启接口", () -> {});
        toggle.setOnClickListener(view -> {
            try { boolean running = api("api_control", new JSONObject()).optBoolean("running");
                boolean now = api("api_control", new JSONObject().put("enabled", !running)).optBoolean("running");
                address.setText("127.0.0.1:" + LocalApi.PORT + " · " + (now ? "已开启" : "已关闭")); toggle.setText(now ? "关闭接口" : "开启接口"); }
            catch (Exception error) { error(error); }
        }); settings.addView(toggle);
        settings.addView(button("复制接口密钥", () -> {
            try {
                String token = api("api_token", new JSONObject()).getString("token");
                ClipData clip = ClipData.newPlainText("Collector API", token); android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
                ((ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
            } catch (Exception error) { error(error); }
        }));
        settings.addView(button("导出当前任务", () -> { try { api("export", new JSONObject()); } catch (Exception error) { error(error); } }));
        alert().setTitle("本机接口").setView(settings).setPositiveButton("关闭", null).show();
    }
    private void events(long id) {
        try {
            JSONArray events = api("events", new JSONObject().put("taskId", id)).getJSONArray("items"); StringBuilder message = new StringBuilder();
            for (int i = 0; i < events.length(); i++) { JSONObject event = events.getJSONObject(i); message.append(date(event.optLong("time"))).append(" · ").append(event.optString("code")).append('\n'); }
            alert().setTitle("任务 #" + id).setMessage(message.length() == 0 ? "暂无运行记录" : message.toString()).setPositiveButton("关闭", null).show();
        } catch (Exception error) { error(error); }
    }
    private void openPost(String id) { if (!id.isEmpty()) launch("kwai://work/" + Uri.encode(id)); }
    private void copyDiagnostics(long id) {
        try {
            JSONObject report = new JSONObject().put("moduleBuild", BuildInfo.ID).put("android", android.os.Build.VERSION.RELEASE)
                .put("appVersion", activity.getPackageManager().getPackageInfo("com.smile.gifmaker", 0).versionName)
                .put("runtime", api("runtime", new JSONObject()))
                .put("task", api("status", new JSONObject().put("taskId", id)))
                .put("events", api("events", new JSONObject().put("taskId", id)).getJSONArray("items"));
            ((ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("任务诊断", report.toString()));
            android.widget.Toast.makeText(activity, "已复制任务诊断", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception error) { error(error); }
    }
    private void openProfile(String id) { if (!id.isEmpty() && !"null".equals(id)) launch("kwai://profile/" + Uri.encode(id)); }
    private void launch(String url) {
        try { if (showing()) open.get().dismiss(); activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.smile.gifmaker")); }
        catch (Exception error) { error(error); }
    }
    private void poll() {
        Runnable refresh = new Runnable() {
            @Override public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (dark(activity) != darkModeAtCreate && showing()) {
                    closePanel.run();
                    handler.postDelayed(() -> show(activity, photo), 120);
                    return;
                }
                try {
                    JSONObject health = api("health", new JSONObject()), task = api("status", new JSONObject());
                    String count = switch (task.optString("kind")) {
                        case "discovery" -> "已发现 " + task.optInt("discoveredPosts") + " 条帖子";
                        case "profile" -> "已补充 " + task.optInt("profilesDone") + "/" + task.optInt("profilesTotal") + " 位";
                        default -> "已读 " + task.optInt("comments") + " 条评论";
                    };
                    String summaryText = "快手 · " + health.optInt("comments") + " 评论 · " + health.optInt("users") + " 用户\n任务 #" + task.optLong("taskId") + " " + state(task.optString("status")) + " · " + count +
                        (showing() && task.optBoolean("auto") && task.optString("status").startsWith("running") && !task.optString("pagingDriver").startsWith("native-") ? "\n自动翻页暂缓 · 正在查看数据库" : "") +
                        (cloudStatusHint.isEmpty() ? "" : "\n" + cloudStatusHint);
                    if (!summaryText.contentEquals(summary.getText())) summary.setText(summaryText);
                    maybeAutoSync(task);
                    if (tab == 4 && !lastTaskSnapshot.equals(task.toString())) { lastTaskSnapshot = task.toString(); render(); }
                } catch (Exception error) { summary.setText("采集服务不可用"); }
                handler.postDelayed(this, 2000);
            }
        }; handler.post(refresh);
    }
    private void maybeAutoSync(JSONObject task) {
        long id = task.optLong("taskId");
        if (id <= 0 || !"complete".equals(task.optString("status")) || id == lastAutoSyncTaskId || autoSyncRunning) return;
        try {
            JSONObject config = CloudAccountClient.config(activity);
            if (config.optString("accessToken").isEmpty()) return;
            lastAutoSyncTaskId = id; autoSyncRunning = true; cloudStatusHint = "云端同步中…";
            new Thread(() -> {
                try { CloudAccountClient.sync(activity); cloudStatusHint = "云端已同步"; }
                catch (Exception error) { cloudStatusHint = "云端同步失败，可在共享账户中重试"; }
                finally { autoSyncRunning = false; }
            }, "collector-cloud-auto-sync").start();
        } catch (Exception ignored) {}
    }
    private String number(JSONObject object, String key) { return object.has(key) && !object.isNull(key) ? object.optString(key) : "未知"; }
    private String date(long time) {
        if (time <= 0) return "时间未知"; if (time < 100000000000L) time *= 1000;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(time));
    }
    private String gender(String gender) { return "female".equals(gender) ? "女" : "male".equals(gender) ? "男" : "未知"; }
    private String runtimeStage(String value) {
        return switch (value) {
            case "comment_loading", "native_root_load" -> "正在读取主评论";
            case "native_first_page_refresh" -> "正在重新获取主评论首页";
            case "native_reply_load" -> "正在读取回复";
            case "native_reply_cache_expanded" -> "已展开缓存回复，继续读取";
            case "native_reply_waiting_ready" -> "等待回复分组准备完成";
            case "native_restore_reply_roots" -> "正在恢复回复所属主评论";
            case "native_fold_expanded" -> "已展开折叠评论，继续读取";
            case "native_waiting_response" -> "等待原生接口响应";
            case "native_pager_not_bound", "native_core_not_bound" -> "等待绑定评论分页器";
            case "opening_target_post" -> "正在打开目标作品";
            case "opening_comments" -> "正在打开评论面板";
            case "database_open" -> "正在查看数据库";
            case "waiting_foreground" -> "等待返回快手";
            case "unsupported_native_version" -> "当前版本未适配原生加载";
            case "native_pending_root_unavailable" -> "未找到待补充回复的主评论";
            case "native_reply_signature_mismatch" -> "回复接口与适配版本不一致";
            case "ui_requested" -> "已选择页面加载";
            case "adapter_error" -> "加载器报错，请查看诊断";
            case "" -> "尚未连接快手";
            default -> state(value);
        };
    }
    private String pagingMode(String value) {
        return switch (value) { case "native" -> "原生接口"; case "ui" -> "页面滚动"; default -> "自动（优先原生）"; };
    }
    private String taskPagingMode(JSONObject task) {
        JSONObject params = task.optJSONObject("params");
        return pagingMode(params == null ? "auto" : params.optString("pagingMode", "auto"));
    }
    private String issue(String code) {
        return switch (code) {
            case "target_reached" -> "已达到发现目标"; case "search_eof_confirmed" -> "搜索末页已确认";
            case "search_page_limit" -> "已达到搜索页数上限"; case "no_new_search_page" -> "搜索页面未继续加载";
            case "no_new_comment_page" -> "评论页面未继续加载"; case "profile_not_returned" -> "主页没有返回可用资料";
            case "root_page_not_observed" -> "尚未读取主评论首页，请返回快手后继续";
            case "native_page_timeout" -> "原生评论请求等待超时，可继续任务后重试";
            case "native_reply_unconfirmed" -> "回复分组尚未准备完成或无法确认末页，请查看诊断后继续";
            case "native_adapter_unavailable" -> "当前页面没有可用的原生分页器，请打开目标作品评论后继续";
            case "native_eof_unconfirmed" -> "页面已停止翻页，但接口尚未确认末页，请查看任务诊断";
            case "manual_login_required" -> "等待手动登录"; case "manual_verification_required" -> "等待手动完成平台验证";
            case "manual_pause" -> "已手动暂停"; default -> code;
        };
    }
    private String state(String state) {
        return switch (state) { case "running" -> "采集中"; case "running-replies" -> "补充回复中"; case "running-profiles" -> "补充资料中"; case "paused" -> "已暂停";
            case "complete" -> "已完成"; case "queued" -> "排队中"; case "stopped" -> "已停止"; default -> "待命"; };
    }
}
