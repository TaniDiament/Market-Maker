package edu.yu.velocitytrading.persistence;

import edu.yu.velocitytrading.model.ExternalOrder;
import edu.yu.velocitytrading.model.Side;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * JPA Entity for ExternalOrder records used by Hazelcast MapStore.
 */
@Entity
@Table(name = "external_orders")
public class ExternalOrderEntity implements IdentifiableEntity<UUID> {

    @Id
    private UUID id;
    private String symbol;
    private int quantity;
    private double limitPrice;
    @Enumerated(EnumType.STRING)
    private Side side;

    // --- Constructors ---

    /**
     * No-args constructor required by JPA.
     */
    public ExternalOrderEntity() {
    }

    /**
     * Constructor for ExternalOrderEntity.
     * @param id unique identifier
     * @param symbol ticker
     * @param quantity number of shares
     * @param limitPrice limit price
     * @param side buy or sell
     */
    public ExternalOrderEntity(UUID id, String symbol, int quantity, double limitPrice, Side side) {
        this.id = id;
        this.symbol = symbol;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.side = side;
    }

    // --- IdentifiableEntity Implementation ---

    /**
     * Returns the unique identifier for this entity.
     * @return
     */
    @Override
    public UUID getId() {
        return id;
    }

    // --- Conversion Methods ---

    /**
     * Converts this JPA entity back into the immutable ExternalOrder record.
     * @return An ExternalOrder record.
     */
    public ExternalOrder toRecord() {
        return new ExternalOrder(this.id, this.symbol, this.quantity, this.limitPrice, this.side);
    }

    /**
     * Static helper to create an Entity from a Record.
     * @param order The external order record.
     * @return A new ExternalOrderEntity.
     */
    public static ExternalOrderEntity fromRecord(ExternalOrder order) {
        return new ExternalOrderEntity(
                order.id(),
                order.symbol(),
                order.quantity(),
                order.limitPrice(),
                order.side()
        );
    }

    // --- Getters and Setters ---

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public double getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(double limitPrice) {
        this.limitPrice = limitPrice;
    }

    public Side getSide() {
        return side;
    }

    public void setSide(Side side) {
        this.side = side;
    }

    @Override
    public String toString() {
        return "ExternalOrderEntity{" +
                "id=" + id +
                ", symbol='" + symbol + '\'' +
                ", quantity=" + quantity +
                ", limitPrice=" + limitPrice +
                ", side=" + side +
                '}';
    }
}

