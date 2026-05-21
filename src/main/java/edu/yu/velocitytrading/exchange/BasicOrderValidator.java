package edu.yu.velocitytrading.exchange;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import edu.yu.velocitytrading.model.ExternalOrder;

@Component
@Profile("exchange")
public class BasicOrderValidator implements OrderValidator {

    @Override
    public void validateOrder(ExternalOrder order) throws OrderValidationException {
        if (order.quantity() <= 0 || order.limitPrice() <= 0) {
            throw new OrderValidationException("Invalid quantity or price");
        }
    }
    
}
