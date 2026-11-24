package com.portfolio.commerce.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductRequest(
        @NotBlank(message = "SKU is required") @Size(max = 100) String sku,
        @NotBlank(message = "Product name is required") @Size(max = 255) String name,
        @Size(max = 1000) String description
) {
}
