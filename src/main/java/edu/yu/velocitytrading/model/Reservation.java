package edu.yu.velocitytrading.model;

import java.io.Serializable;

public record Reservation(String id, String symbol, int requestedBid, int grantedBid, int requestedAsk, int grantedAsk, ReservationStatus status) implements Identifiable<String>, Serializable {

    @Override
    public String getId() {
        return id;
    }
}