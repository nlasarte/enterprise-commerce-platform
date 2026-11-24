package com.portfolio.commerce.inventory.application;

import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationResponse;
import com.portfolio.commerce.inventory.domain.Inventory;
import com.portfolio.commerce.inventory.domain.StockReservation;
import com.portfolio.commerce.inventory.exception.InventoryNotFoundException;
import com.portfolio.commerce.inventory.exception.ReservationNotFoundException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import com.portfolio.commerce.inventory.persistence.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final InventoryRepository inventory;
    private final StockReservationRepository reservations;

    @Transactional
    public ReservationResult reserve(ReservationRequest request) {
        Inventory stock = lockStock(request.productId());
        var existing = reservations.findByProductIdAndOrderId(request.productId(), request.orderId());
        if (existing.isPresent()) {
            StockReservation reservation = existing.get();
            reservation.checkQuantity(request.quantity());
            return new ReservationResult(ReservationResponse.from(reservation), false);
        }
        StockReservation reservation = reservations.save(new StockReservation(stock, request.orderId(), request.quantity()));
        return new ReservationResult(ReservationResponse.from(reservation), true);
    }

    public ReservationResponse getReservation(UUID id) {
        return ReservationResponse.from(findReservation(id));
    }

    @Transactional
    public ReservationResponse finalizeReservation(UUID id, StockReservation.Status target) {
        // Read the reservation state only after acquiring the product's inventory lock.
        UUID productId = reservations.findProductId(id).orElseThrow(() -> new ReservationNotFoundException(id));
        Inventory stock = lockStock(productId);
        StockReservation reservation = findReservation(id);
        reservation.apply(target, stock);
        return ReservationResponse.from(reservation);
    }

    private Inventory lockStock(UUID productId) {
        return inventory.lockByProductId(productId).orElseThrow(() -> new InventoryNotFoundException(productId));
    }

    private StockReservation findReservation(UUID id) {
        return reservations.findById(id).orElseThrow(() -> new ReservationNotFoundException(id));
    }

    public record ReservationResult(ReservationResponse reservation, boolean created) {
    }
}
