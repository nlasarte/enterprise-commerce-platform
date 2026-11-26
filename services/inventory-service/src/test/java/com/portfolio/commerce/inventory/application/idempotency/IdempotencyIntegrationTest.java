package com.portfolio.commerce.inventory.application.idempotency;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.portfolio.commerce.inventory.TestcontainersConfiguration;
import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.application.InventoryService;
import com.portfolio.commerce.inventory.application.ProductService;
import com.portfolio.commerce.inventory.application.ReservationService;
import com.portfolio.commerce.inventory.domain.ReservationStatus;
import com.portfolio.commerce.inventory.exception.DuplicateReservationException;
import com.portfolio.commerce.inventory.exception.IdempotencyConflictException;
import com.portfolio.commerce.inventory.exception.InsufficientStockException;
import com.portfolio.commerce.inventory.persistence.IdempotencyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static com.portfolio.commerce.inventory.application.idempotency.IdempotentReservationService.OPERATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyIntegrationTest {

    @Autowired
    private ProductService products;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private IdempotentReservationService service;

    @Autowired
    private IdempotencyRepository records;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldCreateReservationAndPersistResponse() {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product);

        IdempotencyResult result = service.reserve(key, request);

        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(201);
        var record = records.find(OPERATION, key).orElseThrow();
        assertThat(record.isCompleted()).isTrue();
        assertThat(record.requestHash()).isEqualTo(RequestFingerprint.forReservation(request));
        assertThat(record.reservationId()).isEqualTo(result.reservationId());
        assertThat(record.responseStatus()).isEqualTo(result.status());
        assertThat(record.responseBody()).isEqualTo(result.responseBody());
        assertStockAndReservations(product, 80, 20, 1);
    }

    @Test
    void shouldReplayIdenticalRequest() {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product);

        IdempotencyResult original = service.reserve(key, request);
        IdempotencyResult replay = service.reserve(key, request);

        assertReplay(original, replay);
        assertStockAndReservations(product, 80, 20, 1);
    }

    @Test
    void shouldRejectSameKeyWithDifferentQuantity() {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest original = request(product);
        service.reserve(key, original);

        assertThatThrownBy(() -> service.reserve(key, withDifferentQuantity(original)))
                .isInstanceOf(IdempotencyConflictException.class);

        assertStockAndReservations(product, 80, 20, 1);
    }

    @Test
    void shouldPreserveBusinessUniquenessAcrossKeys() {
        UUID product = stockedProduct(100);
        ReservationRequest request = request(product);
        service.reserve(newKey(), request);
        String secondKey = newKey();

        assertThatThrownBy(() -> service.reserve(secondKey, request)).isInstanceOf(DuplicateReservationException.class);

        assertThat(records.find(OPERATION, secondKey)).isEmpty();
        assertStockAndReservations(product, 80, 20, 1);
    }

    @Test
    void shouldReplayOriginalResponseAfterConfirmation() {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product);
        IdempotencyResult original = service.reserve(key, request);
        reservations.confirm(original.reservationId());

        IdempotencyResult replay = service.reserve(key, request);

        assertReplay(original, replay);
        assertThat(replay.responseBody()).contains("\"RESERVED\"");
        assertThat(reservations.getReservation(original.reservationId()).status())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertStockAndReservations(product, 80, 0, 1);
    }

    @Test
    void shouldRollbackKeyWhenStockIsInsufficient() {
        UUID product = stockedProduct(10);
        String key = newKey();
        ReservationRequest request = request(product);

        assertThatThrownBy(() -> service.reserve(key, request)).isInstanceOf(InsufficientStockException.class);

        assertThat(records.find(OPERATION, key)).isEmpty();
        assertStockAndReservations(product, 10, 0, 0);
    }

    @Test
    void shouldAllowRetryAfterFailedTransaction() {
        UUID product = stockedProduct(10);
        String key = newKey();
        ReservationRequest request = request(product);
        assertThatThrownBy(() -> service.reserve(key, request)).isInstanceOf(InsufficientStockException.class);
        inventory.restock(product, 30);

        IdempotencyResult retry = service.reserve(key, request);

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.status()).isEqualTo(201);
        assertStockAndReservations(product, 20, 20, 1);
    }

    @Test
    void shouldHandleConcurrentIdenticalRequests() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product);

        compete(key, request, request, false,
                (first, second) -> assertReplay(first, second.get(15, TimeUnit.SECONDS)));

        assertStockAndReservations(product, 80, 20, 1);
    }

    @Test
    void shouldRejectConcurrentConflictingPayloads() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest original = request(product);

        compete(key, original, withDifferentQuantity(original), false, (first, second) -> {
            assertThat(first.replayed()).isFalse();
            assertThatThrownBy(() -> second.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IdempotencyConflictException.class);
        });

        assertStockAndReservations(product, 80, 20, 1);
    }

    @Test
    void shouldRollbackAllChangesAfterServiceExecution() {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.reserve(key, request);
            throw new IllegalStateException("Simulated failure before commit");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated failure before commit");

        assertThat(records.find(OPERATION, key)).isEmpty();
        assertStockAndReservations(product, 100, 0, 0);
        assertThat(service.reserve(key, request).replayed()).isFalse();
        assertStockAndReservations(product, 80, 20, 1);
    }

    @Test
    void shouldTransferKeyOwnershipAfterRollback() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product);

        compete(key, request, request, true, (first, second) -> {
            IdempotencyResult winner = second.get(15, TimeUnit.SECONDS);
            assertThat(winner.replayed()).isFalse();
            assertThat(winner.status()).isEqualTo(201);
            assertThat(winner.reservationId()).isNotEqualTo(first.reservationId());
            var record = records.find(OPERATION, key).orElseThrow();
            assertThat(record.isCompleted()).isTrue();
            assertThat(record.reservationId()).isEqualTo(winner.reservationId());
        });

        assertStockAndReservations(product, 80, 20, 1);
    }

    @Test
    void shouldTranslateTimeoutWhileWaitingForAClaimedKey() throws Exception {
        UUID product = stockedProduct(100);
        String key = newKey();
        ReservationRequest request = request(product);
        var claimed = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var worker = Executors.newSingleThreadExecutor()) {
            var owner = worker.submit(() -> transaction.executeWithoutResult(status -> {
                assertThat(records.tryClaim(OPERATION, key, RequestFingerprint.forReservation(request))).isTrue();
                status.setRollbackOnly();
                claimed.countDown();
                await(release);
            }));
            try {
                await(claimed);
                assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '100ms'");
                    service.reserve(key, request);
                })).isInstanceOf(CannotAcquireLockException.class);
            } finally {
                release.countDown();
            }
            owner.get(15, TimeUnit.SECONDS);
        }

        assertThat(records.find(OPERATION, key)).isEmpty();
        assertStockAndReservations(product, 100, 0, 0);
    }

    private void compete(String key, ReservationRequest original, ReservationRequest duplicate, boolean rollback,
                         CompetitionAssertions assertions) throws Exception {
        var ownerPid = new CompletableFuture<Integer>();
        var finish = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transaction.execute(status -> {
                IdempotencyResult result = service.reserve(key, original);
                if (rollback) {
                    status.setRollbackOnly();
                }
                ownerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                await(finish);
                return result;
            }));

            try {
                int pid = ownerPid.get(15, TimeUnit.SECONDS);
                var second = workers.submit(() -> service.reserve(key, duplicate));
                awaitBlockedKeyInsert(pid, second);
                finish.countDown();
                assertions.verify(first.get(15, TimeUnit.SECONDS), second);
            } finally {
                finish.countDown();
            }
        }
    }

    private void awaitBlockedKeyInsert(int ownerPid, Future<?> worker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            assertThat(worker.isDone()).as("Duplicate finished before its key owner").isFalse();
            Boolean waiting = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity
                        WHERE datname = current_database()
                          AND wait_event_type = 'Lock'
                          AND query ILIKE '%INSERT INTO idempotency_records%'
                          AND ? = ANY(pg_blocking_pids(pid))
                    )
                    """, Boolean.class, ownerPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Duplicate request did not wait for the original key claim");
    }

    private UUID stockedProduct(int stock) {
        UUID product = products.createProduct(new ProductRequest("IDEM-" + UUID.randomUUID(),
                "Idempotency Test Product", null)).id();
        inventory.restock(product, stock);
        return product;
    }

    private static ReservationRequest request(UUID product) {
        return new ReservationRequest(UUID.randomUUID(), product, 20);
    }

    private static ReservationRequest withDifferentQuantity(ReservationRequest request) {
        return new ReservationRequest(request.orderId(), request.productId(), 30);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private void assertStockAndReservations(UUID product, int available, int reserved, long count) {
        var stock = inventory.getInventory(product);
        assertThat(stock.availableQuantity()).isEqualTo(available);
        assertThat(stock.reservedQuantity()).isEqualTo(reserved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_reservations WHERE product_id = ?",
                Long.class, product)).isEqualTo(count);
    }

    private static void assertReplay(IdempotencyResult original, IdempotencyResult replay) {
        assertThat(original.replayed()).isFalse();
        assertThat(replay).isEqualTo(new IdempotencyResult(original.reservationId(), original.status(),
                original.responseBody(), true));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).as("Timed out waiting to finish transaction").isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test worker interrupted", exception);
        }
    }

    @FunctionalInterface
    private interface CompetitionAssertions {
        void verify(IdempotencyResult original, Future<IdempotencyResult> duplicate) throws Exception;
    }
}
