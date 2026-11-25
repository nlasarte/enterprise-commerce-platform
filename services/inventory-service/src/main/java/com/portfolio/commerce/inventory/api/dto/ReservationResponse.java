package com.portfolio.commerce.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.commerce.inventory.domain.ReservationStatus;
import com.portfolio.commerce.inventory.domain.StockReservation;

public record ReservationResponse(UUID id, UUID orderId, UUID productId, int quantity, ReservationStatus status,
                                  Instant createdAt, Instant updatedAt) {

    public static ReservationResponse from(StockReservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getOrderId(), reservation.getProduct().getId(),
                reservation.getQuantity(), reservation.getStatus(), reservation.getCreatedAt(),
                reservation.getUpdatedAt());
    }
}
