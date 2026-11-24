package com.portfolio.commerce.inventory;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

@Entity
class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID productId;
    private int availableQuantity;
    private int reservedQuantity;

    @Version
    private Long version;

    protected Inventory() {
    }

    Inventory(UUID productId) {
        this.productId = productId;
    }

    void restock(int quantity) {
        if ((long) availableQuantity + reservedQuantity + quantity > Integer.MAX_VALUE) {
            throw new InventoryConflictException("The total stock would exceed the supported limit.");
        }
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

    StockView view() {
        return new StockView(productId, availableQuantity, reservedQuantity);
    }

    record StockView(UUID productId, int availableQuantity, int reservedQuantity) {
    }
}
