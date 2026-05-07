package edu.yu.marketmaker.ha;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Borg-style leader election for a single service role.
 *
 * <p>Each replica of a given service creates one of these and calls
 * {@link #start()}. Curator's {@link LeaderLatch} coordinates via
 * ephemeral-sequential nodes under {@code /mm/leaders/<service>} and
 * elects exactly one leader. If the leader's ZK session drops, another
 * replica wins the next election within a few seconds.
 *
 * <p>This class is intentionally framework-agnostic (no Spring annotations).
 * The Spring wiring lives in {@link HighAvailabilityConfig}.
 */
public class LeaderElectionService implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LeaderElectionService.class);

    private static final String LEADER_BASE_PATH = "/mm/leaders";

    private final CuratorFramework curator;
    private final String serviceName;
    private final String instanceId;
    private final LeaderLatch latch;
    private final List<LeadershipListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param curator     shared Curator client (managed by {@link HighAvailabilityConfig})
     * @param serviceName service role, e.g. "trading-state", "exchange"
     * @param instanceId  unique ID for this replica (hostname + port works well)
     */
    public LeaderElectionService(CuratorFramework curator, String serviceName, String instanceId) {
        this.curator = curator;
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.latch = new LeaderLatch(
                curator,
                LEADER_BASE_PATH + "/" + serviceName,
                instanceId,
                LeaderLatch.CloseMode.NOTIFY_LEADER  // fire notLeader() on close
        );
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        start();
    }

    public void start() throws Exception {
        latch.addListener(new LeaderLatchListener() {
            @Override
            public void isLeader() {
                log.warn("[{}] LEADER ACQUIRED (instance={})", serviceName, instanceId);
                notifyAcquired();
            }

            @Override
            public void notLeader() {
                log.warn("[{}] LEADER LOST (instance={})", serviceName, instanceId);
                notifyLost();
            }
        });
        latch.start();
        log.info("[{}] joined leader election as instance={}", serviceName, instanceId);
    }

    /**
     * @return true if this replica currently holds the leader lock
     */
    public boolean isLeader() {
        return latch.hasLeadership();
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Register a callback that fires on leadership transitions.
     * Listeners run on Curator's event thread — keep them fast.
     */
    public void addListener(LeadershipListener l) {
        listeners.add(l);
    }

    private void notifyAcquired() {
        for (LeadershipListener l : listeners) {
            try {
                l.onLeaderAcquired();
            } catch (Exception e) {
                log.error("[{}] listener onLeaderAcquired threw", serviceName, e);
            }
        }
    }

    private void notifyLost() {
        for (LeadershipListener l : listeners) {
            try {
                l.onLeaderLost();
            } catch (Exception e) {
                log.error("[{}] listener onLeaderLost threw", serviceName, e);
            }
        }
    }

    @Override
    public void destroy() throws Exception {
        log.info("[{}] closing leader latch (instance={})", serviceName, instanceId);
        latch.close();
    }

    /**
     * Simple two-method callback, avoids pulling in RxJava / Reactor here.
     */
    public interface LeadershipListener {
        default void onLeaderAcquired() {}
        default void onLeaderLost() {}
    }
}
