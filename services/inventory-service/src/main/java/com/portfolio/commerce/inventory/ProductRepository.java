package com.portfolio.commerce.inventory;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProductRepository extends JpaRepository<Product, UUID> {
}
