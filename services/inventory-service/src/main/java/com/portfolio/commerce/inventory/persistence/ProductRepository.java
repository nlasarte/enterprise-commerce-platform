package com.portfolio.commerce.inventory.persistence;

import java.util.UUID;

import com.portfolio.commerce.inventory.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // Honor SKUs stored before uppercase normalization was introduced.
    boolean existsBySkuIgnoreCase(String sku);
}
