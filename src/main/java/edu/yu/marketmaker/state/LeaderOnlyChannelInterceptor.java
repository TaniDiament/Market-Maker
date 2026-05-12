package edu.yu.marketmaker.state;

import edu.yu.marketmaker.ha.LeaderElectionService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Rejects STOMP CONNECT frames on non-leader replicas with an error message,
 * forcing clients to reconnect and eventually hit the leader via load balancer.
 */
@Component
public class LeaderOnlyChannelInterceptor implements ChannelInterceptor {

    private final LeaderElectionService leaderElection;

    public LeaderOnlyChannelInterceptor(LeaderElectionService leaderElection) {
        this.leaderElection = leaderElection;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            if (!leaderElection.isLeader()) {
                throw new IllegalStateException("not leader — reconnect to current leader");
            }
        }
        return message;
    }
}