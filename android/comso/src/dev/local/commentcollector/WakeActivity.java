package dev.local.commentcollector;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible trampoline: brings the collector process back (after the system killed it in the background)
 * by starting the capture service, then closes immediately. Launched from the in-app panel on a user tap,
 * which ColorOS allows even when it blocks background "associated start" of the provider.
 */
public final class WakeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { startForegroundService(new Intent(this, CaptureService.class)); }
        catch (RuntimeException error) { try { startService(new Intent(this, CaptureService.class)); } catch (RuntimeException ignored) {} }
        finish();
        overridePendingTransition(0, 0);
    }
}
