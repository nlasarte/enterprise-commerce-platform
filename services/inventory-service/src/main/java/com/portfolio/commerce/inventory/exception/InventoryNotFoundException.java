package com.portfolio.commerce.inventory.exception;

import java.util.UUID;

public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(UUID id) {
        super("Inventory not found for product: " + id);
    }
}
