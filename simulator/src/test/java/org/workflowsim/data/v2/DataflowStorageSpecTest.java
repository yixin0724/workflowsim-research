package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Physical storage values do not depend on a platform, route or running simulator. */
class DataflowStorageSpecTest {
    @Test
    void exposesHostAndDistinctDecimalMegabyteRatesAndCapacities() {
        DataflowStorageSpec spec = DataflowStorageSpec.of(7, 2.0, 3.0, 5.0);

        assertEquals(7, spec.getAttachmentHostId());
        assertEquals(2.0, spec.getReadBandwidthMbPerSecond(), 0.0);
        assertEquals(3.0, spec.getWriteBandwidthMbPerSecond(), 0.0);
        assertEquals(5.0, spec.getNetworkBandwidthMbPerSecond(), 0.0);
        assertCapacities(spec, 2_000_000.0, 3_000_000.0, 5_000_000.0);
    }

    @Test
    void acceptsZeroAndMaximumHostIdsWithoutPlatformMembershipChecks() {
        assertEquals(0, DataflowStorageSpec.of(0, 2.0, 3.0, 5.0).getAttachmentHostId());
        assertEquals(Integer.MAX_VALUE,
                DataflowStorageSpec.of(Integer.MAX_VALUE, 2.0, 3.0, 5.0).getAttachmentHostId());
    }

    @Test
    void convertsOneHundredThousandthOfAMegabyteToTenBytesPerSecond() {
        DataflowStorageSpec spec = DataflowStorageSpec.of(0, 1e-5, 1e-5, 1e-5);

        assertEquals(1e-5, spec.getReadBandwidthMbPerSecond(), 0.0);
        assertEquals(1e-5, spec.getWriteBandwidthMbPerSecond(), 0.0);
        assertEquals(1e-5, spec.getNetworkBandwidthMbPerSecond(), 0.0);
        assertCapacities(spec, 10.0, 10.0, 10.0);
    }

    @Test
    void preservesFractionalBytesWithoutIntegerRounding() {
        DataflowStorageSpec spec = DataflowStorageSpec.of(0, 2.5e-7, 5e-7, 7.5e-7);

        assertCapacities(spec, 0.25, 0.5, 0.75);
    }

    @Test
    void keepsReadWriteAndSharedNicCapacitiesIndependent() {
        DataflowStorageSpec spec = DataflowStorageSpec.of(7, 20.0, 30.0, 5.0);

        assertCapacities(spec, 20_000_000.0, 30_000_000.0, 5_000_000.0);
    }

    @Test
    void acceptsLargeFiniteCapacitiesWithoutAnArbitraryCeiling() {
        double rate = 1e300;
        double capacity = rate * 1_000_000.0;
        assertTrue(Double.isFinite(capacity));

        DataflowStorageSpec spec = DataflowStorageSpec.of(0, rate, rate, rate);

        assertEquals(rate, spec.getReadBandwidthMbPerSecond(), 0.0);
        assertEquals(rate, spec.getWriteBandwidthMbPerSecond(), 0.0);
        assertEquals(rate, spec.getNetworkBandwidthMbPerSecond(), 0.0);
        assertCapacities(spec, capacity, capacity, capacity);
    }

    @Test
    void acceptsSubnormalMegabyteRatesWhenConvertedCapacityIsNormal() {
        double rate = Math.nextUp(Double.MIN_NORMAL / 1_000_000.0);
        double capacity = rate * 1_000_000.0;
        assertTrue(rate > 0.0 && rate < Double.MIN_NORMAL);
        assertTrue(Double.isFinite(capacity) && capacity >= Double.MIN_NORMAL);

        DataflowStorageSpec spec = DataflowStorageSpec.of(0, rate, rate, rate);

        assertEquals(rate, spec.getReadBandwidthMbPerSecond(), 0.0);
        assertEquals(rate, spec.getWriteBandwidthMbPerSecond(), 0.0);
        assertEquals(rate, spec.getNetworkBandwidthMbPerSecond(), 0.0);
        assertCapacities(spec, capacity, capacity, capacity);
    }

    @Test
    void rejectsNegativeAttachmentHostIds() {
        assertThrows(IllegalArgumentException.class,
                () -> DataflowStorageSpec.of(-1, 2.0, 3.0, 5.0));
        assertThrows(IllegalArgumentException.class,
                () -> DataflowStorageSpec.of(Integer.MIN_VALUE, 2.0, 3.0, 5.0));
    }

    @Test
    void rejectsPositiveAndNegativeZeroInEveryBandwidthPosition() {
        assertRejectedInEveryBandwidthPosition(0.0);
        assertRejectedInEveryBandwidthPosition(-0.0);
    }

    @Test
    void rejectsNegativeRatesInEveryBandwidthPosition() {
        assertRejectedInEveryBandwidthPosition(-1.0);
        assertRejectedInEveryBandwidthPosition(-1e-5);
        assertRejectedInEveryBandwidthPosition(-Double.MIN_VALUE);
    }

    @Test
    void rejectsNaNInEveryBandwidthPosition() {
        assertRejectedInEveryBandwidthPosition(Double.NaN);
    }

    @Test
    void rejectsBothInfinitiesInEveryBandwidthPosition() {
        assertRejectedInEveryBandwidthPosition(Double.POSITIVE_INFINITY);
        assertRejectedInEveryBandwidthPosition(Double.NEGATIVE_INFINITY);
    }

    @Test
    void rejectsFiniteRatesWhoseByteConversionOverflows() {
        assertTrue(Double.isFinite(Double.MAX_VALUE));
        assertTrue(Double.isInfinite(Double.MAX_VALUE * 1_000_000.0));
        assertRejectedInEveryBandwidthPosition(Double.MAX_VALUE);
    }

    @Test
    void rejectsRatesWhoseConvertedCapacityIsSubnormal() {
        double nearNormalRate = Math.nextDown(Double.MIN_NORMAL / 1_000_000.0);
        double nearNormalCapacity = nearNormalRate * 1_000_000.0;
        assertTrue(nearNormalRate > 0.0);
        assertTrue(nearNormalCapacity > 0.0 && nearNormalCapacity < Double.MIN_NORMAL);
        assertTrue(Double.MIN_VALUE * 1_000_000.0 < Double.MIN_NORMAL);

        assertRejectedInEveryBandwidthPosition(Double.MIN_VALUE);
        assertRejectedInEveryBandwidthPosition(nearNormalRate);
    }

    @Test
    void hasExactValueEqualityAndDeterministicHashCodes() {
        DataflowStorageSpec first = DataflowStorageSpec.of(7, 2.0, 3.0, 5.0);
        DataflowStorageSpec second = DataflowStorageSpec.of(7, 2.0, 3.0, 5.0);
        DataflowStorageSpec third = DataflowStorageSpec.of(7, 2.0, 3.0, 5.0);

        assertNotSame(first, second);
        assertEquals(first, first);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(second, third);
        assertEquals(first, third);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(second.hashCode(), third.hashCode());
    }

    @Test
    void everyPhysicalPropertyParticipatesInEqualityWithoutRounding() {
        DataflowStorageSpec spec = DataflowStorageSpec.of(7, 2.0, 3.0, 5.0);

        assertNotEquals(spec, DataflowStorageSpec.of(8, 2.0, 3.0, 5.0));
        assertNotEquals(spec, DataflowStorageSpec.of(7, Math.nextUp(2.0), 3.0, 5.0));
        assertNotEquals(spec, DataflowStorageSpec.of(7, 2.0, Math.nextUp(3.0), 5.0));
        assertNotEquals(spec, DataflowStorageSpec.of(7, 2.0, 3.0, Math.nextUp(5.0)));
    }

    @Test
    void equalityRejectsNullAndUnrelatedTypes() {
        DataflowStorageSpec spec = DataflowStorageSpec.of(7, 2.0, 3.0, 5.0);

        assertFalse(spec.equals(null));
        assertFalse(spec.equals("source"));
    }

    private static void assertRejectedInEveryBandwidthPosition(double invalidRate) {
        assertThrows(IllegalArgumentException.class,
                () -> DataflowStorageSpec.of(0, invalidRate, 3.0, 5.0), "read bandwidth");
        assertThrows(IllegalArgumentException.class,
                () -> DataflowStorageSpec.of(0, 2.0, invalidRate, 5.0), "write bandwidth");
        assertThrows(IllegalArgumentException.class,
                () -> DataflowStorageSpec.of(0, 2.0, 3.0, invalidRate), "network bandwidth");
    }

    private static void assertCapacities(DataflowStorageSpec spec,
            double readBytesPerSecond, double writeBytesPerSecond, double networkBytesPerSecond) {
        assertEquals(readBytesPerSecond, spec.getReadCapacityBytesPerSecond(), 0.0);
        assertEquals(writeBytesPerSecond, spec.getWriteCapacityBytesPerSecond(), 0.0);
        assertEquals(networkBytesPerSecond, spec.getNetworkCapacityBytesPerSecond(), 0.0);
    }
}
