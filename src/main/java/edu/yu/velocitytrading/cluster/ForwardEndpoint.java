package edu.yu.velocitytrading.cluster;

/**
 * The TCP endpoint a worker listens on for leader-forwarded
 * {@code StateSnapshot}s. Each node publishes its own endpoint as the data
 * payload of its ephemeral member znode so the leader can route to peers by
 * nodeId.
 *
 * @param host         advertised hostname reachable from the leader
 * @param forwardPort  TCP port where {@code WorkerForwardReceiver} is listening
 */
public record ForwardEndpoint(String host, int forwardPort) {}
