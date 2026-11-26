package com.portfolio.commerce.inventory.application.idempotency;

import com.portfolio.commerce.inventory.exception.InvalidIdempotencyKeyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "order:123", "reservation.create.v1", "request_123",
            "550e8400-e29b-41d4-a716-446655440000"})
    void shouldAcceptValidKeys(String value) {
        assertThat(IdempotencyKey.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " invalid", "invalid ", "invalid/key", "invalid key", "invalid@key"})
    void shouldRejectInvalidKeys(String value) {
        assertThatThrownBy(() -> IdempotencyKey.of(value)).isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void shouldAcceptMaximumLength() {
        String value = "a".repeat(128);
        assertThat(IdempotencyKey.of(value).value()).isEqualTo(value);
    }

    @Test
    void shouldRejectExcessiveLength() {
        assertThatThrownBy(() -> IdempotencyKey.of("a".repeat(129)))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }
}
