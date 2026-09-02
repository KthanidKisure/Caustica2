package dev.comfyfluffy.caustica.rt.terrain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny synchronized access-order cache used by CausticaLOD's worker-facing tile sources.
 *
 * <p>LOD queries may arrive from several terrain workers, so this intentionally uses one very short
 * monitor rather than a concurrent map plus an approximate eviction queue. The values are immutable
 * after publication and the critical sections are only hash-table operations; disk reads, decompression
 * and terrain sampling happen outside this class.</p>
 */
final class RtLodTileCache<T> {
    private final int maxEntries;
    private final LinkedHashMap<Long, T> entries;

    RtLodTileCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(Math.min(maxEntries, 1024), 0.75f, true);
    }

    synchronized T get(long key) {
        return entries.get(key);
    }

    synchronized boolean containsKey(long key) {
        return entries.containsKey(key);
    }

    synchronized void put(long key, T value) {
        entries.put(key, value);
        trim();
    }

    /** Returns the existing value, or null after inserting {@code value}. */
    synchronized T putIfAbsent(long key, T value) {
        T current = entries.get(key);
        if (current != null) {
            return current;
        }
        entries.put(key, value);
        trim();
        return null;
    }

    synchronized T remove(long key) {
        return entries.remove(key);
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    private void trim() {
        while (entries.size() > maxEntries) {
            Map.Entry<Long, T> eldest = entries.entrySet().iterator().next();
            entries.remove(eldest.getKey());
        }
    }
}
