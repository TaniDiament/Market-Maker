package edu.yu.velocitytrading.model;

import java.io.Serializable;
import java.util.UUID;

public record Quote(String symbol, double bidPrice, int bidQuantity, double askPrice, int askQuantity, UUID quoteId, long expiresAt) implements Identifiable<String>, Serializable {

    @Override
    public String getId() {
        return symbol;
    }
}
