/**
 * ZooKeeper-backed clustering for the Market Maker application.
 *
 * Handles node membership, leader election, and symbol-to-node assignment across
 * a fleet of market-maker JVMs. Each ticker is sharded to exactly one node; this
 * package is responsible for deciding which node owns which symbol, publishing
 * those assignments via ZooKeeper, and forwarding requests to the owning node
 * when a client hits the wrong one.
 *
 * Key responsibilities:
 * <ul>
 *   <li>Node identity and liveness via ephemeral znodes (see {@link edu.yu.velocitytrading.cluster.ClusterNode}).</li>
 *   <li>Leader election via Curator {@code LeaderLatch}.</li>
 *   <li>Assignment computation on the leader ({@link edu.yu.velocitytrading.cluster.Coordinator},
 *       {@link edu.yu.velocitytrading.cluster.EvenSplitStrategy}) and distribution to workers
 *       ({@link edu.yu.velocitytrading.cluster.AssignmentListener}).</li>
 *   <li>Cross-node request forwarding ({@link edu.yu.velocitytrading.cluster.LeaderForwarder},
 *       {@link edu.yu.velocitytrading.cluster.WorkerForwardReceiver}).</li>
 *   <li>Admin surface for adding/removing symbols at runtime
 *       ({@link edu.yu.velocitytrading.cluster.SymbolAdminController}).</li>
 * </ul>
 */
package edu.yu.velocitytrading.cluster;
