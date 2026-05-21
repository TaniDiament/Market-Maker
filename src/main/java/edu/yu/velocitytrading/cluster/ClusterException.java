package edu.yu.velocitytrading.cluster;

/**
 * Unchecked wrapper for cluster-layer failures (ZK I/O, serialization,
 * seed-file reads, ...). One runtime type keeps the cluster API free of
 * checked exceptions while still letting callers tell cluster faults
 * apart from generic {@link RuntimeException}s.
 */
public class ClusterException extends RuntimeException {

    public ClusterException(String message) {
        super(message);
    }

    /** Wraps a lower-level cause (typically {@code KeeperException} or {@code IOException}). */
    public ClusterException(String message, Throwable cause) {
        super(message, cause);
    }
}
