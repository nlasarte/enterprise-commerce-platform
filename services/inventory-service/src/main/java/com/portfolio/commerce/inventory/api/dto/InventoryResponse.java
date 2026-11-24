package com.portfolio.commerce.inventory.api.dto;

import java.util.UUID;

import com.portfolio.commerce.inventory.domain.Inventory;

public record InventoryResponse(UUID productId, String sku, int availableQuantity, int reservedQuantity) {

    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(inventory.getProduct().getId(), inventory.getProduct().getSku(),
                inventory.getAvailableQuantity(), inventory.getReservedQuantity());
    }
}
