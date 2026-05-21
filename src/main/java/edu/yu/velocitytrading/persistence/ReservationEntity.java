package edu.yu.velocitytrading.persistence;

import edu.yu.velocitytrading.model.Reservation;
import edu.yu.velocitytrading.model.ReservationStatus;
import jakarta.persistence.*;

/**
 * JPA Entity for Reservation records used by Hazelcast MapStore.
 */
@Entity
@Table(name = "reservations")
public class ReservationEntity implements IdentifiableEntity<String> {

    @Id
    private String id;
    private String symbol;
    private int requestedBid;
    private int grantedBid;
    private int requestedAsk;
    private int grantedAsk;
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    // --- Constructors ---

    /**
     * No-args constructor required by JPA.
     */
    public ReservationEntity() {
    }

    /**
     * All-args constructor.
     */
    public ReservationEntity(String id, String symbol, int requestedBid, int grantedBid,
                             int requestedAsk, int grantedAsk, ReservationStatus status) {
        this.id = id;
        this.symbol = symbol;
        this.requestedBid = requestedBid;
        this.grantedBid = grantedBid;
        this.requestedAsk = requestedAsk;
        this.grantedAsk = grantedAsk;
        this.status = status;
    }

    // --- IdentifiableEntity Implementation ---

    @Override
    public String getId() {
        return id;
    }

    // --- Conversion Methods ---

    /**
     * Converts this JPA entity back into the immutable Reservation record.
     * @return A Reservation record.
     */
    public Reservation toRecord() {
        return new Reservation(this.id, this.symbol,
                this.requestedBid, this.grantedBid,
                this.requestedAsk, this.grantedAsk,
                this.status);
    }

    /**
     * Static helper to create an Entity from a Record.
     * @param reservation The reservation record.
     * @return A new ReservationEntity.
     */
    public static ReservationEntity fromRecord(Reservation reservation) {
        return new ReservationEntity(
                reservation.id(),
                reservation.symbol(),
                reservation.requestedBid(),
                reservation.grantedBid(),
                reservation.requestedAsk(),
                reservation.grantedAsk(),
                reservation.status()
        );
    }

    // --- Getters and Setters ---

    public void setId(String id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public int getRequestedBid() {
        return requestedBid;
    }

    public void setRequestedBid(int requestedBid) {
        this.requestedBid = requestedBid;
    }

    public int getGrantedBid() {
        return grantedBid;
    }

    public void setGrantedBid(int grantedBid) {
        this.grantedBid = grantedBid;
    }

    public int getRequestedAsk() {
        return requestedAsk;
    }

    public void setRequestedAsk(int requestedAsk) {
        this.requestedAsk = requestedAsk;
    }

    public int getGrantedAsk() {
        return grantedAsk;
    }

    public void setGrantedAsk(int grantedAsk) {
        this.grantedAsk = grantedAsk;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "ReservationEntity{" +
                "id=" + id +
                ", symbol='" + symbol + '\'' +
                ", requestedBid=" + requestedBid +
                ", grantedBid=" + grantedBid +
                ", requestedAsk=" + requestedAsk +
                ", grantedAsk=" + grantedAsk +
                ", status=" + status +
                '}';
    }
}
