package dev.local.commentcollector;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Silent auto-wake for the collector process. Uses non-Activity IPCs (explicit broadcast, startService,
 * and unstable ContentProvider client acquire) to wake the collector process without triggering
 * system or host app risk popups.
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
        boolean woken = false;

        // 1. Explicit broadcast to BridgeReceiver
        try {
            Intent intent = new Intent(BridgeReceiver.ACTION)
                .setClassName("dev.local.commentcollector", "dev.local.commentcollector.BridgeReceiver")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("operation", "wake");
            context.sendBroadcast(intent);
            woken = true;
        } catch (Throwable e) {
            Log.w("CommentCollector", "silent_wake_broadcast_failed " + e.getMessage());
        }

        // 2. startService for CaptureService
        try {
            Intent serviceIntent = new Intent().setClassName("dev.local.commentcollector", "dev.local.commentcollector.CaptureService");
            context.startService(serviceIntent);
            woken = true;
        } catch (Throwable ignored) {}

        // 3. Acquire unstable ContentProviderClient to trigger Provider process startup
        try {
            ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(CaptureProvider.URI);
            if (client != null) {
                client.close();
                woken = true;
            }
        } catch (Throwable e) {
            Log.w("CommentCollector", "silent_wake_provider_failed " + e.getMessage());
        }

        Log.i("CommentCollector", "collector_silent_wake");
        return woken;
    }
}
