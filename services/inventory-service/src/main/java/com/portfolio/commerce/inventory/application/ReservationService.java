package com.portfolio.commerce.inventory.application;

import java.util.UUID;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationResponse;
import com.portfolio.commerce.inventory.domain.Inventory;
import com.portfolio.commerce.inventory.domain.StockReservation;
import com.portfolio.commerce.inventory.exception.DuplicateReservationException;
import com.portfolio.commerce.inventory.exception.InventoryNotFoundException;
import com.portfolio.commerce.inventory.exception.ReservationNotFoundException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import com.portfolio.commerce.inventory.persistence.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final InventoryRepository inventory;
    private final StockReservationRepository reservations;

    @Transactional
    public ReservationResponse reserve(ReservationRequest request) {
        Inventory stock = lockStock(request.productId());
        if (reservations.existsByOrderIdAndProduct_Id(request.orderId(), request.productId())) {
            throw new DuplicateReservationException(request.orderId(), request.productId());
        }
        stock.reserve(request.quantity());
        StockReservation reservation = new StockReservation(stock.getProduct(), request.orderId(), request.quantity());
        try {
            return ReservationResponse.from(reservations.saveAndFlush(reservation));
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && "uq_order_product".equals(violation.getConstraintName())) {
                throw new DuplicateReservationException(request.orderId(), request.productId(), exception);
            }
            throw exception;
        }
    }

    public ReservationResponse getReservation(UUID id) {
        return ReservationResponse.from(findReservation(id));
    }

    @Transactional
    public ReservationResponse confirm(UUID id) {
        return transition(id, StockReservation::confirm, Inventory::confirmReservation);
    }

    @Transactional
    public ReservationResponse release(UUID id) {
        return transition(id, StockReservation::release, Inventory::releaseReservation);
    }

    private ReservationResponse transition(UUID id, Predicate<StockReservation> change,
                                           ObjIntConsumer<Inventory> updateStock) {
        // Read the reservation state only after acquiring the product's inventory lock.
        UUID productId = reservations.findProductId(id).orElseThrow(() -> new ReservationNotFoundException(id));
        Inventory stock = lockStock(productId);
        StockReservation reservation = findReservation(id);
        if (change.test(reservation)) {
            updateStock.accept(stock, reservation.getQuantity());
        }
        return ReservationResponse.from(reservation);
    }

    private Inventory lockStock(UUID productId) {
        return inventory.lockByProductId(productId).orElseThrow(() -> new InventoryNotFoundException(productId));
    }

    private StockReservation findReservation(UUID id) {
        return reservations.findById(id).orElseThrow(() -> new ReservationNotFoundException(id));
    }
}
