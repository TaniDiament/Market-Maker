package edu.yu.marketmaker.state;

import edu.yu.marketmaker.ha.LeaderElectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Tracks the current leader's advertised hostname.
 * Updated on leadership transitions via ZK notifications from {@link LeaderElectionService}.
 * Exposed via REST so non-leader replicas can redirect WebSocket clients to the leader.
 */
@Component
@Profile("trading-state")
public class LeaderInfo {

    private volatile String leaderHost;
    private final String localHost;

    public LeaderInfo(LeaderElectionService leaderElection,
                      @Value("${ha.advertise.host:localhost}") String localHost) {
        this.localHost = localHost;
        this.leaderHost = localHost; // assume we're the leader initially

        leaderElection.addListener(new LeaderElectionService.LeadershipListener() {
            @Override public void onLeaderAcquired() { leaderHost = localHost; }
            @Override public void onLeaderLost()    { /* wait for notification of next leader */ }
        });
    }

    public String getLeaderHost() {
        return leaderHost;
    }

    public void setLeaderHost(String host) {
        this.leaderHost = host;
    }

    public boolean isLeader() {
        return leaderHost.equals(localHost);
    }
}