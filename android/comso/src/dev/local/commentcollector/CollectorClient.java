package dev.local.commentcollector;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;

public final class CollectorClient {
    private CollectorClient() {}
    /** Provider first; if the collector process is gone, wake it silently and retry; finally the bridge service. */
    static Bundle callRaw(Context context, String operation, Bundle args) throws Exception {
        Bundle result = null; Exception providerError = null;
        try { result = context.getContentResolver().call(CaptureProvider.URI, operation, null, args); }
        catch (Exception error) { providerError = error; }
        if (result == null && Platforms.HOSTS.contains(context.getPackageName())) {
            // A rejected background wake is still useful diagnostic information,
            // but it must not decide whether the provider retry happens. The
            // receiver/startService calls can be dropped by ColorOS without an
            // exception. Poll the provider, then use the bound bridge fallback.
            CollectorWake.wake(context);
            for (int attempt = 0; attempt < 14 && result == null; attempt++) {
                try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                try { result = context.getContentResolver().call(CaptureProvider.URI, operation, null, args); providerError = null; }
                catch (Exception error) { providerError = error; }
            }
        }
        if (result != null) return result;
        try { return CaptureBridge.call(context, operation, args); }
        catch (Exception bridgeError) { if (providerError != null) throw providerError; throw bridgeError; }
    }

    public static JSONObject call(Context context, String operation, JSONObject params) throws Exception {
        Bundle args = new Bundle();
        args.putString("body", params == null ? "{}" : params.toString());
        Bundle result = null; Exception providerError = null;
        boolean host = Platforms.HOSTS.contains(context.getPackageName());
        try { result = context.getContentResolver().call(CaptureProvider.URI, operation, null, args); }
        catch (Exception error) { providerError = error; }
        if (result == null && host) {
            CollectorWake.wake(context);
            // The collector process was frozen or killed: wake it silently, then retry the provider for a few seconds.
            for (int attempt = 0; attempt < 14 && result == null; attempt++) {
                try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                try { result = context.getContentResolver().call(CaptureProvider.URI, operation, null, args); providerError = null; }
                catch (Exception error) { providerError = error; }
            }
        }
        if (result == null) {
            try { result = CaptureBridge.call(context, operation, args); }
            catch (Exception bridgeError) {
                if (providerError != null) throw new IllegalStateException("Provider 不可用，桥接服务也不可用：" + bridgeError.getMessage(), bridgeError);
                throw bridgeError;
            }
        }
        if (result == null) throw new IllegalStateException("本地采集服务无响应");
        if (result.containsKey("error")) throw new IllegalStateException(result.getString("error"));
        String data = result.getString("data", result.getString("json", "{}"));
        return new JSONObject(data);
    }
}
