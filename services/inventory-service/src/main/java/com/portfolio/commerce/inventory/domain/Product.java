package com.portfolio.commerce.inventory.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "products")
@Getter
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String sku;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Product() {
    }

    public Product(String sku, String name, String description) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
