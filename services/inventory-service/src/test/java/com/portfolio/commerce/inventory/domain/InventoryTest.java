package com.portfolio.commerce.inventory.domain;

import com.portfolio.commerce.inventory.exception.InsufficientStockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryTest {

    private Inventory inventory;

    @BeforeEach
    void setUp() {
        inventory = new Inventory(new Product("LAPTOP-001", "Dell Laptop", "Test product"));
    }

    @Test
    void initializesWithZeroStock() {
        assertEquals(0, inventory.getAvailableQuantity());
        assertEquals(0, inventory.getReservedQuantity());
    }

    @Test
    void increasesAvailableStock() {
        inventory.restock(100);

        assertEquals(100, inventory.getAvailableQuantity());
        assertEquals(0, inventory.getReservedQuantity());
    }

    @Test
    void accumulatesMultipleRestocks() {
        inventory.restock(100);
        inventory.restock(50);

        assertEquals(150, inventory.getAvailableQuantity());
    }

    @Test
    void rejectsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class, () -> inventory.restock(-10));
        assertEquals(0, inventory.getAvailableQuantity());
    }

    @Test
    void rejectsZeroQuantity() {
        assertThrows(IllegalArgumentException.class, () -> inventory.restock(0));
    }

    @Test
    void preventsIntegerOverflow() {
        inventory.restock(Integer.MAX_VALUE);

        assertThrows(ArithmeticException.class, () -> inventory.restock(1));
        assertEquals(Integer.MAX_VALUE, inventory.getAvailableQuantity());
    }

    @Test
    void reservesAvailableStock() {
        inventory.restock(100);
        inventory.reserve(20);
        assertStock(80, 20);
    }

    @Test
    void rejectsInsufficientStock() {
        inventory.restock(10);
        assertThrows(InsufficientStockException.class, () -> inventory.reserve(20));
        assertStock(10, 0);
    }

    @Test
    void reservesAllAvailableStock() {
        inventory.restock(100);
        inventory.reserve(100);
        assertStock(0, 100);
    }

    @Test
    void rejectsZeroReservationQuantity() {
        assertThrows(IllegalArgumentException.class, () -> inventory.reserve(0));
    }

    @Test
    void rejectsNegativeReservationQuantity() {
        assertThrows(IllegalArgumentException.class, () -> inventory.reserve(-10));
    }

    @Test
    void confirmsReservedStock() {
        inventory.restock(100);
        inventory.reserve(20);
        inventory.confirmReservation(20);
        assertStock(80, 0);
    }

    @Test
    void releasesReservedStock() {
        inventory.restock(100);
        inventory.reserve(20);
        inventory.releaseReservation(20);
        assertStock(100, 0);
    }

    @Test
    void rejectsConfirmationAboveReservedStock() {
        inventory.restock(100);
        inventory.reserve(20);
        assertThrows(IllegalStateException.class, () -> inventory.confirmReservation(30));
        assertStock(80, 20);
    }

    @Test
    void rejectsReleaseAboveReservedStock() {
        inventory.restock(100);
        inventory.reserve(20);
        assertThrows(IllegalStateException.class, () -> inventory.releaseReservation(30));
        assertStock(80, 20);
    }

    @Test
    void preventsTotalStockOverflow() {
        inventory.restock(Integer.MAX_VALUE);
        inventory.reserve(10);
        assertThrows(ArithmeticException.class, () -> inventory.restock(1));
        assertStock(Integer.MAX_VALUE - 10, 10);
    }

    private void assertStock(int available, int reserved) {
        assertEquals(available, inventory.getAvailableQuantity());
        assertEquals(reserved, inventory.getReservedQuantity());
    }
}
