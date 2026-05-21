package edu.yu.velocitytrading.exchange;

import edu.yu.velocitytrading.model.ExternalOrder;

public interface OrderValidator {
    
    void validateOrder(ExternalOrder order) throws OrderValidationException;
}
