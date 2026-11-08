package dev.local.commentcollector;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Silent auto-wake for the collector process. ColorOS freezes or kills the module app in the background and refuses the
 * "associated start" of its provider/service from another app, but allows a foreground app to open a transparent activity
 * of it. {@link WakeActivity} starts the capture service and closes at once, so the user never sees a thing.
 */
final class CollectorWake {
    private static final long MIN_GAP_MS = 6000;
    private static long lastAttempt = -MIN_GAP_MS;

    private CollectorWake() {}

    /** True when a wake was just issued (or is already in flight), so the caller should retry its request shortly. */
    static boolean wake(Context context) {
        if (!Platforms.HOSTS.contains(context.getPackageName())) return false;
        long now = SystemClock.elapsedRealtime();
        synchronized (CollectorWake.class) {
            if (now - lastAttempt < MIN_GAP_MS) return now - lastAttempt < 3500; // an attempt is still settling
            lastAttempt = now;
        }
        try {
            context.startActivity(new Intent().setClassName("dev.local.commentcollector", "dev.local.commentcollector.WakeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
            Log.i("CommentCollector", "collector_auto_wake");
            return true;
        } catch (Exception error) {
            Log.w("CommentCollector", "collector_auto_wake_failed " + error.getClass().getSimpleName());
            return false;
        }
    }
}
