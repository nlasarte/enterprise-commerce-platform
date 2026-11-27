package com.portfolio.commerce.inventory.application.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationResponse;
import com.portfolio.commerce.inventory.application.ReservationService;
import com.portfolio.commerce.inventory.domain.ReservationStatus;
import com.portfolio.commerce.inventory.exception.IdempotencyConflictException;
import com.portfolio.commerce.inventory.exception.InsufficientStockException;
import com.portfolio.commerce.inventory.exception.InvalidIdempotencyKeyException;
import com.portfolio.commerce.inventory.persistence.IdempotencyRecord;
import com.portfolio.commerce.inventory.persistence.IdempotencyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.portfolio.commerce.inventory.application.idempotency.IdempotentReservationService.OPERATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentReservationServiceTest {

    private static final String KEY = "order:reservation";
    private static final String BODY = "{\"status\":\"RESERVED\"}";

    private final ReservationRequest request = new ReservationRequest(UUID.randomUUID(), UUID.randomUUID(), 20);
    private final String hash = RequestFingerprint.forReservation(request);
    private final Instant createdAt = Instant.parse("2026-01-01T12:00:00Z");
    private final ReservationResponse response = new ReservationResponse(UUID.randomUUID(), request.orderId(),
            request.productId(), request.quantity(), ReservationStatus.RESERVED, createdAt, createdAt);

    @Mock
    private ReservationService reservations;

    @Mock
    private IdempotencyRepository records;

    @Mock
    private ObjectMapper json;

    @InjectMocks
    private IdempotentReservationService service;

    @Test
    void validatesBeforeClaimingAKey() {
        assertThatThrownBy(() -> service.reserve("bad/key", request)).isInstanceOf(InvalidIdempotencyKeyException.class);

        verifyNoInteractions(records, reservations, json);
    }

    @Test
    void claimsBeforeCreatingAndOnlyCompletesAfterSerialization() throws Exception {
        when(records.tryClaim(OPERATION, KEY, hash)).thenReturn(true);
        when(reservations.reserve(request)).thenReturn(response);
        when(json.writeValueAsString(response)).thenReturn(BODY);

        var result = service.reserve(KEY, request);

        assertThat(result).isEqualTo(new IdempotencyResult(response.id(), 201, BODY, false));
        var calls = inOrder(records, reservations, json);
        calls.verify(records).tryClaim(OPERATION, KEY, hash);
        calls.verify(reservations).reserve(request);
        calls.verify(json).writeValueAsString(response);
        calls.verify(records).complete(OPERATION, KEY, hash, response.id(), 201, BODY);
    }

    @Test
    void replaysTheStoredResponseWithoutCreatingAnotherReservation() {
        when(records.find(OPERATION, KEY)).thenReturn(Optional.of(completed(hash)));

        var result = service.reserve(KEY, request);

        assertThat(result).isEqualTo(new IdempotencyResult(response.id(), 201, BODY, true));
        verifyNoInteractions(reservations, json);
    }

    @Test
    void rejectsAClaimWithADifferentFingerprint() {
        when(records.find(OPERATION, KEY)).thenReturn(Optional.of(completed("0".repeat(64))));

        assertThatThrownBy(() -> service.reserve(KEY, request)).isInstanceOf(IdempotencyConflictException.class);

        verifyNoInteractions(reservations, json);
    }

    @Test
    void rejectsAnIncompleteCommittedRecord() {
        when(records.find(OPERATION, KEY)).thenReturn(Optional.of(new IdempotencyRecord(
                OPERATION, KEY, hash, null, null, null, createdAt, null)));

        assertThatThrownBy(() -> service.reserve(KEY, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Incomplete committed idempotency record");

        verifyNoInteractions(reservations, json);
    }

    @Test
    void failsWhenTheExistingClaimCannotBeRead() {
        when(records.find(OPERATION, KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(KEY, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Claimed idempotency record was not found");

        verifyNoInteractions(reservations, json);
    }

    @Test
    void propagatesBusinessFailureWithoutCompletingTheClaim() {
        var failure = new InsufficientStockException(request.quantity(), 0);
        when(records.tryClaim(OPERATION, KEY, hash)).thenReturn(true);
        when(reservations.reserve(request)).thenThrow(failure);

        assertThatThrownBy(() -> service.reserve(KEY, request)).isSameAs(failure);

        verify(records).tryClaim(OPERATION, KEY, hash);
        verifyNoMoreInteractions(records);
        verifyNoInteractions(json);
    }

    @Test
    void reportsSerializationFailureWithoutCompletingTheClaim() throws Exception {
        when(records.tryClaim(OPERATION, KEY, hash)).thenReturn(true);
        when(reservations.reserve(request)).thenReturn(response);
        var failure = new JsonProcessingException("Serialization failed") {};
        when(json.writeValueAsString(response)).thenThrow(failure);

        assertThatThrownBy(() -> service.reserve(KEY, request))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(failure);

        var calls = inOrder(records, reservations, json);
        calls.verify(records).tryClaim(OPERATION, KEY, hash);
        calls.verify(reservations).reserve(request);
        calls.verify(json).writeValueAsString(response);
        verifyNoMoreInteractions(records);
    }

    private IdempotencyRecord completed(String requestHash) {
        return new IdempotencyRecord(OPERATION, KEY, requestHash, response.id(), 201, BODY, createdAt, createdAt);
    }
}
