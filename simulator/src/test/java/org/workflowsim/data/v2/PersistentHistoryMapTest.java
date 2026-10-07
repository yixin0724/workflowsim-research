package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Fixed lookup oracles and structural checks; no timing gates or production debug API. */
class PersistentHistoryMapTest {
    @Test
    void emptyAndMissingQueriesDoNotInventMappings() throws ReflectiveOperationException {
        PersistentHistoryMap<String, Integer> empty = PersistentHistoryMap.empty();
        assertEquals(0, empty.size());
        assertNull(empty.get("missing"));
        assertFalse(empty.containsKey("missing"));
        assertShape(empty, 0L, 0);

        PersistentHistoryMap<String, Integer> populated = empty.with("", 0).with("present", -1);
        assertEquals(Integer.valueOf(0), populated.get(""));
        assertTrue(populated.containsKey(""));
        assertEquals(Integer.valueOf(-1), populated.get("present"));
        assertTrue(populated.containsKey("present"));
        assertNull(populated.get("missing"));
        assertFalse(populated.containsKey("missing"));
        assertEquals(2, populated.size());
        assertNull(empty.get(""));
        assertEquals(0, empty.size());

        PersistentHistoryMap<Long, Boolean> separatelyTypedEmpty = PersistentHistoryMap.empty();
        assertNull(separatelyTypedEmpty.get(0L));
        assertEquals(0, separatelyTypedEmpty.size());
        assertEquals(Boolean.FALSE, separatelyTypedEmpty.with(0L, false).get(0L));
        assertEquals(0, separatelyTypedEmpty.size());
    }

    @Test
    void nullKeysAndValuesAreRejectedWithoutChangingAnyRoot() throws ReflectiveOperationException {
        PersistentHistoryMap<String, String> empty = PersistentHistoryMap.empty();
        PersistentHistoryMap<String, String> populated = empty.with("present", "original");
        Object emptyRoot = rootOf(empty);
        Object populatedRoot = rootOf(populated);
        for (PersistentHistoryMap<String, String> map : Arrays.asList(empty, populated)) {
            assertThrows(NullPointerException.class, () -> map.get(null));
            assertThrows(NullPointerException.class, () -> map.containsKey(null));
            assertThrows(NullPointerException.class, () -> map.with(null, "value"));
            assertThrows(NullPointerException.class, () -> map.with("new", null));
            assertThrows(NullPointerException.class, () -> map.with("present", null));
            assertThrows(NullPointerException.class, () -> map.with(null, null));
        }
        assertSame(emptyRoot, rootOf(empty));
        assertSame(populatedRoot, rootOf(populated));
        assertShape(empty, 0L, 0);
        assertShape(populated, 1L, 1);
        assertEquals("original", populated.get("present"));
        assertNull(populated.get("new"));
    }

    @Test
    void distinctInsertionsCrossOneTwoFourEightAndSixteenWriteBoundaries()
            throws ReflectiveOperationException {
        List<PersistentHistoryMap<Integer, Integer>> roots = new ArrayList<>();
        PersistentHistoryMap<Integer, Integer> map = PersistentHistoryMap.empty();
        roots.add(map);
        for (int write = 1; write <= 33; write++) {
            int key = write - 1;
            map = map.with(key, key * 100 + 3);
            roots.add(map);
            assertShape(map, write, write);
            List<Map<Integer, Integer>> chunks = chunksOf(map);
            for (int level = 0; level < chunks.size(); level++) {
                if (chunks.get(level) != null) {
                    assertEquals(1 << level, chunks.get(level).size());
                }
            }
        }
        for (int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
            PersistentHistoryMap<Integer, Integer> retained = roots.get(rootIndex);
            assertShape(retained, rootIndex, rootIndex);
            for (int key = -1; key <= 33; key++) {
                Integer expected = key >= 0 && key < rootIndex ? Integer.valueOf(key * 100 + 3) : null;
                assertEquals(expected, retained.get(key), "root=" + rootIndex + ", key=" + key);
                assertEquals(expected != null, retained.containsKey(key));
            }
        }
    }

    @Test
    void mixedInsertionsAndOverwritesHaveFixedDistinctSizesAcrossCarries()
            throws ReflectiveOperationException {
        String[] keys = {"a", "b", "a", "c", "b", "d", "a", "e", "f", "c", "g", "a", "h", "b", "i", "a", "j"};
        int[] distinctSizes = {1, 2, 2, 3, 3, 4, 4, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10};
        List<String> probes = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "missing");
        List<PersistentHistoryMap<String, ImmutableValue>> roots = new ArrayList<>();
        List<Map<String, ImmutableValue>> snapshots = new ArrayList<>();
        PersistentHistoryMap<String, ImmutableValue> map = PersistentHistoryMap.empty();
        Map<String, ImmutableValue> expected = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            ImmutableValue value = new ImmutableValue(i + 1L, "boundary");
            map = map.with(keys[i], value);
            expected.put(keys[i], value);
            assertEquals(distinctSizes[i], expected.size());
            assertShape(map, i + 1L, distinctSizes[i]);
            assertContents(map, expected, probes);
            roots.add(map);
            snapshots.add(new HashMap<>(expected));
        }
        for (int i = 0; i < roots.size(); i++) {
            assertContents(roots.get(i), snapshots.get(i), probes);
            assertShape(roots.get(i), i + 1L, distinctSizes[i]);
        }
    }

    @Test
    void repeatedShadowingUsesNewestLevelAndSurvivesEveryCarry() throws ReflectiveOperationException {
        PersistentHistoryMap<String, ImmutableValue> map = PersistentHistoryMap.empty();
        List<PersistentHistoryMap<String, ImmutableValue>> roots = new ArrayList<>();
        roots.add(map);
        for (int write = 1; write <= 257; write++) {
            map = map.with("same", new ImmutableValue(write, "latest"));
            roots.add(map);
            assertShape(map, write, 1);
            assertEquals(new ImmutableValue(write, "latest"), map.get("same"));
            assertTrue(map.containsKey("same"));
            assertFalse(map.containsKey("missing"));
            for (Map<String, ImmutableValue> chunk : chunksOf(map)) {
                if (chunk != null) {
                    assertEquals(1, chunk.size(), "chunk occupancy counts writes, not distinct keys");
                }
            }
        }
        for (int write = 0; write < roots.size(); write++) {
            ImmutableValue expected = write == 0 ? null : new ImmutableValue(write, "latest");
            assertEquals(expected, roots.get(write).get("same"));
            assertShape(roots.get(write), write, write == 0 ? 0 : 1);
        }
    }

    @Test
    void identicalKeyAndValueStillConsumeWritesWithoutGrowingLogicalSize()
            throws ReflectiveOperationException {
        ImmutableValue value = new ImmutableValue(7, "unchanged");
        PersistentHistoryMap<String, ImmutableValue> map = PersistentHistoryMap.empty();
        for (int write = 1; write <= 65; write++) {
            PersistentHistoryMap<String, ImmutableValue> previous = map;
            map = map.with("same", value);
            assertNotSame(previous, map);
            assertNotSame(rootOf(previous), rootOf(map));
            assertSame(value, map.get("same"));
            assertShape(map, write, 1);
            assertShape(previous, write - 1L, write == 1 ? 0 : 1);
        }
    }

    @Test
    void parentChildrenSiblingsAndNestedRootsRemainIsolatedInBothDirections()
            throws ReflectiveOperationException {
        PersistentHistoryMap<String, String> parent = PersistentHistoryMap.<String, String>empty()
                .with("shared", "parent");
        Map<String, String> parentExpected = stringSnapshot(Collections.emptyMap(), "shared", "parent");
        List<String> probes = new ArrayList<>(Arrays.asList("shared", "child-only", "sibling-only", "nested-only",
                "parent-only", "child-next", "sibling-next", "nested-next", "missing"));
        for (int i = 0; i < 6; i++) {
            parent = parent.with("stable-" + i, "value-" + i);
            parentExpected.put("stable-" + i, "value-" + i);
            probes.add("stable-" + i);
        }
        // Each branch crosses the full carry from seven writes to eight.
        PersistentHistoryMap<String, String> child = parent.with("shared", "child").with("child-only", "C");
        PersistentHistoryMap<String, String> sibling = parent.with("shared", "sibling").with("sibling-only", "S");
        PersistentHistoryMap<String, String> nested = child.with("shared", "nested").with("nested-only", "N");
        Map<String, String> childExpected = stringSnapshot(parentExpected, "shared", "child", "child-only", "C");
        Map<String, String> siblingExpected = stringSnapshot(parentExpected, "shared", "sibling", "sibling-only", "S");
        Map<String, String> nestedExpected = stringSnapshot(childExpected, "shared", "nested", "nested-only", "N");

        // Advance ancestors and siblings after descendants already exist, then advance descendants too.
        PersistentHistoryMap<String, String> parentNext = parent.with("shared", "parent-next").with("parent-only", "P");
        PersistentHistoryMap<String, String> childNext = child.with("child-only", "C2").with("child-next", "CN");
        PersistentHistoryMap<String, String> siblingNext = sibling.with("sibling-only", "S2").with("sibling-next", "SN");
        PersistentHistoryMap<String, String> nestedNext = nested.with("nested-only", "N2").with("nested-next", "NN");
        assertContents(parent, parentExpected, probes);
        assertContents(child, childExpected, probes);
        assertContents(sibling, siblingExpected, probes);
        assertContents(nested, nestedExpected, probes);
        assertContents(parentNext, stringSnapshot(parentExpected, "shared", "parent-next", "parent-only", "P"), probes);
        assertContents(childNext, stringSnapshot(childExpected, "child-only", "C2", "child-next", "CN"), probes);
        assertContents(siblingNext, stringSnapshot(siblingExpected, "sibling-only", "S2", "sibling-next", "SN"), probes);
        assertContents(nestedNext, stringSnapshot(nestedExpected, "nested-only", "N2", "nested-next", "NN"), probes);
        assertShape(parent, 7L, 7);
        assertShape(child, 9L, 8);
        assertShape(sibling, 9L, 8);
        assertShape(nested, 11L, 9);
    }

    @Test
    void equalButNonidenticalKeysOverwriteOneLogicalEntryAcrossMerges()
            throws ReflectiveOperationException {
        String firstKey = new String("equal");
        String equalKey = new String("equal");
        assertNotSame(firstKey, equalKey);
        assertEquals(firstKey, equalKey);
        ImmutableValue original = new ImmutableValue(1, "equal-key");
        PersistentHistoryMap<String, ImmutableValue> first = PersistentHistoryMap.<String, ImmutableValue>empty()
                .with(firstKey, original);
        PersistentHistoryMap<String, ImmutableValue> map = first;
        Map<String, ImmutableValue> expected = new HashMap<>();
        expected.put("equal", original);
        List<String> probes = new ArrayList<>(Arrays.asList("equal", "missing"));
        for (int i = 0; i < 15; i++) {
            String key = "filler-" + i;
            ImmutableValue value = new ImmutableValue(i + 2L, "filler");
            map = map.with(key, value);
            expected.put(key, value);
            probes.add(key);
        }
        for (int write = 17; write <= 32; write++) {
            ImmutableValue value = new ImmutableValue(write, "equal-key");
            map = map.with(write == 17 ? equalKey : new String("equal"), value);
            expected.put("equal", value);
            assertEquals(value, map.get(new String("equal")));
            assertShape(map, write, 16);
        }
        assertContents(map, expected, probes);
        assertEquals(original, first.get(equalKey));
        assertShape(first, 1L, 1);
    }

    @Test
    void constantHashCollisionsDoNotLoseKeysOrOverwriteTheirNeighbors()
            throws ReflectiveOperationException {
        PersistentHistoryMap<CollisionKey, ImmutableValue> map = PersistentHistoryMap.empty();
        Map<CollisionKey, ImmutableValue> expected = new HashMap<>();
        List<CollisionKey> probes = new ArrayList<>();
        for (int key = -1; key <= 128; key++) {
            probes.add(new CollisionKey(key));
        }
        for (int key = 0; key < 128; key++) {
            ImmutableValue value = new ImmutableValue(key, "collision-insert");
            map = map.with(new CollisionKey(key), value);
            expected.put(new CollisionKey(key), value);
            assertContents(map, expected, probes);
        }
        PersistentHistoryMap<CollisionKey, ImmutableValue> retained = map;
        Map<CollisionKey, ImmutableValue> retainedExpected = new HashMap<>(expected);
        for (int write = 0; write < 128; write++) {
            int key = (write * 73) & 127;
            ImmutableValue value = new ImmutableValue(write, "collision-overwrite");
            map = map.with(new CollisionKey(key), value);
            expected.put(new CollisionKey(key), value);
            if (write % 16 == 0) {
                assertContents(map, expected, probes);
                assertContents(retained, retainedExpected, probes);
            }
        }
        assertContents(map, expected, probes);
        assertContents(retained, retainedExpected, probes);
        assertShape(map, 256L, 128);
        assertShape(retained, 128L, 128);
    }

    @Test
    void negativeAndExtremeIntegerAndLongKeysRetainTheirFullIdentity()
            throws ReflectiveOperationException {
        List<Number> keys = Arrays.<Number>asList(Integer.MIN_VALUE, -1_000_000_007, -65_536, -1, 0, 1,
                65_536, 1_000_000_007, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1,
                -(1L << 40), (long) Integer.MIN_VALUE - 1, -1L, 0L, 1L, 1L << 32,
                (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE);
        List<Number> probes = new ArrayList<>(keys);
        probes.add(Integer.MIN_VALUE + 1);
        probes.add(Long.MIN_VALUE + 2);
        PersistentHistoryMap<Number, ImmutableValue> map = PersistentHistoryMap.empty();
        Map<Number, ImmutableValue> expected = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            ImmutableValue value = new ImmutableValue(i, "numeric-insert");
            map = map.with(keys.get(i), value);
            expected.put(keys.get(i), value);
        }
        assertEquals(keys.size(), expected.size());
        assertNotEquals(map.get(Integer.valueOf(0)), map.get(Long.valueOf(0)));
        PersistentHistoryMap<Number, ImmutableValue> retained = map;
        Map<Number, ImmutableValue> retainedExpected = new HashMap<>(expected);
        for (int i = keys.size() - 1; i >= 0; i--) {
            ImmutableValue value = new ImmutableValue(-100L - i, "numeric-overwrite");
            map = map.with(keys.get(i), value);
            expected.put(keys.get(i), value);
        }
        assertContents(map, expected, probes);
        assertContents(retained, retainedExpected, probes);
        assertShape(map, keys.size() * 2L, keys.size());
    }

    @Test
    void oldRootsRemainUnchangedAfterThousandsOfInsertionsAndOverwrites()
            throws ReflectiveOperationException {
        PersistentHistoryMap<Integer, ImmutableValue> map = PersistentHistoryMap.empty();
        Map<Integer, ImmutableValue> expected = new HashMap<>();
        List<PersistentHistoryMap<Integer, ImmutableValue>> roots = new ArrayList<>();
        List<Map<Integer, ImmutableValue>> snapshots = new ArrayList<>();
        List<Integer> probes = integerKeys(-1, 4097);
        roots.add(map);
        snapshots.add(new HashMap<>(expected));
        for (int write = 1; write <= 4096; write++) {
            ImmutableValue value = new ImmutableValue(write, "insert");
            map = map.with(write - 1, value);
            expected.put(write - 1, value);
            if (Integer.bitCount(write) == 1 || Integer.bitCount(write + 1) == 1) {
                roots.add(map);
                snapshots.add(new HashMap<>(expected));
            }
        }
        for (int key = 4095; key >= 0; key--) {
            ImmutableValue value = new ImmutableValue(-key - 1L, "overwrite");
            map = map.with(key, value);
            expected.put(key, value);
        }
        assertShape(map, 8192L, 4096);
        assertContents(map, expected, probes);
        for (int i = 0; i < roots.size(); i++) {
            assertContents(roots.get(i), snapshots.get(i), probes);
        }
    }

    @Test
    void fixedSeedBranchingOperationsMatchIndependentHashMapSnapshots()
            throws ReflectiveOperationException {
        Random random = new Random(0x4E46303036L);
        List<Integer> keys = integerKeys(-64, 64);
        keys.add(Integer.MIN_VALUE);
        keys.add(Integer.MAX_VALUE);
        List<Integer> probes = new ArrayList<>(keys);
        probes.add(1_000_003);
        List<PersistentHistoryMap<Integer, ImmutableValue>> roots = new ArrayList<>();
        List<Map<Integer, ImmutableValue>> snapshots = new ArrayList<>();
        List<Long> writeCounts = new ArrayList<>();
        roots.add(PersistentHistoryMap.empty());
        snapshots.add(new HashMap<>());
        writeCounts.add(0L);
        for (int operation = 0; operation < 3000; operation++) {
            int baseIndex = operation % 19 == 0 ? random.nextInt(roots.size()) : roots.size() - 1;
            Integer key = keys.get(random.nextInt(keys.size()));
            ImmutableValue value = new ImmutableValue(random.nextLong(), "operation-" + operation);
            Map<Integer, ImmutableValue> expected = new HashMap<>(snapshots.get(baseIndex));
            expected.put(key, value);
            PersistentHistoryMap<Integer, ImmutableValue> next = roots.get(baseIndex).with(key, value);
            long writes = writeCounts.get(baseIndex) + 1L;
            assertShape(next, writes, expected.size());
            assertContents(next, expected, probes);
            roots.add(next);
            snapshots.add(expected);
            writeCounts.add(writes);

            int queriedRoot = random.nextInt(roots.size());
            Integer queriedKey = probes.get(random.nextInt(probes.size()));
            assertEquals(snapshots.get(queriedRoot).get(queriedKey), roots.get(queriedRoot).get(queriedKey));
            assertEquals(snapshots.get(queriedRoot).containsKey(queriedKey), roots.get(queriedRoot).containsKey(queriedKey));
            if (operation % 37 == 0) {
                assertContents(roots.get(baseIndex), snapshots.get(baseIndex), probes);
            }
        }
        for (int i = 0; i < roots.size(); i++) {
            assertContents(roots.get(i), snapshots.get(i), probes);
            assertShape(roots.get(i), writeCounts.get(i), snapshots.get(i).size());
        }
    }

    @Test
    void rootCollectionsChunksAndTheirViewsAreUnmodifiable() throws ReflectiveOperationException {
        PersistentHistoryMap<String, String> map = PersistentHistoryMap.<String, String>empty()
                .with("a", "A").with("b", "B").with("c", "C");
        List<Map<String, String>> chunks = chunksOf(map);
        assertThrows(UnsupportedOperationException.class, () -> chunks.add(null));
        assertThrows(UnsupportedOperationException.class, () -> chunks.set(0, null));
        assertThrows(UnsupportedOperationException.class, chunks::clear);
        for (Map<String, String> chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            Map.Entry<String, String> entry = chunk.entrySet().iterator().next();
            assertThrows(UnsupportedOperationException.class, () -> chunk.put("intruder", "changed"));
            assertThrows(UnsupportedOperationException.class, () -> chunk.remove(entry.getKey()));
            assertThrows(UnsupportedOperationException.class, chunk::clear);
            assertThrows(UnsupportedOperationException.class, () -> chunk.keySet().remove(entry.getKey()));
            assertThrows(UnsupportedOperationException.class, () -> chunk.values().remove(entry.getValue()));
            assertThrows(UnsupportedOperationException.class, () -> entry.setValue("changed"));
            Iterator<Map.Entry<String, String>> iterator = chunk.entrySet().iterator();
            iterator.next();
            assertThrows(UnsupportedOperationException.class, iterator::remove);
        }
        assertContents(map, stringSnapshot(Collections.emptyMap(), "a", "A", "b", "B", "c", "C"),
                Arrays.asList("a", "b", "c", "intruder"));
        assertShape(map, 3L, 3);
        List<Map<String, String>> emptyChunks = chunksOf(PersistentHistoryMap.<String, String>empty());
        assertThrows(UnsupportedOperationException.class, () -> emptyChunks.add(Collections.singletonMap("x", "X")));
    }

    @Test
    void unaffectedChunksAreSharedWhileCarryChunksAndRootListsAreFresh()
            throws ReflectiveOperationException {
        PersistentHistoryMap<Integer, Integer> base = PersistentHistoryMap.empty();
        Map<Integer, Integer> baseExpected = new HashMap<>();
        for (int key = 0; key < 12; key++) {
            base = base.with(key, key);
            baseExpected.put(key, key);
        }
        PersistentHistoryMap<Integer, Integer> first = base.with(12, 12);
        PersistentHistoryMap<Integer, Integer> second = first.with(13, 13);
        PersistentHistoryMap<Integer, Integer> sibling = base.with(-1, -1);
        List<Map<Integer, Integer>> before = chunksOf(base);
        assertNotSame(rootOf(base), rootOf(first));
        assertNotSame(before, chunksOf(first));
        for (PersistentHistoryMap<Integer, Integer> branch : Arrays.asList(first, second, sibling)) {
            assertSame(before.get(2), chunksOf(branch).get(2));
            assertSame(before.get(3), chunksOf(branch).get(3));
        }
        assertNull(before.get(0));
        assertNull(before.get(1));
        assertEquals(Collections.singletonMap(12, 12), chunksOf(first).get(0));
        assertNull(chunksOf(second).get(0));
        assertEquals(2, chunksOf(second).get(1).size());
        assertNotSame(chunksOf(first).get(0), chunksOf(sibling).get(0));

        PersistentHistoryMap<Integer, Integer> fullCarry = second.with(14, 14).with(15, 15);
        assertShape(fullCarry, 16L, 16);
        assertEquals(16, chunksOf(fullCarry).get(4).size());
        assertContents(base, baseExpected, integerKeys(-2, 17));
        assertNull(first.get(13));
        assertNull(second.get(-1));
        assertNull(sibling.get(12));
        assertEquals(Integer.valueOf(-1), sibling.get(-1));
        assertShape(base, 12L, 12);
        assertShape(first, 13L, 13);
        assertShape(second, 14L, 14);
        assertShape(sibling, 13L, 13);
        for (int key = 0; key < 16; key++) {
            assertEquals(Integer.valueOf(key), fullCarry.get(key));
        }
    }

    @Test
    void binaryCarryWorkShapeHasSmallRootsAndOccasionalLinearMerges()
            throws ReflectiveOperationException {
        // Count newly published chunk entries, not timing or hidden temporary allocations.
        // For 1024 distinct writes the binary carry geometry gives exactly 6144 such entries.
        PersistentHistoryMap<Integer, Integer> map = PersistentHistoryMap.empty();
        long publishedEntries = 0;
        long rootReferences = 0;
        for (int write = 1; write <= 1024; write++) {
            List<Map<Integer, Integer>> before = chunksOf(map);
            PersistentHistoryMap<Integer, Integer> next = map.with(write, write * 3);
            List<Map<Integer, Integer>> after = chunksOf(next);
            int carriedLevel = Integer.numberOfTrailingZeros(write);
            assertEquals(1 << carriedLevel, after.get(carriedLevel).size());
            for (int level = 0; level < carriedLevel; level++) {
                assertNull(after.get(level));
            }
            for (int level = carriedLevel + 1; level < before.size(); level++) {
                assertSame(before.get(level), after.get(level), "untouched chunks must not be copied");
            }
            publishedEntries += after.get(carriedLevel).size();
            rootReferences += after.size();
            assertShape(next, write, write);
            map = next;
        }
        assertEquals(6144L, publishedEntries);
        assertTrue(rootReferences <= 1024L * 11);
        assertEquals(11, chunksOf(map).size());
        assertEquals(1024, chunksOf(map).get(10).size(), "a single full carry is not logarithmic work");
        for (int key = 1; key <= 1024; key++) {
            assertEquals(Integer.valueOf(key * 3), map.get(key));
        }
    }

    @Test
    void longWriteCountIsCheckedForBothInsertsAndOverwritesBeforeCarrying()
            throws ReflectiveOperationException {
        // Repeated equal writes can legitimately compress every level to one entry.
        List<Map<String, String>> levels = new ArrayList<>(Collections.nCopies(63,
                Collections.singletonMap("same", "original")));
        PersistentHistoryMap<String, String> exhausted = syntheticMap(levels, Long.MAX_VALUE, 1);
        Object exhaustedRoot = rootOf(exhausted);
        assertShape(exhausted, Long.MAX_VALUE, 1);
        assertThrows(ArithmeticException.class, () -> exhausted.with("same", "replacement"));
        assertThrows(ArithmeticException.class, () -> exhausted.with("new", "new-value"));
        assertSame(exhaustedRoot, rootOf(exhausted));
        assertEquals("original", exhausted.get("same"));
        assertNull(exhausted.get("new"));
        assertShape(exhausted, Long.MAX_VALUE, 1);

        List<Map<String, String>> almostLevels = new ArrayList<>(levels);
        almostLevels.set(0, null);
        PersistentHistoryMap<String, String> almost = syntheticMap(almostLevels, Long.MAX_VALUE - 1L, 1);
        PersistentHistoryMap<String, String> last = almost.with("same", "last");
        assertShape(last, Long.MAX_VALUE, 1);
        assertEquals("last", last.get("same"));
        assertEquals("original", almost.get("same"));
        assertShape(almost, Long.MAX_VALUE - 1L, 1);
        assertThrows(ArithmeticException.class, () -> last.with("same", "too-late"));
    }

    @Test
    void intDistinctSizeOverflowRejectsNewKeysButStillAllowsOverwrites()
            throws ReflectiveOperationException {
        // Synthetic cached size avoids allocating billions of keys. No existing final fields are changed.
        List<Map<String, String>> levels = new ArrayList<>(Collections.<Map<String, String>>nCopies(32, null));
        levels.set(31, Collections.singletonMap("present", "original"));
        long writes = 1L << 31;
        PersistentHistoryMap<String, String> almost = syntheticMap(levels, writes, Integer.MAX_VALUE - 1);
        PersistentHistoryMap<String, String> full = almost.with("last", "last-value");
        assertShape(full, writes + 1L, Integer.MAX_VALUE);
        Object fullRoot = rootOf(full);
        assertThrows(ArithmeticException.class, () -> full.with("overflow", "rejected"));
        assertSame(fullRoot, rootOf(full));
        assertShape(full, writes + 1L, Integer.MAX_VALUE);
        assertNull(full.get("overflow"));

        PersistentHistoryMap<String, String> overwritten = full.with("present", "replacement");
        assertShape(overwritten, writes + 2L, Integer.MAX_VALUE);
        assertEquals("replacement", overwritten.get("present"));
        assertEquals("last-value", overwritten.get("last"));
        assertEquals("original", full.get("present"));
        assertEquals("original", almost.get("present"));
        assertNull(almost.get("last"));
        assertShape(almost, writes, Integer.MAX_VALUE - 1);
    }

    private static <K, V> void assertContents(PersistentHistoryMap<K, V> actual, Map<K, V> expected,
            Iterable<K> probes) {
        assertEquals(expected.size(), actual.size());
        for (K key : probes) {
            assertEquals(expected.get(key), actual.get(key), "value for key=" + key);
            assertEquals(expected.containsKey(key), actual.containsKey(key), "presence for key=" + key);
        }
    }

    private static List<Integer> integerKeys(int from, int to) {
        List<Integer> keys = new ArrayList<>();
        for (int key = from; key < to; key++) {
            keys.add(key);
        }
        return keys;
    }

    private static Map<String, String> stringSnapshot(Map<String, String> base, String... keyValues) {
        Map<String, String> snapshot = new HashMap<>(base);
        for (int i = 0; i < keyValues.length; i += 2) {
            snapshot.put(keyValues[i], keyValues[i + 1]);
        }
        return snapshot;
    }

    // White-box checks stay in the test: production exposes only the locked lookup/update API.
    private static Object rootOf(PersistentHistoryMap<?, ?> map) throws ReflectiveOperationException {
        Field field = PersistentHistoryMap.class.getDeclaredField("root");
        field.setAccessible(true);
        return field.get(map);
    }

    private static Object rootField(PersistentHistoryMap<?, ?> map, String name)
            throws ReflectiveOperationException {
        Object root = rootOf(map);
        Field field = root.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(root);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> List<Map<K, V>> chunksOf(PersistentHistoryMap<K, V> map)
            throws ReflectiveOperationException {
        return (List<Map<K, V>>) rootField(map, "chunks");
    }

    private static <K, V> void assertShape(PersistentHistoryMap<K, V> map, long writes, int distinctSize)
            throws ReflectiveOperationException {
        assertEquals(writes, ((Long) rootField(map, "writeCount")).longValue());
        assertEquals(distinctSize, map.size());
        List<Map<K, V>> chunks = chunksOf(map);
        int expectedLevels = writes == 0 ? 0 : Long.SIZE - Long.numberOfLeadingZeros(writes);
        assertEquals(expectedLevels, chunks.size(), "root length follows writes, not distinct size");
        for (int level = 0; level < chunks.size(); level++) {
            Map<K, V> chunk = chunks.get(level);
            if ((writes & (1L << level)) == 0) {
                assertNull(chunk, "unoccupied level=" + level);
            } else {
                assertNotNull(chunk, "occupied level=" + level);
                assertFalse(chunk.isEmpty());
                assertTrue(chunk.size() <= (1L << level), "deduplication cannot add represented writes");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> PersistentHistoryMap<K, V> syntheticMap(List<Map<K, V>> chunks,
            long writes, int distinctSize) throws ReflectiveOperationException {
        Class<?> rootClass = rootOf(PersistentHistoryMap.empty()).getClass();
        Constructor<?> rootConstructor = rootClass.getDeclaredConstructor(List.class, long.class, int.class);
        rootConstructor.setAccessible(true);
        List<Map<K, V>> frozen = Collections.unmodifiableList(new ArrayList<>(chunks));
        Object root = rootConstructor.newInstance(frozen, writes, distinctSize);
        Constructor<?> mapConstructor = PersistentHistoryMap.class.getDeclaredConstructor(rootClass);
        mapConstructor.setAccessible(true);
        return (PersistentHistoryMap<K, V>) mapConstructor.newInstance(root);
    }

    private static final class CollisionKey {
        private final int id;

        private CollisionKey(int id) {
            this.id = id;
        }

        @Override public boolean equals(Object other) {
            return other instanceof CollisionKey && id == ((CollisionKey) other).id;
        }

        @Override public int hashCode() {
            return 7;
        }

        @Override public String toString() {
            return "collision-" + id;
        }
    }

    /** Payloads have no mutable members; independent snapshot maps may safely share them. */
    private static final class ImmutableValue {
        private final long sequence;
        private final String tag;

        private ImmutableValue(long sequence, String tag) {
            this.sequence = sequence;
            this.tag = tag;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof ImmutableValue)) {
                return false;
            }
            ImmutableValue value = (ImmutableValue) other;
            return sequence == value.sequence && tag.equals(value.tag);
        }

        @Override public int hashCode() {
            return 31 * Long.hashCode(sequence) + tag.hashCode();
        }

        @Override public String toString() {
            return tag + "@" + sequence;
        }
    }
}
