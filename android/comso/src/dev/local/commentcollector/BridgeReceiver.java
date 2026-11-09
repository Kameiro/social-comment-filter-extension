package dev.local.commentcollector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public final class BridgeReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.local.commentcollector.BRIDGE";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        try { context.startForegroundService(new Intent(context, CaptureService.class)); }
        catch (Throwable e) { try { context.startService(new Intent(context, CaptureService.class)); } catch (Throwable ignored) {} }

        final String operation = intent.getStringExtra("operation");
        final PendingResult pending = goAsync();
        if (operation == null || operation.isEmpty() || "wake".equals(operation)) {
            Bundle result = new Bundle();
            result.putString("data", "{\"ok\":true}");
            pending.setResultExtras(result);
            pending.finish();
            Log.i("CommentCollector", "broadcast_bridge_wake_done");
            return;
        }

        final int uid = getSentFromUid();
        final Bundle extras = intent.getBundleExtra("extras");
        new Thread(() -> {
            Bundle result = new Bundle();
            try { result = CaptureProvider.callFromService(operation, extras, uid); }
            catch (Exception error) { result.putString("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
            pending.setResultExtras(result); pending.finish();
            Log.i("CommentCollector", "broadcast_bridge_done operation=" + operation + " uid=" + uid);
        }, "collector-broadcast-bridge").start();
    }
}
