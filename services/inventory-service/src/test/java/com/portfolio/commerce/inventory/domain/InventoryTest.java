package com.portfolio.commerce.inventory.domain;

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
}
