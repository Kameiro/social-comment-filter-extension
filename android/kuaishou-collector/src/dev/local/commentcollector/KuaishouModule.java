package dev.local.commentcollector;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class KuaishouModule extends XposedModule {
    private static final String TARGET = "com.smile.gifmaker";
    private static final String TAG = "CommentCollector";
    private final ThreadLocal<JSONObject> request = new ThreadLocal<>();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), runnable -> new Thread(runnable, "comment-capture"));
    private String process;
    private volatile Context context;
    private volatile String lastPhoto = "";
    private String version = "";
    private AutoPager pager;
    private final CommentPageCache recentPages = new CommentPageCache();
    private final NativeCommentPager nativePager = new NativeCommentPager();
    private final WeakIdentityMap<Boolean> processed = new WeakIdentityMap<>();
    private final WeakIdentityMap<String> profileSeen = new WeakIdentityMap<>();
    private final WeakIdentityMap<JSONObject> replyContext = new WeakIdentityMap<>();
    private final WeakIdentityMap<String> commentSeen = new WeakIdentityMap<>();
    private final java.util.Set<String> commentSchemas = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName();
        Log.i(TAG, "module_loaded process=" + process);
        if (!TARGET.equals(process) && !"dev.local.commentcollector".equals(process)) detach();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        Log.i(TAG, "package_ready first=" + param.isFirstPackage() + " package=" + param.getPackageName());
        if (!param.isFirstPackage() || !param.getPackageName().equals(process)) return;
        ClassLoader loader = param.getClassLoader();
        if ("dev.local.commentcollector".equals(process)) {
            try {
                Method probe = loader.loadClass("dev.local.commentcollector.MainActivity").getDeclaredMethod("hookProbe");
                hook(probe).intercept(chain -> {
                    chain.proceed();
                    log(Log.INFO, TAG, "probe_executed api=" + getApiVersion());
                    Log.i(TAG, "probe_executed api=" + getApiVersion());
                    return "hook-active / API " + getApiVersion();
                });
            } catch (Exception error) { log(Log.ERROR, TAG, "probe_hook_failed", error); }
            return;
        }
        try {
            Method applicationStart = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            hook(applicationStart).intercept(chain -> {
                Object result = chain.proceed();
                attach((Application) chain.getArg(0));
                return result;
            });
            hook(Dialog.class.getDeclaredMethod("show")).intercept(chain -> {
                Object result = chain.proceed();
                if (pager != null) pager.dialog = new WeakReference<>((Dialog) chain.getThisObject());
                return result;
            });
            Method windowAdd = Class.forName("android.view.WindowManagerImpl")
                .getDeclaredMethod("addView", android.view.View.class, android.view.ViewGroup.LayoutParams.class);
            hook(windowAdd).intercept(chain -> {
                Object result = chain.proceed();
                if (pager != null) pager.trackWindow((android.view.View) chain.getArg(0));
                return result;
            });
        } catch (Exception error) { log(Log.ERROR, TAG, "lifecycle_hook_failed", error); }
        installRequests(loader);
        installNativePaging(loader);
        installConverter(loader, "retrofit2.converter.gson.GsonResponseBodyConverter");
        installConverter(loader, "com.kwai.feature.api.corona.api.fast.CoronaProxyGsonResponseBodyConverter");
        try {
            Class<?> gson = loader.loadClass("com.google.gson.Gson");
            for (Method method : gson.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (method.getReturnType() != Object.class || args.length != 2 ||
                    (!java.lang.reflect.Type.class.isAssignableFrom(args[1]) && args[1] != Class.class)) continue;
                observe(method);
            }
        } catch (Exception error) { log(Log.WARN, TAG, "gson_hook_unavailable"); }
        try {
            Method getter = loader.loadClass("com.yxcorp.gifshow.comment.common.model.response.CommonCommentResponse")
                .getDeclaredMethod("getMComments");
            hook(getter).intercept(chain -> {
                Object result = chain.proceed();
                capture(chain.getThisObject());
                return result;
            });
        } catch (Exception error) { log(Log.WARN, TAG, "model_hook_unavailable"); }
        try {
            Method getter = loader.loadClass("com.kuaishou.android.model.mix.QSubComment").getDeclaredMethod("getKComments");
            hook(getter).intercept(chain -> { Object result = chain.proceed(); capture(chain.getThisObject()); return result; });
        } catch (Exception error) { log(Log.WARN, TAG, "reply_model_hook_unavailable"); }
        try {
            Method getter = loader.loadClass("com.kwai.framework.model.user.UserProfile").getDeclaredMethod("getKUserInfo");
            hook(getter).intercept(chain -> { Object result = chain.proceed(); capture(chain.getThisObject()); return result; });
            Method caption = loader.loadClass("com.yxcorp.gifshow.entity.QPhoto").getDeclaredMethod("getCaption");
            hook(caption).intercept(chain -> {
                Object result = chain.proceed(); Object value = chain.getThisObject();
                try { if (context != null) synchronized (processed) {
                    if (!processed.containsKey(value)) {
                        processed.put(value, Boolean.TRUE); JSONObject post = PublicMapper.post(value);
                        if (post != null) writer.execute(() -> { try { CollectorClient.call(context, "record_post", new JSONObject().put("post", post)); } catch (Exception ignored) {} });
                    }
                } } catch (Exception ignored) { Log.w(TAG, "post_observation_failed"); }
                return result;
            });
        } catch (Exception error) { log(Log.WARN, TAG, "public_model_hook_unavailable"); }
        log(Log.INFO, TAG, "hooks_installed");
    }

    private void attach(Application app) {
        if (context != null) return;
        context = app;
        try { version = app.getPackageManager().getPackageInfo(TARGET, 0).versionName; }
        catch (Exception ignored) {}
        nativePager.attach(app, version, data -> writer.execute(() -> {
            try { CollectorClient.call(app, "native_eof", data); }
            catch (Exception error) { Log.w(TAG, "native_eof_storage_failed"); }
        }), (sub, metadata) -> {
            replyContext.put(sub, metadata);
            capture(sub);
        });
        pager = new AutoPager(app, () -> lastPhoto, this::replayCached, nativePager);
        app.registerActivityLifecycleCallbacks(pager);
        pager.start();
        log(Log.INFO, TAG, "application_attached version=" + version);
    }

    private void installNativePaging(ClassLoader loader) {
        try {
            Class<?> type = loader.loadClass("com.kwai.kmp.comment.mvi.panel.pagelist.KmpCommentFeaturePageList");
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                nativePager.bind(chain.getThisObject());
                Log.i(TAG, "native_kmp_pager_created");
                return result;
            });
            hook(type.getDeclaredMethod("k")).intercept(chain -> {
                nativePager.bind(chain.getThisObject());
                Log.i(TAG, "native_kmp_load_observed");
                return chain.proceed();
            });
        } catch (Exception error) { Log.i(TAG, "native_kmp_probe_unavailable"); }
    }

    private void installRequests(ClassLoader loader) {
        try {
            // 14.8.40.50567's Retrofit call class is obfuscated, but its response signature is stable.
            Class<?> call;
            try { call = loader.loadClass("retrofit2.OkHttpCall"); }
            catch (ClassNotFoundException ignored) { call = loader.loadClass("retrofit2.k"); }
            for (Method method : call.getDeclaredMethods()) {
                if (method.getParameterCount() != 1 ||
                    !"okhttp3.Response".equals(method.getParameterTypes()[0].getName())) continue;
                hook(method).intercept(chain -> {
                    JSONObject previous = request.get();
                    JSONObject metadata = requestInfo(chain.getArg(0));
                    request.set(metadata);
                    try {
                        Object result = chain.proceed();
                        Object body = SnapshotMapper.field(result, "body", "b");
                        if (body != null) capture(body);
                        try { if (metadata != null && context != null) {
                            Object code = SnapshotMapper.field(chain.getArg(0), "code");
                            JSONObject task = CollectorClient.call(context, "status", new JSONObject());
                            boolean matches = "comment".equals(task.optString("kind")) && !metadata.optString("photoId").isEmpty() && metadata.optString("photoId").equals(task.optString("photoId")) ||
                                "discovery".equals(task.optString("kind")) && !metadata.optString("keyword").isEmpty() && metadata.optString("keyword").equals(task.getJSONObject("params").optString("keyword"));
                            if (matches &&
                                code instanceof Number && ((Number) code).intValue() >= 400)
                                pause("http_" + ((Number) code).intValue());
                        } } catch (Exception ignored) { Log.w(TAG, "response_diagnostic_unavailable"); }
                        return result;
                    } finally { if (previous == null) request.remove(); else request.set(previous); }
                });
            }
        } catch (Exception error) { log(Log.WARN, TAG, "request_correlation_unavailable"); }
    }

    private JSONObject requestInfo(Object response) {
        try {
            Object req = SnapshotMapper.field(response, "request");
            Object url = SnapshotMapper.field(req, "url");
            if (url == null) return null;
            URI uri = URI.create(url.toString());
            if (!uri.getPath().toLowerCase(java.util.Locale.ROOT).matches(".*(?:comment|search|profile).*")) return null;
            JSONObject out = new JSONObject().put("appVersion", version);
            out.put("streamType", uri.getPath().toLowerCase(java.util.Locale.ROOT).matches(".*(?:sub|reply).*" ) ? "reply" : "root");
            String query = uri.getRawQuery();
            if (query != null) for (String term : query.split("&")) {
                String[] parts = term.split("=", 2);
                if (parts.length == 2) parameter(out, decode(parts[0]), decode(parts[1]));
            }
            Object body = SnapshotMapper.field(req, "body");
            if (body != null && "okhttp3.FormBody".equals(body.getClass().getName())) {
                Object names = SnapshotMapper.field(body, "encodedNames");
                Object values = SnapshotMapper.field(body, "encodedValues");
                if (names instanceof List<?> && values instanceof List<?>) {
                    for (int i = 0; i < ((List<?>) names).size(); i++) {
                        parameter(out, decode(String.valueOf(((List<?>) names).get(i))), decode(String.valueOf(((List<?>) values).get(i))));
                    }
                }
            }
            if (!out.optString("rootId").isEmpty()) out.put("streamType", "reply");
            else if ("reply".equals(out.optString("streamType")) && out.has("commentAnchorId")) out.put("rootId", out.getString("commentAnchorId"));
            out.remove("commentAnchorId");
            return out;
        } catch (Exception ignored) { return null; }
    }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private void parameter(JSONObject out, String name, String value) throws Exception {
        if ("photoId".equals(name) || "photo_id".equals(name)) out.put("photoId", value);
        boolean parent = !value.isEmpty() && !value.matches("0+|-1|null|undefined");
        if (parent && ("rootCommentId".equals(name) || "rootComment_id".equals(name) || "root_comment_id".equals(name) || "rootId".equals(name))) out.put("rootId", value);
        if (parent && ("commentId".equals(name) || "comment_id".equals(name))) out.put("commentAnchorId", value);
        if ("keyword".equals(name) || "query".equals(name)) out.put("keyword", value);
        if ("pcursor".equals(name) || "cursor".equals(name)) out.put("requestCursorKnown", true).put("requestCursor", value);
    }
    private void installConverter(ClassLoader loader, String name) {
        try {
            for (Method method : loader.loadClass(name).getDeclaredMethods()) {
                if ("convert".equals(method.getName()) && method.getParameterCount() == 1 && !method.isBridge()) observe(method);
            }
        } catch (Exception error) { log(Log.WARN, TAG, "converter_unavailable: " + name); }
    }
    private void observe(Method method) {
        hook(method).intercept(chain -> {
            Object result = chain.proceed();
            capture(result);
            return result;
        });
    }
    private void capture(Object response) {
        if (context == null) return;
        try {
            JSONObject metadata = request.get();
            if (metadata == null) metadata = new JSONObject().put("appVersion", version);
            JSONObject bound = replyContext.get(response);
            if (bound != null) metadata = bound;
            JSONObject profile = PublicMapper.profile(response);
            JSONObject search = PublicMapper.search(response, metadata);
            if (profile != null || search != null) {
                JSONObject state = CollectorClient.call(context, "status", new JSONObject());
                if (profile != null) {
                    JSONObject signature = new JSONObject(profile.toString()); signature.remove("profileCapturedAt"); String fingerprint = JsonContracts.canonical(signature);
                    fingerprint += ":" + state.optLong("taskId") + ":" + state.optLong("generation");
                    synchronized (profileSeen) { if (fingerprint.equals(profileSeen.put(response, fingerprint))) return; }
                } else synchronized (processed) { if (processed.put(response, Boolean.TRUE) != null) return; }
                JSONObject data = new JSONObject().put("taskId", state.optLong("taskId")).put("generation", state.optLong("generation"));
                if (profile != null) data.put("user", profile); else data.put("page", search);
                String operation = profile != null ? "record_profile" : "record_search";
                writer.execute(() -> { try { CollectorClient.call(context, operation, data); } catch (Exception ignored) { pause("storage_error"); } });
                Log.i(TAG, profile != null ? "public_profile_observed" : "search_page_observed posts=" + search.getJSONArray("posts").length());
                return;
            }
            JSONObject page = SnapshotMapper.page(response, metadata);
            if (response != null && "reply".equals(metadata.optString("streamType")) && commentSchemas.add("reply:" + response.getClass().getName()))
                Log.i(TAG, "reply_response_type=" + response.getClass().getName() + " bound_post=" + metadata.has("photoId") + " bound_parent=" + metadata.has("rootId"));
            if (page == null) return;
            recentPages.offer(page, android.os.SystemClock.elapsedRealtime());
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (int i = 0; i < page.getJSONArray("rows").length(); i++) {
                JSONObject row = page.getJSONArray("rows").getJSONObject(i);
                if (row.optInt("level") == 1 && !row.isNull("replies")) counts.put(row.getString("commentId"), row.optInt("replies"));
            }
            Object groups = SnapshotMapper.field(response, "mSubCommentMap");
            if (groups instanceof java.util.Map<?, ?>) for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) groups).entrySet())
                if (entry.getValue() != null) replyContext.put(entry.getValue(), new JSONObject().put("photoId", page.getString("photoId")).put("rootId", String.valueOf(entry.getKey())).put("appVersion", version)
                    .put("expectedReplies", counts.getOrDefault(String.valueOf(entry.getKey()), -1)));
            Object roots = SnapshotMapper.field(response, "mComments");
            nativePager.observeRoots(page.getString("photoId"), roots);
            if (roots instanceof List<?>) for (Object comment : (List<?>) roots) {
                Object sub = SnapshotMapper.field(comment, "mSubComment"), parent = SnapshotMapper.field(comment, "mId");
                if (sub != null && parent != null) replyContext.put(sub, new JSONObject().put("photoId", page.getString("photoId")).put("rootId", String.valueOf(parent)).put("appVersion", version)
                    .put("expectedReplies", counts.getOrDefault(String.valueOf(parent), -1)));
            }
            lastPhoto = page.getString("photoId");
            JSONObject task = CollectorClient.call(context, "status", new JSONObject());
            long taskId = task.optLong("taskId"), generation = task.optLong("generation");
            if (!"comment".equals(task.optString("kind")) || !task.optString("status").startsWith("running") ||
                (!task.optString("photoId").isEmpty() && !lastPhoto.equals(task.optString("photoId")))) return;
            org.json.JSONArray ids = new org.json.JSONArray();
            for (int i = 0; i < page.getJSONArray("rows").length(); i++) {
                JSONObject row = new JSONObject(page.getJSONArray("rows").getJSONObject(i).toString()); row.remove("capturedAt"); ids.put(row);
            }
            String fingerprint = new JSONObject().put("task", taskId).put("generation", generation).put("parent", page.optString("rootId"))
                .put("cursor", page.opt("cursor")).put("more", page.opt("hasMore")).put("rows", ids).put("groups", page.optJSONArray("groups")).toString();
            synchronized (commentSeen) { if (fingerprint.equals(commentSeen.put(response, fingerprint))) return; }
            Object more = SnapshotMapper.field(response, "mHasMore", "hasMore");
            Log.i(TAG, "comment_page_observed rows=" + page.getJSONArray("rows").length() + " more=" + page.opt("hasMore") +
                " more_type=" + (more == null ? "missing" : more.getClass().getSimpleName()) +
                " more_method=" + SnapshotMapper.invoke(response, "hasMore", new Class<?>[0]) + " eof=" + page.optString("eofEvidence"));
            enqueuePage(page, taskId, generation);
        } catch (java.util.concurrent.RejectedExecutionException error) { pause("storage_queue_full"); }
        catch (Exception error) { log(Log.WARN, TAG, "comment_mapping_failed: " + error.getClass().getSimpleName()); }
    }
    private void replayCached(JSONObject task) {
        try {
            if (task.optInt("rootPages") > 0 || task.optBoolean("rootComplete")) return;
            List<JSONObject> pages = recentPages.read(task.optString("photoId"), android.os.SystemClock.elapsedRealtime());
            for (JSONObject page : pages) enqueuePage(page, task.optLong("taskId"), task.optLong("generation"));
            Log.i(TAG, "root_cache_replay pages=" + pages.size());
        } catch (Exception error) { Log.w(TAG, "root_cache_replay_unavailable"); }
    }
    private void enqueuePage(JSONObject page, long taskId, long generation) throws Exception {
        String payload = page.toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > 500000) { pause("page_too_large"); return; }
        writer.execute(() -> {
            try {
                Bundle args = new Bundle(); args.putString("page", payload); args.putLong("taskId", taskId); args.putLong("generation", generation);
                Bundle result = context.getContentResolver().call(CaptureProvider.URI, "append", null, args);
                if (result != null && result.containsKey("error")) Log.w(TAG, "page_storage_failed");
            } catch (Exception error) { Log.w(TAG, "storage_unavailable"); }
        });
    }
    private void pause(String code) {
        if (context == null) return;
        try {
            Bundle args = new Bundle(); args.putString("code", code);
            context.getContentResolver().call(CaptureProvider.URI, "pause", null, args);
        } catch (Exception ignored) {}
    }
}
