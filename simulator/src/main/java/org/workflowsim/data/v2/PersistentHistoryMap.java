package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal immutable, lookup-only history with binary-carry write chunks.
 *
 * <p>Callers must supply immutable, non-null keys and values, including stable
 * key equality and hash codes. They are shared, not defensively copied. There
 * is no deletion or iteration API; mutable live-state maps belong elsewhere.
 * Keeping a reference to this map shares its immutable root in O(1), so forks
 * need not copy history. Updating any fork leaves all other roots unchanged.
 *
 * <p>Level {@code i}, when occupied, summarizes exactly {@code 2^i} writes,
 * not that many distinct keys. Every {@link #with(Object, Object)} counts as a
 * write, even an identical overwrite. Lower occupied levels are newer than
 * higher ones. Carries merge older entries first and newer entries last.
 *
 * <p>For {@code W} writes, lookup examines O(log(W + 1)) chunks, assuming
 * expected constant-time hashing within each chunk; {@link #size()} is O(1).
 * Each update copies O(log(W + 1)) root references. Entry-copy work is also
 * O(log(W + 1)) amortized per update along a single linear history. A single
 * carry can nevertheless copy O(W) entries (O(N) for N distinct insertions),
 * so insertion is not worst-case logarithmic. Separate branches that repeat
 * an expensive carry do not share that work or its amortization.
 *
 * @param <K> immutable key type
 * @param <V> immutable value type
 */
final class PersistentHistoryMap<K, V> {
    private static final PersistentHistoryMap<?, ?> EMPTY =
            new PersistentHistoryMap<>(new Root<>(Collections.emptyList(), 0L, 0));

    private final Root<K, V> root;

    private PersistentHistoryMap(Root<K, V> root) {
        this.root = root;
    }

    /**
     * Returns an empty immutable history, safely shared across type arguments.
     *
     * @param <K> immutable key type
     * @param <V> immutable value type
     * @return an empty history
     */
    @SuppressWarnings("unchecked")
    static <K, V> PersistentHistoryMap<K, V> empty() {
        return (PersistentHistoryMap<K, V>) EMPTY;
    }

    /**
     * @param key non-null immutable key
     * @return its most recently written value, or null if absent
     * @throws NullPointerException if the key is null
     */
    V get(K key) {
        Objects.requireNonNull(key, "key");
        for (Map<K, V> chunk : root.chunks) {
            if (chunk != null) {
                V value = chunk.get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * @param key non-null immutable key
     * @return whether the key has a value in this history
     * @throws NullPointerException if the key is null
     */
    boolean containsKey(K key) {
        // Null values are forbidden, so a separate membership lookup is unnecessary.
        return get(key) != null;
    }

    /** @return cached number of distinct keys, not the number of writes */
    int size() {
        return root.distinctSize;
    }

    /**
     * Adds one write to a new history, leaving this root and its chunks intact.
     * The write count and, for a new key, the distinct size are checked before
     * building or publishing the new root. See the class-level amortized bound.
     *
     * @param key non-null immutable key
     * @param value non-null immutable value
     * @return a new history in which this write shadows any older value
     * @throws NullPointerException if the key or value is null
     * @throws ArithmeticException if the long write count or int distinct size overflows
     */
    PersistentHistoryMap<K, V> with(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long nextWriteCount = Math.addExact(root.writeCount, 1L);
        int nextSize = containsKey(key) ? root.distinctSize : Math.addExact(root.distinctSize, 1);

        List<Map<K, V>> chunks = new ArrayList<>(root.chunks);
        Map<K, V> carry = Collections.singletonMap(key, value);
        int level = 0;
        while (level < chunks.size() && chunks.get(level) != null) {
            // Both inputs are immutable; only this fresh, unexposed backing map is changed.
            Map<K, V> merged = new HashMap<>(chunks.get(level));
            merged.putAll(carry);
            carry = Collections.unmodifiableMap(merged);
            chunks.set(level, null);
            level++;
        }
        if (level == chunks.size()) {
            chunks.add(carry);
        } else {
            chunks.set(level, carry);
        }
        return new PersistentHistoryMap<>(new Root<>(chunks, nextWriteCount, nextSize));
    }

    /** Immutable metadata and immutable chunks; no mutable backing handles escape. */
    private static final class Root<K, V> {
        private final List<Map<K, V>> chunks;
        private final long writeCount;
        private final int distinctSize;

        private Root(List<Map<K, V>> ownedChunks, long writeCount, int distinctSize) {
            // Only the shared empty root and with() supply this list; with() relinquishes its fresh list.
            this.chunks = Collections.unmodifiableList(ownedChunks);
            this.writeCount = writeCount;
            this.distinctSize = distinctSize;
        }
    }
}
