package com.portfolio.commerce.inventory.persistence;

import java.util.Optional;
import java.util.UUID;

import com.portfolio.commerce.inventory.domain.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    Optional<StockReservation> findByProductIdAndOrderId(UUID productId, UUID orderId);

    @Query("select r.productId from StockReservation r where r.id = :id")
    Optional<UUID> findProductId(UUID id);
}
