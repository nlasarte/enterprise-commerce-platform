package com.portfolio.commerce.inventory.application;

import java.util.UUID;

import com.portfolio.commerce.inventory.TestcontainersConfiguration;
import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.domain.ReservationStatus;
import com.portfolio.commerce.inventory.exception.InsufficientStockException;
import com.portfolio.commerce.inventory.persistence.StockReservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationIntegrationTest {

    @Autowired
    private ProductService products;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void persistsReservationAndConfirmsStock() {
        UUID productId = createProductWithStock(100);
        UUID orderId = UUID.randomUUID();
        var reservation = reservations.reserve(new ReservationRequest(orderId, productId, 20));

        assertNotNull(reservation.id());
        assertEquals(ReservationStatus.RESERVED, reservation.status());
        assertStock(productId, 80, 20);
        assertEquals(ReservationStatus.CONFIRMED, reservations.confirm(reservation.id()).status());
        assertStock(productId, 80, 0);
        assertEquals(ReservationStatus.CONFIRMED,
                reservationRepository.findByOrderIdAndProduct_Id(orderId, productId).orElseThrow().getStatus());
    }

    @Test
    void restoresStockOnRelease() {
        UUID productId = createProductWithStock(100);
        var reservation = reservations.reserve(new ReservationRequest(UUID.randomUUID(), productId, 30));

        assertEquals(ReservationStatus.RELEASED, reservations.release(reservation.id()).status());
        assertStock(productId, 100, 0);
    }

    @Test
    void leavesNoReservationOnInsufficientStock() {
        UUID productId = createProductWithStock(10);
        UUID orderId = UUID.randomUUID();

        assertThrows(InsufficientStockException.class,
                () -> reservations.reserve(new ReservationRequest(orderId, productId, 20)));
        assertStock(productId, 10, 0);
        assertFalse(reservationRepository.existsByOrderIdAndProduct_Id(orderId, productId));
    }

    @Test
    void rollsBackFlushedReservationAndStockChangesTogether() {
        UUID productId = createProductWithStock(100);
        UUID orderId = UUID.randomUUID();

        var failure = assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            reservations.reserve(new ReservationRequest(orderId, productId, 20));
            reservationRepository.flush();
            assertTrue(reservationRepository.existsByOrderIdAndProduct_Id(orderId, productId));
            assertStock(productId, 80, 20);
            throw new IllegalStateException("Simulated transaction failure");
        }));

        assertEquals("Simulated transaction failure", failure.getMessage());
        assertStock(productId, 100, 0);
        assertFalse(reservationRepository.existsByOrderIdAndProduct_Id(orderId, productId));
    }

    private UUID createProductWithStock(int quantity) {
        UUID id = products.createProduct(new ProductRequest("TEST-" + UUID.randomUUID(), "Test product", null)).id();
        inventory.restock(id, quantity);
        return id;
    }

    private void assertStock(UUID productId, int available, int reserved) {
        var stock = inventory.getInventory(productId);
        assertEquals(available, stock.availableQuantity());
        assertEquals(reserved, stock.reservedQuantity());
    }
}
