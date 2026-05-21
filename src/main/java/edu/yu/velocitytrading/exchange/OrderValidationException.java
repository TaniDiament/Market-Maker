package edu.yu.velocitytrading.exchange;

public class OrderValidationException extends RuntimeException {

    public OrderValidationException(String reason) {
        super("Order validation failed because: " + reason);
    }
}
