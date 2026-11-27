package com.portfolio.commerce.inventory.api;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.commerce.inventory.TestcontainersConfiguration;
import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.application.InventoryService;
import com.portfolio.commerce.inventory.application.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static com.portfolio.commerce.inventory.application.idempotency.IdempotentReservationService.OPERATION;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReservationHttpIT {

    private static final String RESERVATIONS = "/api/v1/reservations";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "Idempotency-Replayed";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProductService products;

    @Autowired
    private InventoryService inventory;

    @BeforeEach
    void configureHttpTimeouts() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        http.getRestTemplate().setRequestFactory(factory);
    }

    @Test
    void shouldCreateReservationWithoutIdempotencyKey() throws Exception {
        UUID product = stockedProduct(100);
        ReservationRequest request = request(product, 20);

        var response = reserve(null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertContentType(response, MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst(REPLAY_HEADER)).isNull();
        UUID id = reservationId(response);
        assertThat(response.getHeaders().getLocation()).isNotNull().satisfies(location -> {
            assertThat(location.isAbsolute()).isTrue();
            assertThat(location.getPath()).isEqualTo(RESERVATIONS + "/" + id);
        });
        assertThat(body(response).required("status").asText()).isEqualTo("RESERVED");
        assertReservation(id, request, "RESERVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE reservation_id = ?",
                Long.class, id)).isZero();
        assertStock(product, 80, 20, 1);
    }

    @Test
    void shouldCreateReservationWithNewIdempotencyKey() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product, 20);

        var response = reserve(key, request);

        assertCreated(response, false);
        UUID id = reservationId(response);
        assertThat(body(response).required("status").asText()).isEqualTo("RESERVED");
        assertReservation(id, request, "RESERVED");
        assertStoredResponse(key, id, response);
        assertStock(product, 80, 20, 1);
    }

    @Test
    void shouldReplayIdenticalRequestWithoutMutatingStock() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product, 20);
        var original = reserve(key, request);
        assertCreated(original, false);
        long version = inventoryVersion(product);

        assertReplay(original, reserve(key, request));

        assertThat(inventoryVersion(product)).isEqualTo(version);
        assertStoredResponse(key, reservationId(original), original);
        assertStock(product, 80, 20, 1);
    }

    @Test
    void shouldRejectSameKeyWithDifferentQuantity() throws Exception {
        UUID product = stockedProduct(100);
        ReservationRequest original = request(product, 20);

        assertFingerprintConflict(original, new ReservationRequest(original.orderId(), product, 30));
    }

    @Test
    void shouldRejectSameKeyWithDifferentOrder() throws Exception {
        UUID product = stockedProduct(100);

        assertFingerprintConflict(request(product, 20), request(product, 20));
    }

    @Test
    void shouldRejectSameKeyWithDifferentProduct() throws Exception {
        UUID first = stockedProduct(100);
        UUID second = stockedProduct(100);
        ReservationRequest original = request(first, 20);

        assertFingerprintConflict(original, new ReservationRequest(original.orderId(), second, 20));

        assertStock(second, 100, 0, 0);
    }

    @Test
    void shouldRejectDuplicateReservationWithDifferentKey() throws Exception {
        UUID product = stockedProduct(100);
        String originalKey = newKey();
        String duplicateKey = newKey();
        ReservationRequest request = request(product, 20);
        var original = reserve(originalKey, request);
        assertCreated(original, false);

        var duplicate = reserve(duplicateKey, request);

        assertThat(problem(duplicate, HttpStatus.CONFLICT).required("title").asText())
                .isEqualTo("Duplicate reservation");
        assertKeyAbsent(duplicateKey);
        assertStoredResponse(originalKey, reservationId(original), original);
        assertStock(product, 80, 20, 1);
    }

    @Test
    void shouldRejectInvalidIdempotencyKey() throws Exception {
        UUID product = stockedProduct(100);
        String key = "invalid key " + UUID.randomUUID();

        var response = reserve(key, request(product, 20));

        assertProblemCode(response, HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        assertKeyAbsent(key);
        assertStock(product, 100, 0, 0);
    }

    @Test
    void shouldRejectInvalidBodyBeforeClaimingKey() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();

        var response = reserve(key, request(product, 0));

        assertThat(problem(response, HttpStatus.BAD_REQUEST).required("errors").required("quantity").isTextual())
                .isTrue();
        assertKeyAbsent(key);
        assertStock(product, 100, 0, 0);
    }

    @Test
    void shouldRollbackIdempotencyClaimOnInsufficientStock() throws Exception {
        UUID product = stockedProduct(10);
        String key = newKey();
        long version = inventoryVersion(product);

        assertInsufficientStock(reserve(key, request(product, 20)));

        assertKeyAbsent(key);
        assertThat(inventoryVersion(product)).isEqualTo(version);
        assertStock(product, 10, 0, 0);
    }

    @Test
    void shouldAllowRetryAfterFailedTransaction() throws Exception {
        UUID product = stockedProduct(10);
        String key = newKey();
        ReservationRequest request = request(product, 20);
        assertInsufficientStock(reserve(key, request));
        assertKeyAbsent(key);
        assertStock(product, 10, 0, 0);
        inventory.restock(product, 40);

        var retry = reserve(key, request);

        assertCreated(retry, false);
        assertReplay(retry, reserve(key, request));
        assertStoredResponse(key, reservationId(retry), retry);
        assertStock(product, 30, 20, 1);
    }

    @Test
    void shouldReplayOriginalResponseAfterConfirmation() throws Exception {
        assertReplayAfterTransition("confirm", "CONFIRMED", 80);
    }

    @Test
    void shouldReplayOriginalResponseAfterRelease() throws Exception {
        assertReplayAfterTransition("release", "RELEASED", 100);
    }

    @Test
    void shouldProcessConcurrentIdenticalRequestsOnlyOnce() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product, 20);

        var responses = reserveConcurrently(key, Collections.nCopies(8, request));

        var originals = responses.stream().filter(response -> "false".equals(replayHeader(response))).toList();
        var replays = responses.stream().filter(response -> "true".equals(replayHeader(response))).toList();
        assertThat(originals).hasSize(1);
        assertThat(replays).hasSize(7);
        var original = originals.getFirst();
        assertCreated(original, false);
        for (var replay : replays) {
            assertReplay(original, replay);
        }
        UUID id = reservationId(original);
        assertReservation(id, request, "RESERVED");
        assertStoredResponse(key, id, original);
        assertStock(product, 80, 20, 1);
    }

    @Test
    void shouldRejectOneOfTwoConcurrentConflictingRequests() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest first = request(product, 20);
        ReservationRequest second = new ReservationRequest(first.orderId(), product, 30);
        var requests = List.of(first, second);

        var responses = reserveConcurrently(key, requests);

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        int winnerIndex = responses.getFirst().getStatusCode().equals(HttpStatus.CREATED) ? 0 : 1;
        var winner = responses.get(winnerIndex);
        var winningRequest = requests.get(winnerIndex);
        assertCreated(winner, false);
        assertProblemCode(responses.get(1 - winnerIndex), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(body(winner).required("quantity").asInt()).isEqualTo(winningRequest.quantity());
        assertReplay(winner, reserve(key, winningRequest));
        UUID id = reservationId(winner);
        assertReservation(id, winningRequest, "RESERVED");
        assertStoredResponse(key, id, winner);
        assertStock(product, 100 - winningRequest.quantity(), winningRequest.quantity(), 1);
    }

    private void assertFingerprintConflict(ReservationRequest original, ReservationRequest changed) throws Exception {
        String key = newKey();
        var created = reserve(key, original);
        assertCreated(created, false);

        assertProblemCode(reserve(key, changed), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");

        assertStoredResponse(key, reservationId(created), created);
        assertStock(original.productId(), 80, 20, 1);
    }

    private void assertReplayAfterTransition(String action, String expectedStatus, int available) throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product, 20);
        var original = reserve(key, request);
        assertCreated(original, false);
        UUID id = reservationId(original);
        String path = RESERVATIONS + "/" + id;

        var transition = http.postForEntity(path + "/" + action, null, String.class);
        assertThat(transition.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(transition).required("status").asText()).isEqualTo(expectedStatus);

        var replay = reserve(key, request);
        assertReplay(original, replay);
        assertThat(body(replay).required("status").asText()).isEqualTo("RESERVED");
        var current = http.getForEntity(path, String.class);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(current).required("status").asText()).isEqualTo(expectedStatus);
        assertReservation(id, request, expectedStatus);
        assertStoredResponse(key, id, original);
        assertStock(product, available, 0, 1);
    }

    private ResponseEntity<String> reserve(String key, ReservationRequest request) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        if (key != null) {
            headers.set(KEY_HEADER, key);
        }
        return http.postForEntity(RESERVATIONS, new HttpEntity<>(request, headers), String.class);
    }

    private List<ResponseEntity<String>> reserveConcurrently(String key, List<ReservationRequest> requests)
            throws Exception {
        var ready = new CountDownLatch(requests.size());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (ReservationRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("HTTP start signal timed out");
                    }
                    return reserve(key, request);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("All HTTP clients must be ready").isTrue();
            start.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (var future : futures) {
                responses.add(future.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(25, TimeUnit.SECONDS)).as("HTTP workers terminated").isTrue();
        }
    }

    private UUID stockedProduct(int quantity) {
        UUID product = products.createProduct(new ProductRequest("HTTP-" + UUID.randomUUID(),
                "HTTP integration test product", null)).id();
        inventory.restock(product, quantity);
        return product;
    }

    private static ReservationRequest request(UUID product, int quantity) {
        return new ReservationRequest(UUID.randomUUID(), product, quantity);
    }

    private static String newKey() {
        return "http-test-" + UUID.randomUUID();
    }

    private JsonNode body(ResponseEntity<String> response) throws JsonProcessingException {
        assertThat(response.getBody()).isNotBlank();
        return json.readTree(response.getBody());
    }

    private UUID reservationId(ResponseEntity<String> response) throws JsonProcessingException {
        return UUID.fromString(body(response).required("id").asText());
    }

    private static String replayHeader(ResponseEntity<String> response) {
        return response.getHeaders().getFirst(REPLAY_HEADER);
    }

    private void assertCreated(ResponseEntity<String> response, boolean replayed) throws JsonProcessingException {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertContentType(response, MediaType.APPLICATION_JSON);
        assertThat(replayHeader(response)).isEqualTo(Boolean.toString(replayed));
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(RESERVATIONS + "/" + reservationId(response)));
    }

    private void assertReplay(ResponseEntity<String> original, ResponseEntity<String> replay)
            throws JsonProcessingException {
        assertCreated(replay, true);
        assertThat(replay.getHeaders().getLocation()).isEqualTo(original.getHeaders().getLocation());
        assertThat(replay.getBody()).isEqualTo(original.getBody());
    }

    private JsonNode problem(ResponseEntity<String> response, HttpStatus status) throws JsonProcessingException {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertContentType(response, MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(replayHeader(response)).isNull();
        JsonNode problem = body(response);
        assertThat(problem.required("status").asInt()).isEqualTo(status.value());
        return problem;
    }

    private void assertProblemCode(ResponseEntity<String> response, HttpStatus status, String code)
            throws JsonProcessingException {
        assertThat(problem(response, status).required("errorCode").asText()).isEqualTo(code);
    }

    private void assertInsufficientStock(ResponseEntity<String> response) throws JsonProcessingException {
        assertThat(problem(response, HttpStatus.CONFLICT).required("title").asText()).isEqualTo("Insufficient stock");
    }

    private static void assertContentType(ResponseEntity<?> response, MediaType expected) {
        assertThat(response.getHeaders().getContentType()).isNotNull().satisfies(
                type -> assertThat(type.isCompatibleWith(expected)).isTrue());
    }

    private void assertStock(UUID product, int available, int reserved, long reservations) {
        var snapshot = jdbc.queryForObject("""
                SELECT i.available_quantity, i.reserved_quantity, count(r.id) AS reservation_count,
                       coalesce(sum(r.quantity) FILTER (WHERE r.status = 'RESERVED'), 0) AS active_quantity
                FROM inventory i
                LEFT JOIN stock_reservations r ON r.product_id = i.product_id
                WHERE i.product_id = ?
                GROUP BY i.available_quantity, i.reserved_quantity
                """, (row, index) -> new StockSnapshot(row.getInt("available_quantity"),
                row.getInt("reserved_quantity"), row.getLong("reservation_count"), row.getLong("active_quantity")),
                product);
        assertThat(snapshot).as("Committed stock, reservation count, and active stock for %s", product)
                .isEqualTo(new StockSnapshot(available, reserved, reservations, reserved));
    }

    private long inventoryVersion(UUID product) {
        return jdbc.queryForObject("SELECT version FROM inventory WHERE product_id = ?", Long.class, product);
    }

    private void assertReservation(UUID id, ReservationRequest request, String status) {
        var reservation = jdbc.queryForObject("""
                SELECT order_id, product_id, quantity, status
                FROM stock_reservations WHERE id = ?
                """, (row, index) -> new StoredReservation(row.getObject("order_id", UUID.class),
                row.getObject("product_id", UUID.class), row.getInt("quantity"), row.getString("status")), id);
        assertThat(reservation).isEqualTo(new StoredReservation(request.orderId(), request.productId(),
                request.quantity(), status));
    }

    private void assertKeyAbsent(String key) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM idempotency_records WHERE operation = ? AND idempotency_key = ?
                """, Long.class, OPERATION, key)).as("No committed claim for %s", key).isZero();
    }

    private void assertStoredResponse(String key, UUID id, ResponseEntity<String> original) {
        var stored = jdbc.queryForObject("""
                SELECT reservation_id, response_status, response_body, completed_at IS NOT NULL AS completed
                FROM idempotency_records WHERE operation = ? AND idempotency_key = ?
                """, (row, index) -> new StoredResponse(row.getObject("reservation_id", UUID.class),
                row.getInt("response_status"), row.getString("response_body"), row.getBoolean("completed")),
                OPERATION, key);
        assertThat(stored).isEqualTo(new StoredResponse(id, original.getStatusCode().value(), original.getBody(), true));
    }

    private record StockSnapshot(int available, int reserved, long reservations, long activeQuantity) {
    }

    private record StoredReservation(UUID order, UUID product, int quantity, String status) {
    }

    private record StoredResponse(UUID reservation, int status, String body, boolean completed) {
    }
}
