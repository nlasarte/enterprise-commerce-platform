package com.portfolio.commerce.inventory.api.dto;

import jakarta.validation.constraints.Positive;

public record RestockRequest(@Positive(message = "Quantity must be greater than zero") int quantity) {
}
