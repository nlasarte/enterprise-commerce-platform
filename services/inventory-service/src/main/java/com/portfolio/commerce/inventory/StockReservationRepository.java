package com.portfolio.commerce.inventory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    Optional<StockReservation> findByProductIdAndOrderId(UUID productId, UUID orderId);

    @Query("select r.productId from StockReservation r where r.id = :id")
    Optional<UUID> findProductId(UUID id);
}
