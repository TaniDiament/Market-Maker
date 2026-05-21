package edu.yu.velocitytrading.cluster;

/**
 * Builds every ZooKeeper path the cluster uses. One type keeps the znode
 * layout in sync across readers and writers.
 *
 * Layout under {@code base}:
 * <pre>
 *   {base}/election           — Curator LeaderLatch participants
 *   {base}/members            — parent of one ephemeral-sequential znode per live node
 *   {base}/members/n-XXXXX    — a single live node (id = "n-XXXXX")
 *   {base}/symbols            — JSON array: the cluster-wide symbol list
 *   {base}/assignments        — parent of one persistent znode per node
 *   {base}/assignments/n-X    — JSON array of symbols this node should own
 * </pre>
 */
public final class ZkPaths {

    private final String base;

    /**
     * @param base absolute znode path (must start with '/'), e.g. "/marketmaker"
     * @throws IllegalArgumentException if base is null, blank, or not absolute
     */
    public ZkPaths(String base) {
        if (base == null || base.isBlank() || !base.startsWith("/")) {
            throw new IllegalArgumentException("base path must start with '/'");
        }
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** @return the cluster's root znode path (no trailing slash). */
    public String base() {
        return base;
    }

    /** @return the LeaderLatch parent znode. */
    public String election() {
        return base + "/election";
    }

    /** @return the parent znode for ephemeral member entries. */
    public String members() {
        return base + "/members";
    }

    /**
     * @return the prefix for EPHEMERAL_SEQUENTIAL creates; ZK appends a
     *         10-digit suffix.
     */
    public String memberNodePrefix() {
        return members() + "/n-";
    }

    /** @return the znode storing the symbol list as a JSON array. */
    public String symbols() {
        return base + "/symbols";
    }

    /** @return the parent znode for per-node assignment lists. */
    public String assignments() {
        return base + "/assignments";
    }

    /**
     * @param nodeId cluster member id (e.g. "n-0000000003")
     * @return the znode path holding that node's assigned symbols
     */
    public String assignmentFor(String nodeId) {
        return assignments() + "/" + nodeId;
    }
}
