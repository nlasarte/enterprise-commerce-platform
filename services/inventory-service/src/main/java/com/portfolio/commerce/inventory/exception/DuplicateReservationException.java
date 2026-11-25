package com.portfolio.commerce.inventory.exception;

import java.util.UUID;

public class DuplicateReservationException extends RuntimeException {

    public DuplicateReservationException(UUID orderId, UUID productId) {
        this(orderId, productId, null);
    }

    public DuplicateReservationException(UUID orderId, UUID productId, Throwable cause) {
        super("A reservation already exists for order " + orderId + " and product " + productId, cause);
    }
}
