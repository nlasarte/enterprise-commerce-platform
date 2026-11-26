package com.portfolio.commerce.inventory.persistence;

import java.util.Optional;
import java.util.UUID;

import com.portfolio.commerce.inventory.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findByProduct_Id(UUID productId);
}
