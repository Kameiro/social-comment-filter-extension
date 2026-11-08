package dev.local.commentcollector;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class LocalApi extends NanoHTTPD {
    public static final int PORT = 8765;
    private static LocalApi instance;
    private final Context context;
    private final byte[] authorization;

    private LocalApi(Context context, String token) {
        super("127.0.0.1", PORT); this.context = context.getApplicationContext();
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        setAsyncRunner(new BoundedRunner());
    }
    public static synchronized boolean running() { return instance != null && instance.isAlive(); }
    public static synchronized void enable(Context context) throws Exception {
        if (running()) return;
        String token = CollectorClient.call(context, "api_token", new JSONObject()).getString("token");
        LocalApi server = new LocalApi(context, token); server.start(SOCKET_READ_TIMEOUT, true); instance = server;
    }
    public static synchronized void disable() { if (instance != null) instance.stop(); instance = null; }

    @Override public Response serve(IHTTPSession session) {
        try {
            if (session.getHeaders().containsKey("origin")) return response(Response.Status.FORBIDDEN, "ORIGIN_NOT_ALLOWED", null);
            byte[] supplied = session.getHeaders().getOrDefault("authorization", "").getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(authorization, supplied)) return response(Response.Status.UNAUTHORIZED, "AUTH_REQUIRED", null);
            JSONObject params = new JSONObject();
            for (Map.Entry<String, String> entry : session.getParms().entrySet()) params.put(entry.getKey(), entry.getValue());
            Method method = session.getMethod();
            if (method == Method.POST) {
                ContentType contentType = new ContentType(session.getHeaders().getOrDefault("content-type", "")).tryUTF8();
                if (!"application/json".equalsIgnoreCase(contentType.getContentType()) ||
                    !"UTF-8".equalsIgnoreCase(contentType.getEncoding()))
                    return response(Response.Status.UNSUPPORTED_MEDIA_TYPE, "JSON_REQUIRED", null);
                String length = session.getHeaders().get("content-length");
                if (length == null) return response(Response.Status.LENGTH_REQUIRED, "CONTENT_LENGTH_REQUIRED", null);
                if (Long.parseLong(length) < 0 || Long.parseLong(length) > 65536 || session.getHeaders().containsKey("transfer-encoding"))
                    return response(Response.Status.PAYLOAD_TOO_LARGE, "BODY_TOO_LARGE", null);
                // NanoHTTPD defaults an omitted JSON charset to US-ASCII.
                session.getHeaders().put("content-type", contentType.getContentTypeHeader());
                Map<String, String> body = new HashMap<>(); session.parseBody(body);
                params = new JSONObject(body.getOrDefault("postData", "{}"));
            } else if (method != Method.GET) return response(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", null);
            String path = session.getUri(); String operation;
            if (method == Method.GET && "/v1/health".equals(path)) operation = "health";
            else if (method == Method.GET && "/v1/runtime".equals(path)) operation = "runtime";
            else if (method == Method.GET && "/v1/comments".equals(path)) operation = "comments";
            else if (method == Method.GET && "/v1/users".equals(path)) operation = "users";
            else if (method == Method.GET && "/v1/posts".equals(path)) operation = "posts";
            else if (method == Method.POST && "/v1/analysis".equals(path)) operation = "analysis";
            else if (method == Method.POST && "/v1/tasks/batch".equals(path)) operation = "batch_start";
            else if ("/v1/tasks".equals(path)) {
                operation = method == Method.GET ? "tasks" : "start";
                if (method == Method.POST && "comment".equals(params.optString("kind", "comment")) && params.optString("photoId").isBlank())
                    return response(Response.Status.BAD_REQUEST, "POST_ID_REQUIRED", null);
            }
            else if (path.matches("/v1/tasks/[1-9][0-9]{0,17}(?:/(?:resume|stop|pause|retry|events|targets|auto))?")) {
                String[] parts = path.split("/"); params.put("taskId", Long.parseLong(parts[3]));
                operation = parts.length == 4 ? "status" : parts[4];
                if (("status".equals(operation) || "events".equals(operation) || "targets".equals(operation)) != (method == Method.GET))
                    return response(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", null);
                if ("pause".equals(operation)) params.put("code", "manual_pause");
            } else if ("/v1/settings".equals(path)) operation = method == Method.GET ? "settings_get" : "settings_save";
            else return response(Response.Status.NOT_FOUND, "NOT_FOUND", null);
            return response(Response.Status.OK, null, CollectorClient.call(context, operation, params));
        } catch (IllegalStateException error) { return response(Response.Status.CONFLICT, "STATE_CONFLICT", error.getMessage()); }
        catch (Exception error) { return response(Response.Status.BAD_REQUEST, "INVALID_REQUEST", null); }
    }
    private Response response(Response.Status status, String code, Object data) {
        JSONObject body = new JSONObject();
        try {
            body.put("apiVersion", 1).put("ok", code == null);
            if (code == null) body.put("data", data);
            else body.put("error", new JSONObject().put("code", code).put("message", data == null ? code : data));
        } catch (Exception ignored) {}
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString());
        response.addHeader("Cache-Control", "no-store"); response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }
    private static final class BoundedRunner implements AsyncRunner {
        private final Set<ClientHandler> handlers = ConcurrentHashMap.newKeySet();
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> new Thread(runnable, "collector-api"));
        @Override public void exec(ClientHandler handler) {
            handlers.add(handler);
            try { executor.execute(handler); }
            catch (java.util.concurrent.RejectedExecutionException error) { handler.close(); handlers.remove(handler); }
        }
        @Override public void closed(ClientHandler handler) { handlers.remove(handler); }
        @Override public void closeAll() { for (ClientHandler handler : handlers) handler.close(); handlers.clear(); executor.shutdownNow(); }
    }
}
