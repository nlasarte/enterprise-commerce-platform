package com.portfolio.commerce.inventory.persistence;

import java.util.Optional;
import java.util.UUID;

import com.portfolio.commerce.inventory.domain.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    boolean existsByOrderIdAndProduct_Id(UUID orderId, UUID productId);

    Optional<StockReservation> findByOrderIdAndProduct_Id(UUID orderId, UUID productId);
}
