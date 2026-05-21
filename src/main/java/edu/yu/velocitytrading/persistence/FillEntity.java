package edu.yu.velocitytrading.persistence;

import edu.yu.velocitytrading.model.Fill;
import edu.yu.velocitytrading.model.Side;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * JPA Entity for Fill records used by Hazelcast MapStore.
 */
@Entity
@Table(name = "fills")
public class FillEntity implements IdentifiableEntity<UUID> {

    @Id
    private UUID orderId;
    private String symbol;
    @Enumerated(EnumType.STRING)
    private Side side;
    private int quantity;
    private double price;
    private UUID quoteId;
    private long createdAt;

    // --- Constructors ---

    /**
     * No-args constructor required by JPA.
     */
    public FillEntity() {
    }

    /**
     * All-args constructor.
     */
    public FillEntity(UUID orderId, String symbol, Side side, int quantity, double price, UUID quoteId, long createdAt) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.quoteId = quoteId;
        this.createdAt = createdAt;
    }

    // --- IdentifiableEntity Implementation ---

    @Override
    public UUID getId() {
        return orderId;
    }

    // --- Conversion Methods ---

    /**
     * Converts this JPA entity back into the immutable Fill record.
     * @return A Fill record.
     */
    public Fill toRecord() {
        return new Fill(this.orderId, this.symbol, this.side, this.quantity, this.price, this.quoteId, this.createdAt);
    }

    /**
     * Static helper to create an Entity from a Record.
     * @param fill The fill record.
     * @return A new FillEntity.
     */
    public static FillEntity fromRecord(Fill fill) {
        return new FillEntity(
                fill.orderId(),
                fill.symbol(),
                fill.side(),
                fill.quantity(),
                fill.price(),
                fill.quoteId(),
                fill.createdAt()
        );
    }

    // --- Getters and Setters ---

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Side getSide() {
        return side;
    }

    public void setSide(Side side) {
        this.side = side;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public void setQuoteId(UUID quoteId) {
        this.quoteId = quoteId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "FillEntity{" +
                "orderId=" + orderId +
                ", symbol='" + symbol + '\'' +
                ", side=" + side +
                ", quantity=" + quantity +
                ", price=" + price +
                ", quoteId=" + quoteId +
                ", createdAt=" + createdAt +
                '}';
    }
}

