package com.portfolio.commerce.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.commerce.inventory.domain.Product;

public record ProductResponse(UUID id, String sku, String name, String description, Instant createdAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getSku(), product.getName(),
                product.getDescription(), product.getCreatedAt());
    }
}
