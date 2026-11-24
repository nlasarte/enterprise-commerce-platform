package com.portfolio.commerce.inventory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String sku;
    private String name;
    private String description;
    private Instant createdAt;

    protected Product() {
    }

    Product(String sku, String name, String description) {
        this.sku = sku.strip();
        this.name = name.strip();
        this.description = description;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    UUID id() {
        return id;
    }

    ProductView view() {
        return new ProductView(id, sku, name, description, createdAt);
    }

    record ProductView(UUID id, String sku, String name, String description, Instant createdAt) {
    }
}
