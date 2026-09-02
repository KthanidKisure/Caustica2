package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.Objects;

/**
 * Tiny synchronized access-order cache used by CausticaLOD's worker-facing tile sources.
 *
 * <p>LOD queries may arrive from several terrain workers, so this intentionally uses one very short
 * monitor rather than a concurrent map plus an approximate eviction queue. Keys stay primitive all the
 * way through the hot lookup path: distant-terrain sampling performs many cache probes, and boxing every
 * packed chunk coordinate into a {@link Long} would otherwise create avoidable GC pressure. Values are
 * immutable after publication and disk reads/decompression/terrain sampling happen outside this class.</p>
 */
final class RtLodTileCache<T> {
    private final int maxEntries;
    private final Long2ObjectLinkedOpenHashMap<T> entries;

    RtLodTileCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.entries = new Long2ObjectLinkedOpenHashMap<>(Math.min(maxEntries, 1024), 0.75f);
    }

    /** Access-order lookup: a hit becomes the newest LRU entry without boxing {@code key}. */
    synchronized T get(long key) {
        return entries.getAndMoveToLast(key);
    }

    /** Matches LinkedHashMap.containsKey semantics: membership tests do not refresh LRU age. */
    synchronized boolean containsKey(long key) {
        return entries.containsKey(key);
    }

    synchronized void put(long key, T value) {
        entries.putAndMoveToLast(key, Objects.requireNonNull(value, "value"));
        trim();
    }

    /** Returns the existing value, or null after inserting {@code value}. */
    synchronized T putIfAbsent(long key, T value) {
        T current = entries.getAndMoveToLast(key);
        if (current != null) {
            return current;
        }
        entries.putAndMoveToLast(key, Objects.requireNonNull(value, "value"));
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
            entries.removeFirst();
        }
    }
}
