package dev.local.commentcollector;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads model fields by Java name or by their JSON key (Gson {@code @SerializedName}), because apps such as
 * Douyin obfuscate many Java field names but keep the serialized names.
 */
final class ModelReader {
    private static final Map<String, Field> cache = new ConcurrentHashMap<>();
    private static final Field MISSING;
    static { try { MISSING = ModelReader.class.getDeclaredField("cache"); } catch (NoSuchFieldException error) { throw new IllegalStateException(error); } }

    private ModelReader() {}

    /** First non-null value among the given keys (Java field names or serialized JSON names). */
    static Object get(Object object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            Field field = find(object.getClass(), key);
            if (field == null) continue;
            try { Object value = field.get(object); if (value != null) return value; } catch (Throwable ignored) {}
        }
        return null;
    }

    static String text(Object object, String... keys) {
        Object value = get(object, keys);
        if (value == null) return "";
        String text = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    /** Follows a dotted path, e.g. "video.play_addr.url_list". */
    static Object path(Object object, String path) {
        Object current = object;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            current = get(current, part.split("\\|"));
        }
        return current;
    }

    /** First http(s) URL in a UrlModel-like object, list or string. */
    static String url(Object value) {
        if (value == null) return "";
        if (value instanceof CharSequence) { String text = value.toString(); return text.startsWith("http") ? text : ""; }
        if (value instanceof Iterable) { for (Object item : (Iterable<?>) value) { String url = url(item); if (!url.isEmpty()) return url; } return ""; }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) { String url = url(java.lang.reflect.Array.get(value, i)); if (!url.isEmpty()) return url; }
            return "";
        }
        Object list = get(value, "urlList", "url_list", "mUrls", "urls");
        String url = url(list);
        if (!url.isEmpty()) return url;
        return url(get(value, "url", "mUrl"));
    }

    private static Field find(Class<?> type, String key) {
        String cacheKey = type.getName() + "#" + key;
        Field cached = cache.get(cacheKey);
        if (cached != null) return cached == MISSING ? null : cached;
        Field found = null;
        for (Class<?> current = type; current != null && current != Object.class && found == null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (field.getName().equals(key) || serialized(field, key)) { found = field; break; }
            }
        }
        if (found != null) { try { found.setAccessible(true); } catch (Throwable ignored) { found = null; } }
        cache.put(cacheKey, found == null ? MISSING : found);
        return found;
    }

    private static boolean serialized(Field field, String key) {
        for (Annotation annotation : field.getDeclaredAnnotations()) {
            Class<?> type = annotation.annotationType();
            if (!type.getSimpleName().endsWith("SerializedName")) continue;
            try {
                Method value = type.getMethod("value");
                if (key.equals(value.invoke(annotation))) return true;
                Method alternate = type.getMethod("alternate");
                for (String name : (String[]) alternate.invoke(annotation)) if (key.equals(name)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
