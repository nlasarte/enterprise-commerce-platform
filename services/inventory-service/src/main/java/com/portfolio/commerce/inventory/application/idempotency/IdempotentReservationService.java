package com.portfolio.commerce.inventory.application.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationResponse;
import com.portfolio.commerce.inventory.application.ReservationService;
import com.portfolio.commerce.inventory.exception.IdempotencyConflictException;
import com.portfolio.commerce.inventory.persistence.IdempotencyRecord;
import com.portfolio.commerce.inventory.persistence.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotentReservationService {

    public static final String OPERATION = "reservation:create";

    private final ReservationService reservations;
    private final IdempotencyRepository records;
    private final ObjectMapper objectMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IdempotencyResult reserve(String rawKey, ReservationRequest request) {
        String key = IdempotencyKey.of(rawKey).value();
        String requestHash = RequestFingerprint.forReservation(request);

        if (!records.tryClaim(OPERATION, key, requestHash)) {
            return replay(key, requestHash);
        }

        // ReservationService flushes the JPA insert before JDBC writes its foreign key.
        ReservationResponse reservation = reservations.reserve(request);
        String body = serialize(reservation);
        int status = HttpStatus.CREATED.value();
        records.complete(OPERATION, key, requestHash, reservation.id(), status, body);
        return new IdempotencyResult(reservation.id(), status, body, false);
    }

    private IdempotencyResult replay(String key, String requestHash) {
        IdempotencyRecord record = records.find(OPERATION, key)
                .orElseThrow(() -> new IllegalStateException("Claimed idempotency record was not found"));
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        if (!record.isCompleted()) {
            throw new IllegalStateException("Incomplete committed idempotency record");
        }
        return new IdempotencyResult(record.reservationId(), record.responseStatus(), record.responseBody(), true);
    }

    private String serialize(ReservationResponse reservation) {
        try {
            return objectMapper.writeValueAsString(reservation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize reservation response", exception);
        }
    }
}
