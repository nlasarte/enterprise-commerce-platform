package com.portfolio.commerce.inventory.domain;

import java.util.Objects;
import java.util.UUID;

import com.portfolio.commerce.inventory.exception.InsufficientStockException;
import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Getter
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(nullable = false)
    private int availableQuantity;

    @Column(nullable = false)
    private int reservedQuantity;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Inventory() {
    }

    public Inventory(Product product) {
        this.product = Objects.requireNonNull(product);
    }

    public void restock(int quantity) {
        validatePositiveQuantity(quantity);
        int newAvailableQuantity = Math.addExact(availableQuantity, quantity);
        Math.addExact(newAvailableQuantity, reservedQuantity);
        availableQuantity = newAvailableQuantity;
    }

    public void reserve(int quantity) {
        validatePositiveQuantity(quantity);
        if (availableQuantity < quantity) {
            throw new InsufficientStockException(quantity, availableQuantity);
        }
        int newReservedQuantity = Math.addExact(reservedQuantity, quantity);
        availableQuantity -= quantity;
        reservedQuantity = newReservedQuantity;
    }

    public void confirmReservation(int quantity) {
        validatePositiveQuantity(quantity);
        validateReservedQuantity(quantity);
        reservedQuantity -= quantity;
    }

    public void releaseReservation(int quantity) {
        validatePositiveQuantity(quantity);
        validateReservedQuantity(quantity);
        int newAvailableQuantity = Math.addExact(availableQuantity, quantity);
        reservedQuantity -= quantity;
        availableQuantity = newAvailableQuantity;
    }

    private static void validatePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
    }

    private void validateReservedQuantity(int quantity) {
        if (reservedQuantity < quantity) {
            throw new IllegalStateException("Reserved stock is lower than the requested quantity");
        }
    }
}
