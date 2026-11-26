package com.portfolio.commerce.inventory.application.idempotency;

import java.util.UUID;

public record IdempotencyResult(UUID reservationId, int status, String responseBody, boolean replayed) {
}
