package dev.local.commentcollector;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Small client for the same account service used by the browser extension.
 * It deliberately keeps the local database as the source of truth when the
 * network is unavailable; sync is an eventually-consistent side effect.
 */
public final class CloudAccountClient {
    public static final String DEFAULT_API_BASE_URL = "https://comment-filter.04210410.xyz";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 90_000;
    private static final String SETTINGS_NAME = "cloud";

    private CloudAccountClient() {}

    public static JSONObject config(Context context) throws Exception {
        JSONObject config = CollectorClient.call(context, "settings_get", new JSONObject().put("name", SETTINGS_NAME));
        String base = cleanBaseUrl(config.optString("apiBaseUrl", DEFAULT_API_BASE_URL));
        return new JSONObject(config.toString()).put("apiBaseUrl", base);
    }

    public static JSONObject login(Context context, String email, String password, String apiBaseUrl) throws Exception {
        String cleanEmail = String.valueOf(email == null ? "" : email).trim().toLowerCase(java.util.Locale.ROOT);
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) throw new IllegalArgumentException("请输入有效邮箱。");
        if (password == null || password.length() < 12) throw new IllegalArgumentException("密码至少需要 12 个字符。");
        JSONObject response = request(cleanBaseUrl(apiBaseUrl), "/v1/auth/login", "POST", null,
            new JSONObject().put("email", cleanEmail).put("password", password));
        String token = response.optString("accessToken");
        if (token.isEmpty()) throw new IllegalStateException("账户服务没有返回登录令牌。");
        JSONObject user = response.optJSONObject("user");
        JSONObject saved = new JSONObject().put("apiBaseUrl", cleanBaseUrl(apiBaseUrl))
            .put("email", cleanEmail).put("tenantId", user == null ? "" : user.optString("tenantId"))
            .put("accessToken", token).put("lastSyncAt", 0).put("lastSyncError", "");
        save(context, saved);
        return user == null ? new JSONObject().put("email", cleanEmail) : user;
    }

    public static JSONObject currentUser(Context context) throws Exception {
        JSONObject config = config(context);
        String token = config.optString("accessToken");
        if (token.isEmpty()) throw new IllegalStateException("尚未登录云端账户。");
        return request(config.optString("apiBaseUrl"), "/v1/auth/me", "GET", token, null).optJSONObject("user");
    }

    public static void logout(Context context) throws Exception {
        JSONObject config = config(context).put("accessToken", "").put("tenantId", "").put("lastSyncError", "");
        save(context, config);
    }

    public static JSONObject sync(Context context) throws Exception {
        JSONObject config = config(context);
        String token = config.optString("accessToken");
        if (token.isEmpty()) throw new IllegalStateException("请先登录云端账户。");
        JSONObject snapshot = CollectorClient.call(context, "cloud_snapshot", new JSONObject());
        try {
            JSONObject result = request(config.optString("apiBaseUrl"), "/v1/sync/snapshot", "POST", token,
                new JSONObject().put("posts", snapshot.optJSONArray("posts") == null ? new org.json.JSONArray() : snapshot.optJSONArray("posts"))
                    .put("comments", snapshot.optJSONArray("comments") == null ? new org.json.JSONArray() : snapshot.optJSONArray("comments"))
                    .put("users", snapshot.optJSONArray("users") == null ? new org.json.JSONArray() : snapshot.optJSONArray("users"))
                    .put("analysisScopes", new org.json.JSONArray()));
            save(context, config.put("lastSyncAt", System.currentTimeMillis()).put("lastSyncError", ""));
            return result;
        } catch (Exception error) {
            save(context, config.put("lastSyncError", error.getMessage() == null ? "同步失败" : error.getMessage()));
            throw error;
        }
    }

    public static void save(Context context, JSONObject config) throws Exception {
        CollectorClient.call(context, "settings_save", new JSONObject().put("name", SETTINGS_NAME).put("value", config));
    }

    private static String cleanBaseUrl(String value) {
        String base = String.valueOf(value == null ? "" : value).trim().replaceAll("/+$", "");
        if (base.isEmpty()) base = DEFAULT_API_BASE_URL;
        if (!base.matches("https://[^/\\s]+(?:/[^\\s]*)?")) throw new IllegalArgumentException("账户服务地址必须使用 HTTPS。");
        return base;
    }

    private static JSONObject request(String base, String path, String method, String token, JSONObject body) throws Exception {
        URL url = new URL(cleanBaseUrl(base) + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(CONNECT_TIMEOUT_MS); connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        String payload = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        JSONObject result;
        try { result = payload.isEmpty() ? new JSONObject() : new JSONObject(payload); }
        catch (Exception error) { throw new IllegalStateException("账户服务返回了无法解析的响应（" + status + "）。"); }
        if (status < 200 || status >= 300) {
            String message = result.optString("error", result.optString("message", "账户服务请求失败（" + status + "）。"));
            IllegalStateException error = new IllegalStateException(message); if (status == 401) error = new IllegalStateException("登录已过期，请重新登录。");
            throw error;
        }
        return result;
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) output.append(line);
        }
        return output.toString();
    }
}
