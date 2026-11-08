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
        final int uid = getSentFromUid();
        final String operation = intent.getStringExtra("operation");
        final Bundle extras = intent.getBundleExtra("extras");
        final PendingResult pending = goAsync();
        new Thread(() -> {
            Bundle result = new Bundle();
            try { result = CaptureProvider.callFromService(operation, extras, uid); }
            catch (Exception error) { result.putString("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
            pending.setResultExtras(result); pending.finish();
            Log.i("CommentCollector", "broadcast_bridge_done operation=" + operation + " uid=" + uid);
        }, "collector-broadcast-bridge").start();
    }
}
