package com.portfolio.commerce.inventory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "stock_reservations")
class StockReservation {

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

    StockReservation(UUID productId, UUID orderId, int quantity) {
        this.productId = productId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.status = Status.RESERVED;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    void checkQuantity(int requestedQuantity) {
        if (quantity != requestedQuantity) {
            throw new InventoryConflictException("This order has a reservation for a different quantity.");
        }
    }

    void apply(Status target, Inventory inventory) {
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

    enum Status {
        RESERVED, CONFIRMED, RELEASED
    }

    ReservationView view() {
        return new ReservationView(id, productId, orderId, quantity, status, createdAt, updatedAt);
    }

    record ReservationView(UUID id, UUID productId, UUID orderId, int quantity, Status status,
                           Instant createdAt, Instant updatedAt) {
    }
}
