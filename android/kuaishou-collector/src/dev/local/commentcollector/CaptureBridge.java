package dev.local.commentcollector;

import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class CaptureBridge {
    private static final Object LOCK = new Object();
    private static final AtomicInteger IDS = new AtomicInteger(1);
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Executor CALLBACKS = command -> {
        Thread thread = new Thread(command, "collector-bridge-callback"); thread.setDaemon(true); thread.start();
    };
    private static Messenger service;
    private static CountDownLatch connecting;
    private static Messenger reply;
    private static Handler replyHandler;
    private static volatile boolean bindUnavailable;

    private CaptureBridge() {}

    static Bundle call(Context context, String operation, Bundle extras) throws Exception {
        Log.i("CommentCollector", "bridge_call operation=" + operation);
        try {
            if (!bindUnavailable) {
                ensureBound(context.getApplicationContext());
                return callBound(operation, extras);
            }
        } catch (Exception bindError) {
            bindUnavailable = true;
            Log.w("CommentCollector", "bridge_bind_failed, using broadcast");
        }
        return callBroadcast(context.getApplicationContext(), operation, extras);
    }

    private static Bundle callBound(String operation, Bundle extras) throws Exception {
        int id = IDS.getAndIncrement(); Pending pending = new Pending(); PENDING.put(id, pending);
        Message request = Message.obtain(); request.what = id; request.replyTo = reply;
        Bundle body = new Bundle(); body.putString("operation", operation); if (extras != null) body.putBundle("extras", extras); request.setData(body);
        Messenger target;
        synchronized (LOCK) { target = service; }
        if (target == null) { PENDING.remove(id); throw new IllegalStateException("采集桥接服务未连接"); }
        try { target.send(request); }
        catch (RemoteException error) { PENDING.remove(id); resetConnection(); throw error; }
        if (!pending.done.await(15, TimeUnit.SECONDS)) { PENDING.remove(id); throw new IllegalStateException("采集桥接服务响应超时"); }
        if (pending.result == null) throw new IllegalStateException("采集桥接服务无响应");
        return pending.result;
    }

    private static Bundle callBroadcast(Context context, String operation, Bundle extras) throws Exception {
        if (replyHandler == null) ensureReplyHandler();
        CountDownLatch done = new CountDownLatch(1); final Bundle[] result = new Bundle[1];
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { result[0] = getResultExtras(true); done.countDown(); }
        };
        Intent intent = new Intent(BridgeReceiver.ACTION).setComponent(new ComponentName("dev.local.commentcollector", "dev.local.commentcollector.BridgeReceiver"));
        intent.putExtra("operation", operation); if (extras != null) intent.putExtra("extras", extras);
        context.sendOrderedBroadcast(intent, null, receiver, replyHandler, 0, null, null);
        if (!done.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("采集广播桥接响应超时");
        if (result[0] == null) throw new IllegalStateException("采集广播桥接无响应");
        return result[0];
    }

    private static void ensureReplyHandler() {
        if (replyHandler != null) return;
        HandlerThread thread = new HandlerThread("collector-bridge-reply"); thread.start();
        replyHandler = new Handler(thread.getLooper());
    }

    private static void ensureBound(Context context) throws Exception {
        CountDownLatch wait;
        synchronized (LOCK) {
            if (service != null) return;
            if (reply == null) {
                ensureReplyHandler();
                reply = new Messenger(new Handler(replyHandler.getLooper(), message -> {
                    Pending pending = PENDING.remove(message.what); if (pending != null) { pending.result = message.getData(); pending.done.countDown(); } return true;
                }));
            }
            if (connecting == null) {
                connecting = new CountDownLatch(1);
                Intent intent = new Intent().setComponent(new ComponentName("dev.local.commentcollector", "dev.local.commentcollector.CaptureService"));
                boolean bound = context.bindService(intent, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_ABOVE_CLIENT, CALLBACKS, CONNECTION);
                Log.i("CommentCollector", "bridge_bind_requested bound=" + bound);
                if (!bound) { connecting = null; throw new IllegalStateException("系统拒绝启动采集桥接服务"); }
            }
            wait = connecting;
        }
        if (!wait.await(10, TimeUnit.SECONDS)) { resetConnection(); throw new IllegalStateException("采集桥接服务连接超时"); }
        synchronized (LOCK) { if (service == null) throw new IllegalStateException("采集桥接服务未返回 Binder"); }
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i("CommentCollector", "bridge_connected " + name);
            synchronized (LOCK) { service = new Messenger(binder); if (connecting != null) connecting.countDown(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { Log.w("CommentCollector", "bridge_disconnected"); resetConnection(); }
        @Override public void onBindingDied(ComponentName name) { resetConnection(); }
        @Override public void onNullBinding(ComponentName name) { synchronized (LOCK) { service = null; if (connecting != null) connecting.countDown(); } }
    };

    private static void resetConnection() {
        synchronized (LOCK) { service = null; connecting = null; bindUnavailable = false; }
    }

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Bundle result;
    }
}
