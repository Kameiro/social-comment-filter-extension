package dev.local.commentcollector;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/** Explicit cross-process bridge for callers that cannot resolve the exported Provider. */
public final class CaptureService extends Service {
    private static final String CHANNEL = "collector_bridge";
    private HandlerThread worker;
    private Messenger messenger;

    @Override public void onCreate() {
        super.onCreate();
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "评论采集桥接", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder notification = android.os.Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        notification.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("快手评论采集").setContentText("桥接服务运行中").setOngoing(true);
        startForeground(1001, notification.build());
        worker = new HandlerThread("collector-bridge"); worker.start();
        messenger = new Messenger(new Handler(worker.getLooper(), message -> {
            handle(message); return true;
        }));
    }

    private void handle(Message message) {
        Bundle input = message.getData();
        String operation = input == null ? "" : input.getString("operation", "");
        Log.i("CommentCollector", "bridge_handle operation=" + operation + " uid=" + message.sendingUid);
        Bundle extras = input == null ? null : input.getBundle("extras");
        Bundle result = new Bundle();
        try {
            result = CaptureProvider.callFromService(operation, extras, message.sendingUid);
        } catch (Exception error) {
            result.putString("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        if (message.replyTo == null) return;
        Message reply = Message.obtain(); reply.what = message.what; reply.setData(result);
        try { message.replyTo.send(reply); } catch (RemoteException error) { Log.w("CommentCollector", "bridge_reply_failed"); }
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        if (worker != null) worker.quitSafely();
        super.onDestroy();
    }
}
