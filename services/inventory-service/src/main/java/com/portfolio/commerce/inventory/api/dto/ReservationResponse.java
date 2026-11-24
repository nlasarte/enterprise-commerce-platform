package com.portfolio.commerce.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.commerce.inventory.domain.StockReservation;

public record ReservationResponse(UUID id, UUID productId, UUID orderId, int quantity, StockReservation.Status status,
                                  Instant createdAt, Instant updatedAt) {

    public static ReservationResponse from(StockReservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getProductId(), reservation.getOrderId(),
                reservation.getQuantity(), reservation.getStatus(), reservation.getCreatedAt(),
                reservation.getUpdatedAt());
    }
}
