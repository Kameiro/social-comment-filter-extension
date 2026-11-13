package dev.local.commentcollector;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;
import java.util.function.LongFunction;

/**
 * Shared UI motion for the injected panels. Everything uses ViewPropertyAnimator / ValueAnimator,
 * so it follows the system "animator duration scale" (and turns off when animations are disabled).
 */
final class Motion {
    static final Interpolator ENTER = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);   // emphasized decelerate
    static final Interpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);    // emphasized accelerate
    static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private Motion() {}

    /** Scale-down feedback while pressed; never consumes the event, so clicks still fire. */
    @SuppressLint("ClickableViewAccessibility")
    static void press(View view) {
        if (view == null) return;
        view.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    target.animate().scaleX(.96f).scaleY(.96f).alpha(.92f).setDuration(90).setInterpolator(STANDARD).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(1.4f)).start();
                    break;
                default: break;
            }
            return false;
        });
    }

    /** Tactile spring pop / bounce for selections, toggles, chips, and icons. */
    static void pop(View view) {
        if (view == null) return;
        view.setScaleX(0.92f);
        view.setScaleY(0.92f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(new OvershootInterpolator(1.6f)).start();
    }

    /** Applies press feedback to every clickable descendant. */
    static void pressAll(View root) {
        if (root == null) return;
        if (root.isClickable() && !(root instanceof android.widget.CompoundButton) && !(root instanceof android.widget.ScrollView)) press(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) pressAll(group.getChildAt(i));
        }
    }

    /** Material 3 Expressive dialog entrance: scale up, slight slide up, and fade in with emphasized decelerate. */
    static void dialogEnter(android.app.Dialog dialog) {
        if (dialog == null) return;
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (decor == null) return;
        pressAll(decor);
        float dy = 22 * decor.getResources().getDisplayMetrics().density;
        decor.setScaleX(0.92f);
        decor.setScaleY(0.92f);
        decor.setTranslationY(dy);
        decor.setAlpha(0f);
        decor.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f)
            .setDuration(280).setInterpolator(ENTER).start();
    }

    /** Smooth accordion expand / collapse for expandable cards and sections. */
    static void expand(View view, boolean open, Runnable onComplete) {
        if (view == null) return;
        view.animate().cancel();
        float dy = 12 * view.getResources().getDisplayMetrics().density;
        if (open) {
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setTranslationY(-dy);
            view.animate().alpha(1f).translationY(0f)
                .setDuration(240).setInterpolator(ENTER)
                .withEndAction(onComplete).start();
        } else {
            view.animate().alpha(0f).translationY(-dy)
                .setDuration(180).setInterpolator(EXIT)
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setTranslationY(0f);
                    if (onComplete != null) onComplete.run();
                }).start();
        }
    }

    /** Smooth snap for swipe-to-reveal rows. */
    static void swipeSnap(View row, float targetX, Runnable onEnd) {
        if (row == null) return;
        android.view.ViewPropertyAnimator anim = row.animate().translationX(targetX).setDuration(220).setInterpolator(ENTER);
        if (onEnd != null) anim.withEndAction(onEnd);
        anim.start();
    }

    /** Bottom sheet slides up; centered card scales and fades in. The scrim fades to {@code dim}. */
    /** Asks the compositor for the panel's highest refresh rate while our window is shown. */
    static void highRefreshRate(android.app.Activity activity, android.view.Window window) {
        try {
            android.view.Display display = activity.getDisplay();
            if (display == null || window == null) return;
            android.view.Display.Mode current = display.getMode();
            float best = current.getRefreshRate();
            for (android.view.Display.Mode mode : display.getSupportedModes())
                if (mode.getPhysicalWidth() == current.getPhysicalWidth() && mode.getRefreshRate() > best) best = mode.getRefreshRate();
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.preferredRefreshRate = best;
            window.setAttributes(params);
        } catch (RuntimeException ignored) {}
    }

    static void enter(View sheet, View scrim, boolean centered, float dim) {
        scrim.animate().alpha(dim).setDuration(centered ? 220 : 300).setInterpolator(STANDARD).withLayer().start();
        sheet.setAlpha(0f);
        sheet.post(() -> {
            if (centered) {
                sheet.setScaleX(.9f); sheet.setScaleY(.9f);
                sheet.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(ENTER).withLayer().start();
            } else {
                sheet.setTranslationY(Math.max(sheet.getHeight(), 1));
                sheet.setAlpha(1f);
                sheet.animate().translationY(0f).setDuration(340).setInterpolator(ENTER).withLayer().start();
            }
        });
    }

    /** Reverse of {@link #enter}; runs {@code end} once the sheet is off screen. */
    static void exit(View sheet, View scrim, boolean centered, float dim, Runnable end) {
        long duration = centered ? 170 : 280;
        sheet.animate().cancel();
        scrim.animate().alpha(0f).setDuration(duration).setInterpolator(STANDARD).withLayer().start();
        android.view.ViewPropertyAnimator animator = sheet.animate().setDuration(duration).setInterpolator(EXIT).withLayer();
        if (centered) animator.alpha(0f).scaleX(.94f).scaleY(.94f);
        // Fade while sliding: the fastest last frames are nearly transparent, so a tall sheet leaves no smear.
        else animator.translationY(sheet.getHeight() * .6f + Math.max(0f, sheet.getTranslationY())).alpha(0f);
        animator.withEndAction(() -> {
            // Hide before the window is removed so no last frame lingers on screen.
            sheet.setVisibility(View.INVISIBLE); scrim.setVisibility(View.INVISIBLE);
            sheet.post(end);
        }).start();
    }

    /** Children rise and fade in one after another. */
    static void stagger(ViewGroup parent, long startDelay) {
        float rise = 14 * parent.getResources().getDisplayMetrics().density;
        int count = Math.min(parent.getChildCount(), 12);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            child.setAlpha(0f); child.setTranslationY(rise);
            child.animate().alpha(1f).translationY(0f).setStartDelay(startDelay + Math.min(i, count) * 35L)
                .setDuration(320).setInterpolator(ENTER).start();
        }
    }

    static void fadeIn(View view, long duration) {
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(duration).setInterpolator(STANDARD).start();
    }

    /** Counts a number up from zero; leaves non-numeric values untouched. */
    static void countUp(TextView view, Object value, LongFunction<String> format, long startDelay) {
        long target;
        try { target = Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return; }
        if (target <= 0) return;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f).setDuration(700);
        animator.setStartDelay(startDelay);
        animator.setInterpolator(STANDARD);
        animator.addUpdateListener(step -> view.setText(format.apply(Math.round(target * (float) step.getAnimatedValue()))));
        view.setText(format.apply(0));
        animator.start();
    }

    /** Briefly swaps a button label (e.g. "已复制 ✓") with a crossfade, then restores it. */
    private static final java.util.Map<TextView, Runnable> pendingRestore = new java.util.WeakHashMap<>();
    private static final java.util.Map<TextView, CharSequence> originalLabel = new java.util.WeakHashMap<>();

    static void flashLabel(TextView view, String temporary, long holdMs) {
        CharSequence original = originalLabel.get(view);
        if (original == null) { original = view.getText(); originalLabel.put(view, original); }
        Runnable previous = pendingRestore.remove(view);
        if (previous != null) view.removeCallbacks(previous);
        swapText(view, temporary);
        CharSequence restoreTo = original;
        Runnable restore = () -> { pendingRestore.remove(view); originalLabel.remove(view); swapText(view, restoreTo); };
        pendingRestore.put(view, restore);
        view.postDelayed(restore, holdMs);
    }

    private static void swapText(TextView view, CharSequence text) {
        view.animate().alpha(0f).setDuration(90).setInterpolator(STANDARD).withEndAction(() -> {
            view.setText(text);
            view.animate().alpha(1f).setDuration(140).setInterpolator(STANDARD).start();
        }).start();
    }

    /** Content swap when switching tabs: slight slide toward the chosen direction plus fade. */
    static void tabContent(View body, int direction) {
        if (body == null) return;
        float shift = 28 * body.getResources().getDisplayMetrics().density * Math.signum(direction);
        body.setAlpha(0f); body.setTranslationX(shift);
        body.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(ENTER).start();
    }

    /** Selected-tab indicator grows from the centre with a spring pop. */
    static void indicator(View view) {
        if (view == null) return;
        view.setScaleX(0f);
        view.animate().scaleX(1f).setDuration(260).setInterpolator(new OvershootInterpolator(1.4f)).start();
    }

    /** Lets the user drag a bottom sheet down by {@code grip}; past a threshold or on a fling it dismisses. */
    @SuppressLint("ClickableViewAccessibility")
    static void dragToDismiss(View grip, View sheet, View scrim, float dim, Runnable dismiss) {
        float[] start = new float[1];
        VelocityTracker[] tracker = new VelocityTracker[1];
        grip.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = event.getRawY();
                    sheet.animate().cancel();
                    tracker[0] = VelocityTracker.obtain(); tracker[0].addMovement(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tracker[0] != null) tracker[0].addMovement(event);
                    float dy = event.getRawY() - start[0];
                    // Rubber-band upward drags, follow the finger downward.
                    sheet.setTranslationY(dy > 0 ? dy : dy * .15f);
                    if (sheet.getHeight() > 0) scrim.setAlpha(dim * Math.max(0f, 1f - Math.max(0f, dy) / sheet.getHeight()));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float velocity = 0f;
                    if (tracker[0] != null) {
                        tracker[0].addMovement(event); tracker[0].computeCurrentVelocity(1000);
                        velocity = tracker[0].getYVelocity(); tracker[0].recycle(); tracker[0] = null;
                    }
                    float moved = sheet.getTranslationY();
                    float threshold = sheet.getHeight() * .25f;
                    float fling = 1200 * sheet.getResources().getDisplayMetrics().density / 2.6f;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && (moved > threshold || velocity > fling)) dismiss.run();
                    else {
                        sheet.animate().translationY(0f).setDuration(280).setInterpolator(new OvershootInterpolator(1.1f)).start();
                        scrim.animate().alpha(dim).setDuration(280).setInterpolator(STANDARD).start();
                    }
                    return true;
                default: return false;
            }
        });
    }
}
