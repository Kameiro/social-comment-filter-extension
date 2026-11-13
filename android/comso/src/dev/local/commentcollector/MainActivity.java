package dev.local.commentcollector;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class MainActivity extends Activity {
    public String hookProbe() { return "original"; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(new Intent(this, CaptureService.class));
        else startService(new Intent(this, CaptureService.class));
        setContentView(CollectorPanel.standalone(this, hookProbe(), BackgroundCheck.SECTION.equals(getIntent().getStringExtra("section"))));
    }
}
