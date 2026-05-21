package edu.yu.velocitytrading.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * An external order is a request submitted to the exchange.
 * Represents a buy or sell order with specified quantity and limit price.
 * @param id unique identifier for the order
 * @param symbol ticker symbol
 * @param quantity number of units to trade
 * @param limitPrice maximum price for buy, minimum price for sell
 * @param side buy or sell
 */
public record ExternalOrder(UUID id, String symbol, int quantity, double limitPrice, Side side) implements Identifiable<UUID>, Serializable {

    @Override
    public UUID getId() {
        return id;
    }
}