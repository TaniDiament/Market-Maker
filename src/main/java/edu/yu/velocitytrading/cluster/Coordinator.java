package edu.yu.velocitytrading.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Symbol-to-node assignment engine.
 *
 * Dormant on every JVM until leadership is acquired; once leader it:
 * <ul>
 *   <li>Seeds the symbol znode from {@code symbols.txt} if empty.</li>
 *   <li>Watches {@code /marketmaker/members} (membership churn) and
 *       {@code /marketmaker/symbols} (operator-driven changes).</li>
 *   <li>Debounces triggers, then writes per-node assignment znodes from
 *       {@link EvenSplitStrategy}.</li>
 *   <li>Prunes assignment znodes left behind by departed nodes.</li>
 * </ul>
 *
 * Per the deployment design (dedicated leader): the leader writes itself an
 * empty assignment so it does not market-make while leading. On leadership
 * loss all watches are torn down, so the JVM can re-enter the worker pool on
 * the next rebalance from the new leader.
 */
@Component
@Profile("market-maker-node")
public class Coordinator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Coordinator.class);
    private static final long DEBOUNCE_MS = 200;

    private final CuratorFramework curator;
    private final ZkPaths paths;
    private final ClusterNode clusterNode;
    private final ConfigStore configStore;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean leading = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cluster-coordinator");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pendingRebalance;
    private volatile Map<String, List<String>> lastAssignment = Map.of();
    private volatile boolean forceFullWrite = false;

    private CuratorCache symbolsCache;
    private CuratorCacheListener membersListener;
    private CuratorCacheListener symbolsListener;

    public Coordinator(CuratorFramework curator,
                       ZkPaths paths,
                       ClusterNode clusterNode,
                       ConfigStore configStore) {
        this.curator = curator;
        this.paths = paths;
        this.clusterNode = clusterNode;
        this.configStore = configStore;
    }

    /**
     * Subscribes to leadership transitions and invokes
     * {@link #onAcquireLeadership()} if we're already leader on startup
     * (a benign race during boot).
     */
    @Override
    public void run(ApplicationArguments args) {
        clusterNode.getLeaderLatch().addListener(new LeaderLatchListener() {
            @Override
            public void isLeader() {
                onAcquireLeadership();
            }

            @Override
            public void notLeader() {
                onLoseLeadership();
            }
        });
        if (clusterNode.getLeaderLatch().hasLeadership()) {
            onAcquireLeadership();
        }
    }

    /**
     * Enter active-leader state: seed symbols if needed, attach watchers,
     * and schedule the initial rebalance. Idempotent — an {@link AtomicBoolean}
     * guards against duplicate notifications double-installing watches.
     */
    private void onAcquireLeadership() {
        if (!leading.compareAndSet(false, true)) {
            return;
        }
        log.info("node {} acquired leadership", clusterNode.getNodeId());

        lastAssignment = Map.of();
        forceFullWrite = true;

        try {
            configStore.seedIfEmpty();
        } catch (Exception e) {
            log.error("failed to seed symbols on leadership acquisition", e);
        }

        this.symbolsCache = CuratorCache.build(curator, paths.symbols());
        this.symbolsListener = (type, oldData, data) -> scheduleRebalance("symbols change: " + type);
        this.symbolsCache.listenable().addListener(symbolsListener);
        this.symbolsCache.start();

        this.membersListener = (type, oldData, data) -> scheduleRebalance("members change: " + type);
        clusterNode.getMembersCache().listenable().addListener(membersListener);

        scheduleRebalance("initial rebalance on leadership acquisition");
    }

    /**
     * Leave leader state: remove watchers, close the symbols cache, cancel
     * any pending rebalance. Idempotent.
     */
    private void onLoseLeadership() {
        if (!leading.compareAndSet(true, false)) {
            return;
        }
        log.info("node {} lost leadership", clusterNode.getNodeId());

        if (symbolsCache != null) {
            if (symbolsListener != null) {
                symbolsCache.listenable().removeListener(symbolsListener);
            }
            symbolsCache.close();
            symbolsCache = null;
            symbolsListener = null;
        }
        if (membersListener != null) {
            clusterNode.getMembersCache().listenable().removeListener(membersListener);
            membersListener = null;
        }
        synchronized (this) {
            if (pendingRebalance != null) {
                pendingRebalance.cancel(false);
                pendingRebalance = null;
            }
        }
        lastAssignment = Map.of();
    }

    /**
     * Coalesce a burst of triggers into a single rebalance. Cancels any
     * pending rebalance and re-schedules {@link #DEBOUNCE_MS} ms out, so a
     * flurry of events runs the computation only once.
     *
     * @param reason trigger description (logged at rebalance time)
     */
    private synchronized void scheduleRebalance(String reason) {
        if (!leading.get()) return;
        if (pendingRebalance != null) {
            pendingRebalance.cancel(false);
        }
        pendingRebalance = scheduler.schedule(() -> doRebalance(reason), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Compute and persist the new assignment map. The leader gets an empty
     * list (dedicated-leader mode); the rest share symbols via
     * {@link EvenSplitStrategy#split}. Stale znodes are pruned at the end.
     *
     * Failures are logged but not propagated, so a transient ZK error
     * doesn't kill the scheduler thread.
     */
    private void doRebalance(String reason) {
        if (!leading.get()) return;
        try {
            String leaderId = clusterNode.getNodeId();
            Set<String> members = clusterNode.getLiveMembers();
            if (!members.contains(leaderId)) {
                members = new TreeSet<>(members);
                members.add(leaderId);
            }
            Set<String> workers = new TreeSet<>(members);
            workers.remove(leaderId);
            List<String> symbols = configStore.readSymbols();

            Map<String, List<String>> assignments = EvenSplitStrategy.split(workers, symbols);

            log.info("rebalance ({}): leader={} workers={} symbols={} assignments={}",
                    reason, leaderId, workers, symbols, assignments);

            Map<String, List<String>> newAssignment = new HashMap<>(assignments);
            newAssignment.put(leaderId, List.of());

            boolean fullWrite = forceFullWrite;
            forceFullWrite = false;
            Set<String> unchanged = fullWrite
                    ? Set.of()
                    : EvenSplitStrategy.unchangedWorkers(lastAssignment, newAssignment);
            int skipped = 0;
            for (Map.Entry<String, List<String>> e : newAssignment.entrySet()) {
                if (unchanged.contains(e.getKey())) {
                    skipped++;
                    continue;
                }
                writeAssignment(e.getKey(), e.getValue());
            }
            if (skipped > 0) {
                log.debug("rebalance skipped {} unchanged znodes", skipped);
            }

            pruneStaleAssignments(members);
            lastAssignment = newAssignment;
        } catch (Exception e) {
            log.error("rebalance failed ({})", reason, e);
        }
    }

    /**
     * Create-or-update the per-node assignment znode.
     *
     * @throws Exception on serialization or ZK failure
     */
    private void writeAssignment(String nodeId, List<String> symbols) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(new ArrayList<>(symbols));
        String path = paths.assignmentFor(nodeId);
        if (curator.checkExists().forPath(path) == null) {
            try {
                curator.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.PERSISTENT)
                        .forPath(path, bytes);
                return;
            } catch (KeeperException.NodeExistsException ignored) {
                // fall through to setData
            }
        }
        curator.setData().forPath(path, bytes);
    }

    /**
     * Delete assignment znodes whose owner is no longer live. Tolerates
     * races (e.g. another rebalance deleting the same node).
     *
     * @param liveMembers current live members (anything else is stale)
     * @throws Exception on a non-ignored ZK error
     */
    private void pruneStaleAssignments(Set<String> liveMembers) throws Exception {
        List<String> existing;
        try {
            existing = curator.getChildren().forPath(paths.assignments());
        } catch (KeeperException.NoNodeException e) {
            return;
        }
        Set<String> live = new HashSet<>(liveMembers);
        for (String id : existing) {
            if (!live.contains(id)) {
                try {
                    curator.delete().forPath(paths.assignmentFor(id));
                    log.info("pruned stale assignment znode for dead node {}", id);
                } catch (KeeperException.NoNodeException ignored) {
                    // raced another deletion
                }
            }
        }
    }

    /** Shutdown hook: relinquish leadership and stop the scheduler. */
    @PreDestroy
    public void shutdown() {
        onLoseLeadership();
        scheduler.shutdownNow();
    }
}
