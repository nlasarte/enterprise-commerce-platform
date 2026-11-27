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
import com.portfolio.commerce.inventory.exception.DuplicateReservationException;
import com.portfolio.commerce.inventory.exception.IdempotencyConflictException;
import com.portfolio.commerce.inventory.exception.InsufficientStockException;
import com.portfolio.commerce.inventory.exception.InvalidIdempotencyKeyException;
import com.portfolio.commerce.inventory.exception.InvalidReservationStateException;
import com.portfolio.commerce.inventory.exception.ReservationNotFoundException;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void exposesMachineReadableKeyedFailures(String key, RuntimeException failure, int statusCode, String errorCode)
            throws Exception {
        when(idempotency.reserve(key, REQUEST)).thenThrow(failure);

        mvc.perform(post(RESERVATIONS).header(KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.errorCode").value(errorCode))
                .andExpect(header().doesNotExist(REPLAY_HEADER));

        verifyNoInteractions(reservations);
    }

    static Stream<Arguments> idempotencyProblems() {
        return Stream.of(
                Arguments.of("invalid key", new InvalidIdempotencyKeyException("Invalid key format"),
                        400, "INVALID_IDEMPOTENCY_KEY"),
                Arguments.of(KEY, new IdempotencyConflictException(), 409, "IDEMPOTENCY_KEY_CONFLICT"));
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
    void preservesLocationAndBodyAcrossCreationAndReplay() throws Exception {
        when(idempotency.reserve(KEY, REQUEST)).thenReturn(
                new IdempotencyResult(ID, 201, RESPONSE_JSON, false),
                new IdempotencyResult(ID, 201, RESPONSE_JSON, true));

        mvc.perform(post(RESERVATIONS).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, RESERVATIONS + "/" + ID))
                .andExpect(header().string(REPLAY_HEADER, "false"))
                .andExpect(content().string(RESPONSE_JSON));
        mvc.perform(post(RESERVATIONS).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, RESERVATIONS + "/" + ID))
                .andExpect(header().string(REPLAY_HEADER, "true"))
                .andExpect(content().string(RESPONSE_JSON));

        verify(idempotency, times(2)).reserve(KEY, REQUEST);
        verifyNoInteractions(reservations);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidBodyWithoutAKey(String body, String field) throws Exception {
        createWithoutKey(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors." + field).isString());

        verifyNoInteractions(reservations, idempotency);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of(REQUEST_JSON.replace(":20", ":-10"), "quantity"),
                Arguments.of(REQUEST_JSON.replace(":20", ":0"), "quantity"),
                Arguments.of("{\"productId\":\"%s\",\"quantity\":20}".formatted(REQUEST.productId()), "orderId"),
                Arguments.of("{\"orderId\":\"%s\",\"quantity\":20}".formatted(REQUEST.orderId()), "productId"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        createWithoutKey("{invalid-json").andExpect(status().isBadRequest());
        verifyNoInteractions(reservations, idempotency);
    }

    @ParameterizedTest
    @MethodSource("reservationConflicts")
    void reportsBusinessConflicts(RuntimeException failure, String title) throws Exception {
        when(reservations.reserve(REQUEST)).thenThrow(failure);

        assertConflict(createWithoutKey(REQUEST_JSON), title);
        verifyNoInteractions(idempotency);
    }

    static Stream<Arguments> reservationConflicts() {
        return Stream.of(
                Arguments.of(new InsufficientStockException(20, 10), "Insufficient stock"),
                Arguments.of(new DuplicateReservationException(REQUEST.orderId(), REQUEST.productId()),
                        "Duplicate reservation"));
    }

    @Test
    void retrievesReservation() throws Exception {
        when(reservations.getReservation(ID)).thenReturn(response(ReservationStatus.RESERVED));

        mvc.perform(get(RESERVATIONS + "/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(content().json(RESPONSE_JSON, JsonCompareMode.STRICT));
    }

    @Test
    void reportsMissingReservation() throws Exception {
        when(reservations.getReservation(ID)).thenThrow(new ReservationNotFoundException(ID));

        mvc.perform(get(RESERVATIONS + "/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rejectsInvalidReservationId() throws Exception {
        mvc.perform(get(RESERVATIONS + "/invalid-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(reservations, idempotency);
    }

    @Test
    void confirmsReservation() throws Exception {
        when(reservations.confirm(ID)).thenReturn(response(ReservationStatus.CONFIRMED));

        mvc.perform(post(RESERVATIONS + "/{id}/confirm", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void releasesReservation() throws Exception {
        when(reservations.release(ID)).thenReturn(response(ReservationStatus.RELEASED));

        mvc.perform(post(RESERVATIONS + "/{id}/release", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void rejectsConfirmationAfterRelease() throws Exception {
        when(reservations.confirm(ID)).thenThrow(
                new InvalidReservationStateException(ReservationStatus.RELEASED, ReservationStatus.CONFIRMED));

        assertConflict(mvc.perform(post(RESERVATIONS + "/{id}/confirm", ID)), "Invalid reservation state");
    }

    @Test
    void rejectsReleaseAfterConfirmation() throws Exception {
        when(reservations.release(ID)).thenThrow(
                new InvalidReservationStateException(ReservationStatus.CONFIRMED, ReservationStatus.RELEASED));

        assertConflict(mvc.perform(post(RESERVATIONS + "/{id}/release", ID)), "Invalid reservation state");
    }

    @Test
    void exposesRetryableConcurrencyConflictForKeyedCreation() throws Exception {
        when(idempotency.reserve(KEY, REQUEST)).thenThrow(new OptimisticLockingFailureException("Stale inventory"));

        mvc.perform(post(RESERVATIONS).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));

        verifyNoInteractions(reservations);
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

    private static ReservationResponse response(ReservationStatus status) {
        return new ReservationResponse(ID, REQUEST.orderId(), REQUEST.productId(), REQUEST.quantity(),
                status, CREATED_AT, CREATED_AT);
    }

    private ResultActions createWithoutKey(String body) throws Exception {
        return mvc.perform(post(RESERVATIONS).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void assertConflict(ResultActions result, String title) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value(title));
    }
}
