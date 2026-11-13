package dev.local.commentcollector;

import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Background paging for Douyin comments. The app's own Retrofit layer performs {@code CommentRealApi.fetchCommentListV2Post};
 * we remember the proxy, method and arguments of the app's most recent real call, then replay it with a new cursor.
 * Signing, headers and parsing all stay inside Douyin's pipeline, and the parsed {@code CommentItemList} flows through the
 * same model hook as an on-screen load, so nothing here touches the UI.
 */
final class DouyinNativePager {
    private static final String TAG = "CommentCollector";
    private static volatile Object proxy;
    private static volatile Method method;
    private static volatile Object[] args;
    private static volatile boolean hooked;
    private static volatile long capturedAt;
    private static final ThreadLocal<Boolean> replaying = new ThreadLocal<>();
    // Douyin tells the server which comments the session already showed (session_show_cids) and how many top-level
    // comments are loaded (current_l1_comment_count); replaying the first call unchanged makes it resend the same ones.
    private static final int L1_COUNT = 27, SHOWN_CIDS = 46, SHOWN_CAP = 5000;
    private static final java.util.LinkedHashSet<String> shown = new java.util.LinkedHashSet<>();
    private static volatile boolean replayStarted;
    private static String shownPost = "";
    private static volatile String lastCapturedPost = "";
    static volatile java.util.function.Consumer<String> onPostCaptured;

    private static volatile Object profileProxy;
    private static volatile Method profileMethod;

    static boolean ready() { return proxy != null && method != null && args != null; }
    static boolean ready(String expectPost) {
        return proxy != null && method != null && args != null && (expectPost == null || expectPost.isEmpty() || expectPost.equals(post()));
    }
    static boolean profileReady() { return profileProxy != null && profileMethod != null; }
    static String post() { Object[] a = args; return a != null && a.length > 0 && a[0] != null ? String.valueOf(a[0]) : ""; }
    static String lastCapturedPost() { return lastCapturedPost == null ? "" : lastCapturedPost; }

    /** Finds the Retrofit invocation handler class through a throwaway proxy and hooks its invoke(). */
    static synchronized void install(XposedInterface xposed, ClassLoader loader) {
        if (hooked) return;
        Log.i(TAG, "douyin_native_install_start");
        try {
            Class<?> api = loader.loadClass("com.ss.android.ugc.aweme.comment.api.CommentRealApi");
            Class<?> serviceType = loader.loadClass("com.ss.android.ugc.aweme.framework.services.IRetrofitService");
            Class<?> factory = loader.loadClass("com.ss.android.ugc.aweme.services.RetrofitService");
            Object service = null;
            for (Method m : factory.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && serviceType.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true); service = m.invoke(null); if (service != null) break;
                }
            }
            Log.i(TAG, "douyin_native_service " + service);
            if (service == null) { Log.w(TAG, "douyin_native_no_service"); return; }
            Object retrofit = serviceType.getMethod("createNewRetrofit", String.class).invoke(service, "https://api.amemv.com");
            Object sample = retrofit.getClass().getMethod("create", Class.class).invoke(retrofit, api);
            Log.i(TAG, "douyin_native_sample " + sample);
            if (proxy == null) proxy = sample;
            if (method == null) {
                for (Method m : api.getDeclaredMethods()) {
                    if ("fetchCommentListV2Post".equals(m.getName())) { method = m; break; }
                }
            }
            try {
                Class<?> profileApi = loader.loadClass("com.ss.android.ugc.aweme.feedliveshare.screen.api.IFeedLiveShareProfileApi");
                profileProxy = retrofit.getClass().getMethod("create", Class.class).invoke(retrofit, profileApi);
                for (Method m : profileApi.getDeclaredMethods()) {
                    if ("getProfileUserOther".equals(m.getName())) { profileMethod = m; break; }
                }
                Log.i(TAG, "douyin_native_profile_api " + profileMethod);
            } catch (Throwable profileError) {
                Log.w(TAG, "douyin_native_profile_init_failed", profileError);
            }
            Class<?> handler = Proxy.getInvocationHandler(sample).getClass();
            Method invoke = handler.getDeclaredMethod("invoke", Object.class, Method.class, Object[].class);
            xposed.hook(invoke).intercept(chain -> {
                Method called = (Method) chain.getArg(1);
                if (called != null && "fetchCommentListV2Post".equals(called.getName()) && called.getDeclaringClass() == api) {
                    if (replaying.get() != null) {
                        return chain.proceed();
                    }
                    Object[] raw = (Object[]) chain.getArg(2);
                    if (raw != null) {
                        proxy = chain.getArg(0); method = called; args = raw.clone(); capturedAt = System.currentTimeMillis();
                        String current = post();
                        if (!current.isEmpty()) {
                            lastCapturedPost = current;
                            java.util.function.Consumer<String> cb = onPostCaptured;
                            if (cb != null) cb.accept(current);
                        }
                        Log.i(TAG, "douyin_native_captured post=" + current + " cursor=" + raw[1] + " count=" + raw[2]);
                    }
                }
                return chain.proceed();
            });
            hooked = true;
            Log.i(TAG, "douyin_native_installed handler=" + handler.getName());
        } catch (Throwable error) { Log.w(TAG, "douyin_native_install_failed", error); }
    }

    /** Replays the captured request from a worker thread; the parsed list reaches DouyinComments via the model hook. */
    static Object fetch(long cursor, String expectPost) {
        Object p = proxy; Method m = method; Object[] a = args;
        if (p == null || m == null || a == null) return null;
        if (expectPost != null && !expectPost.isEmpty() && !expectPost.equals(post())) {
            Log.w(TAG, "douyin_native_fetch_skipped: not bound to post=" + expectPost + " current=" + post());
            return null;
        }
        try {
            Object[] copy = a.clone();
            if (copy.length > 0) copy[0] = expectPost;
            if (copy.length > 1) copy[1] = cursor;
            if (copy.length > 2 && copy[2] instanceof Integer && ((Integer) copy[2]) <= 0) copy[2] = 20;
            synchronized (shown) {
                if (!expectPost.equals(shownPost) || cursor == 0) { shown.clear(); shownPost = expectPost; }
                if (a != null && !replayStarted) {
                    if (a.length > SHOWN_CIDS && a[SHOWN_CIDS] instanceof String) {
                        for (String id : ((String) a[SHOWN_CIDS]).split(",")) if (!id.isEmpty()) shown.add(id);
                    }
                    replayStarted = true;
                }
                if (copy.length > SHOWN_CIDS && copy[L1_COUNT] instanceof Integer && copy[SHOWN_CIDS] instanceof String) {
                    copy[L1_COUNT] = (int) cursor;
                    StringBuilder ids = new StringBuilder(); int skip = Math.max(0, shown.size() - SHOWN_CAP), n = 0;
                    for (String id : shown) { if (n++ < skip) continue; if (ids.length() > 0) ids.append(','); ids.append(id); }
                    copy[SHOWN_CIDS] = ids.toString();
                }
            }
            replaying.set(Boolean.TRUE);
            Object task;
            try {
                task = m.invoke(p, copy);
            } finally { replaying.set(null); }
            if (task != null) {
                try {
                    Method waitM = task.getClass().getMethod("waitForCompletion");
                    waitM.invoke(task);
                } catch (Throwable ignored) {}
            }
            Object result = null;
            if (task != null) {
                try { result = task.getClass().getMethod("getResult").invoke(task); } catch (Throwable ignored) {}
            }
            String outcome = " result=" + (result == null ? "null" : result.getClass().getName());
            if (result != null) try {
                org.json.JSONObject page = DouyinComments.page(result, expectPost);
                if (page != null) synchronized (shown) {
                    org.json.JSONArray rows = page.getJSONArray("rows");
                    for (int i = 0; i < rows.length(); i++) shown.add(rows.getJSONObject(i).optString("commentId"));
                }
            } catch (Throwable ignored) {}
            Log.i(TAG, "douyin_native_fetched post=" + expectPost + " cursor=" + cursor + outcome);
            return result;
        } catch (Throwable error) { Log.w(TAG, "douyin_native_fetch_failed", error); return null; }
    }

    private static String paramName(java.lang.annotation.Annotation[] annotations) {
        for (java.lang.annotation.Annotation a : annotations) {
            String type = a.annotationType().getSimpleName();
            if (type.equals("Query") || type.equals("Field")) {
                try { return String.valueOf(a.annotationType().getMethod("value").invoke(a)); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Method replyMethod(Class<?> api) {
        for (Method m : api.getMethods()) {
            if (!m.getReturnType().getName().equals("bolts.Task")) continue;
            for (java.lang.annotation.Annotation a : m.getAnnotations()) {
                if (!a.annotationType().getSimpleName().equals("POST")) continue;
                try { if ("/aweme/v1/comment/list/reply/".equals(a.annotationType().getMethod("value").invoke(a))) return m; } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class; if (type == long.class) return Long.class; if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class; if (type == double.class) return Double.class; if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class; return Character.class;
    }

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE; if (type == long.class) return 0L; if (type == float.class) return 0f;
        if (type == double.class) return 0d; if (type == short.class) return (short) 0; if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0; return 0;
    }

    /**
     * Replays Douyin's own reply-list call ({@code /aweme/v1/comment/list/reply/}) for one top-level comment. The argument
     * list is built by query-parameter name from the captured comment-list call, so it survives obfuscated renames.
     * Returns the parsed CommentItemList (possibly empty), or null when the call itself failed.
     */
    static Object fetchReplies(String rootId, long cursor, String expectPost) {
        Object p = proxy; Method lm = method; Object[] la = args;
        if (p == null || lm == null) return null;
        try {
            Method rm = replyMethod(lm.getDeclaringClass());
            if (rm == null) { Log.w(TAG, "douyin_reply_method_missing"); return null; }
            java.util.Map<String, Object> known = new java.util.HashMap<>();
            if (la != null) {
                java.lang.annotation.Annotation[][] listNotes = lm.getParameterAnnotations();
                for (int i = 0; i < la.length && i < listNotes.length; i++) { String n = paramName(listNotes[i]); if (n != null && la[i] != null) known.put(n, la[i]); }
                known.remove("insert_ids");
            }
            known.put("comment_id", rootId); known.put("cursor", cursor); known.put("count", 20); known.put("item_id", expectPost);
            Class<?>[] types = rm.getParameterTypes(); java.lang.annotation.Annotation[][] notes = rm.getParameterAnnotations();
            Object[] out = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                String n = paramName(notes[i]); Object v = n == null ? null : known.get(n);
                out[i] = v != null && box(types[i]).isInstance(v) ? v : zero(types[i]);
            }
            replaying.set(Boolean.TRUE);
            Object task;
            try { task = rm.invoke(p, out); } finally { replaying.set(null); }
            if (task == null) return null;
            try { task.getClass().getMethod("waitForCompletion").invoke(task); } catch (NoSuchMethodException ignored) {}
            Object result = null;
            try { result = task.getClass().getMethod("getResult").invoke(task); } catch (Throwable ignored) {}
            if (result == null) { try { Object error = task.getClass().getMethod("getError").invoke(task); Log.w(TAG, "douyin_reply_fetch_error " + error); } catch (Throwable ignored) {} }
            Log.i(TAG, "douyin_reply_fetched root=" + rootId + " cursor=" + cursor + " result=" + (result == null ? "null" : "ok"));
            return result;
        } catch (Throwable error) { Log.w(TAG, "douyin_reply_fetch_failed", error); return null; }
    }

    /**
     * Calls Douyin's own user profile API (/aweme/v1/user/profile/other/) via Retrofit proxy.
     * Returns the parsed UserResponse or null.
     */
    static Object fetchProfile(String userId, String secUserId) {
        Object p = profileProxy; Method m = profileMethod;
        if (p == null || m == null) return null;
        try {
            Object obs = m.invoke(p, userId, secUserId == null ? "" : secUserId, "");
            if (obs == null) return null;
            Method blockingFirst = obs.getClass().getMethod("blockingFirst");
            return blockingFirst.invoke(obs);
        } catch (Throwable error) {
            Log.w(TAG, "douyin_native_profile_call_failed uid=" + userId, error);
            return null;
        }
    }
}
