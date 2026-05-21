package edu.yu.velocitytrading.exchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import edu.yu.velocitytrading.model.ExternalOrder;

@Component
@Profile("testing")
public class TestOrderDispatcher implements OrderDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(TestOrderDispatcher.class);

    @Override
    public void dispatchOrder(ExternalOrder order) {
        logger.info("Dispatching {} order: {} x {} @ {}",
            order.side(), order.symbol(), order.quantity(), order.limitPrice());
    }
    
}
