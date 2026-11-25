package com.portfolio.commerce.inventory.exception;

import com.portfolio.commerce.inventory.domain.ReservationStatus;

public class InvalidReservationStateException extends RuntimeException {

    public InvalidReservationStateException(ReservationStatus current, ReservationStatus requested) {
        super("Cannot transition reservation from " + current + " to " + requested);
    }
}
