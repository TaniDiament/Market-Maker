package edu.yu.velocitytrading.model;

public record ReservationResponse(
        String id,
        ReservationStatus status,
        int grantedBidQuantity,
        int grantedAskQuantity
)
{

}
