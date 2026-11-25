package com.portfolio.commerce.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import com.portfolio.commerce.inventory.exception.InvalidReservationStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StockReservationTest {

    private final Product product = new Product("LAPTOP-001", "Test Laptop", null);
    private final UUID orderId = UUID.randomUUID();
    private StockReservation reservation;

    @BeforeEach
    void setUp() {
        reservation = new StockReservation(product, orderId, 20);
    }

    @Test
    void startsReserved() {
        assertEquals(ReservationStatus.RESERVED, reservation.getStatus());
        assertEquals(20, reservation.getQuantity());
        assertEquals(orderId, reservation.getOrderId());
        assertNotNull(reservation.getCreatedAt());
        assertEquals(reservation.getCreatedAt(), reservation.getUpdatedAt());
    }

    @Test
    void confirms() {
        assertTrue(reservation.confirm());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
    }

    @Test
    void releases() {
        assertTrue(reservation.release());
        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
    }

    @Test
    void ignoresRepeatedConfirmation() {
        reservation.confirm();
        Instant updatedAt = reservation.getUpdatedAt();
        assertFalse(reservation.confirm());
        assertEquals(updatedAt, reservation.getUpdatedAt());
    }

    @Test
    void ignoresRepeatedRelease() {
        reservation.release();
        Instant updatedAt = reservation.getUpdatedAt();
        assertFalse(reservation.release());
        assertEquals(updatedAt, reservation.getUpdatedAt());
    }

    @Test
    void rejectsReleaseAfterConfirmation() {
        reservation.confirm();
        assertThrows(InvalidReservationStateException.class, reservation::release);
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
    }

    @Test
    void rejectsConfirmationAfterRelease() {
        reservation.release();
        assertThrows(InvalidReservationStateException.class, reservation::confirm);
        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
    }

    @Test
    void rejectsZeroQuantity() {
        assertThrows(IllegalArgumentException.class, () -> new StockReservation(product, orderId, 0));
    }

    @Test
    void rejectsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class, () -> new StockReservation(product, orderId, -10));
    }
}
