package dev.local.commentcollector;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;

public final class CollectorClient {
    private CollectorClient() {}
    /** Host-process failure memory and status cache; unused inside the collector app itself. */
    private static final CollectorBackoff BACKOFF = new CollectorBackoff(SystemClock::elapsedRealtime, System::currentTimeMillis,
        CollectorBackoff.DOWN_WINDOW_MS, CollectorBackoff.STATUS_TTL_MS);

    /** Cheap, non-blocking: true when this host process recently failed to reach the collector. */
    public static boolean collectorUnreachable() { return BACKOFF.unreachable(); }

    private static void succeeded(Context context) {
        long[] outage = BACKOFF.recordSuccess();
        if (outage == null) return;
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        // Evidence for the collector's "后台运行检查"; fire-and-forget so the caller never waits on it.
        new Thread(() -> {
            try {
                Bundle args = new Bundle();
                args.putString("body", new JSONObject().put("hostPackage", app.getPackageName())
                    .put("outageStart", outage[0]).put("outageEnd", outage[1]).put("failures", outage[2]).toString());
                reach(app, "reach_report", args);
            } catch (Throwable ignored) {}
        }, "collector-reach-report").start();
    }

    /**
     * Provider first; if the collector process is gone, wake it silently and retry; finally the bridge service.
     * In host apps, once a full attempt has failed, calls in the next few seconds make one quick provider
     * attempt and fail immediately instead of paying the wake/retry/bridge cost again.
     */
    private static Bundle reach(Context context, String operation, Bundle args) throws Exception {
        boolean host = Platforms.HOSTS.contains(context.getPackageName());
        Bundle result = null; Exception providerError = null;
        try { result = context.getContentResolver().call(CaptureProvider.URI, operation, null, args); }
        catch (Exception error) { providerError = error; }
        if (result != null) { if (host) succeeded(context); return result; }
        if (host && BACKOFF.failFast()) {
            BACKOFF.recordFastFailure();
            throw new IllegalStateException("本地采集服务暂不可用（快速失败）", providerError);
        }
        if (host) {
            // A rejected background wake is still useful diagnostic information, but it must not decide whether
            // the provider retry happens. The receiver/startService calls can be dropped by ColorOS without an
            // exception. Poll the provider, then use the bound bridge fallback.
            CollectorWake.wake(context);
            for (int attempt = 0; attempt < 14 && result == null; attempt++) {
                try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                try { result = context.getContentResolver().call(CaptureProvider.URI, operation, null, args); providerError = null; }
                catch (Exception error) { providerError = error; }
            }
            if (result != null) { succeeded(context); return result; }
        }
        try {
            result = CaptureBridge.call(context, operation, args);
            if (host && result != null) succeeded(context);
            else if (host) BACKOFF.recordFailure();
            return result;
        } catch (Exception bridgeError) {
            if (host) BACKOFF.recordFailure();
            if (providerError != null) throw new IllegalStateException("Provider 不可用，桥接服务也不可用：" + bridgeError.getMessage(), bridgeError);
            throw bridgeError;
        }
    }

    static Bundle callRaw(Context context, String operation, Bundle args) throws Exception {
        boolean host = Platforms.HOSTS.contains(context.getPackageName());
        if (host && !"status".equals(operation)) BACKOFF.invalidateStatus();
        return reach(context, operation, args);
    }

    public static JSONObject call(Context context, String operation, JSONObject params) throws Exception {
        boolean host = Platforms.HOSTS.contains(context.getPackageName());
        String body = params == null ? "{}" : params.toString();
        boolean status = host && "status".equals(operation);
        if (status) { String cached = BACKOFF.cachedStatus(body); if (cached != null) return new JSONObject(cached); }
        else if (host) BACKOFF.invalidateStatus();
        Bundle args = new Bundle();
        args.putString("body", body);
        Bundle result = reach(context, operation, args);
        if (result == null) throw new IllegalStateException("本地采集服务无响应");
        if (result.containsKey("error")) throw new IllegalStateException(result.getString("error"));
        String data = result.getString("data", result.getString("json", "{}"));
        if (status) BACKOFF.cacheStatus(body, data);
        return new JSONObject(data);
    }
}
