package edu.yu.marketmaker.exchange;

import edu.yu.marketmaker.ha.LeaderAwareRSocketClient;
import edu.yu.marketmaker.model.Fill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Leader-aware RSocket fill sender. Resolves the current trading-state leader
 * via Zookeeper on every call. Handles failover transparently.
 */
@Component
@Profile("exchange")
public class RSocketFillSender implements FillSender {

    private static final Logger logger = LoggerFactory.getLogger(RSocketFillSender.class);
    private final LeaderAwareRSocketClient client;

    public RSocketFillSender(LeaderAwareRSocketClient client) {
        this.client = client;
    }

    @Override
    public void sendFill(Fill fill) {
        logger.info("Sending {} fill: {} x {} @ {}",
                fill.side(), fill.symbol(), fill.quantity(), fill.price());
        client.send("trading-state", "state.fills", fill);
    }
}