package dev.local.commentcollector;

import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Background native paging for Xiaohongshu (com.xingin.xhs) comments.
 * The app's own Retrofit CommentService performs getCommentList and getSubCommentsV3;
 * we capture the proxy, method and arguments of real calls and replay them with new cursors.
 * Returned RxJava2 Observables are resolved via blockingFirst() on worker threads, bypassing UI scrolling.
 */
final class XhsNativePager {
    private static final String TAG = "CommentCollector";
    private static final String SERVICE_CLASS = "com.xingin.matrix.comment.model.service.CommentService";

    private static volatile Object proxy;
    private static volatile Method listMethod, replyMethod;
    private static volatile Object[] listArgs, replyArgs;
    private static volatile boolean hooked;
    private static volatile String lastCapturedPost = "";
    static volatile Consumer<String> onPostCaptured;
    private static final ThreadLocal<Boolean> replaying = new ThreadLocal<>();
    private static volatile ClassLoader cachedLoader;

    private XhsNativePager() {}

    static boolean ready() {
        if (proxy == null && cachedLoader != null) tryInitProxy(cachedLoader);
        return proxy != null && listMethod != null;
    }

    static String post() {
        Object[] a = listArgs;
        if (a != null && a.length > 0 && a[0] != null) {
            String p = String.valueOf(a[0]);
            if (!p.isEmpty() && !"null".equals(p)) return p;
        }
        return lastCapturedPost == null ? "" : lastCapturedPost;
    }

    static String lastCapturedPost() {
        return lastCapturedPost == null ? "" : lastCapturedPost;
    }

    static synchronized void install(XposedInterface xposed, ClassLoader loader) {
        if (hooked) return;
        cachedLoader = loader;
        Log.i(TAG, "xhs_native_install_start");
        try {
            Class<?> serviceClass = loader.loadClass(SERVICE_CLASS);
            for (Method m : serviceClass.getDeclaredMethods()) {
                if ("getCommentList".equals(m.getName())) listMethod = m;
                else if ("getSubCommentsV3".equals(m.getName())) replyMethod = m;
            }
            Log.i(TAG, "xhs_native_methods list=" + listMethod + " reply=" + replyMethod);

            tryInitProxy(loader);

            Class<?> handlerClass = null;
            if (proxy != null && Proxy.isProxyClass(proxy.getClass())) {
                handlerClass = Proxy.getInvocationHandler(proxy).getClass();
            }
            if (handlerClass == null) {
                try { handlerClass = loader.loadClass("retrofit2.z$a"); } catch (Throwable ignored) {}
            }
            if (handlerClass == null) {
                for (String candidate : new String[]{"retrofit2.z$a", "retrofit2.y$a", "retrofit2.x$a"}) {
                    try {
                        Class<?> c = loader.loadClass(candidate);
                        if (InvocationHandler.class.isAssignableFrom(c)) { handlerClass = c; break; }
                    } catch (Throwable ignored) {}
                }
            }

            if (handlerClass != null) {
                Method invoke = handlerClass.getDeclaredMethod("invoke", Object.class, Method.class, Object[].class);
                xposed.hook(invoke).intercept(chain -> {
                    Method called = (Method) chain.getArg(1);
                    if (called != null && SERVICE_CLASS.equals(called.getDeclaringClass().getName())) {
                        String name = called.getName();
                        Object[] rawArgs = (Object[]) chain.getArg(2);
                        if ("getCommentList".equals(name)) {
                            proxy = chain.getArg(0);
                            listMethod = called;
                            if (rawArgs != null) listArgs = rawArgs.clone();
                            if (replaying.get() == null && rawArgs != null && rawArgs.length > 0 && rawArgs[0] != null) {
                                String current = String.valueOf(rawArgs[0]);
                                if (!current.isEmpty() && !"null".equals(current)) {
                                    lastCapturedPost = current;
                                    Consumer<String> cb = onPostCaptured;
                                    if (cb != null) cb.accept(current);
                                }
                            }
                            Log.i(TAG, "xhs_native_captured getCommentList post=" + lastCapturedPost);
                        } else if ("getSubCommentsV3".equals(name)) {
                            proxy = chain.getArg(0);
                            replyMethod = called;
                            if (rawArgs != null) replyArgs = rawArgs.clone();
                            Log.i(TAG, "xhs_native_captured getSubCommentsV3");
                        }
                    }
                    return chain.proceed();
                });
                hooked = true;
                Log.i(TAG, "xhs_native_installed handler=" + handlerClass.getName());
            } else {
                Log.w(TAG, "xhs_native_handler_not_found");
            }
        } catch (Throwable error) {
            Log.w(TAG, "xhs_native_install_failed", error);
        }
    }

    private static void tryInitProxy(ClassLoader loader) {
        if (proxy != null) return;
        try {
            Class<?> serviceClass = loader.loadClass(SERVICE_CLASS);
            // 1. Try static instance in dx8.i.c
            try {
                Class<?> dx8Class = loader.loadClass("dx8.i");
                Field cField = dx8Class.getDeclaredField("c");
                cField.setAccessible(true);
                Object instance = cField.get(null);
                if (instance != null && serviceClass.isInstance(instance)) {
                    proxy = instance;
                    Log.i(TAG, "xhs_native_found_static_service " + instance);
                    return;
                }
            } catch (Throwable ignored) {}

            // 2. Try singleton getter via hba.c.a.b(CommentService.class)
            try {
                Class<?> hbaClass = loader.loadClass("hba.c");
                Field aField = hbaClass.getDeclaredField("a");
                aField.setAccessible(true);
                Object aInstance = aField.get(null);
                if (aInstance != null) {
                    Method bMethod = aInstance.getClass().getMethod("b", Class.class);
                    Object instance = bMethod.invoke(aInstance, serviceClass);
                    if (instance != null && serviceClass.isInstance(instance)) {
                        proxy = instance;
                        Log.i(TAG, "xhs_native_found_hba_service " + instance);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** Replays getCommentList on worker thread and blocks synchronously via blockingFirst(). */
    static Object fetch(String cursor, String expectPost) {
        if (!ready() || expectPost == null || expectPost.isEmpty()) return null;
        Object p = proxy;
        Method m = listMethod;
        if (p == null || m == null) return null;
        try {
            Object[] copy = buildListArgs(m, expectPost, cursor);
            replaying.set(Boolean.TRUE);
            Object observable;
            try {
                observable = m.invoke(p, copy);
            } finally {
                replaying.set(null);
            }
            if (observable == null) return null;
            Method blockingFirst = observable.getClass().getMethod("blockingFirst");
            Object response = blockingFirst.invoke(observable);
            Log.i(TAG, "xhs_native_fetch post=" + expectPost + " cursor=" + cursor + " result=" + (response == null ? "null" : response.getClass().getName()));
            return response;
        } catch (Throwable error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            Log.w(TAG, "xhs_native_fetch_failed " + cause.getMessage());
            return null;
        }
    }

    /** Replays getSubCommentsV3 on worker thread and blocks synchronously via blockingFirst(). */
    static Object fetchReplies(String rootId, String cursor, String expectPost) {
        if (!ready() || replyMethod == null || expectPost == null || expectPost.isEmpty() || rootId == null || rootId.isEmpty()) return null;
        Object p = proxy;
        Method m = replyMethod;
        if (p == null || m == null) return null;
        try {
            Object[] copy = buildReplyArgs(m, expectPost, rootId, cursor);
            replaying.set(Boolean.TRUE);
            Object observable;
            try {
                observable = m.invoke(p, copy);
            } finally {
                replaying.set(null);
            }
            if (observable == null) return null;
            Method blockingFirst = observable.getClass().getMethod("blockingFirst");
            Object response = blockingFirst.invoke(observable);
            Log.i(TAG, "xhs_native_fetch_replies root=" + rootId + " cursor=" + cursor + " result=" + (response == null ? "null" : response.getClass().getName()));
            return response;
        } catch (Throwable error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            Log.w(TAG, "xhs_native_fetch_replies_failed " + cause.getMessage());
            return null;
        }
    }

    private static Object[] buildListArgs(Method m, String expectPost, String cursor) {
        Object[] a = listArgs;
        if (a != null && a.length == m.getParameterCount()) {
            Object[] copy = a.clone();
            copy[0] = expectPost;
            copy[1] = cursor == null ? "" : cursor;
            if (copy.length > 2 && copy[2] instanceof Integer) copy[2] = 20;
            return copy;
        }
        Class<?>[] types = m.getParameterTypes();
        Object[] copy = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (i == 0 && t == String.class) copy[i] = expectPost;
            else if (i == 1 && t == String.class) copy[i] = cursor == null ? "" : cursor;
            else if (i == 2 && (t == int.class || t == Integer.class)) copy[i] = 20;
            else if (t == String.class) copy[i] = "";
            else if (t == boolean.class || t == Boolean.class) copy[i] = Boolean.FALSE;
            else if (t == int.class || t == Integer.class) copy[i] = 0;
            else if (t == long.class || t == Long.class) copy[i] = 0L;
            else copy[i] = null;
        }
        return copy;
    }

    private static Object[] buildReplyArgs(Method m, String expectPost, String rootId, String cursor) {
        Object[] a = replyArgs;
        if (a != null && a.length == m.getParameterCount()) {
            Object[] copy = a.clone();
            copy[0] = expectPost;
            copy[1] = rootId;
            copy[2] = cursor == null ? "" : cursor;
            if (copy.length > 3 && copy[3] instanceof Integer) copy[3] = 10;
            return copy;
        }
        Class<?>[] types = m.getParameterTypes();
        Object[] copy = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (i == 0 && t == String.class) copy[i] = expectPost;
            else if (i == 1 && t == String.class) copy[i] = rootId;
            else if (i == 2 && t == String.class) copy[i] = cursor == null ? "" : cursor;
            else if (i == 3 && (t == int.class || t == Integer.class)) copy[i] = 10;
            else if (t == String.class) copy[i] = "";
            else if (t == boolean.class || t == Boolean.class) copy[i] = Boolean.FALSE;
            else if (t == int.class || t == Integer.class) copy[i] = 0;
            else if (t == long.class || t == Long.class) copy[i] = 0L;
            else copy[i] = null;
        }
        return copy;
    }
}
