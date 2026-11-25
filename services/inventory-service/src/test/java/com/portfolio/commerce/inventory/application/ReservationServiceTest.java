package com.portfolio.commerce.inventory.application;

import java.util.Optional;
import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.domain.Inventory;
import com.portfolio.commerce.inventory.domain.Product;
import com.portfolio.commerce.inventory.domain.ReservationStatus;
import com.portfolio.commerce.inventory.domain.StockReservation;
import com.portfolio.commerce.inventory.exception.DuplicateReservationException;
import com.portfolio.commerce.inventory.exception.InsufficientStockException;
import com.portfolio.commerce.inventory.exception.InvalidReservationStateException;
import com.portfolio.commerce.inventory.exception.ReservationNotFoundException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import com.portfolio.commerce.inventory.persistence.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private StockReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservations;

    private final UUID productId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final Product product = mock(Product.class);
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        inventory = new Inventory(product);
        inventory.restock(100);
    }

    @Test
    void reservesStockAndPersistsReservation() {
        lockStock();
        when(product.getId()).thenReturn(productId);
        when(reservationRepository.saveAndFlush(any(StockReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var reservation = reservations.reserve(new ReservationRequest(orderId, productId, 20));

        assertEquals(ReservationStatus.RESERVED, reservation.status());
        assertEquals(productId, reservation.productId());
        assertEquals(20, reservation.quantity());
        assertStock(80, 20);
        verify(reservationRepository).saveAndFlush(any(StockReservation.class));
    }

    @Test
    void rejectsDuplicateReservationWithoutChangingStock() {
        lockStock();
        when(reservationRepository.existsByOrderIdAndProduct_Id(orderId, productId)).thenReturn(true);

        assertThrows(DuplicateReservationException.class,
                () -> reservations.reserve(new ReservationRequest(orderId, productId, 20)));
        assertStock(100, 0);
        verify(reservationRepository, never()).saveAndFlush(any(StockReservation.class));
    }

    @Test
    void rejectsInsufficientStockWithoutSavingReservation() {
        lockStock();

        assertThrows(InsufficientStockException.class,
                () -> reservations.reserve(new ReservationRequest(orderId, productId, 200)));
        assertStock(100, 0);
        verify(reservationRepository, never()).saveAndFlush(any(StockReservation.class));
    }

    @Test
    void confirmsAndConsumesReservedStock() {
        existingReservation();
        when(product.getId()).thenReturn(productId);

        assertEquals(ReservationStatus.CONFIRMED, reservations.confirm(reservationId).status());
        assertStock(80, 0);
    }

    @Test
    void releasesAndRestoresAvailableStock() {
        existingReservation();
        when(product.getId()).thenReturn(productId);

        assertEquals(ReservationStatus.RELEASED, reservations.release(reservationId).status());
        assertStock(100, 0);
    }

    @Test
    void repeatedConfirmationDoesNotConsumeStockAgain() {
        var reservation = existingReservation();
        reservation.confirm();
        inventory.confirmReservation(20);
        when(product.getId()).thenReturn(productId);

        assertEquals(ReservationStatus.CONFIRMED, reservations.confirm(reservationId).status());
        assertStock(80, 0);
    }

    @Test
    void repeatedReleaseDoesNotRestoreStockAgain() {
        var reservation = existingReservation();
        reservation.release();
        inventory.releaseReservation(20);
        when(product.getId()).thenReturn(productId);

        assertEquals(ReservationStatus.RELEASED, reservations.release(reservationId).status());
        assertStock(100, 0);
    }

    @Test
    void rejectsInvalidStateTransitionWithoutChangingStock() {
        var reservation = existingReservation();
        reservation.confirm();
        inventory.confirmReservation(20);

        assertThrows(InvalidReservationStateException.class, () -> reservations.release(reservationId));
        assertStock(80, 0);
    }

    @Test
    void rejectsUnknownReservation() {
        when(reservationRepository.findProductId(reservationId)).thenReturn(Optional.empty());

        assertThrows(ReservationNotFoundException.class, () -> reservations.confirm(reservationId));
        verifyNoInteractions(inventoryRepository);
    }

    private StockReservation existingReservation() {
        inventory.reserve(20);
        StockReservation reservation = new StockReservation(product, orderId, 20);
        when(reservationRepository.findProductId(reservationId)).thenReturn(Optional.of(productId));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        lockStock();
        return reservation;
    }

    private void lockStock() {
        when(inventoryRepository.lockByProductId(productId)).thenReturn(Optional.of(inventory));
    }

    private void assertStock(int available, int reserved) {
        assertEquals(available, inventory.getAvailableQuantity());
        assertEquals(reserved, inventory.getReservedQuantity());
    }
}
