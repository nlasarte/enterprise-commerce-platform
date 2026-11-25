package com.portfolio.commerce.inventory.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReservationRequest(
        @NotNull(message = "Order ID is required") UUID orderId,
        @NotNull(message = "Product ID is required") UUID productId,
        @Positive(message = "Quantity must be greater than zero") int quantity
) {
}
