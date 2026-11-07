package dev.local.commentcollector;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Platform-neutral double-tap detector for apps whose own detector is not an android GestureDetector.
 * Hooks {@code dispatchTouchEvent} on Activity and on every app activity class that overrides it, recognises two
 * quick taps in the media area, and — when the handler accepts — swallows the second tap so the app never likes.
 */
final class DoubleTapDetector {
    interface Handler {
        /** Current resumed activity, or null. */
        Activity activity();
        /** Called on the second tap in the media area. Return true to consume it. */
        boolean onDoubleTap(Activity activity, float rawX, float rawY);
    }

    private static final String TAG = "CommentCollector";
    private final XposedInterface xposed;
    private final Handler handler;
    private final Set<Class<?>> hooked = Collections.synchronizedSet(new HashSet<>());
    private int depth;
    private long lastTapUp, lastTapDown;
    private float lastX, lastY, downX, downY;
    private boolean consuming;

    DoubleTapDetector(XposedInterface xposed, Handler handler) { this.xposed = xposed; this.handler = handler; }

    void install() { hook(Activity.class); }

    /** Hook this activity class and each superclass that overrides dispatchTouchEvent. */
    void hook(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!hooked.add(c)) continue;
            Method m;
            try { m = c.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class); } catch (NoSuchMethodException ignored) { continue; }
            hookMethod(m);
        }
    }

    /** Time of the last real finger touch; module-injected gestures bypass Activity.dispatchTouchEvent, so they never set it. */
    static volatile long lastUserTouch;

    private void hookMethod(Method dispatch) {
        try {
            xposed.hook(dispatch).intercept(chain -> {
                if (depth > 0) return chain.proceed();
                MotionEvent e = (MotionEvent) chain.getArg(0);
                lastUserTouch = android.os.SystemClock.uptimeMillis();
                Activity host = chain.getThisObject() instanceof Activity ? (Activity) chain.getThisObject() : handler.activity();
                if (host == null) return chain.proceed();
                try {
                    int action = e.getActionMasked();
                    if (consuming) {
                        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) consuming = false;
                        return true;
                    }
                    if (action == MotionEvent.ACTION_DOWN) {
                        downX = e.getX(); downY = e.getY(); lastTapDown = e.getEventTime();
                        boolean second = lastTapUp > 0 && e.getEventTime() - lastTapUp < 320
                            && Math.hypot(downX - lastX, downY - lastY) < 47 * host.getResources().getDisplayMetrics().density
                            && inMedia(host, downX, downY);
                        if (second && handler.onDoubleTap(host, e.getRawX(), e.getRawY())) { consuming = true; lastTapUp = 0; return true; }
                    } else if (action == MotionEvent.ACTION_UP) {
                        boolean tap = e.getEventTime() - lastTapDown < 220 && Math.hypot(e.getX() - downX, e.getY() - downY) < 40;
                        lastTapUp = tap ? e.getEventTime() : 0; lastX = e.getX(); lastY = e.getY();
                    }
                } catch (Throwable error) { Log.w(TAG, "double_tap_failed", error); }
                depth++;
                try { return chain.proceed(); } finally { depth--; }
            });
        } catch (Throwable error) { Log.w(TAG, "dispatch_hook_failed", error); }
    }

    /** Media area: clear of the right-hand action rail, top tabs and the caption/bottom bar. */
    static boolean inMedia(Activity host, float x, float y) {
        DisplayMetrics m = host.getResources().getDisplayMetrics();
        return x < m.widthPixels * 0.80f && y > m.heightPixels * 0.12f && y < m.heightPixels * 0.72f;
    }
}
