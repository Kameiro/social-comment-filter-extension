package dev.local.commentcollector;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;

public final class CollectorClient {
    private CollectorClient() {}
    public static JSONObject call(Context context, String operation, JSONObject params) throws Exception {
        Bundle args = new Bundle();
        args.putString("body", params == null ? "{}" : params.toString());
        Bundle result = null; Exception providerError = null;
        try { result = context.getContentResolver().call(CaptureProvider.URI, operation, null, args); }
        catch (Exception error) { providerError = error; }
        if (result == null) {
            // ColorOS can reject cross-UID provider lookup. Never block Kuaishou's
            // render/network callbacks on a 15-second bridge timeout for read-only
            // status probes; the next write will still try the bridge.
            if (("status".equals(operation) || "runtime_update".equals(operation)) && "com.smile.gifmaker".equals(context.getPackageName())) {
                if (providerError != null) throw providerError;
                throw new IllegalStateException("Provider 不可用");
            }
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
