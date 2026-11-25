package com.portfolio.commerce.inventory.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import com.portfolio.commerce.inventory.exception.InvalidReservationStateException;
import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "stock_reservations", uniqueConstraints = @UniqueConstraint(
        name = "uq_order_product", columnNames = {"order_id", "product_id"}))
@Getter
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockReservation() {
    }

    public StockReservation(Product product, UUID orderId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be greater than zero");
        }
        this.product = Objects.requireNonNull(product);
        this.orderId = Objects.requireNonNull(orderId);
        this.quantity = quantity;
        this.status = ReservationStatus.RESERVED;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    public boolean confirm() {
        return transitionTo(ReservationStatus.CONFIRMED);
    }

    public boolean release() {
        return transitionTo(ReservationStatus.RELEASED);
    }

    private boolean transitionTo(ReservationStatus target) {
        if (status == target) {
            return false;
        }
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException(status, target);
        }
        status = target;
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return true;
    }
}
