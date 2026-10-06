package org.workflowsim.data.v2;

/**
 * Immutable physical specification for one explicitly bounded SOURCE/shared
 * store with the fixed logical identifier {@code source}.
 *
 * <p>Read service, write service and the shared network interface are three
 * distinct resource capacities. Bandwidths are modeled in decimal megabytes
 * per second (MB/s), not megabits: one MB is exactly 1,000,000 bytes. Conversion
 * to bytes per second uses double multiplication, without integer rounding.
 *
 * <p>This specification has no configurable store identity. The containing
 * platform is responsible for validating attachment host membership and route
 * topology. This value defines no clock, filesystem integration or output
 * policy, and does not change the existing infinite, off-fabric SOURCE model.
 */
public final class DataflowStorageSpec {
    private static final double BYTES_PER_DECIMAL_MEGABYTE = 1_000_000.0;

    private final int attachmentHostId;
    private final double readBandwidthMbPerSecond;
    private final double writeBandwidthMbPerSecond;
    private final double networkBandwidthMbPerSecond;

    private DataflowStorageSpec(int attachmentHostId,
            double readBandwidthMbPerSecond,
            double writeBandwidthMbPerSecond,
            double networkBandwidthMbPerSecond) {
        this.attachmentHostId = attachmentHostId;
        this.readBandwidthMbPerSecond = readBandwidthMbPerSecond;
        this.writeBandwidthMbPerSecond = writeBandwidthMbPerSecond;
        this.networkBandwidthMbPerSecond = networkBandwidthMbPerSecond;
    }

    /**
     * Validates all physical parameters before constructing a specification.
     * No relation between the three independent bandwidths is required.
     *
     * @param attachmentHostId nonnegative attachment host ID; membership in the
     *        platform and available routes are not checked here
     * @param readBandwidthMbPerSecond read-service bandwidth in decimal MB/s
     * @param writeBandwidthMbPerSecond write-service bandwidth in decimal MB/s
     * @param networkBandwidthMbPerSecond shared network-interface bandwidth in
     *        decimal MB/s, distinct from read and write service
     * @return an immutable specification for the fixed {@code source} store
     * @throws IllegalArgumentException if the host ID is negative, any bandwidth
     *         is not finite and strictly positive, or its conversion to bytes
     *         per second is not finite or is below {@link Double#MIN_NORMAL}
     */
    public static DataflowStorageSpec of(int attachmentHostId,
            double readBandwidthMbPerSecond,
            double writeBandwidthMbPerSecond,
            double networkBandwidthMbPerSecond) {
        if (attachmentHostId < 0) {
            throw new IllegalArgumentException("attachmentHostId must be nonnegative");
        }
        validateBandwidth("readBandwidthMbPerSecond", readBandwidthMbPerSecond);
        validateBandwidth("writeBandwidthMbPerSecond", writeBandwidthMbPerSecond);
        validateBandwidth("networkBandwidthMbPerSecond", networkBandwidthMbPerSecond);
        return new DataflowStorageSpec(attachmentHostId, readBandwidthMbPerSecond,
                writeBandwidthMbPerSecond, networkBandwidthMbPerSecond);
    }

    private static void validateBandwidth(String name, double bandwidthMbPerSecond) {
        if (!Double.isFinite(bandwidthMbPerSecond) || bandwidthMbPerSecond <= 0.0) {
            throw new IllegalArgumentException(name
                    + " must be finite and greater than zero (decimal MB/s)");
        }
        double capacityBytesPerSecond = bandwidthMbPerSecond * BYTES_PER_DECIMAL_MEGABYTE;
        if (!Double.isFinite(capacityBytesPerSecond)
                || capacityBytesPerSecond < Double.MIN_NORMAL) {
            throw new IllegalArgumentException(name
                    + " must convert to a finite normal bytes-per-second capacity");
        }
    }

    /** @return the nonnegative attachment host ID, without a topology assertion */
    public int getAttachmentHostId() {
        return attachmentHostId;
    }

    /** @return the original read-service bandwidth in decimal MB/s */
    public double getReadBandwidthMbPerSecond() {
        return readBandwidthMbPerSecond;
    }

    /** @return the original write-service bandwidth in decimal MB/s */
    public double getWriteBandwidthMbPerSecond() {
        return writeBandwidthMbPerSecond;
    }

    /** @return the original shared network-interface bandwidth in decimal MB/s */
    public double getNetworkBandwidthMbPerSecond() {
        return networkBandwidthMbPerSecond;
    }

    /** @return the finite, positive normal read-service capacity in bytes/s */
    public double getReadCapacityBytesPerSecond() {
        return readBandwidthMbPerSecond * BYTES_PER_DECIMAL_MEGABYTE;
    }

    /** @return the finite, positive normal write-service capacity in bytes/s */
    public double getWriteCapacityBytesPerSecond() {
        return writeBandwidthMbPerSecond * BYTES_PER_DECIMAL_MEGABYTE;
    }

    /** @return the finite, positive normal shared network-interface capacity in bytes/s */
    public double getNetworkCapacityBytesPerSecond() {
        return networkBandwidthMbPerSecond * BYTES_PER_DECIMAL_MEGABYTE;
    }

    /**
     * Compares the host ID and the exact original decimal MB/s values.
     *
     * @param other object to compare with this specification
     * @return whether both objects describe the same physical values
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DataflowStorageSpec)) {
            return false;
        }
        DataflowStorageSpec that = (DataflowStorageSpec) other;
        return attachmentHostId == that.attachmentHostId
                && Double.compare(readBandwidthMbPerSecond, that.readBandwidthMbPerSecond) == 0
                && Double.compare(writeBandwidthMbPerSecond, that.writeBandwidthMbPerSecond) == 0
                && Double.compare(networkBandwidthMbPerSecond, that.networkBandwidthMbPerSecond) == 0;
    }

    /** @return a deterministic hash of the host ID and original decimal MB/s values */
    @Override
    public int hashCode() {
        int result = attachmentHostId;
        result = 31 * result + Double.hashCode(readBandwidthMbPerSecond);
        result = 31 * result + Double.hashCode(writeBandwidthMbPerSecond);
        result = 31 * result + Double.hashCode(networkBandwidthMbPerSecond);
        return result;
    }
}
