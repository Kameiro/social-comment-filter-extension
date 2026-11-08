package dev.local.commentcollector;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;

/** Observer keys must not call platform objects' mutable hashCode/equals methods. */
final class WeakIdentityMap<V> {
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final LinkedHashMap<Key, V> entries = new LinkedHashMap<>();
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key && get() != null && get() == ((Key) other).get();
        }
    }
    private void cleanup() { Key key; while ((key = (Key) queue.poll()) != null) entries.remove(key); }
    synchronized V get(Object key) { cleanup(); return key == null ? null : entries.get(new Key(key, null)); }
    synchronized boolean containsKey(Object key) { return get(key) != null; }
    synchronized V put(Object key, V value) {
        cleanup(); if (key == null) return null;
        V previous = entries.put(new Key(key, queue), value);
        if (entries.size() > 8192) entries.remove(entries.keySet().iterator().next());
        return previous;
    }
}
