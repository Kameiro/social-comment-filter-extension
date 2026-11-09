package dev.local.commentcollector;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.UiModeManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phone UI for the collector: a bottom sheet over Kuaishou with four sections in a bottom bar
 * (采集 / 数据 / 分析 / 我的), chip-based filters and a floating progress pill while a task runs.
 */
public final class CollectorPanel {
    private static WeakReference<Dialog> open = new WeakReference<>(null);
    private static final String FLOATING_TAG = "comment_collector_fab";
    private static final String PILL_TAG = "comment_collector_pill";
    private static final String SETTINGS_ENTRY_TAG = "comment_collector_douyin_settings_entry";

    /** Builds the styled "comso 模块设置" settings entry card used in Douyin and Kuaishou settings screens. */
    public static RelativeLayout createSettingsCard(Activity activity, Supplier<String> currentPhoto, String tag) {
        boolean dark = dark(activity);
        float d = activity.getResources().getDisplayMetrics().density;
        RelativeLayout card = new RelativeLayout(activity);
        card.setTag(tag);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF252630 : 0xFFF3F4F6);
        bg.setCornerRadius(12 * d);
        card.setBackground(bg);
        int padH = Math.round(16 * d);
        card.setPadding(padH, 0, padH, 0);

        M3.Scheme sc = M3.scheme(activity, dark);
        ImageView icon = new ImageView(activity);
        icon.setId(0x7f0f0101);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(sc.primaryContainer);
        iconBg.setCornerRadius(10 * d);
        icon.setBackground(iconBg);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageDrawable(new M3.IconDrawable(M3.Icon.CHAT, sc.onPrimaryContainer));
        int iconSize = Math.round(24 * d);
        RelativeLayout.LayoutParams iconLp = new RelativeLayout.LayoutParams(iconSize, iconSize);
        iconLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        iconLp.addRule(RelativeLayout.CENTER_VERTICAL);
        card.addView(icon, iconLp);

        TextView label = new TextView(activity);
        label.setText("comso 模块设置");
        label.setTextSize(16);
        label.setTextColor(dark ? Color.WHITE : 0xFF161823);
        RelativeLayout.LayoutParams labelLp = new RelativeLayout.LayoutParams(-2, -2);
        labelLp.addRule(RelativeLayout.END_OF, icon.getId());
        labelLp.addRule(RelativeLayout.CENTER_VERTICAL);
        labelLp.leftMargin = Math.round(12 * d);
        card.addView(label, labelLp);

        TextView chevron = new TextView(activity);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(dark ? 0x80FFFFFF : 0x80161823);
        RelativeLayout.LayoutParams chevLp = new RelativeLayout.LayoutParams(-2, -2);
        chevLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        chevLp.addRule(RelativeLayout.CENTER_VERTICAL);
        card.addView(chevron, chevLp);

        card.setOnClickListener(v -> showVideoInfoSettings(activity, currentPhoto));
        card.setOnLongClickListener(v -> { showSettings(activity, currentPhoto); return true; });
        Motion.press(card);
        return card;
    }

    /** Injects a "comso 模块设置" row into Douyin's and Xiaohongshu's Settings screen right below the top module card (or at the top of the settings list). */
    public static void attachDouyinSettingsEntry(Activity activity, Supplier<String> currentPhoto) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String name = activity.getClass().getName();
        if (!name.contains("DouYinSettingNewVersionActivity") && !name.contains("SettingActivity")) return;
        try { activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) {}
        Runnable inject = () -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (!(decor instanceof ViewGroup) || decor.findViewWithTag(SETTINGS_ENTRY_TAG) != null) return;
                int[] indexOut = {0};
                ViewGroup container = findDouyinSettingsContainer((ViewGroup) decor, indexOut);
                if (container == null) return;
                float d = activity.getResources().getDisplayMetrics().density;
                RelativeLayout card = createSettingsCard(activity, currentPhoto, SETTINGS_ENTRY_TAG);

                int insertIndex = Math.max(0, Math.min(container.getChildCount(), indexOut[0]));
                ViewGroup.MarginLayoutParams lp;
                if (container instanceof LinearLayout) {
                    lp = new LinearLayout.LayoutParams(-1, Math.round(54 * d));
                    int marginH = Math.round(16 * d);
                    lp.setMargins(marginH, Math.round(10 * d), marginH, 0);
                } else if (container instanceof RelativeLayout) {
                    lp = new RelativeLayout.LayoutParams(-1, Math.round(54 * d));
                    int marginH = Math.round(16 * d);
                    lp.setMargins(marginH, Math.round(56 * d), marginH, 0);
                } else {
                    lp = new FrameLayout.LayoutParams(-1, Math.round(54 * d));
                    int marginH = Math.round(16 * d);
                    lp.setMargins(marginH, Math.round(56 * d), marginH, 0);
                }
                container.addView(card, insertIndex, lp);
            } catch (Throwable ignored) {}
        };
        inject.run();
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(inject, 150);
        h.postDelayed(inject, 400);
        h.postDelayed(inject, 900);
    }

    private static ViewGroup findDouyinSettingsContainer(ViewGroup root, int[] indexOut) {
        String[] anchors = {"账号", "模块设置", "账号与安全", "通用", "通用设置", "通知设置", "隐私", "帮助与客服"};
        TextView anchor = null;
        boolean afterAnchor = false;
        for (String a : anchors) {
            anchor = findTextViewByText(root, a);
            if (anchor != null) {
                if ("模块设置".equals(a)) afterAnchor = true;
                break;
            }
        }
        if (anchor != null) {
            View curr = anchor;
            while (curr.getParent() instanceof View) {
                View parent = (View) curr.getParent();
                if (parent instanceof LinearLayout) {
                    LinearLayout ll = (LinearLayout) parent;
                    if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() >= 3 && ll.getWidth() >= root.getWidth() / 2) {
                        int idx = ll.indexOfChild(curr);
                        indexOut[0] = afterAnchor ? idx + 1 : Math.max(0, idx);
                        return ll;
                    }
                }
                if (parent.getClass().getName().contains("RecyclerView")) {
                    View rv = parent;
                    if (rv.getParent() instanceof ViewGroup) {
                        ViewGroup rvParent = (ViewGroup) rv.getParent();
                        int rvIdx = rvParent.indexOfChild(rv);
                        indexOut[0] = Math.max(0, rvIdx);
                        return rvParent;
                    }
                }
                curr = parent;
            }
        }
        View rv = findViewByClassName(root, "RecyclerView");
        if (rv != null && rv.getParent() instanceof ViewGroup) {
            ViewGroup rvParent = (ViewGroup) rv.getParent();
            int rvIdx = rvParent.indexOfChild(rv);
            indexOut[0] = Math.max(0, rvIdx);
            return rvParent;
        }
        return null;
    }

    private static View findViewByClassName(View view, String name) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (view.getClass().getName().contains(name)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findViewByClassName(g.getChildAt(i), name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findTextViewByText(View view, String target) {
        if (view instanceof TextView) {
            CharSequence cs = ((TextView) view).getText();
            if (cs != null && target.equals(cs.toString().trim())) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextViewByText(group.getChildAt(i), target);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static final int R_FLAGS = 0x7f0f0002; // view tag key for the capsule state flags
    private static final int R_SHAPES = 0x7f0f0001; // view tag key for the capsule re-shape callback
    private static final int PAGE_COLLECT = 0, PAGE_DATA = 1, PAGE_ANALYSIS = 2, PAGE_ME = 3;
    private static final int DATA_COMMENTS = 0, DATA_POSTS = 1, DATA_USERS = 2;

    private final Activity activity;
    private final Context uiContext;
    private final Supplier<String> photo;
    private final Runnable closePanel;
    private final LinearLayout root, body;
    private final TextView summary;
    private final boolean darkModeAtCreate;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject filters = new JSONObject();
    private int page = PAGE_COLLECT, dataKind = DATA_COMMENTS, offset;
    private String userFilter = "", userFilterName = "", postFilter = "", search = "";
    /** When non-zero, the comments page is scoped to one capture task. */
    private long taskFilter;
    private boolean allTasks;
    private ScrollView currentScroll;
    /** Data-page scroll container; the header and result list share one scroll surface. */
    private ScrollView dataScroll;
    private LinearLayout dataContent;
    private String lastTaskSnapshot = "";
    /** Structure-only fingerprint of the active task (numbers stripped): progress ticks update views in place instead of rebuilding the page. */
    private String lastTaskShape = "";
    private java.util.function.Consumer<JSONObject> liveUpdate;
    private long lastAutoSyncTaskId;
    private View openSwipeRow;
    private boolean autoSyncRunning;
    private volatile String cloudStatusHint = "";
    private final Set<String> selectedPosts = new LinkedHashSet<>(), selectedUsers = new LinkedHashSet<>();
    private final List<TextView> navLabels = new ArrayList<>();
    private final List<ImageView> navIcons = new ArrayList<>();
    private final List<FrameLayout> navIndicators = new ArrayList<>();
    private final M3.Scheme sc;
    private final int surface, surfaceContainer, surfaceVariant, foreground, secondary, link, primary, onPrimaryContainer, errorColor;

    // ---------------------------------------------------------------- entry points

    public static boolean showing() {
        Dialog value = open.get();
        return value != null && value.isShowing() && value.getOwnerActivity() != null && !value.getOwnerActivity().isDestroyed() &&
            value.getWindow() != null && value.getWindow().getDecorView().isAttachedToWindow();
    }
    public static void detach(Activity activity) {
        Dialog value = open.get();
        if (value != null && value.getOwnerActivity() == activity) { value.dismiss(); open.clear(); }
    }

    /** Adds the draggable green button plus the progress pill that appears beside it while a task runs. */
    public static void attach(Activity activity, Supplier<String> currentPhoto) {
        if (activity == null || activity.isFinishing() || activity.getClass().getName().contains(".login.")) return;
        try { activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) {}
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout) || decor.findViewWithTag(FLOATING_TAG) != null) return;
        M3.Scheme sc = M3.scheme(activity, dark(activity));
        int size = dp(activity, 48);

        ImageButton button = new ImageButton(activity); button.setTag(FLOATING_TAG);
        button.setContentDescription("打开评论采集");
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageDrawable(new M3.IconDrawable(M3.Icon.CHAT, sc.onPrimaryContainer));
        button.setBackground(M3.shape(sc.primaryContainer, dp(activity, 16)));
        button.setElevation(dp(activity, 4));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
        params.leftMargin = 0; params.topMargin = dp(activity, 140);

        TextView pill = new TextView(activity); pill.setTag(PILL_TAG);
        M3.type(pill, M3.LABEL_MEDIUM, true, sc.onPrimaryContainer);
        pill.setGravity(Gravity.CENTER_VERTICAL); pill.setPadding(dp(activity, 6), 0, dp(activity, 16), 0);
        pill.setElevation(dp(activity, 4)); pill.setVisibility(View.GONE);
        int pillHeight = size; // same height as the button so the two read as one capsule
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillHeight, Gravity.TOP | Gravity.START);
        pill.setOnClickListener(view -> show(activity, currentPhoto));
        button.setOnClickListener(view -> show(activity, currentPhoto));

        // Edge docking: the button can be dragged anywhere; on release it snaps to the nearest side and slides mostly out
        // of sight. Touching (or swiping on) the exposed sliver slides it back out; it tucks away again after a moment.
        boolean[] dockRight = {false};
        Handler main = new Handler(Looper.getMainLooper());
        // One capsule: while a task runs the pill sits flush against the button and shares its colour; the touching corners go square.
        Runnable shapes = () -> {
            float r = dp(activity, 16), flat = 0f;
            boolean joined = pill.getVisibility() == View.VISIBLE, right = dockRight[0];
            android.graphics.drawable.GradientDrawable buttonShape = new android.graphics.drawable.GradientDrawable();
            buttonShape.setColor(sc.primaryContainer);
            android.graphics.drawable.GradientDrawable pillShape = new android.graphics.drawable.GradientDrawable();
            pillShape.setColor(sc.primaryContainer);
            if (!joined) buttonShape.setCornerRadius(r);
            else if (right) buttonShape.setCornerRadii(new float[]{flat, flat, r, r, r, r, flat, flat}); // pill sits on its left
            else buttonShape.setCornerRadii(new float[]{r, r, flat, flat, flat, flat, r, r});           // pill sits on its right
            if (right) pillShape.setCornerRadii(new float[]{r, r, flat, flat, flat, flat, r, r});
            else pillShape.setCornerRadii(new float[]{flat, flat, r, r, r, r, flat, flat});
            button.setBackground(buttonShape); pill.setBackground(pillShape);
        };
        pill.setTag(R_SHAPES, shapes);
        float hidden = size * .62f;
        Runnable place = () -> {
            int gap = 0, pillWidth = pill.getWidth() > 0 ? pill.getWidth() : dp(activity, 110);
            pillParams.topMargin = params.topMargin + (size - pillHeight) / 2;
            pillParams.leftMargin = dockRight[0] ? Math.max(0, params.leftMargin + Math.round(button.getTranslationX()) - pillWidth - gap)
                : params.leftMargin + size + Math.round(button.getTranslationX()) + gap;
            pill.setLayoutParams(pillParams);
        };
        Runnable[] tuck = new Runnable[1];
        tuck[0] = () -> {
            if (!button.isAttachedToWindow()) return;
            pillFlags(pill)[0] = false; syncPill(activity, pill);
            button.animate().translationX(dockRight[0] ? hidden : -hidden).alpha(.72f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Motion.EXIT)
                .setUpdateListener(animation -> place.run()).start();
        };
        Runnable reveal = () -> {
            main.removeCallbacks(tuck[0]);
            pillFlags(pill)[0] = true; syncPill(activity, pill);
            button.animate().translationX(0f).alpha(1f).setDuration(180).setInterpolator(Motion.ENTER).setUpdateListener(animation -> place.run()).start();
        };
        button.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY; int left, top; boolean moving;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX(); startY = event.getRawY(); left = params.leftMargin; top = params.topMargin; moving = false;
                        reveal.run(); view.animate().scaleX(.94f).scaleY(.94f).setDuration(90).start(); return true;
                    case MotionEvent.ACTION_MOVE: {
                        float x = event.getRawX() - startX, y = event.getRawY() - startY;
                        if (Math.abs(x) + Math.abs(y) > dp(activity, 8)) moving = true;
                        if (moving) {
                            params.leftMargin = Math.max(0, Math.min(decor.getWidth() - size, left + (int) x));
                            params.topMargin = Math.max(dp(activity, 30), Math.min(decor.getHeight() - size - dp(activity, 40), top + (int) y));
                            button.setLayoutParams(params); place.run();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                        if (moving) {
                            dockRight[0] = params.leftMargin + size / 2 > decor.getWidth() / 2;
                            shapes.run();
                            int target = dockRight[0] ? decor.getWidth() - size : 0;
                            android.animation.ValueAnimator snap = android.animation.ValueAnimator.ofInt(params.leftMargin, target);
                            snap.setDuration(220); snap.setInterpolator(Motion.ENTER);
                            snap.addUpdateListener(animation -> { params.leftMargin = (Integer) animation.getAnimatedValue(); button.setLayoutParams(params); place.run(); });
                            snap.start();
                        } else if (event.getAction() == MotionEvent.ACTION_UP) button.performClick();
                        main.removeCallbacks(tuck[0]); main.postDelayed(tuck[0], moving ? 700 : 2600);
                        return true;
                    }
                }
                return false;
            }
        });
        shapes.run();
        button.setTranslationX(-hidden); button.setAlpha(.72f);
        if (!VideoInfoSettings.enabled(VideoInfoSettings.current(activity), "floating_button")) button.setVisibility(View.GONE);
        ((FrameLayout) decor).addView(pill, pillParams);
        ((FrameLayout) decor).addView(button, params);
        place.run();
        startPillUpdates(activity, pill);
    }

    private static void syncFloatingButtonVisibility(Activity activity, boolean visible) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        View fab = decor == null ? null : decor.findViewWithTag(FLOATING_TAG);
        if (fab != null) fab.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Polls task status off the main thread and shows "采集中 120/450" next to the button while a task is active. */
    private static void startPillUpdates(Activity activity, TextView pill) {
        Handler main = new Handler(Looper.getMainLooper());
        java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (activity.isDestroyed() || !pill.isAttachedToWindow() && pill.getParent() == null) return;
                if (activity.hasWindowFocus() || showing()) {
                    if (busy.compareAndSet(false, true)) new Thread(() -> {
                        String label = null;
                        try {
                            JSONObject task = CollectorClient.call(activity, "status", new JSONObject());
                            label = pillLabel(task);
                        } catch (Exception ignored) {}
                        String text = label;
                        main.post(() -> {
                            busy.set(false);
                            // Keep the screen on while CollectorPanel is attached
                            try { activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) {}
                            syncFloatingButtonVisibility(activity, VideoInfoSettings.enabled(VideoInfoSettings.current(activity), "floating_button"));
                            boolean[] flags = pillFlags(pill);
                            flags[1] = text != null;
                            if (text != null && !text.contentEquals(pill.getText())) pill.setText(text);
                            syncPill(activity, pill);
                        });
                    }, "collector-pill").start();
                }
                main.postDelayed(this, 2000);
            }
        };
        main.postDelayed(tick, 800);
    }

    /** flags[0]: the button is revealed (touched); flags[1]: a task is active. The capsule shows only when both hold. */
    private static boolean[] pillFlags(TextView pill) {
        Object flags = pill.getTag(R_FLAGS);
        if (!(flags instanceof boolean[])) { flags = new boolean[2]; pill.setTag(R_FLAGS, flags); }
        return (boolean[]) flags;
    }

    private static void syncPill(Activity activity, TextView pill) {
        boolean[] flags = pillFlags(pill);
        boolean show = flags[0] && flags[1] && !showing() && pill.getText().length() > 0;
        if (show) {
            if (pill.getVisibility() != View.VISIBLE) {
                pill.animate().cancel();
                pill.setVisibility(View.VISIBLE); reshape(pill); pill.setAlpha(0f); pill.setTranslationX(-dp(activity, 16));
                pill.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(Motion.ENTER).start();
            }
        } else if (pill.getVisibility() == View.VISIBLE) {
            pill.animate().alpha(0f).translationX(-dp(activity, 16)).setDuration(180).setInterpolator(Motion.EXIT)
                .withEndAction(() -> { if (!(pillFlags(pill)[0] && pillFlags(pill)[1])) { pill.setVisibility(View.GONE); reshape(pill); } }).start();
        }
    }

    private static void reshape(TextView pill) {
        Object callback = pill.getTag(R_SHAPES);
        if (callback instanceof Runnable) ((Runnable) callback).run();
    }

    private static String pillLabel(JSONObject task) {
        String state = task.optString("status");
        if (!(state.startsWith("running") || "paused".equals(state) || "queued".equals(state))) return null;
        String kind = task.optString("kind");
        if ("paused".equals(state)) return "已暂停 #" + task.optLong("taskId");
        if ("queued".equals(state)) return "排队中 #" + task.optLong("taskId");
        if ("profile".equals(kind)) return "补充资料 " + task.optInt("profilesDone") + "/" + task.optInt("profilesTotal");
        if ("discovery".equals(kind)) return "发现帖子 " + task.optInt("discoveredPosts");
        long total = task.isNull("total") ? 0 : task.optLong("total");
        return "采集中 " + task.optInt("comments") + (total > 0 ? "/" + total : "");
    }

    public static void showVideoInfoSettings(Activity activity) {
        showVideoInfoSettings(activity, () -> "");
    }

    public static void showVideoInfoSettings(Activity activity, Supplier<String> currentPhoto) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        CollectorPanel panel = new CollectorPanel(activity, currentPhoto == null ? () -> "" : currentPhoto, () -> {});
        panel.videoInfoSettings();
    }

    /** Opens the panel as a bottom sheet over the current screen. */
    public static void show(Activity activity, Supplier<String> currentPhoto) {
        show(activity, currentPhoto, PAGE_COLLECT);
    }

    public static void showSettings(Activity activity, Supplier<String> currentPhoto) {
        show(activity, currentPhoto, PAGE_ME);
    }

    public static void show(Activity activity, Supplier<String> currentPhoto, int initialPage) {
        if (showing()) return;
        Dialog stale = open.get(); if (stale != null) stale.dismiss(); open.clear();
        FrameLayout frame = new FrameLayout(activity);
        View scrim = new View(activity); scrim.setBackgroundColor(Color.BLACK); scrim.setAlpha(0f);
        float dim = .4f;
        View[] sheetRef = new View[1];
        Dialog dialog = new Dialog(activity, dark(activity) ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar) {
            private boolean closing;
            @Override public void dismiss() {
                View sheet = sheetRef[0];
                if (closing || !isShowing() || sheet == null || sheet.getWindowToken() == null) { super.dismiss(); return; }
                closing = true;
                Motion.exit(sheet, scrim, false, dim, () -> { try { super.dismiss(); } catch (RuntimeException ignored) {} });
            }
        };
        dialog.setOwnerActivity(activity);
        CollectorPanel panel = new CollectorPanel(activity, currentPhoto, dialog::dismiss, initialPage);
        sheetRef[0] = panel.root;
        int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * .9f);
        panel.root.setBackground(panel.topRounded(panel.surface, 28));
        panel.root.setClipToOutline(true);
        scrim.setOnClickListener(view -> dialog.dismiss());
        frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(panel.root, new FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM));
        dialog.setContentView(frame);
        dialog.setOnDismissListener(value -> { panel.handler.removeCallbacksAndMessages(null); if (open.get() == dialog) open.clear(); });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setFormat(PixelFormat.TRANSLUCENT); // see PostInfoPanel: an opaque full-screen surface ghosts on close
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setWindowAnimations(0);
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(Color.TRANSPARENT);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            Motion.highRefreshRate(activity, window);
        }
        panel.grip.setVisibility(View.VISIBLE);
        Motion.dragToDismiss(panel.grip, panel.root, scrim, dim, dialog::dismiss);
        dialog.show();
        open = new WeakReference<>(dialog);
        Motion.enter(panel.root, scrim, false, dim);
        panel.poll();
    }

    /** Full-screen variant used by the collector app's own launcher activity. */
    public static View standalone(Activity activity, String probe) {
        CollectorPanel panel = new CollectorPanel(activity, () -> "", activity::finish);
        panel.summary.setContentDescription("Vector Hook " + probe); panel.poll(); return panel.root;
    }

    // ---------------------------------------------------------------- construction

    private View grip;

    private CollectorPanel(Activity activity, Supplier<String> photo, Runnable close) {
        this(activity, photo, close, PAGE_COLLECT);
    }

    private CollectorPanel(Activity activity, Supplier<String> photo, Runnable close, int initialPage) {
        this.activity = activity; this.photo = photo; this.closePanel = close; this.page = initialPage;
        boolean dark = dark(activity);
        darkModeAtCreate = dark;
        uiContext = new ContextThemeWrapper(activity, dark ? android.R.style.Theme_DeviceDefault_NoActionBar : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        sc = M3.scheme(activity, dark);
        surface = sc.surfaceContainerLow;
        surfaceContainer = sc.surfaceContainer;
        surfaceVariant = sc.surfaceContainerHighest;
        foreground = sc.onSurface;
        secondary = sc.onSurfaceVariant;
        primary = sc.primary;
        onPrimaryContainer = sc.onPrimary; // text/icon colour on filled primary surfaces
        link = foreground;
        errorColor = sc.error;
        root = vertical(); root.setBackgroundColor(surface);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { handler.removeCallbacksAndMessages(null); }
        });
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            // As a bottom sheet the top inset does not apply; standalone it does.
            boolean sheet = grip != null && grip.getVisibility() == View.VISIBLE;
            view.setPadding(bars.left, sheet ? 0 : bars.top, bars.right, bars.bottom); return insets;
        });

        FrameLayout gripFrame = new FrameLayout(activity);
        View handle = new View(activity); handle.setBackground(round(M3.mix(sc.onSurfaceVariant, sc.surfaceContainerLow, .6f), 2));
        gripFrame.addView(handle, new FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER));
        gripFrame.setVisibility(View.GONE); grip = gripFrame;
        root.addView(gripFrame, new LinearLayout.LayoutParams(-1, dp(22)));

        LinearLayout header = horizontal(); header.setPadding(dp(20), dp(4), dp(8), dp(4));
        LinearLayout titles = vertical();
        titles.addView(M3.type(new TextView(activity), M3.TITLE_LARGE, false, foreground)); ((TextView) titles.getChildAt(0)).setText("comso");
        summary = text("正在读取…", 14, false); summary.setTextColor(secondary); summary.setSingleLine(true); summary.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(summary);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(icon(M3.Icon.CLOSE, "关闭", close));
        root.addView(header);

        body = vertical(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bottomNav());
        try { filters = api("settings_get", new JSONObject().put("name", "filters")); } catch (Exception ignored) {}
        render();
    }

    /** M3 navigation bar: tonal surface, 64x32 pill indicator behind the active icon, medium-weight labels. */
    private View bottomNav() {
        LinearLayout nav = horizontal(); nav.setBackgroundColor(surfaceContainer); nav.setPadding(0, dp(12), 0, dp(16));
        String[] labels = {"采集", "数据", "分析", "我的"};
        M3.Icon[] icons = {M3.Icon.COLLECT, M3.Icon.DATA, M3.Icon.ANALYSIS, M3.Icon.PERSON};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout item = vertical(); item.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout indicator = new FrameLayout(activity); indicator.setBackground(round(Color.TRANSPARENT, 16));
            ImageView image = new ImageView(activity); image.setImageDrawable(new M3.IconDrawable(icons[i], secondary)); image.setScaleType(ImageView.ScaleType.CENTER);
            indicator.addView(image, new FrameLayout.LayoutParams(dp(64), dp(32), Gravity.CENTER));
            item.addView(indicator, new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView label = M3.type(new TextView(activity), M3.LABEL_MEDIUM, false, secondary); label.setText(labels[i]); label.setGravity(Gravity.CENTER); label.setPadding(0, dp(4), 0, 0);
            item.addView(label);
            item.setOnClickListener(view -> go(index)); Motion.press(item);
            item.setContentDescription(labels[i]);
            navLabels.add(label); navIcons.add(image); navIndicators.add(indicator);
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return nav;
    }

    private void go(int target) {
        int previous = page;
        page = target; offset = 0; currentScroll = null; allTasks = false;
        render();
        if (previous != target) Motion.tabContent(body, target - previous);
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        for (int i = 0; i < navLabels.size(); i++) {
            boolean on = i == page;
            navLabels.get(i).setTextColor(on ? foreground : secondary);
            navLabels.get(i).setTypeface(on ? M3.medium() : Typeface.DEFAULT);
            ((M3.IconDrawable) navIcons.get(i).getDrawable()).setIconColor(on ? sc.onSecondaryContainer : secondary);
            navIndicators.get(i).setBackground(round(on ? sc.secondaryContainer : Color.TRANSPARENT, 16));
        }
        int scrollY = currentScroll == null ? 0 : currentScroll.getScrollY(); currentScroll = null; liveUpdate = null;
        body.removeAllViews();
        try {
            switch (page) {
                case PAGE_COLLECT -> collectPage();
                case PAGE_DATA -> dataPage();
                case PAGE_ANALYSIS -> analysisPage();
                default -> mePage();
            }
        } catch (Exception error) { failure(error); }
        if (currentScroll != null && scrollY > 0) currentScroll.post(() -> currentScroll.scrollTo(0, scrollY));
    }

    private void failure(Exception error) {
        body.removeAllViews();
        LinearLayout list = scrolling();
        LinearLayout card = card(list);
        TextView failed = text("读取失败", 16, true); failed.setTextColor(errorColor); card.addView(failed);
        card.addView(muted(String.valueOf(error.getMessage()), 12));
        TextView hint = text("采集服务可能被系统在后台结束了。点下方按钮唤醒；若经常出现，请在系统设置中为“comso”开启自启动、关联启动并允许后台运行。", 13, false);
        hint.setPadding(0, dp(8), 0, dp(12)); card.addView(hint);
        card.addView(primary("唤醒采集服务", this::wakeCollector), new LinearLayout.LayoutParams(-1, dp(44)));
    }

    // ---- 采集

    private void collectPage() throws Exception {
        LinearLayout list = scrolling();
        String suppliedCurrent = photo.get();
        final String current = suppliedCurrent == null ? "" : suppliedCurrent;

        LinearLayout postCard = card(list);
        postCard.addView(muted("当前帖子", 12));
        if (current.isEmpty()) {
            postCard.addView(text("还没有识别到帖子", 16, true));
            postCard.addView(muted("在" + hostName() + "打开一个帖子的评论区，再回到这里即可一键采集；也可以批量添加帖子链接。", 12));
        } else {
            JSONObject post = null;
            try { JSONArray found = api("posts", new JSONObject().put("search", current).put("limit", 1)).optJSONArray("items"); if (found != null && found.length() > 0) post = found.getJSONObject(0); }
            catch (Exception ignored) {}
            String title = post == null ? "" : post.optString("title");
            TextView name = text(title.isEmpty() || "null".equals(title) ? "作品 " + current : title, 16, true);
            name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); postCard.addView(name);
            String author = post == null ? "" : post.optString("authorName");
            postCard.addView(muted((author.isEmpty() || "null".equals(author) ? "" : author + " · ") + "ID " + current
                + (post != null && !post.isNull("commentTotal") ? " · 共 " + post.optString("commentTotal") + " 条评论" : ""), 12));
        }
        TextView conditionsLabel = muted("采集条件", 12); conditionsLabel.setPadding(0, dp(12), 0, dp(4)); postCard.addView(conditionsLabel);
        postCard.addView(conditionChips(true));
        Button collect = primary("采集本帖评论", () -> collectNow(current, true));
        collect.setEnabled(!current.isEmpty()); collect.setAlpha(current.isEmpty() ? .45f : 1f); collect.setTextSize(15);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(-1, dp(48)); big.topMargin = dp(12);
        postCard.addView(collect, big);
        LinearLayout more = horizontal(); more.setPadding(0, dp(8), 0, 0);
        more.addView(button("批量添加帖子", this::batchDialog), weighted(0));
        more.addView(button("发现帖子", this::discoveryDialog), weighted(8));
        postCard.addView(more);

        JSONObject active = api("status", new JSONObject());
        String state = active.optString("status");
        if (active.optLong("taskId") > 0 && (state.startsWith("running") || "paused".equals(state) || "queued".equals(state))) {
            sectionTitle(list, "进行中");
            taskCard(list, active, true);
        }

        JSONArray items = api("tasks", new JSONObject()).getJSONArray("items");
        LinearLayout heading = horizontal(); heading.setPadding(dp(4), dp(14), 0, dp(6));
        heading.addView(text(allTasks ? "全部任务" : "最近任务", 15, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (items.length() > 4) { TextView toggle = text(allTasks ? "收起" : "全部 ›", 13, false); toggle.setTextColor(primary); toggle.setPadding(dp(12), dp(6), dp(4), dp(6));
            toggle.setOnClickListener(view -> { allTasks = !allTasks; render(); }); heading.addView(toggle); }
        list.addView(heading);
        int shown = 0;
        for (int i = 0; i < items.length() && (allTasks || shown < 4); i++) {
            JSONObject task = items.getJSONObject(i);
            if (task.optLong("taskId") == active.optLong("taskId") && (state.startsWith("running") || "paused".equals(state) || "queued".equals(state))) continue;
            taskRow(list, task); shown++;
        }
        if (shown == 0) list.addView(muted("暂无其他任务", 13));
    }

    /** Compact one-line task row; swipe left for refresh & delete actions, tap to expand into the full card. */
    private void taskRow(LinearLayout parent, JSONObject task) {
        FrameLayout swipeWrap = new FrameLayout(activity);
        swipeWrap.setBackground(round(surfaceVariant, M3.SHAPE_LARGE));
        swipeWrap.setClipToOutline(true);
        swipeWrap.setClipChildren(true);
        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(-1, -2);
        wrapParams.setMargins(0, 0, 0, dp(10));
        parent.addView(swipeWrap, wrapParams);

        int actionWidth = dp(168);
        LinearLayout actions = horizontal();
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(actionWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        swipeWrap.addView(actions, actionParams);

        LinearLayout row = vertical();
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(round(surfaceContainer, M3.SHAPE_LARGE));
        swipeWrap.addView(row, new FrameLayout.LayoutParams(-1, -2));

        Button refreshBtn = new Button(uiContext);
        refreshBtn.setText("更新抓取");
        refreshBtn.setTextColor(sc.onPrimary);
        refreshBtn.setTextSize(13);
        refreshBtn.setTypeface(refreshBtn.getTypeface(), Typeface.BOLD);
        refreshBtn.setBackgroundColor(primary);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setPadding(0, 0, 0, 0);
        refreshBtn.setOnClickListener(v -> {
            row.animate().translationX(0f).setDuration(160).start();
            openSwipeRow = null;
            taskAction("refresh_task", task.optLong("taskId"));
        });
        actions.addView(refreshBtn, new LinearLayout.LayoutParams(dp(92), -1));

        Button deleteBtn = new Button(uiContext);
        deleteBtn.setText("删除");
        deleteBtn.setTextColor(Color.WHITE);
        deleteBtn.setTextSize(13);
        deleteBtn.setTypeface(deleteBtn.getTypeface(), Typeface.BOLD);
        deleteBtn.setBackgroundColor(errorColor);
        deleteBtn.setGravity(Gravity.CENTER);
        deleteBtn.setPadding(0, 0, 0, 0);
        deleteBtn.setOnClickListener(v -> {
            row.animate().translationX(0f).setDuration(160).start();
            openSwipeRow = null;
            deleteTask(task.optLong("taskId"));
        });
        actions.addView(deleteBtn, new LinearLayout.LayoutParams(dp(76), -1));

        LinearLayout line = horizontal();
        String kind = task.optString("kind"), state = task.optString("status");
        TextView title = text("#" + task.optLong("taskId") + " " + kindName(kind), 14, true);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text(state(state), 12, true); badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setTextColor("paused".equals(state) || "stopped".equals(state) ? errorColor : "complete".equals(state) ? primary : secondary);
        badge.setBackground(round(surfaceVariant, 10)); line.addView(badge);
        row.addView(line);
        TextView summaryView = muted(taskSummary(task), 12);
        row.addView(summaryView);
        LinearLayout details = vertical(); details.setVisibility(View.GONE); row.addView(details);

        row.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY, startTx;
            private boolean swiping;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startTx = row.getTranslationX();
                        swiping = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!swiping && Math.abs(dx) > dp(8) && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                            swiping = true;
                            if (row.getParent() != null) row.getParent().requestDisallowInterceptTouchEvent(true);
                            if (openSwipeRow != null && openSwipeRow != row) {
                                openSwipeRow.animate().translationX(0f).setDuration(160).start();
                            }
                        }
                        if (swiping) {
                            float tx = Math.max(-actionWidth, Math.min(0f, startTx + dx));
                            row.setTranslationX(tx);
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (swiping) {
                            float dx2 = event.getRawX() - downRawX;
                            if (row.getTranslationX() < -actionWidth * 0.35f || (dx2 < -dp(24) && startTx == 0)) {
                                row.animate().translationX(-actionWidth).setDuration(180).start();
                                openSwipeRow = row;
                            } else {
                                row.animate().translationX(0f).setDuration(180).start();
                                if (openSwipeRow == row) openSwipeRow = null;
                            }
                            return true;
                        }
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            if (row.getTranslationX() < -dp(8)) {
                                row.animate().translationX(0f).setDuration(180).start();
                                if (openSwipeRow == row) openSwipeRow = null;
                                return true;
                            }
                            if (details.getVisibility() == View.GONE) {
                                if (details.getChildCount() == 0) {
                                    try { taskDetails(details, task, false); }
                                    catch (Exception error) { details.addView(muted(String.valueOf(error.getMessage()), 12)); }
                                }
                                summaryView.setVisibility(View.GONE);
                                details.setVisibility(View.VISIBLE);
                                Motion.fadeIn(details, 200);
                            } else {
                                details.setVisibility(View.GONE);
                                summaryView.setVisibility(View.VISIBLE);
                            }
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });
    }

    private String taskSummary(JSONObject task) {
        String kind = task.optString("kind");
        if ("profile".equals(kind)) return "已采集资料 " + task.optInt("profilesDone") + " / " + task.optInt("profilesTotal") + " 位";
        if ("discovery".equals(kind)) return task.optJSONObject("params") == null ? "" : "关键词「" + task.optJSONObject("params").optString("keyword") + "」· 已找到 " + task.optInt("discoveredPosts") + " 条帖子";
        String issue = task.optString("issue");
        String title = task.optString("postTitle").replace('\n', ' ').trim();
        if (title.length() > 14) title = title.substring(0, 14) + "…";
        String prefix = title.isEmpty() ? "" : title + " · ";
        String profSummary = "";
        int profTotal = task.optInt("profilesTotal");
        if (profTotal > 0) profSummary = " · 资料 " + task.optInt("profilesDone") + "/" + profTotal;
        return prefix + "已采集 " + task.optInt("comments") + " 条" + (task.isNull("total") ? "" : "（帖子显示共 " + task.optLong("total") + " 条）") + profSummary + (issue.isEmpty() ? "" : " · " + issue(issue));
    }

    private void taskCard(LinearLayout parent, JSONObject task, boolean live) throws Exception {
        LinearLayout card = card(parent);
        String kind = task.optString("kind"), state = task.optString("status");
        LinearLayout line = horizontal();
        line.addView(text("#" + task.optLong("taskId") + " " + kindName(kind), 15, true), new LinearLayout.LayoutParams(0, -2, 1));
        boolean waitingForPage = showing() && task.optBoolean("auto") && state.startsWith("running") && !task.optString("pagingDriver").startsWith("native");
        TextView badge = text(waitingForPage ? "等待返回应用" : state(state), 12, true);
        boolean bad = "paused".equals(state) || "stopped".equals(state) || "failed".equals(state), good = "complete".equals(state);
        badge.setTextColor(bad ? sc.onErrorContainer : good ? sc.onPrimaryContainer : sc.onSecondaryContainer); badge.setPadding(dp(10), dp(3), dp(10), dp(3));
        badge.setBackground(round(bad ? sc.errorContainer : good ? sc.primaryContainer : sc.secondaryContainer, 8)); line.addView(badge);
        card.addView(line);
        taskDetails(card, task, live);
    }

    private String readLine(JSONObject task) {
        String title = task.optString("postTitle").replace('\n', ' ').trim();
        if (title.length() > 22) title = title.substring(0, 22) + "…";
        String head = title.isEmpty() ? "帖子 ID " + task.optString("photoId") : title;
        return head + "\n已采集 " + task.optInt("comments") + " 条" + (task.isNull("total") ? "" : "（帖子显示共 " + number(task, "total") + " 条）");
    }
    private String repliesLine(JSONObject task) {
        return "含一级评论 " + task.optInt("rootComments") + " 条、回复 " + task.optInt("replyComments") + " 条 · "
            + (task.optBoolean("rootComplete") ? "已滑到底部" : "尚未滑到底部");
    }
    private String driverLine(JSONObject task) {
        return "采集方式：" + (task.optString("pagingDriver").startsWith("native") ? "接口直连加载" : "自动滑动页面");
    }
    private static void setIfChanged(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    /** Task JSON with every number removed except ids, so only real state changes (status, buttons, issues) count as structural. */
    private static String shape(Object value) {
        if (value instanceof JSONObject object) {
            StringBuilder out = new StringBuilder("{");
            java.util.TreeSet<String> keys = new java.util.TreeSet<>(); for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) keys.add(it.next());
            for (String key : keys) {
                Object child = object.opt(key);
                if (child instanceof Number && !key.toLowerCase(java.util.Locale.ROOT).endsWith("id")) { if (key.equals("platformCountGap")) out.append(key).append('=').append(((Number) child).longValue() > 0); continue; }
                out.append(key).append('=').append(shape(child)).append(',');
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray array) { StringBuilder out = new StringBuilder("["); for (int i = 0; i < array.length(); i++) out.append(shape(array.opt(i))).append(','); return out.append(']').toString(); }
        return String.valueOf(value);
    }

    private void taskDetails(LinearLayout card, JSONObject task, boolean live) throws Exception {
        long id = task.getLong("taskId"); String state = task.optString("status"), kind = task.optString("kind");
        boolean waitingForPage = showing() && task.optBoolean("auto") && state.startsWith("running") && !task.optString("pagingDriver").startsWith("native");
        long total = "profile".equals(kind) ? task.optLong("profilesTotal") : "discovery".equals(kind) ? task.getJSONObject("params").optLong("target", 20) : task.optLong("total");
        long read = "profile".equals(kind) ? task.optLong("profilesDone") : "discovery".equals(kind) ? task.optLong("discoveredPosts") : task.optLong("comments");
        if ("profile".equals(kind)) card.addView(text("成功 " + task.optInt("profilesDone") + " / " + task.optInt("profilesTotal") + " 位 · 失败 " + task.optInt("profilesFailed") + " 位", 13, false));
        else if ("discovery".equals(kind)) card.addView(text(task.getJSONObject("params").optString("keyword") + " · 已发现 " + task.optInt("discoveredPosts") + " 条", 13, false));
        else card.addView(text(readLine(task), 13, false));
        TextView readView = (TextView) card.getChildAt(card.getChildCount() - 1);
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(primary)); progress.setIndeterminateTintList(ColorStateList.valueOf(primary));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(surfaceVariant));
        progress.setIndeterminate(total <= 0 && state.startsWith("running") && !waitingForPage);
        if (total > 0) progress.setProgress(Math.min("complete".equals(state) ? 100 : 99, (int) (100 * read / total)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(10)); barParams.setMargins(0, dp(8), 0, dp(6));
        card.addView(progress, barParams);
        TextView repliesView = null, driverView = null;
        if ("comment".equals(kind)) {
            repliesView = muted(repliesLine(task), 12); card.addView(repliesView);
            driverView = muted(driverLine(task), 12); card.addView(driverView);
            if (task.optInt("profilesTotal") > 0) {
                card.addView(muted("资料采集：已完成 " + task.optInt("profilesDone") + " / " + task.optInt("profilesTotal") + " 位" + (task.optInt("profilesFailed") > 0 ? " · 失败 " + task.optInt("profilesFailed") : ""), 12));
            }
            JSONObject params = task.optJSONObject("params");
            if (params != null) { String conditions = describe(params, true); if (!conditions.isEmpty()) card.addView(muted("筛选条件：" + conditions, 12)); }
        }
        if (live && (state.startsWith("running") || "paused".equals(state))) {
            Switch auto = new Switch(uiContext); auto.setText("自动翻页加载"); auto.setTextColor(foreground); auto.setTextSize(13); auto.setChecked(task.optBoolean("auto"));
            auto.setOnCheckedChangeListener((view, enabled) -> { try { api("auto", new JSONObject().put("taskId", id).put("enabled", enabled)); } catch (Exception error) { error(error); } });
            card.addView(auto);
        }
        if (live) {
            final TextView fRead = readView, fReplies = repliesView, fDriver = driverView;
            liveUpdate = fresh -> {
                String freshKind = fresh.optString("kind"), freshState = fresh.optString("status");
                long freshTotal = "profile".equals(freshKind) ? fresh.optLong("profilesTotal") : "discovery".equals(freshKind) ? (fresh.optJSONObject("params") == null ? 20 : fresh.optJSONObject("params").optLong("target", 20)) : fresh.optLong("total");
                long freshRead = "profile".equals(freshKind) ? fresh.optLong("profilesDone") : "discovery".equals(freshKind) ? fresh.optLong("discoveredPosts") : fresh.optLong("comments");
                if ("profile".equals(freshKind)) setIfChanged(fRead, "成功 " + fresh.optInt("profilesDone") + " / " + fresh.optInt("profilesTotal") + " 位 · 失败 " + fresh.optInt("profilesFailed") + " 位");
                else if ("discovery".equals(freshKind)) setIfChanged(fRead, (fresh.optJSONObject("params") == null ? "" : fresh.optJSONObject("params").optString("keyword")) + " · 已发现 " + fresh.optInt("discoveredPosts") + " 条");
                else setIfChanged(fRead, readLine(fresh));
                if (freshTotal > 0) progress.setProgress(Math.min("complete".equals(freshState) ? 100 : 99, (int) (100 * freshRead / freshTotal)));
                if (fReplies != null) setIfChanged(fReplies, repliesLine(fresh));
                if (fDriver != null) setIfChanged(fDriver, driverLine(fresh));
            };
        }
        String issue = task.optString("issue");
        if (!issue.isEmpty()) { TextView reason = text(issue(issue), 12, false); reason.setTextColor("complete".equals(state) ? primary : errorColor); card.addView(reason); }
        LinearLayout actions = horizontal(); actions.setPadding(0, dp(10), 0, 0);
        List<Button> buttons = new ArrayList<>();
        if (waitingForPage) buttons.add(primary("返回" + hostName() + "继续", closePanel));
        if ("paused".equals(state)) buttons.add(primary("继续", () -> taskAction("resume", id)));
        if (state.startsWith("running") || "paused".equals(state) || "queued".equals(state)) buttons.add(button("停止", () -> taskAction("stop", id)));
        if ("stopped".equals(state)) buttons.add(button("续跑", () -> taskAction("retry", id)));
        if (!state.startsWith("running") && !"queued".equals(state)) {
            buttons.add(button("更新抓取", () -> taskAction("refresh_task", id)));
            buttons.add(button("删除", () -> deleteTask(id)));
        }
        if ("comment".equals(kind)) {
            buttons.add(button("查看评论", () -> showTaskComments(id)));
            buttons.add(button("打开帖子", () -> openPost(task.optString("photoId"))));
        }
        buttons.add(button("记录", () -> events(id)));
        buttons.add(button("诊断", () -> copyDiagnostics(id)));
        for (int i = 0; i < buttons.size(); i++) actions.addView(buttons.get(i), weighted(i == 0 ? 0 : 6));
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(activity); scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(actions); card.addView(scroller);
    }

    /** Opens the complete comment list captured by one task, including replies. */
    private void showTaskComments(long taskId) {
        if (taskId <= 0) return;
        page = PAGE_DATA;
        dataKind = DATA_COMMENTS;
        // A task can belong to another host app (for example an XHS task while the panel is opened from Kuaishou).
        // Task-scoped navigation must therefore include all platforms; the taskId predicate remains authoritative.
        allPlatforms = true;
        taskFilter = taskId;
        userFilter = "";
        userFilterName = "";
        postFilter = "";
        search = "";
        offset = 0;
        currentScroll = null;
        render();
    }

    // ---- 数据

    /** Host app's platform key ("kuaishou"/"douyin"/"xhs") and display name. */
    private String hostPlatform() { return Platforms.name(activity.getPackageName()); }
    private String hostName() { return Platforms.label(hostPlatform()); }
    private boolean allPlatforms;
    /** Platform sent with data queries: the host app's own data unless the user switched to all platforms. */
    private String scope() { return allPlatforms ? "all" : hostPlatform(); }

    private void dataPage() throws Exception {
        dataScroll = new ScrollView(activity);
        dataScroll.setClipToPadding(false);
        dataScroll.setFillViewport(true);
        dataContent = vertical();
        dataScroll.addView(dataContent);
        body.addView(dataScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout top = vertical(); top.setPadding(dp(12), dp(2), dp(12), dp(4));
        top.addView(segmented(new String[]{"评论", "帖子", "用户"}, dataKind, index -> {
            dataKind = index; offset = 0; selectedPosts.clear(); selectedUsers.clear();
            if (index != DATA_COMMENTS) taskFilter = 0;
            render();
        }));
        LinearLayout selectorRow = horizontal();
        selectorRow.setPadding(0, dp(8), 0, 0);
        String[] scopes = {"仅" + hostName(), "全部平台"};
        selectorRow.addView(dropdown("数据范围", scopes, allPlatforms ? 1 : 0, index -> {
            boolean next = index == 1;
            if (allPlatforms == next) return;
            allPlatforms = next; offset = 0; selectedPosts.clear(); selectedUsers.clear(); render();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        String[] sortKeys = {"captured_desc", "created_desc", "created_asc", "likes_desc", "captured_asc"};
        String[] sortLabels = {"最新抓取", "最新评论", "最早评论", "点赞最多", "最早抓取"};
        int selectedSort = 0;
        String savedSort = filters.optString("sort", "captured_desc");
        for (int i = 0; i < sortKeys.length; i++) if (sortKeys[i].equals(savedSort)) selectedSort = i;
        View sortPicker = dropdown("排序", sortLabels, selectedSort, index -> {
            if (index < 0 || index >= sortKeys.length || sortKeys[index].equals(filters.optString("sort", "captured_desc"))) return;
            try { filters.put("sort", sortKeys[index]); saveFilters(); render(); } catch (Exception error) { error(error); }
        });
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(0, -2, 1); sortParams.leftMargin = dp(8); selectorRow.addView(sortPicker, sortParams);
        top.addView(selectorRow);
        EditText box = input(dataKind == DATA_COMMENTS ? "搜索评论内容或昵称" : dataKind == DATA_POSTS ? "搜索帖子标题、作者或 ID" : "搜索昵称或用户 ID", search, false);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH); box.setBackground(round(surfaceContainer, 14)); box.setPadding(dp(14), 0, dp(14), 0);
        box.setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0); box.setCompoundDrawablePadding(dp(6));
        box.setOnEditorActionListener((view, action, event) -> { search = view.getText().toString().trim(); offset = 0; render(); return true; });
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, dp(44)); boxParams.topMargin = dp(8); top.addView(box, boxParams);
        View chips = dataKind == DATA_POSTS ? postChips() : conditionChips(false);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-1, -2); chipParams.topMargin = dp(8); top.addView(chips, chipParams);
        dataContent.addView(top);
        try {
            switch (dataKind) {
                case DATA_POSTS -> posts();
                case DATA_USERS -> users();
                default -> comments();
            }
        } finally {
            dataScroll = null;
            dataContent = null;
        }
    }

    /** Compact labeled Spinner used for data-page choices that should not consume a full row of chips. */
    private View dropdown(String label, String[] options, int selected, Consumer<Integer> onSelect) {
        LinearLayout box = vertical();
        TextView caption = muted(label, 11); caption.setPadding(dp(4), 0, 0, dp(1)); box.addView(caption);
        Spinner spinner = new Spinner(uiContext);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(uiContext, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setTag(selected);
        spinner.setSelection(Math.max(0, Math.min(selected, options.length - 1)), false);
        spinner.setMinimumHeight(dp(40));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object previous = spinner.getTag(); spinner.setTag(position);
                if (!(previous instanceof Integer) || ((Integer) previous) != position) onSelect.accept(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        box.addView(spinner, new LinearLayout.LayoutParams(-1, dp(40)));
        return box;
    }

    private View postChips() {
        Flow flow = new Flow(activity, dp(6));
        if (!search.isEmpty()) flow.addView(chip("搜索：" + search, true, () -> { search = ""; render(); }));
        flow.addView(chip(selectedPosts.isEmpty() ? "勾选帖子后可批量采集" : "采集选中 " + selectedPosts.size() + " 个", false, () -> {
            if (selectedPosts.isEmpty()) return;
            batchStart(new ArrayList<>(selectedPosts));
        }));
        return flow;
    }

    private void countLine(String label, JSONObject data) {
        String unit = label.startsWith("用户") ? " 位" : " 条";
        String extra = "";
        if (data.has("pending") && data.optInt("pending") > 0) {
            extra = "（命中 " + data.optInt("matched") + " · 待判定 " + data.optInt("pending") + "）";
        }
        LinearLayout row = horizontal();
        row.setPadding(dp(16), dp(8), dp(16), dp(6));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = text(label + " · " + data.optInt("total") + unit + extra, 13, true);
        count.setTextColor(secondary);
        row.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        if (taskFilter > 0 && dataKind == DATA_COMMENTS) {
            TextView backfill = linkText("补采本任务资料", () -> backfillTaskProfiles(taskFilter));
            row.addView(backfill);
        }
        (dataContent != null ? dataContent : body).addView(row);
    }

    private void backfillTaskProfiles(long taskId) {
        try {
            JSONObject res = api("profile_queue_add", new JSONObject().put("taskId", taskId).put("backfillTask", true));
            int added = res.optInt("added");
            Toast.makeText(activity, added > 0 ? "已将 " + added + " 位待采集用户加入队列" : "该任务所有用户资料已采集", Toast.LENGTH_SHORT).show();
            render();
        } catch (Exception error) { error(error); }
    }

    private void comments() throws Exception {
        JSONObject query = new JSONObject(filters.toString()).put("offset", offset).put("limit", 20).put("platform", scope());
        if (taskFilter > 0) query.put("taskId", taskFilter);
        if (!userFilter.isEmpty()) query.put("userId", userFilter);
        if (!postFilter.isEmpty()) query.put("photoId", postFilter);
        if (!search.isEmpty()) query.put("search", search);
        JSONObject data = api("comments", query);
        String label = taskFilter > 0 ? "任务 #" + taskFilter + " 的评论" : userFilter.isEmpty() ? "评论" : "该用户的评论";
        countLine(label, data);
        LinearLayout page = scrolling(); JSONArray items = data.getJSONArray("items");
        if (items.length() == 0) page.addView(empty("当前条件下暂无评论"));
        for (int i = 0; i < items.length(); i++) {
            LinearLayout card = card(page);
            JSONObject row = items.getJSONObject(i), user = row.optJSONObject("user");
            String uid = row.optString("userId");
            String name = row.optString("nickname");
            if (name.isEmpty() && user != null) name = user.optString("nickname");
            if (name.isEmpty()) name = uid;
            if (name.isEmpty() || "null".equals(name)) name = "未知用户";
            boolean reply = row.optInt("level") == 2;
            if (reply) card.setPadding(dp(30), dp(14), dp(16), dp(14));
            LinearLayout line = horizontal();
            line.setGravity(Gravity.CENTER_VERTICAL);

            FrameLayout avatarBox = new FrameLayout(uiContext);
            ImageView avatar = thumbnail(row, "avatarUrl", "userAvatarUrl");
            FrameLayout.LayoutParams avParams = new FrameLayout.LayoutParams(dp(36), dp(36));
            avParams.gravity = Gravity.CENTER;
            avatarBox.addView(avatar, avParams);
            final String finalUid = uid, finalName = name;
            final JSONObject finalRow = row, finalUser = user;
            Runnable openUser = () -> {
                if (finalUid.isEmpty() || "null".equals(finalUid)) {
                    Toast.makeText(activity, "该评论未记录用户信息，请更新抓取", Toast.LENGTH_SHORT).show();
                } else {
                    userDetail(finalUid, finalName);
                }
            };
            Motion.press(avatarBox);
            avatarBox.setOnClickListener(view -> openUser.run());
            line.addView(avatarBox, new LinearLayout.LayoutParams(dp(44), dp(44)));

            LinearLayout authorBox = vertical();
            authorBox.setPadding(dp(8), 0, 0, 0);
            LinearLayout authorLine = horizontal();
            authorLine.setGravity(Gravity.CENTER_VERTICAL);
            TextView author = text(name, 15, true);
            Motion.press(author);
            author.setOnClickListener(view -> openUser.run());
            authorLine.addView(author);

            boolean pending = row.optBoolean("profilePending");
            TextView genderBadge;
            if (pending) {
                genderBadge = text("待采集", 11, true);
                genderBadge.setTextColor(secondary);
                genderBadge.setBackground(round(surfaceVariant, 8));
            } else {
                String g = row.optString("gender", user == null ? "unknown" : user.optString("gender", "unknown"));
                String gText = "male".equals(g) ? "男" : "female".equals(g) ? "女" : "未知";
                genderBadge = text(gText, 11, true);
                genderBadge.setTextColor("male".equals(g) ? primary : "female".equals(g) ? errorColor : secondary);
                genderBadge.setBackground(round(surfaceVariant, 8));
            }
            genderBadge.setPadding(dp(6), dp(1), dp(6), dp(1));
            LinearLayout.LayoutParams gbParams = new LinearLayout.LayoutParams(-2, -2);
            gbParams.leftMargin = dp(8);
            authorLine.addView(genderBadge, gbParams);
            authorBox.addView(authorLine);
            line.addView(authorBox, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(line);

            String commentIp = row.optString("ipRegion");
            if (commentIp.isEmpty() || "null".equals(commentIp)) commentIp = "未知";
            String profileIp = user != null ? user.optString("profileIpRegion") : "";
            if (profileIp.isEmpty()) profileIp = row.optString("profileIpRegion");
            String mainIp = pending ? "待采集" : profileIp.isEmpty() || "null".equals(profileIp) ? "未知" : profileIp;
            card.addView(muted((reply ? "↳ 回复" : "评论") + " · 评论 IP " + commentIp + " · 主页 IP " + mainIp + " · " + date(row.optLong("created")), 12));

            String commentText = row.optString("text");
            TextView content = text(commentText, 15, false);
            content.setPadding(0, dp(6), 0, dp(6));
            content.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("评论内容", commentText));
                Toast.makeText(activity, "已复制评论内容", Toast.LENGTH_SHORT).show();
                return true;
            });
            card.addView(content);

            Motion.press(card);
            card.setOnClickListener(v -> showCommentDetails(finalRow, finalUser, finalName));

            LinearLayout foot = horizontal();
            foot.addView(muted("赞 " + number(row, "likes") + " · 回复 " + number(row, "replies"), 12), new LinearLayout.LayoutParams(0, -2, 1));
            foot.addView(linkText("帖子", () -> openPost(finalRow.optString("photoId"))));
            foot.addView(linkText("TA 的评论", () -> { userFilter = finalUid; userFilterName = finalName; offset = 0; render(); }));
            card.addView(foot);
        }
        pageControls(data);
    }

    private void showCommentDetails(JSONObject row, JSONObject user, String name) {
        String uid = row.optString("userId");
        String text = row.optString("text");
        String photoId = row.optString("photoId");
        String cid = row.optString("commentId");
        long taskId = row.optLong("taskId", taskFilter);
        boolean pending = row.optBoolean("profilePending");
        String commentIp = row.optString("ipRegion");
        if (commentIp.isEmpty() || "null".equals(commentIp)) commentIp = "未知";
        String profileIp = user != null ? user.optString("profileIpRegion") : "";
        if (profileIp.isEmpty()) profileIp = row.optString("profileIpRegion");
        String mainIp = pending ? "待采集" : profileIp.isEmpty() || "null".equals(profileIp) ? "未知" : profileIp;
        String g = row.optString("gender", user == null ? "unknown" : user.optString("gender", "unknown"));
        String gText = pending ? "待采集" : "male".equals(g) ? "男" : "female".equals(g) ? "女" : "未知";

        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(10), dp(20), dp(10));

        TextView textMsg = new TextView(uiContext);
        textMsg.setText(text);
        textMsg.setTextSize(16);
        textMsg.setTextColor(sc.onSurface);
        textMsg.setTextIsSelectable(true);
        textMsg.setPadding(0, 0, 0, dp(12));
        box.addView(textMsg);

        LinearLayout info = vertical();
        info.setBackground(round(surfaceVariant, 10));
        info.setPadding(dp(12), dp(10), dp(12), dp(10));
        info.addView(text("用户: " + name + (uid.isEmpty() ? "" : " (" + uid + ")"), 13, true));
        info.addView(muted("性别: " + gText + " · 评论 IP: " + commentIp + " · 主页 IP: " + mainIp, 12));
        info.addView(muted("时间: " + date(row.optLong("created")) + " · 赞: " + number(row, "likes") + " · 回复: " + number(row, "replies"), 12));
        if (!cid.isEmpty()) info.addView(muted("评论 ID: " + cid, 11));
        box.addView(info);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);

        AlertDialog.Builder builder = alert().setTitle("评论详情").setView(scroll)
            .setNeutralButton("复制评论", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("评论内容", text));
                Toast.makeText(activity, "已复制评论", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("关闭", null);

        if (!uid.isEmpty() && !"null".equals(uid)) {
            builder.setPositiveButton("立即采集资料", (d, w) -> {
                try {
                    api("profile_queue_add", new JSONObject().put("taskId", taskId).put("prioritizeUser", uid).put("secUid", row.optString("secUid")));
                    Toast.makeText(activity, "已加入优先资料采集", Toast.LENGTH_SHORT).show();
                    render();
                } catch (Exception err) { error(err); }
            });
        }
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void posts() throws Exception {
        JSONObject query = new JSONObject(filters.toString()).put("offset", offset).put("limit", 30).put("platform", scope());
        if (!search.isEmpty()) query.put("search", search);
        JSONObject data = api("posts", query);
        JSONArray items = data.getJSONArray("items");
        LinearLayout countRow = horizontal(); countRow.setPadding(dp(16), dp(4), dp(8), dp(4));
        TextView count = text("帖子（刷到和发现的）· " + data.optInt("total") + " 个", 13, true); count.setTextColor(secondary);
        countRow.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        List<String> pageIds = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) pageIds.add(items.getJSONObject(i).optString("postId"));
        boolean allSelected = !pageIds.isEmpty() && selectedPosts.containsAll(pageIds);
        countRow.addView(linkText(allSelected ? "取消全选" : "全选本页", () -> { if (allSelected) selectedPosts.removeAll(pageIds); else selectedPosts.addAll(pageIds); render(); }));
        (dataContent != null ? dataContent : body).addView(countRow);
        LinearLayout page = scrolling();
        if (items.length() == 0) page.addView(empty("暂无已保存作品"));
        for (int i = 0; i < items.length(); i++) {
            LinearLayout card = card(page);
            JSONObject row = items.getJSONObject(i); String id = row.optString("postId");
            LinearLayout heading = horizontal();
            CheckBox select = tint(new CheckBox(uiContext)); select.setContentDescription("选择作品 " + id); select.setChecked(selectedPosts.contains(id));
            select.setOnCheckedChangeListener((view, checked) -> { if (checked) selectedPosts.add(id); else selectedPosts.remove(id); render(); });
            heading.addView(select);
            String rawTitle = row.optString("title");
            TextView title = text(rawTitle.isEmpty() || "null".equals(rawTitle) ? "作品 " + id : rawTitle, 15, true); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(heading);
            String author = row.optString("authorName");
            LinearLayout authorLine = horizontal();
            authorLine.addView(thumbnail(row, "authorAvatarUrl", "avatarUrl"), new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView authorText = muted((author.isEmpty() || "null".equals(author) ? "" : author + " · ") + "赞 " + number(row, "likes") + " · 评论 " + number(row, "commentTotal") + " · 收藏 " + number(row, "favorites"), 12);
            authorText.setPadding(dp(8), 0, 0, 0); authorLine.addView(authorText, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(authorLine);
            LinearLayout foot = horizontal(); foot.setPadding(0, dp(6), 0, 0);
            foot.addView(muted("快照 " + date(row.optLong("snapshotAt")), 12), new LinearLayout.LayoutParams(0, -2, 1));
            foot.addView(linkText("评论", () -> { postFilter = id; dataKind = DATA_COMMENTS; offset = 0; render(); }));
            foot.addView(linkText("打开", () -> openPost(id)));
            foot.addView(linkText("采集", () -> collectNow(id, true)));
            card.addView(foot);
        }
        pageControls(data);
    }

    private void users() throws Exception {
        JSONObject query = new JSONObject(filters.toString()).put("offset", offset).put("limit", 30).put("platform", scope());
        query.remove("keyword"); // comment keyword does not apply to nicknames
        if (!search.isEmpty()) query.put("search", search);
        JSONObject data = api("users", query);
        countLine("用户", data);
        if (!selectedUsers.isEmpty()) {
            Button fill = primary("补充选中 " + selectedUsers.size() + " 位用户的主页资料", () -> profileTask(new JSONArray(selectedUsers)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(40)); params.setMargins(dp(12), 0, dp(12), dp(4));
            (dataContent != null ? dataContent : body).addView(fill, params);
        }
        LinearLayout page = scrolling(); JSONArray items = data.getJSONArray("items");
        if (items.length() == 0) page.addView(empty("暂无已保存用户"));
        for (int i = 0; i < items.length(); i++) {
            LinearLayout card = card(page);
            JSONObject user = items.getJSONObject(i); String id = user.optString("userId");
            LinearLayout heading = horizontal();
            CheckBox select = tint(new CheckBox(uiContext)); select.setContentDescription("选择用户 " + id); select.setChecked(selectedUsers.contains(id));
            select.setOnCheckedChangeListener((view, checked) -> { if (checked) selectedUsers.add(id); else selectedUsers.remove(id); render(); });
            heading.addView(select);
            heading.addView(thumbnail(user, "avatarUrl", "authorAvatarUrl"), new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView name = text(user.optString("nickname", id), 15, true); name.setOnClickListener(view -> userDetail(id, user.optString("nickname", id)));
            name.setPadding(dp(10), 0, 0, 0);
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(heading);
            card.addView(muted("性别 " + gender(user.optString("gender")) + " · 年龄 " + number(user, "age") + " · 评论 IP " + number(user, "lastCommentIpRegion"), 12));
            card.addView(muted("主页 IP " + number(user, "profileIpRegion") + " · 所在地 " + number(user, "location"), 12));
            LinearLayout foot = horizontal(); foot.setPadding(0, dp(6), 0, 0);
            foot.addView(muted("评论 " + user.optInt("commentCount") + " 条 · " + user.optInt("postCount") + " 个帖子", 12), new LinearLayout.LayoutParams(0, -2, 1));
            foot.addView(linkText("详情", () -> userDetail(id, user.optString("nickname", id))));
            foot.addView(linkText("补充资料", () -> profileTask(new JSONArray().put(id))));
            card.addView(foot);
        }
        pageControls(data);
    }

    /**
     * One user's public profile fields and every collected comment, grouped by the post it was left on,
     * so the operator can judge context before contacting them manually in Kuaishou.
     */
    private void userDetail(String id, String name) {
        if (id == null || id.isEmpty() || "null".equals(id)) return;
        LinearLayout content = vertical(); content.setPadding(dp(20), dp(4), dp(20), dp(8));
        TextView loading = muted("正在读取…", 13); content.addView(loading);
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * .6f);
        android.app.AlertDialog dialog = alert().setTitle(name == null || name.isEmpty() ? id : name).setView(scroll)
            .setNeutralButton("复制昵称", (d, w) -> {
                ((ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("昵称", name));
                Toast.makeText(activity, "已复制昵称", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("关闭", null)
            .setPositiveButton("打开主页", (d, w) -> openProfile(id)).create();
        dialog.show();
        // Grow with the content, but never past ~60% of the screen.
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int wanted = Math.min(b - t, Math.min(maxHeight, dp(560)));
            ViewGroup.LayoutParams params = scroll.getLayoutParams();
            if (params != null && params.height != wanted) { params.height = wanted; scroll.post(() -> scroll.setLayoutParams(params)); }
        });
        new Thread(() -> {
            try {
                JSONObject userData = api("users", new JSONObject().put("search", id).put("limit", 1));
                JSONArray users = userData.optJSONArray("items");
                JSONObject user = users != null && users.length() > 0 ? users.getJSONObject(0) : new JSONObject();
                JSONArray comments = api("comments", new JSONObject().put("userId", id).put("sort", "created_desc").put("limit", 200)).optJSONArray("items");
                java.util.LinkedHashMap<String, List<JSONObject>> byPost = new java.util.LinkedHashMap<>();
                for (int i = 0; comments != null && i < comments.length(); i++) {
                    JSONObject row = comments.getJSONObject(i);
                    byPost.computeIfAbsent(row.optString("photoId"), key -> new ArrayList<>()).add(row);
                }
                java.util.Map<String, JSONObject> posts = new java.util.HashMap<>();
                for (String postId : byPost.keySet()) {
                    try { JSONArray found = api("posts", new JSONObject().put("search", postId).put("limit", 1)).optJSONArray("items");
                        if (found != null && found.length() > 0) posts.put(postId, found.getJSONObject(0)); } catch (Exception ignored) {}
                }
                handler.post(() -> {
                    content.removeAllViews();
                    content.addView(muted("性别 " + gender(user.optString("gender")) + " · 年龄 " + number(user, "age")
                        + "\n评论 IP " + number(user, "lastCommentIpRegion") + " · 主页 IP " + number(user, "profileIpRegion") + " · 所在地 " + number(user, "location"), 12));
                    TextView total = text("在 " + byPost.size() + " 个帖子下发表了 " + (comments == null ? 0 : comments.length()) + " 条评论", 14, true);
                    total.setPadding(0, dp(10), 0, dp(4)); content.addView(total);
                    for (java.util.Map.Entry<String, List<JSONObject>> entry : byPost.entrySet()) {
                        JSONObject post = posts.get(entry.getKey());
                        String title = post == null ? "" : post.optString("title");
                        LinearLayout block = vertical(); block.setPadding(dp(12), dp(10), dp(12), dp(10)); block.setBackground(round(surfaceVariant, 12));
                        LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(-1, -2); blockParams.topMargin = dp(8);
                        TextView postTitle = text(title.isEmpty() || "null".equals(title) ? "作品 " + entry.getKey() : title, 13, true);
                        postTitle.setMaxLines(2); postTitle.setEllipsize(TextUtils.TruncateAt.END); postTitle.setTextColor(primary);
                        postTitle.setOnClickListener(view -> { dialog.dismiss(); openPost(entry.getKey()); });
                        block.addView(postTitle);
                        for (JSONObject row : entry.getValue()) {
                            TextView said = text(row.optString("text"), 14, false); said.setTextIsSelectable(true); said.setPadding(0, dp(6), 0, 0); block.addView(said);
                            block.addView(muted(date(row.optLong("created")) + " · 赞 " + number(row, "likes") + (row.optInt("level") == 2 ? " · 回复" : ""), 11));
                        }
                        content.addView(block, blockParams);
                    }
                    Motion.stagger(content, 0);
                });
            } catch (Exception error) { handler.post(() -> loading.setText("读取失败：" + error.getMessage())); }
        }, "collector-user-detail").start();
    }

    // ---- 分析

    private void analysisPage() {
        LinearLayout top = vertical(); top.setPadding(dp(12), dp(2), dp(12), dp(4));
        top.addView(conditionChips(false)); body.addView(top);
        LinearLayout list = scrolling(); TextView pending = muted("正在计算…", 14); list.addView(pending);
        new Thread(() -> {
            try { JSONObject data = api("analysis", new JSONObject(filters.toString())); handler.post(() -> {
                if (page != PAGE_ANALYSIS || activity.isFinishing()) return; list.removeAllViews();
                list.addView(text("已分析 " + data.optInt("commentsAnalyzed") + " 条公开评论" + (data.optBoolean("truncated") ? " · 部分结果" : ""), 14, true));
                JSONArray items = data.optJSONArray("items");
                if (items == null || items.length() == 0) list.addView(empty("当前条件下暂无结果"));
                else for (int i = 0; i < items.length(); i++) {
                    JSONObject user = items.optJSONObject(i); String uid = user.optString("userId"); LinearLayout card = card(list);
                    LinearLayout line = horizontal();
                    TextView rank = text(String.valueOf(i + 1), 13, true); rank.setTextColor(primary); rank.setMinWidth(dp(24)); line.addView(rank);
                    TextView name = text(user.optString("nickname", uid), 15, true); name.setOnClickListener(view -> userDetail(uid, user.optString("nickname", uid)));
                    line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                    line.addView(linkText("评论", () -> { page = PAGE_DATA; dataKind = DATA_COMMENTS; userFilter = uid; userFilterName = user.optString("nickname", uid); offset = 0; render(); }));
                    card.addView(line);
                    card.addView(muted("评论 " + user.optInt("commentCount") + " 条 · " + user.optInt("postCount") + " 个帖子 · 获赞 " + user.optLong("likesReceived") + " · 回复 " + user.optLong("replyCount"), 12));
                    card.addView(muted(date(user.optLong("firstCommentAt")) + " 至 " + date(user.optLong("lastCommentAt")), 12));
                }
                Motion.stagger(list, 0);
            }); } catch (Exception error) { handler.post(() -> pending.setText("计算失败：" + error.getMessage())); }
        }, "collector-analysis").start();
    }

    // ---- 我的

    private void mePage() throws Exception {
        LinearLayout list = scrolling();
        JSONObject config = new JSONObject();
        try { config = CloudAccountClient.config(activity); } catch (Exception ignored) {}
        boolean signedIn = !config.optString("accessToken").isEmpty();
        LinearLayout group = card(list); group.setPadding(0, dp(4), 0, dp(4));
        menuRow(group, android.R.drawable.ic_menu_myplaces, "登录账户", signedIn ? (config.optString("email") + " · " + syncStateText(config)) : "未登录 · 登录后与电脑端同步", this::accountSettings);
        menuRow(group, android.R.drawable.ic_menu_info_details, "视频信息设置", "双击帖子弹出的信息与下载按钮", this::videoInfoSettings);
        menuRow(group, android.R.drawable.ic_menu_preferences, "本机接口", "127.0.0.1:" + LocalApi.PORT + " · 接口密钥、导出任务", this::apiSettings);
        LinearLayout group2 = card(list); group2.setPadding(0, dp(4), 0, dp(4));
        menuRow(group2, android.R.drawable.ic_menu_recent_history, "全部任务", "查看、续跑或诊断所有任务", () -> { page = PAGE_COLLECT; allTasks = true; offset = 0; render(); });
        menuRow(group2, android.R.drawable.ic_popup_sync, "唤醒采集服务", "数据库读取失败时使用", this::wakeCollector);
        String runtimeText = "";
        try { JSONObject runtime = api("runtime", new JSONObject()); runtimeText = "模块 " + runtime.optString("installedVersion") + " · " + hostName() + " " + runtime.optString("appVersion", ""); } catch (Exception ignored) {}
        TextView about = muted((runtimeText.isEmpty() ? "" : runtimeText + "\n") + "构建 " + BuildInfo.ID, 11);
        about.setPadding(dp(8), dp(12), dp(8), 0); list.addView(about);
    }

    private void menuRow(LinearLayout parent, int iconRes, String title, String subtitle, Runnable action) {
        LinearLayout row = horizontal(); row.setPadding(dp(16), dp(12), dp(12), dp(12)); row.setMinimumHeight(dp(60));
        ImageView image = new ImageView(activity); image.setImageResource(iconRes); image.setImageTintList(ColorStateList.valueOf(primary));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24)); iconParams.rightMargin = dp(14); row.addView(image, iconParams);
        LinearLayout texts = vertical(); texts.addView(text(title, 15, true));
        TextView sub = muted(subtitle, 12); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END); texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = muted("›", 20); row.addView(chevron);
        row.setOnClickListener(view -> action.run()); Motion.press(row);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------------------------------------------------------------- conditions

    /** Chips summarising the active conditions; tap a chip's × to clear it, "条件" opens the editor. */
    private View conditionChips(boolean forCollect) {
        Flow flow = new Flow(activity, dp(6));
        if (!forCollect) {
            if (dataKind == DATA_COMMENTS && taskFilter > 0) {
                flow.addView(chip("任务 #" + taskFilter, true, () -> { taskFilter = 0; offset = 0; render(); }));
            }
            if (!userFilter.isEmpty()) flow.addView(chip("用户：" + (userFilterName.isEmpty() ? userFilter : userFilterName), true, () -> { userFilter = ""; userFilterName = ""; offset = 0; render(); }));
            if (!postFilter.isEmpty()) flow.addView(chip("帖子：" + postFilter, true, () -> { postFilter = ""; offset = 0; render(); }));
            if (!search.isEmpty()) flow.addView(chip("搜索：" + search, true, () -> { search = ""; offset = 0; render(); }));
        }
        String[][] parts = conditionParts(filters, forCollect);
        for (String[] part : parts) flow.addView(chip(part[1], true, () -> { filters.remove(part[0]); if ("ipRegion".equals(part[0])) { filters.remove("relation"); filters.remove("ipSource"); } saveFilters(); render(); }));
        flow.addView(chip(parts.length == 0 ? "+ 添加条件" : "编辑条件", false, () -> filterSheet(forCollect)));
        return flow;
    }

    /** [key, label] pairs for the non-default conditions. */
    private String[][] conditionParts(JSONObject f, boolean forCollect) {
        List<String[]> out = new ArrayList<>();
        if (!f.optString("keyword").isEmpty()) out.add(new String[]{"keyword", "[初筛] 含 " + f.optString("keyword")});
        if (!f.optString("excludeKeyword").isEmpty()) out.add(new String[]{"excludeKeyword", "[初筛] 排除 " + f.optString("excludeKeyword")});
        if (f.optInt("recentDays") > 0) out.add(new String[]{"recentDays", "[初筛] 近 " + f.optInt("recentDays") + " 天"});
        if (!f.optString("ipRegion").isEmpty()) out.add(new String[]{"ipRegion", "[补充] " + ("or".equals(f.optString("relation")) && !f.optString("keyword").isEmpty() ? "或 " : "") + ipSourceName(f.optString("ipSource", "profile")) + " " + f.optString("ipRegion")});
        String g = f.optString("gender", "all");
        if (!"all".equals(g)) out.add(new String[]{"gender", "[补充] 性别: " + genderFilterName(g)});
        if (f.has("minAge") || f.has("maxAge")) out.add(new String[]{"minAge", "[补充] " + (f.has("minAge") ? f.optString("minAge") : "") + "–" + (f.has("maxAge") ? f.optString("maxAge") : "") + " 岁"});
        if (forCollect) {
            if (f.optInt("targetCount") > 0) out.add(new String[]{"targetCount", "目标 " + f.optInt("targetCount") + " 条"});
            if (f.optInt("maxPages") > 0) out.add(new String[]{"maxPages", "上限 " + f.optInt("maxPages") + " 页"});
            if (!"auto".equals(f.optString("pagingMode", "auto"))) out.add(new String[]{"pagingMode", pagingMode(f.optString("pagingMode"))});
        } else if (!"captured_desc".equals(f.optString("sort", "captured_desc"))) out.add(new String[]{"sort", sortName(f.optString("sort"))});
        return out.toArray(new String[0][]);
    }

    private String describe(JSONObject f, boolean forCollect) {
        StringBuilder out = new StringBuilder();
        for (String[] part : conditionParts(f, forCollect)) { if (out.length() > 0) out.append(" · "); out.append(part[1]); }
        return out.toString();
    }

    private void saveFilters() {
        try { api("settings_save", new JSONObject().put("name", "filters").put("value", filters)); offset = 0; }
        catch (Exception error) { error(error); }
    }

    /** Condition editor shared by data views and collection tasks (desktop field set). */
    private void filterSheet(boolean forCollect) {
        LinearLayout fields = vertical(); fields.setPadding(dp(20), dp(4), dp(20), dp(8));

        // Block 1: ① 初次筛选（评论直接可用）
        sectionBlock(fields, "① 初次筛选（评论直接可用）", "抓取评论时实时初筛；只有满足初筛的评论，才会进入二次主页采集");
        EditText keyword = input("包含关键词，多个用逗号分隔", filters.optString("keyword"), false); fields.addView(keyword);
        EditText exclude = input("排除关键词，多个用逗号分隔", filters.optString("excludeKeyword"), false); fields.addView(exclude);
        EditText days = input("只看最近多少天内（空为不限）", filters.optInt("recentDays") > 0 ? String.valueOf(filters.optInt("recentDays")) : "", true); fields.addView(days);
        int[] relation = {"or".equals(filters.optString("relation")) ? 1 : 0};
        fields.addView(segmented(new String[]{"同时满足 (AND)", "满足其一 (OR)"}, relation[0], index -> relation[0] = index));
        fields.addView(muted("评论关键词和地区的关系：同时满足两个条件，或满足任意一个", 11));

        // Block 2: ② 二次筛选（补充主页资料后）
        sectionBlock(fields, "② 二次筛选（补充主页资料后）", "初筛命中后，自动调取主页资料补充头像、现在主页IP、发帖地点及年龄");
        EditText region = input("IP 属地（北京、河北）", filters.optString("ipRegion"), false); fields.addView(region);
        String[] ipSources = {"profile", "comment", "post"};
        int[] ipSource = {0}; for (int i = 0; i < ipSources.length; i++) if (ipSources[i].equals(filters.optString("ipSource", "profile"))) ipSource[0] = i;
        fields.addView(segmented(new String[]{"主页 IP", "评论 IP", "最新发帖地点"}, ipSource[0], index -> ipSource[0] = index));
        fields.addView(muted("按哪个 IP 判断地区；最新发帖地点在抖音精确到省+市，小红书为省IP", 11));

        String[] genderKeys = {"female", "male", "unknown"};
        boolean[] genderOn = new boolean[3];
        String savedGender = filters.optString("gender", "all");
        if ("female-or-unknown".equals(savedGender)) savedGender = "female,unknown";
        for (int i = 0; i < 3; i++) genderOn[i] = ("," + savedGender + ",").contains("," + genderKeys[i] + ",");
        fields.addView(multiChips(new String[]{"女", "男", "未知"}, genderOn));
        fields.addView(muted("可多选；都不选表示不限性别", 11));

        LinearLayout ages = horizontal();
        EditText min = input("年龄下限", filters.has("minAge") ? filters.optString("minAge") : "", true);
        EditText max = input("年龄上限", filters.has("maxAge") ? filters.optString("maxAge") : "", true);
        ages.addView(min, new LinearLayout.LayoutParams(0, dp(52), 1)); ages.addView(max, new LinearLayout.LayoutParams(0, dp(52), 1)); fields.addView(ages);

        // Block 3: 采集与排序
        sectionBlock(fields, "③ 采集与排序", forCollect ? "控制翻页上限与采集模式" : "数据列表排序规则");
        EditText target = null, pages = null;
        String[] pagingKeys = {"auto", "native", "ui"};
        int[] pagingIndex = {0};
        if (forCollect) {
            target = input("目标评论数，达到后停止（空为全部）", filters.optInt("targetCount") > 0 ? String.valueOf(filters.optInt("targetCount")) : "", true); fields.addView(target);
            pages = input("最多读取页数（空为全部）", filters.optInt("maxPages") > 0 ? String.valueOf(filters.optInt("maxPages")) : "", true); fields.addView(pages);
            for (int i = 0; i < pagingKeys.length; i++) if (pagingKeys[i].equals(filters.optString("pagingMode", "auto"))) pagingIndex[0] = i;
            fields.addView(segmented(new String[]{"自动", "原生接口", "页面滚动"}, pagingIndex[0], index -> pagingIndex[0] = index));
        }
        String[] sortKeys = {"captured_desc", "created_desc", "created_asc", "likes_desc", "captured_asc"};
        Spinner sorts = new Spinner(uiContext);
        sorts.setAdapter(new ArrayAdapter<>(uiContext, android.R.layout.simple_spinner_dropdown_item, new String[]{"最新抓取", "最新评论", "最早评论", "点赞最多", "最早抓取"}));
        sorts.setMinimumHeight(dp(48)); fields.addView(sorts);
        for (int i = 0; i < sortKeys.length; i++) if (sortKeys[i].equals(filters.optString("sort", "captured_desc"))) sorts.setSelection(i);

        final EditText fTarget = target, fPages = pages;
        ScrollView scroll = new ScrollView(activity); scroll.addView(fields);
        alert().setTitle(forCollect ? "采集条件" : "筛选条件").setView(scroll)
            .setNeutralButton("重置", (dialog, which) -> { filters = new JSONObject(); saveFilters(); render(); })
            .setNegativeButton("取消", null)
            .setPositiveButton("应用", (dialog, which) -> {
                try {
                    JSONObject next = new JSONObject()
                        .put("keyword", keyword.getText().toString().trim()).put("excludeKeyword", exclude.getText().toString().trim())
                        .put("ipRegion", region.getText().toString().trim()).put("ipSource", ipSources[ipSource[0]]).put("relation", relation[0] == 1 ? "or" : "and")
                        .put("gender", genderValue(genderKeys, genderOn)).put("sort", sortKeys[sorts.getSelectedItemPosition()])
                        .put("pagingMode", pagingKeys[pagingIndex[0]]);
                    putNumber(next, "minAge", min); putNumber(next, "maxAge", max);
                    putNumber(next, "recentDays", days);
                    if (fTarget != null) putNumber(next, "targetCount", fTarget);
                    else if (filters.has("targetCount")) next.put("targetCount", filters.get("targetCount"));
                    if (fPages != null) putNumber(next, "maxPages", fPages);
                    else if (filters.has("maxPages")) next.put("maxPages", filters.get("maxPages"));
                    filters = next; saveFilters(); render();
                } catch (Exception error) { error(error); }
            }).show();
    }

    private static void putNumber(JSONObject target, String key, EditText field) throws Exception {
        String value = field.getText().toString().trim();
        if (!value.isEmpty()) { int number = Integer.parseInt(value); if (number > 0) target.put(key, number); }
    }

    private void section(LinearLayout parent, String title) { sectionBlock(parent, title, null); }
    private void sectionBlock(LinearLayout parent, String title, String subtitle) {
        TextView label = text(title, 14, true); label.setTextColor(primary); label.setPadding(0, dp(14), 0, dp(2)); parent.addView(label);
        if (subtitle != null && !subtitle.isEmpty()) { TextView sub = muted(subtitle, 11); sub.setPadding(0, 0, 0, dp(4)); parent.addView(sub); }
    }

    // ---------------------------------------------------------------- task creation

    private void collectNow(String id, boolean enqueue) {
        if (id == null || id.isEmpty()) { error(new IllegalStateException("还没有识别到当前帖子")); return; }
        try {
            JSONObject params = new JSONObject(filters.toString()).put("photoId", id).put("kind", "comment").put("auto", true).put("enqueue", enqueue).put("platform", hostPlatform());
            JSONObject result = api("start", params);
            boolean isNative = "douyin".equals(hostPlatform()) || result.optString("pagingDriver").startsWith("native");
            if (isNative) {
                Toast.makeText(activity, result.optBoolean("duplicate") ? "该帖子已有任务 #" + result.optLong("taskId") : "已开始原生接口采集", Toast.LENGTH_SHORT).show();
                render();
            } else {
                Toast.makeText(activity, result.optBoolean("duplicate") ? "该帖子已有任务 #" + result.optLong("taskId") : "已开始采集，返回" + hostName() + "继续", Toast.LENGTH_SHORT).show();
                closePanel.run();
            }
        } catch (Exception error) { error(error); }
    }

    private static final Pattern POST_ID = Pattern.compile("(?:short-video/|photo/|photoId=|work/|explore/|discovery/item/|item/|video/)([A-Za-z0-9_-]{6,40})");

    /** Paste several links or IDs (one per line or space-separated) to queue them with the current conditions. */
    private void batchDialog() {
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), 0);
        TextView note = muted("每行一个" + hostName() + "帖子链接或作品 ID；会按当前采集条件依次排队。", 12); note.setPadding(0, 0, 0, dp(8)); fields.addView(note);
        EditText links = input("粘贴链接或 ID", "", false); links.setSingleLine(false); links.setMinLines(4); links.setMaxLines(8); links.setGravity(Gravity.TOP);
        links.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && !parseIds(clip.toString()).isEmpty()) links.setText(clip);
            }
        } catch (RuntimeException ignored) {}
        fields.addView(links);
        alert().setTitle("批量添加帖子").setView(fields).setNegativeButton("取消", null).setPositiveButton("加入队列", (dialog, which) -> {
            List<String> ids = parseIds(links.getText().toString());
            if (ids.isEmpty()) { error(new IllegalStateException("没有识别到帖子 ID。短链接请先在" + hostName() + "打开后复制完整链接。")); return; }
            batchStart(ids);
        }).show();
    }

    static List<String> parseIds(String text) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String token : text.split("[\\s,，;；]+")) {
            if (token.isEmpty()) continue;
            Matcher matcher = POST_ID.matcher(token);
            if (matcher.find()) ids.add(matcher.group(1));
            else if (token.matches("[0-9]{8,25}|[0-9a-fA-F]{24}")) ids.add(token);
            if (ids.size() >= 100) break;
        }
        return new ArrayList<>(ids);
    }

    private void batchStart(List<String> ids) {
        try {
            JSONArray tasks = new JSONArray();
            for (String id : ids) tasks.put(new JSONObject(filters.toString()).put("photoId", id).put("kind", "comment").put("auto", true).put("enqueue", true).put("platform", hostPlatform()));
            api("batch_start", new JSONObject().put("tasks", tasks));
            Toast.makeText(activity, "已加入 " + ids.size() + " 个帖子", Toast.LENGTH_SHORT).show();
            selectedPosts.clear(); page = PAGE_COLLECT; render();
        } catch (Exception error) { error(error); }
    }

    private void profileTask(JSONArray users) {
        if (users.length() == 0) { error(new IllegalStateException("请先选择用户")); return; }
        try {
            if (taskFilter > 0) {
                JSONObject res = api("profile_queue_add", new JSONObject().put("taskId", taskFilter).put("userIds", users));
                int added = res.optInt("added");
                Toast.makeText(activity, "已加入资料采集队列（" + added + " 位）", Toast.LENGTH_SHORT).show();
                render();
                return;
            }
            api("start", new JSONObject().put("kind", "profile").put("userIds", users).put("auto", true).put("enqueue", true).put("platform", hostPlatform()));
            boolean isNative = "douyin".equals(hostPlatform());
            if (isNative) {
                Toast.makeText(activity, "已开始资料采集", Toast.LENGTH_SHORT).show();
                render();
            } else {
                closePanel.run();
            }
        }
        catch (Exception error) { error(error); }
    }

    private void discoveryDialog() {
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), 0);
        EditText keyword = input("搜索关键词", "", false), count = input("目标数量", "20", true), pages = input("最多加载页数", "20", true);
        fields.addView(keyword); fields.addView(count); fields.addView(pages);
        alert().setTitle("发现帖子").setView(fields).setNegativeButton("取消", null).setPositiveButton("开始发现", (dialog, which) -> {
            try { api("start", new JSONObject().put("kind", "discovery").put("keyword", keyword.getText().toString().trim())
                .put("target", Integer.parseInt(count.getText().toString())).put("maxPages", Integer.parseInt(pages.getText().toString())).put("auto", true).put("enqueue", true).put("platform", hostPlatform())); closePanel.run(); }
            catch (Exception error) { error(error); }
        }).show();
    }

    private void deleteTask(long id) {
        try {
            api("delete_task", new JSONObject().put("taskId", id));
            if (taskFilter == id) taskFilter = 0;
            Toast.makeText(activity, "已删除任务 #" + id, Toast.LENGTH_SHORT).show();
            render();
        } catch (Exception error) { error(error); }
    }

    private void taskAction(String operation, long id) {
        try {
            JSONObject updated = api(operation, new JSONObject().put("taskId", id));
            if (("resume".equals(operation) || "retry".equals(operation) || "refresh_task".equals(operation)) && "queued".equals(updated.optString("status"))) {
                Toast.makeText(activity, "refresh_task".equals(operation) ? "已加入排队，当前任务完成后自动更新抓取" : "已加入排队，当前任务完成后自动续跑", Toast.LENGTH_SHORT).show();
                render();
                return;
            }
            if (("resume".equals(operation) || "retry".equals(operation) || "refresh_task".equals(operation)) && showing()) {
                if ("refresh_task".equals(operation)) Toast.makeText(activity, "已开始更新抓取", Toast.LENGTH_SHORT).show();
                boolean isNative = "douyin".equals(hostPlatform()) || updated.optString("pagingDriver").startsWith("native");
                if (isNative) {
                    render();
                } else {
                    String targetPhoto = updated.optString("photoId");
                    String currentPhoto = photo.get();
                    if (currentPhoto == null) currentPhoto = "";
                    if ("comment".equals(updated.optString("kind")) && !targetPhoto.isEmpty() && !targetPhoto.equals(currentPhoto)) {
                        openPost(targetPhoto);
                    } else {
                        open.get().dismiss();
                    }
                }
            } else render();
        } catch (Exception error) { error(error); }
    }

    // ---------------------------------------------------------------- settings dialogs (unchanged behaviour)

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
        actions.addView(login, new LinearLayout.LayoutParams(-1, dp(44))); actions.addView(sync, new LinearLayout.LayoutParams(-1, dp(44))); actions.addView(logout, new LinearLayout.LayoutParams(-1, dp(44))); fields.addView(actions);
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
        alert().setTitle("登录账户").setView(scroll).setNegativeButton("关闭", (dialog, which) -> render()).show();
    }
    private String syncStateText(JSONObject config) {
        long last = config.optLong("lastSyncAt"); String error = config.optString("lastSyncError");
        if (!error.isEmpty()) return "上次同步失败：" + error;
        return last > 0 ? "上次同步：" + date(last) : "尚未同步本机数据";
    }

    private void videoInfoSettings() {
        JSONObject saved = VideoInfoSettings.load(activity);
        LinearLayout fields = vertical(); fields.setPadding(dp(20), 0, dp(20), dp(12));
        TextView note = text("控制 comso 模块悬浮按钮与双击作品信息面板的显示项。", 13, false);
        note.setTextColor(secondary); note.setPadding(0, 0, 0, dp(10)); fields.addView(note);
        String[] keys = {"floating_button", "double_tap_info", "location", "publish_time", "duration", "statistics", "video", "cover", "current_image", "music", "description", "copy_description"};
        String[] labels = {"显示侧边悬浮采集按钮", "双击作品弹出信息面板", "发布地址 / IP 属地", "发布时间", "视频时长", "点赞 / 评论 / 收藏 / 分享", "下载视频 / 图片", "封面图片", "当前图片（暂未支持）", "音乐信息与下载", "作品文案", "复制文案按钮"};
        Switch[] switches = new Switch[keys.length];
        for (int i = 0; i < keys.length; i++) {
            Switch toggle = new Switch(uiContext); toggle.setText(labels[i]); toggle.setTextSize(15); toggle.setTextColor(foreground);
            toggle.setMinHeight(dp(48)); toggle.setChecked(saved.optBoolean(keys[i], !"current_image".equals(keys[i])));
            if ("current_image".equals(keys[i])) toggle.setEnabled(false);
            fields.addView(toggle); switches[i] = toggle;
        }
        TextView modeLabel = text("弹窗位置", 14, true); modeLabel.setPadding(0, dp(12), 0, dp(4)); fields.addView(modeLabel);
        int[] mode = {VideoInfoSettings.centered(saved) ? 1 : 0};
        fields.addView(segmented(new String[]{"底部抽屉", "居中对话框"}, mode[0], index -> mode[0] = index));
        EditText timeFormat = input("时间格式，例如 yyyy-MM-dd HH:mm:ss", saved.optString("time_format", "yyyy-MM-dd HH:mm:ss"), false);
        fields.addView(timeFormat);
        ScrollView scroll = new ScrollView(activity); scroll.addView(fields);
        alert().setTitle("comso 模块设置").setView(scroll).setNegativeButton("取消", null).setNeutralButton("打开采集面板", (dialog, which) -> show(activity, photo, PAGE_COLLECT)).setPositiveButton("保存", (dialog, which) -> {
            try {
                JSONObject value = VideoInfoSettings.defaults();
                for (int i = 0; i < keys.length; i++) value.put(keys[i], switches[i].isChecked());
                value.put("presentation", mode[0] == 1 ? "center" : "bottom");
                String format = timeFormat.getText().toString().trim();
                if (!format.isEmpty()) value.put("time_format", format);
                VideoInfoSettings.save(activity, value);
                syncFloatingButtonVisibility(activity, value.optBoolean("floating_button", true));
                Toast.makeText(activity, "模块设置已保存", Toast.LENGTH_SHORT).show();
            } catch (Exception error) { error(error); }
        }).show();
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
        });
        settings.addView(toggle, new LinearLayout.LayoutParams(-1, dp(44)));
        settings.addView(button("复制接口密钥", () -> {
            try {
                String token = api("api_token", new JSONObject()).getString("token");
                ClipData clip = ClipData.newPlainText("Collector API", token); android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
                ((ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE)).setPrimaryClip(clip);
            } catch (Exception error) { error(error); }
        }), new LinearLayout.LayoutParams(-1, dp(44)));
        settings.addView(button("导出当前任务", () -> { try { api("export", new JSONObject()); } catch (Exception error) { error(error); } }), new LinearLayout.LayoutParams(-1, dp(44)));
        alert().setTitle("本机接口").setView(settings).setPositiveButton("关闭", null).show();
    }

    private void events(long id) {
        try {
            JSONArray events = api("events", new JSONObject().put("taskId", id)).getJSONArray("items"); StringBuilder message = new StringBuilder();
            for (int i = 0; i < events.length(); i++) { JSONObject event = events.getJSONObject(i); message.append(date(event.optLong("time"))).append(" · ").append(event.optString("code")).append('\n'); }
            alert().setTitle("任务 #" + id).setMessage(message.length() == 0 ? "暂无运行记录" : message.toString()).setPositiveButton("关闭", null).show();
        } catch (Exception error) { error(error); }
    }
    private void copyDiagnostics(long id) {
        try {
            JSONObject report = new JSONObject().put("moduleBuild", BuildInfo.ID).put("android", android.os.Build.VERSION.RELEASE)
                .put("appVersion", activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName)
                .put("runtime", api("runtime", new JSONObject()))
                .put("task", api("status", new JSONObject().put("taskId", id)))
                .put("events", api("events", new JSONObject().put("taskId", id)).getJSONArray("items"));
            ((ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("任务诊断", report.toString()));
            Toast.makeText(activity, "已复制任务诊断", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { error(error); }
    }

    // ---------------------------------------------------------------- navigation helpers

    private void openPost(String id) {
        if (id.isEmpty()) return;
        String scheme = switch (hostPlatform()) { case "douyin" -> "snssdk1128://aweme/detail/"; case "xhs" -> "xhsdiscover://item/"; default -> "kwai://work/"; };
        launch(scheme + Uri.encode(id));
    }
    private void openProfile(String id) {
        if (id.isEmpty() || "null".equals(id)) return;
        String scheme = switch (hostPlatform()) { case "douyin" -> "snssdk1128://user/profile/"; case "xhs" -> "xhsdiscover://user/"; default -> "kwai://profile/"; };
        launch(scheme + Uri.encode(id));
    }
    private void launch(String url) {
        try {
            if (showing()) open.get().dismiss();
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(activity.getPackageName()));
            if ("douyin".equals(hostPlatform()) && url.startsWith("snssdk1128://aweme/detail/")) {
                handler.postDelayed(() -> clickCommentButton(activity, 0), 2500);
            }
        } catch (Exception error) { error(error); }
    }
    private static void clickCommentButton(Activity host, int attempt) {
        if (attempt > 6 || host == null || host.isFinishing()) return;
        try {
            View root = host.getWindow().getDecorView();
            View btn = findCommentButton(root);
            if (btn != null && btn.performClick()) return;
        } catch (Throwable ignored) {}
        new Handler(Looper.getMainLooper()).postDelayed(() -> clickCommentButton(host, attempt + 1), 1200);
    }
    private static View findCommentButton(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().contains("评论") && !description.toString().contains("发表") && view.isClickable()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findCommentButton(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    /** Relaunches the collector process via its invisible WakeActivity, then reloads the panel. */
    private void wakeCollector() {
        try {
            activity.startActivity(new Intent().setClassName("dev.local.commentcollector", "dev.local.commentcollector.WakeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            Toast.makeText(activity, "正在唤醒采集服务…", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(activity, "唤醒失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); return;
        }
        handler.postDelayed(this::render, 1200);
        handler.postDelayed(this::render, 3000);
    }

    // ---------------------------------------------------------------- polling

    private void poll() {
        Runnable refresh = new Runnable() {
            @Override public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (dark(activity) != darkModeAtCreate && showing()) {
                    closePanel.run();
                    handler.postDelayed(() -> show(activity, photo), 320);
                    return;
                }
                try {
                    JSONObject counts = api("summary", new JSONObject().put("platform", hostPlatform())), task = api("status", new JSONObject());
                    String summaryText = hostName() + " · 帖子 " + counts.optInt("posts") + " · 评论 " + counts.optInt("comments") + " · 用户 " + counts.optInt("users")
                        + (cloudStatusHint.isEmpty() ? "" : " · " + cloudStatusHint);
                    if (!summaryText.contentEquals(summary.getText())) summary.setText(summaryText);
                    maybeAutoSync(task);
                    if (page == PAGE_COLLECT && !lastTaskSnapshot.equals(task.toString())) {
                        boolean first = lastTaskSnapshot.isEmpty(); lastTaskSnapshot = task.toString();
                        String shaped = shape(task); boolean sameShape = shaped.equals(lastTaskShape); lastTaskShape = shaped;
                        if (!first) { if (sameShape && liveUpdate != null) liveUpdate.accept(task); else render(); }
                    }
                } catch (Exception error) { summary.setText("采集服务不可用 · 可在“我的”中唤醒"); }
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
                catch (Exception error) { cloudStatusHint = "云端同步失败"; }
                finally { autoSyncRunning = false; }
            }, "collector-cloud-auto-sync").start();
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- widgets

    private int dp(int value) { return dp(activity, value); }
    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static boolean dark(Activity activity) {
        UiModeManager mode = activity.getSystemService(UiModeManager.class);
        if (mode != null) {
            try { return (Boolean) UiModeManager.class.getMethod("isNightModeActive").invoke(mode); }
            catch (Throwable ignored) { }
        }
        int systemConfigured = Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int configured = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (mode != null && mode.getNightMode() == UiModeManager.MODE_NIGHT_YES) return true;
        if (mode != null && mode.getNightMode() == UiModeManager.MODE_NIGHT_NO) return false;
        return systemConfigured == Configuration.UI_MODE_NIGHT_YES || configured == Configuration.UI_MODE_NIGHT_YES;
    }
    private android.app.AlertDialog.Builder alert() { return new android.app.AlertDialog.Builder(activity, dark(activity) ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert); }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(activity); view.setText(value); M3.type(view, size, bold, foreground); return view;
    }
    private TextView muted(String value, int size) { TextView view = text(value, size, false); view.setTextColor(secondary); return view; }
    private TextView empty(String value) { TextView view = muted(value, 14); view.setGravity(Gravity.CENTER); view.setPadding(0, dp(40), 0, dp(40)); return view; }
    /** Displays the small avatar URL captured with a post/comment, with a neutral fallback icon. */
    private ImageView thumbnail(JSONObject object, String... keys) {
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(round(surfaceVariant, 18));
        image.setClipToOutline(true);
        image.setImageResource(android.R.drawable.ic_menu_myplaces);
        String url = "";
        for (String key : keys) {
            url = object == null ? "" : object.optString(key, "");
            if (!url.isEmpty() && !"null".equalsIgnoreCase(url)) break;
            url = "";
        }
        if (url.isEmpty() && object != null) {
            JSONObject user = object.optJSONObject("user");
            if (user != null) for (String key : keys) {
                url = user.optString(key, "");
                if (!url.isEmpty() && !"null".equalsIgnoreCase(url)) break;
                url = "";
            }
        }
        if (url.startsWith("http")) PostInfoPanel.loadImage(image, url);
        return image;
    }
    private TextView linkText(String label, Runnable action) {
        TextView view = text(label, 13, true); view.setTextColor(primary); view.setPadding(dp(10), dp(8), dp(4), dp(8));
        view.setOnClickListener(v -> action.run()); Motion.press(view); return view;
    }
    private void sectionTitle(LinearLayout parent, String title) {
        TextView label = text(title, 15, true); label.setPadding(dp(4), dp(14), 0, dp(6)); parent.addView(label);
    }
    private ImageButton icon(M3.Icon glyph, String label, Runnable action) {
        ImageButton button = new ImageButton(activity); button.setImageDrawable(new M3.IconDrawable(glyph, secondary)); button.setContentDescription(label);
        button.setScaleType(ImageView.ScaleType.CENTER); button.setBackground(round(Color.TRANSPARENT, 24));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); button.setOnClickListener(view -> action.run()); Motion.press(button); return button;
    }
    private Button button(String label, Runnable action) { return styledButton(label, action, sc.secondaryContainer, sc.onSecondaryContainer); }
    private Button primary(String label, Runnable action) { return styledButton(label, action, primary, sc.onPrimary); }
    private Button styledButton(String label, Runnable action, int background, int textColor) {
        Button button = new Button(uiContext); button.setText(label); button.setTextSize(M3.LABEL_LARGE); button.setAllCaps(false); button.setMinHeight(dp(40)); button.setMinimumHeight(dp(40)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setTypeface(M3.medium()); button.setTextColor(textColor); button.setBackground(round(background, 20)); button.setLetterSpacing(.01f);
        button.setStateListAnimator(null); button.setPadding(dp(20), 0, dp(20), 0);
        button.setOnClickListener(view -> action.run()); Motion.press(button); return button;
    }
    private LinearLayout.LayoutParams weighted(int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(40)); params.leftMargin = dp(leftMargin); return params;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = vertical(); card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackground(round(surfaceContainer, M3.SHAPE_LARGE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, 0, 0, dp(10)); parent.addView(card, params); return card;
    }
    /** Rounded pill; removable chips show "×" and clear on tap, action chips are outlined. */
    private TextView chip(String label, boolean removable, Runnable action) {
        TextView chip = M3.type(new TextView(activity), M3.LABEL_LARGE, false, removable ? sc.onSecondaryContainer : foreground);
        chip.setText(removable ? label + "  ✕" : label);
        chip.setGravity(Gravity.CENTER_VERTICAL); chip.setMinHeight(dp(32)); chip.setSingleLine(true); chip.setEllipsize(TextUtils.TruncateAt.END); chip.setMaxWidth(dp(240));
        chip.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable shape = round(removable ? sc.secondaryContainer : Color.TRANSPARENT, M3.SHAPE_SMALL);
        if (!removable) shape.setStroke(dp(1), sc.outline); else shape.setStroke(dp(1), sc.secondaryContainer);
        chip.setBackground(shape); chip.setOnClickListener(view -> action.run()); Motion.press(chip);
        return chip;
    }
    /** M3 segmented button: outlined pill, the selected segment filled with the secondary container. */
    private View segmented(String[] options, int selected, Consumer<Integer> onSelect) {
        LinearLayout row = horizontal();
        GradientDrawable frame = round(Color.TRANSPARENT, 20); frame.setStroke(dp(1), sc.outline); row.setBackground(frame); row.setClipToOutline(true);
        TextView[] items = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView item = M3.type(new TextView(activity), M3.LABEL_LARGE, true, secondary); item.setText(options[i]);
            item.setGravity(Gravity.CENTER); item.setSingleLine(true); item.setEllipsize(TextUtils.TruncateAt.END); item.setPadding(dp(4), 0, dp(4), 0);
            item.setOnClickListener(view -> {
                for (int j = 0; j < items.length; j++) styleSegment(items[j], j == index);
                onSelect.accept(index);
            });
            styleSegment(item, i == selected); items[i] = item;
            row.addView(item, new LinearLayout.LayoutParams(0, dp(40), 1));
            if (i < options.length - 1) { View divider = new View(activity); divider.setBackgroundColor(sc.outline); row.addView(divider, new LinearLayout.LayoutParams(dp(1), -1)); }
        }
        LinearLayout wrapper = vertical(); wrapper.setPadding(0, dp(4), 0, dp(4)); wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrapper;
    }
    private void styleSegment(TextView item, boolean on) {
        item.setBackgroundColor(on ? sc.secondaryContainer : Color.TRANSPARENT);
        item.setTextColor(on ? sc.onSecondaryContainer : foreground); item.setText(on ? "✓ " + item.getText().toString().replaceFirst("^✓ ", "") : item.getText().toString().replaceFirst("^✓ ", ""));
    }
    private CheckBox tint(CheckBox box) { box.setButtonTintList(ColorStateList.valueOf(primary)); return box; }
    private GradientDrawable round(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private GradientDrawable topRounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color);
        float r = dp(radius); shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0}); return shape;
    }
    private JSONObject api(String operation, JSONObject params) throws Exception { return CollectorClient.call(activity, operation, params); }
    private void error(Exception error) { alert().setMessage(error.getMessage()).setPositiveButton("确定", null).show(); }
    private LinearLayout scrolling() {
        if (dataContent != null && dataScroll != null) {
            currentScroll = dataScroll;
            LinearLayout list = vertical(); list.setPadding(dp(12), dp(4), dp(12), dp(16));
            dataContent.addView(list);
            return list;
        }
        ScrollView scroll = new ScrollView(activity); currentScroll = scroll; LinearLayout list = vertical(); list.setPadding(dp(12), dp(4), dp(12), dp(16)); scroll.addView(list);
        scroll.setClipToPadding(false);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); return list;
    }
    private void pageControls(JSONObject data) {
        int limit = data.optInt("limit", 50), total = data.optInt("total");
        if (total <= limit) return;
        LinearLayout controls = horizontal(); controls.setPadding(dp(12), dp(6), dp(12), dp(8));
        Button previous = button("上一页", () -> { offset = Math.max(0, offset - limit); render(); }); previous.setEnabled(offset > 0); previous.setAlpha(offset > 0 ? 1f : .4f);
        Button next = button("下一页", () -> { offset += limit; render(); }); boolean more = offset + limit < total; next.setEnabled(more); next.setAlpha(more ? 1f : .4f);
        TextView where = muted((offset / limit + 1) + " / " + ((total + limit - 1) / limit), 12); where.setGravity(Gravity.CENTER);
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(38), 1)); controls.addView(where, new LinearLayout.LayoutParams(dp(64), -2)); controls.addView(next, new LinearLayout.LayoutParams(0, dp(38), 1));
        (dataContent != null ? dataContent : body).addView(controls);
    }
    private EditText input(String hint, String value, boolean numeric) {
        EditText view = new EditText(uiContext); view.setHint(hint); view.setText(value); view.setTextSize(14); view.setTextColor(foreground); view.setHintTextColor(secondary);
        view.setSingleLine(true); view.setMinHeight(dp(56)); view.setPadding(dp(16), dp(8), dp(16), dp(8)); GradientDrawable field = round(Color.TRANSPARENT, M3.SHAPE_EXTRA_SMALL); field.setStroke(dp(1), sc.outline); view.setBackground(field); if (numeric) view.setInputType(InputType.TYPE_CLASS_NUMBER); return view;
    }

    /** Minimal wrapping row for chips. */
    private static final class Flow extends ViewGroup {
        private final int gap;
        Flow(Context context, int gap) { super(context); this.gap = gap; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), x = 0, y = 0, rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += rowHeight + gap; rowHeight = 0; }
                x += child.getMeasuredWidth() + gap; rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + rowHeight);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, x = 0, y = 0, rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += rowHeight + gap; rowHeight = 0; }
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                x += child.getMeasuredWidth() + gap; rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
        }
    }

    // ---------------------------------------------------------------- labels

    private String number(JSONObject object, String key) { return object.has(key) && !object.isNull(key) ? object.optString(key) : "未知"; }
    private String date(long time) {
        if (time <= 0) return "时间未知"; if (time < 100000000000L) time *= 1000;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(time));
    }
    private static String genderValue(String[] keys, boolean[] on) {
        StringBuilder out = new StringBuilder(); int count = 0;
        for (int i = 0; i < keys.length; i++) if (on[i]) { out.append(count++ == 0 ? "" : ",").append(keys[i]); }
        return count == 0 || count == keys.length ? "all" : out.toString();
    }
    /** Independent toggle chips (multi-select), each filled when selected. */
    private View multiChips(String[] labels, boolean[] on) {
        LinearLayout row = horizontal();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = M3.type(new TextView(activity), M3.LABEL_LARGE, true, secondary); item.setText(labels[i]);
            item.setGravity(Gravity.CENTER); item.setMinHeight(dp(40)); item.setPadding(dp(16), 0, dp(16), 0);
            Runnable paint = () -> {
                GradientDrawable shape = round(on[index] ? sc.secondaryContainer : Color.TRANSPARENT, M3.SHAPE_SMALL);
                shape.setStroke(dp(1), on[index] ? sc.secondaryContainer : sc.outline);
                item.setBackground(shape); item.setTextColor(on[index] ? sc.onSecondaryContainer : secondary);
                item.setText((on[index] ? "✓ " : "") + labels[index]);
            };
            paint.run();
            item.setOnClickListener(view -> { on[index] = !on[index]; paint.run(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
            if (i > 0) params.leftMargin = dp(8);
            row.addView(item, params);
        }
        return row;
    }
    private static String ipSourceName(String source) { return "comment".equals(source) ? "评论IP" : "post".equals(source) ? "发帖地点" : "主页IP"; }
    private String gender(String gender) { return "female".equals(gender) ? "女" : "male".equals(gender) ? "男" : "未知"; }
    private String genderFilterName(String key) {
        if (key.contains(",")) { StringBuilder names = new StringBuilder(); for (String part : key.split(",")) names.append(names.length() == 0 ? "" : "/").append(gender(part)); return names.toString(); }
        return switch (key) { case "female" -> "女"; case "male" -> "男"; case "unknown" -> "性别未知"; case "female-or-unknown" -> "女或未知"; default -> "全部性别"; };
    }
    private String sortName(String key) {
        return switch (key) { case "created_desc" -> "最新评论"; case "created_asc" -> "最早评论"; case "likes_desc" -> "点赞最多"; case "captured_asc" -> "最早抓取"; default -> "最新抓取"; };
    }
    private String kindName(String kind) { return "profile".equals(kind) ? "主页资料" : "discovery".equals(kind) ? "发现帖子" : "评论采集"; }
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
            case "waiting_foreground" -> "等待返回" + hostName();
            case "unsupported_native_version" -> "当前版本未适配原生加载";
            case "native_pending_root_unavailable" -> "未找到待补充回复的主评论";
            case "native_reply_signature_mismatch" -> "回复接口与适配版本不一致";
            case "ui_requested" -> "已选择页面加载";
            case "adapter_error" -> "加载器报错，请查看诊断";
            case "" -> "尚未连接" + hostName();
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
            case "target_reached" -> "已达到目标数量"; case "page_limit_reached" -> "已达到页数上限（未确认末页）";
            case "search_eof_confirmed" -> "搜索末页已确认";
            case "search_page_limit" -> "已达到搜索页数上限"; case "no_new_search_page" -> "搜索页面未继续加载";
            case "no_new_comment_page" -> "评论页面未继续加载"; case "profile_not_returned" -> "主页没有返回可用资料";
            case "root_page_not_observed" -> "尚未读取主评论首页，请返回" + hostName() + "后继续";
            case "native_page_timeout" -> "原生评论请求等待超时，可继续任务后重试";
            case "native_reply_unconfirmed" -> "回复分组尚未准备完成或无法确认末页，请查看诊断后继续";
            case "native_adapter_unavailable" -> "当前页面没有可用的原生分页器，请打开目标作品评论后继续";
            case "native_eof_unconfirmed" -> "页面已停止翻页，但接口尚未确认末页，请查看任务诊断";
            case "manual_login_required" -> "等待手动登录"; case "manual_verification_required" -> "等待手动完成平台验证";
            case "manual_pause" -> "已手动暂停"; case "superseded_by_new_task" -> "已被新任务替换，可续跑";
            default -> code;
        };
    }
    private String state(String state) {
        return switch (state) { case "running" -> "采集中"; case "running-replies" -> "补充回复中"; case "running-profiles" -> "补充资料中"; case "paused" -> "已暂停";
            case "complete" -> "已完成"; case "queued" -> "排队中"; case "stopped" -> "已停止"; default -> "待命"; };
    }
}
