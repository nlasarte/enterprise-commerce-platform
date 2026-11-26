package com.portfolio.commerce.inventory.persistence;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(String operation, String idempotencyKey, String requestHash, UUID reservationId,
                                Integer responseStatus, String responseBody, Instant createdAt, Instant completedAt) {

    public boolean isCompleted() {
        return reservationId != null && responseStatus != null && responseBody != null && completedAt != null;
    }
}
