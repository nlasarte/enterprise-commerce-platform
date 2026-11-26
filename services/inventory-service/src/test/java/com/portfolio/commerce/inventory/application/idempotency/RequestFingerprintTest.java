package com.portfolio.commerce.inventory.application.idempotency;

import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @Test
    void shouldGenerateDeterministicFingerprint() {
        assertThat(fingerprint(orderId, productId, 20)).isEqualTo(fingerprint(orderId, productId, 20));
    }

    @Test
    void shouldDetectDifferentQuantity() {
        assertThat(fingerprint(orderId, productId, 20)).isNotEqualTo(fingerprint(orderId, productId, 30));
    }

    @Test
    void shouldDetectDifferentOrder() {
        assertThat(fingerprint(orderId, productId, 20)).isNotEqualTo(fingerprint(UUID.randomUUID(), productId, 20));
    }

    @Test
    void shouldDetectDifferentProduct() {
        assertThat(fingerprint(orderId, productId, 20)).isNotEqualTo(fingerprint(orderId, UUID.randomUUID(), 20));
    }

    @Test
    void shouldGenerateSha256HexadecimalHash() {
        assertThat(fingerprint(orderId, productId, 20)).matches("[0-9a-f]{64}");
    }

    private String fingerprint(UUID order, UUID product, int quantity) {
        return RequestFingerprint.forReservation(new ReservationRequest(order, product, quantity));
    }
}
