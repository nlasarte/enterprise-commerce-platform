package com.portfolio.commerce.inventory.api;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationResponse;
import com.portfolio.commerce.inventory.application.ReservationService;
import com.portfolio.commerce.inventory.application.idempotency.IdempotencyResult;
import com.portfolio.commerce.inventory.application.idempotency.IdempotentReservationService;
import com.portfolio.commerce.inventory.domain.ReservationStatus;
import com.portfolio.commerce.inventory.exception.IdempotencyConflictException;
import com.portfolio.commerce.inventory.exception.InvalidIdempotencyKeyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    private static final String RESERVATIONS = "/api/v1/reservations";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "Idempotency-Replayed";
    private static final String KEY = "order:reservation";
    private static final UUID ID = UUID.randomUUID();
    private static final ReservationRequest REQUEST = new ReservationRequest(UUID.randomUUID(), UUID.randomUUID(), 20);
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");
    private static final String REQUEST_JSON = """
            {"orderId":"%s","productId":"%s","quantity":20}
            """.formatted(REQUEST.orderId(), REQUEST.productId());
    private static final String RESPONSE_JSON = """
            {"id":"%s","orderId":"%s","productId":"%s","quantity":20,"status":"RESERVED",
            "createdAt":"%s","updatedAt":"%s"}
            """.formatted(ID, REQUEST.orderId(), REQUEST.productId(), CREATED_AT, CREATED_AT);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReservationService reservations;

    @MockitoBean
    private IdempotentReservationService idempotency;

    @Test
    void createsWithoutAKeyAndReturnsTheLegacyLocation() throws Exception {
        when(reservations.reserve(REQUEST)).thenReturn(new ReservationResponse(ID, REQUEST.orderId(),
                REQUEST.productId(), 20, ReservationStatus.RESERVED, CREATED_AT, CREATED_AT));

        mvc.perform(post(RESERVATIONS).contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost" + RESERVATIONS + "/" + ID))
                .andExpect(header().doesNotExist(REPLAY_HEADER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(RESPONSE_JSON, JsonCompareMode.STRICT));

        verifyNoInteractions(idempotency);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnsStoredResponseAndKeyedHeaders(boolean replayed) throws Exception {
        when(idempotency.reserve(KEY, REQUEST)).thenReturn(new IdempotencyResult(ID, 201, RESPONSE_JSON, replayed));

        mvc.perform(post(RESERVATIONS).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, RESERVATIONS + "/" + ID))
                .andExpect(header().string(REPLAY_HEADER, Boolean.toString(replayed)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(RESPONSE_JSON));

        verifyNoInteractions(reservations);
    }

    @Test
    void rejectsInvalidReservationBeforeInvokingService() throws Exception {
        mvc.perform(post(RESERVATIONS).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.orderId").isString())
                .andExpect(jsonPath("$.errors.productId").isString())
                .andExpect(jsonPath("$.errors.quantity").isString());

        verifyNoInteractions(reservations, idempotency);
    }

    @ParameterizedTest
    @MethodSource("idempotencyProblems")
    void exposesMachineReadableKeyedFailures(RuntimeException failure, int statusCode, String errorCode)
            throws Exception {
        when(idempotency.reserve(KEY, REQUEST)).thenThrow(failure);

        mvc.perform(post(RESERVATIONS).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.errorCode").value(errorCode))
                .andExpect(header().doesNotExist(REPLAY_HEADER));
    }

    static Stream<Arguments> idempotencyProblems() {
        return Stream.of(
                Arguments.of(new InvalidIdempotencyKeyException("Invalid key format"), 400, "INVALID_IDEMPOTENCY_KEY"),
                Arguments.of(new IdempotencyConflictException(), 409, "IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void returnsARetryHintForDatabaseContention() throws Exception {
        when(idempotency.reserve(KEY, REQUEST)).thenThrow(new CannotAcquireLockException("Held key"));

        mvc.perform(post(RESERVATIONS).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.errorCode").value("TRANSIENT_DATABASE_CONFLICT"));
    }

    @Test
    void exposesTheMachineReadableConcurrencyConflict() throws Exception {
        when(reservations.confirm(ID)).thenThrow(new OptimisticLockingFailureException("Stale reservation"));

        mvc.perform(post(RESERVATIONS + "/{id}/confirm", ID))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent modification"))
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));
    }
}
