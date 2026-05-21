package edu.yu.velocitytrading.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * A fill is the result of a successful execution against a quote.
 * @param orderId
 * @param symbol
 * @param side buy/sell
 * @param quantity
 * @param price
 * @param quoteId
 * @param createdAt timestamp
 */
public record Fill(UUID orderId, String symbol, Side side, int quantity, double price, UUID quoteId, long createdAt) implements Identifiable<UUID>, Serializable {

    @Override
    public UUID getId() {
        return orderId;
    }
}
