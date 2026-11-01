package dev.local.commentcollector;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;

public final class CollectorClient {
    private CollectorClient() {}
    public static JSONObject call(Context context, String operation, JSONObject params) throws Exception {
        Bundle args = new Bundle();
        args.putString("body", params == null ? "{}" : params.toString());
        Bundle result = context.getContentResolver().call(CaptureProvider.URI, operation, null, args);
        if (result == null) throw new IllegalStateException("本地采集服务无响应");
        if (result.containsKey("error")) throw new IllegalStateException(result.getString("error"));
        String data = result.getString("data", result.getString("json", "{}"));
        return new JSONObject(data);
    }
}
