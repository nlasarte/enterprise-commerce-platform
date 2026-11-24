package com.portfolio.commerce.inventory.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.portfolio.commerce.inventory.exception.InventoryConflictException;
import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "stock_reservations")
@Getter
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID productId;
    private UUID orderId;
    private int quantity;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant createdAt;
    private Instant updatedAt;

    protected StockReservation() {
    }

    public StockReservation(Inventory inventory, UUID orderId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be greater than zero");
        }
        inventory.reserve(quantity);
        this.productId = inventory.getProduct().getId();
        this.orderId = orderId;
        this.quantity = quantity;
        this.status = Status.RESERVED;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    public void checkQuantity(int requestedQuantity) {
        if (quantity != requestedQuantity) {
            throw new InventoryConflictException("This order has a reservation for a different quantity.");
        }
    }

    public void apply(Status target, Inventory inventory) {
        if (status == target) {
            return;
        }
        if (status != Status.RESERVED) {
            throw new InventoryConflictException("This reservation is already " + status + ".");
        }
        switch (target) {
            case CONFIRMED -> inventory.confirm(quantity);
            case RELEASED -> inventory.release(quantity);
            case RESERVED -> throw new IllegalArgumentException("Expected a final reservation status.");
        }
        status = target;
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public enum Status {
        RESERVED, CONFIRMED, RELEASED
    }
}
