package com.portfolio.commerce.inventory.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    @Test
    void keepsProductMetadataAndUsesDatabaseTimestampPrecision() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var product = new Product("ITEM-1", "Test product", "Description");
        Instant after = Instant.now();

        assertThat(product.getSku()).isEqualTo("ITEM-1");
        assertThat(product.getName()).isEqualTo("Test product");
        assertThat(product.getDescription()).isEqualTo("Description");
        assertThat(product.getCreatedAt()).isBetween(before, after);
        assertThat(product.getCreatedAt().getNano() % 1_000).isZero();
    }
}
