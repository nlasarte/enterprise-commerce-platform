package com.portfolio.commerce.inventory.domain;

import java.util.Objects;
import java.util.UUID;

import com.portfolio.commerce.inventory.exception.InventoryConflictException;
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
        if (quantity <= 0) {
            throw new IllegalArgumentException("Restock quantity must be greater than zero");
        }
        // Include reserved stock so releasing it can never overflow available stock.
        Math.addExact(Math.addExact(availableQuantity, reservedQuantity), quantity);
        availableQuantity += quantity;
    }

    void reserve(int quantity) {
        if (availableQuantity < quantity) {
            throw new InventoryConflictException("Insufficient available stock.");
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
    }

    void confirm(int quantity) {
        reservedQuantity -= quantity;
    }

    void release(int quantity) {
        reservedQuantity -= quantity;
        availableQuantity += quantity;
    }
}
