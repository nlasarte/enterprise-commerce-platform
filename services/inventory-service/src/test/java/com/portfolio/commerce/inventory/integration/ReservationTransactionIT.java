package com.portfolio.commerce.inventory.integration;

import java.util.UUID;

import com.portfolio.commerce.inventory.TestcontainersConfiguration;
import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.application.InventoryService;
import com.portfolio.commerce.inventory.application.ProductService;
import com.portfolio.commerce.inventory.application.ReservationService;
import com.portfolio.commerce.inventory.application.idempotency.IdempotentReservationService;
import com.portfolio.commerce.inventory.exception.InsufficientStockException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import com.portfolio.commerce.inventory.persistence.StockReservationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import static com.portfolio.commerce.inventory.application.idempotency.IdempotentReservationService.OPERATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReservationTransactionIT {

    @Autowired
    private ProductService products;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private IdempotentReservationService idempotency;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate required;
    private TransactionTemplate requiresNew;

    @BeforeEach
    void configureTransactions() {
        required = transaction(TransactionDefinition.PROPAGATION_REQUIRED);
        requiresNew = transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void shouldRollbackAfterStockMutationAndFlush() {
        UUID product = stockedProduct();
        var original = stock(product);

        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            inventoryRepository.findByProduct_Id(product).orElseThrow().reserve(20);
            entityManager.flush();

            // No reservation has been written at this stage.
            assertThat(stock(product)).isEqualTo(new StockState(80, 20, original.version() + 1, 0, 0));
            throw new SimulatedFailure();
        })).isInstanceOf(SimulatedFailure.class);

        assertThat(stock(product)).isEqualTo(original);
    }

    @Test
    void shouldRollbackAfterReservationPersistence() {
        UUID product = stockedProduct();
        var request = request(product, 20);
        var original = stock(product);

        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            UUID id = reservations.reserve(request).id();
            entityManager.flush();

            assertStock(product, 80, 20, 1);
            assertThat(reservation(id).status()).isEqualTo("RESERVED");
            throw new SimulatedFailure();
        })).isInstanceOf(SimulatedFailure.class);

        assertThat(stock(product)).isEqualTo(original);
    }

    @Test
    void shouldRollbackAfterIdempotencyCompletion() {
        UUID product = stockedProduct();
        var request = request(product, 20);
        String key = newKey();
        var original = stock(product);

        rollbackCompletedOperation(key, request);

        assertKeyAbsent(key);
        assertThat(stock(product)).isEqualTo(original);
    }

    @Test
    void shouldCommitAllReservationEffectsAtomically() {
        UUID product = stockedProduct();
        var request = request(product, 20);
        String key = newKey();
        long version = stock(product).version();

        UUID id = required.execute(status -> {
            UUID reservation = idempotency.reserve(key, request).reservationId();
            entityManager.flush();

            assertStock(product, 80, 20, 1);
            assertCompleted(key, reservation);
            return reservation;
        });

        assertStock(product, 80, 20, 1);
        assertThat(stock(product).version()).isEqualTo(version + 1);
        assertThat(reservation(id).status()).isEqualTo("RESERVED");
        assertCompleted(key, id);
    }

    @Test
    void shouldAllowSameKeyAfterCompleteRollback() {
        UUID product = stockedProduct();
        var request = request(product, 20);
        String key = newKey();

        rollbackCompletedOperation(key, request);
        assertKeyAbsent(key);
        assertStock(product, 100, 0, 0);

        var retry = idempotency.reserve(key, request);
        var committed = stock(product);
        var replay = idempotency.reserve(key, request);

        assertThat(retry.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reservationId()).isEqualTo(retry.reservationId());
        assertThat(replay.responseBody()).isEqualTo(retry.responseBody());
        assertThat(stock(product)).isEqualTo(committed);
        assertStock(product, 80, 20, 1);
        assertCompleted(key, retry.reservationId());
    }

    @Test
    void shouldHideUncommittedChangesFromIndependentTransaction() {
        UUID product = stockedProduct();
        var request = request(product, 20);
        String key = newKey();
        var original = stock(product);

        UUID id = required.execute(status -> {
            UUID reservation = idempotency.reserve(key, request).reservationId();
            entityManager.flush();
            assertStock(product, 80, 20, 1);
            assertCompleted(key, reservation);

            requiresNew.executeWithoutResult(independent -> {
                assertThat(stock(product)).isEqualTo(original);
                assertKeyAbsent(key);
            });

            assertStock(product, 80, 20, 1);
            assertCompleted(key, reservation);
            return reservation;
        });

        assertStock(product, 80, 20, 1);
        assertCompleted(key, id);
    }

    @Test
    void shouldRollbackOuterTransactionWhenInnerRequiredFails() {
        UUID product = stockedProduct();
        String key = newKey();
        var original = stock(product);

        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            UUID id = idempotency.reserve(key, request(product, 20)).reservationId();
            entityManager.flush();
            assertStock(product, 80, 20, 1);
            assertCompleted(key, id);

            assertThatThrownBy(() -> reservations.reserve(request(product, 200)))
                    .isInstanceOf(InsufficientStockException.class);
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);

        assertKeyAbsent(key);
        assertThat(stock(product)).isEqualTo(original);
    }

    @Test
    void shouldRejectStaleInventoryVersion() {
        UUID product = stockedProduct();
        long version = stock(product).version();

        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            var stale = inventoryRepository.findByProduct_Id(product).orElseThrow();
            assertThat(stale.getVersion()).isEqualTo(version);

            requiresNew.executeWithoutResult(independent -> inventory.restock(product, 50));

            stale.restock(10);
            inventoryRepository.flush();
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertStock(product, 150, 0, 0);
        assertThat(stock(product).version()).isEqualTo(version + 1);
    }

    @Test
    void shouldRejectStaleReservationVersion() {
        UUID product = stockedProduct();
        UUID id = reservations.reserve(request(product, 20)).id();
        long version = reservation(id).version();

        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            var stale = reservationRepository.findById(id).orElseThrow();
            assertThat(stale.getVersion()).isEqualTo(version);

            requiresNew.executeWithoutResult(independent -> reservations.confirm(id));

            assertThat(stale.release()).isTrue();
            reservationRepository.flush();
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(reservation(id)).isEqualTo(new ReservationState("CONFIRMED", version + 1));
        assertStock(product, 80, 0, 1);
    }

    @Test
    void shouldRollbackConfirmationAndReleaseTogether() {
        UUID product = stockedProduct();
        UUID first = reservations.reserve(request(product, 20)).id();
        UUID second = reservations.reserve(request(product, 30)).id();
        assertStock(product, 50, 50, 2);
        var originalStock = stock(product);
        var originalFirst = reservation(first);
        var originalSecond = reservation(second);

        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            reservations.confirm(first);
            reservations.release(second);
            entityManager.flush();

            assertThat(reservation(first)).isEqualTo(new ReservationState("CONFIRMED", originalFirst.version() + 1));
            assertThat(reservation(second)).isEqualTo(new ReservationState("RELEASED", originalSecond.version() + 1));
            assertStock(product, 80, 0, 2);
            throw new SimulatedFailure();
        })).isInstanceOf(SimulatedFailure.class);

        assertThat(stock(product)).isEqualTo(originalStock);
        assertThat(reservation(first)).isEqualTo(originalFirst);
        assertThat(reservation(second)).isEqualTo(originalSecond);

        reservations.confirm(first);
        assertStock(product, 50, 30, 2);
        reservations.release(second);
        assertStock(product, 80, 0, 2);
        assertThat(reservation(first).status()).isEqualTo("CONFIRMED");
        assertThat(reservation(second).status()).isEqualTo("RELEASED");
    }

    private void rollbackCompletedOperation(String key, ReservationRequest request) {
        assertThatThrownBy(() -> required.executeWithoutResult(status -> {
            UUID id = idempotency.reserve(key, request).reservationId();
            entityManager.flush();

            assertStock(request.productId(), 80, 20, 1);
            assertCompleted(key, id);
            throw new SimulatedFailure();
        })).isInstanceOf(SimulatedFailure.class);
    }

    private TransactionTemplate transaction(int propagation) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(20);
        return template;
    }

    private UUID stockedProduct() {
        UUID product = products.createProduct(new ProductRequest("TX-" + UUID.randomUUID(),
                "Transaction test product", null)).id();
        inventory.restock(product, 100);
        return product;
    }

    private static ReservationRequest request(UUID product, int quantity) {
        return new ReservationRequest(UUID.randomUUID(), product, quantity);
    }

    private static String newKey() {
        return "tx-test-" + UUID.randomUUID();
    }

    private StockState stock(UUID product) {
        return jdbc.queryForObject("""
                SELECT i.available_quantity, i.reserved_quantity, i.version,
                       count(r.id) AS reservation_count,
                       coalesce(sum(r.quantity) FILTER (WHERE r.status = 'RESERVED'), 0) AS active_quantity
                FROM inventory i LEFT JOIN stock_reservations r ON r.product_id = i.product_id
                WHERE i.product_id = ?
                GROUP BY i.available_quantity, i.reserved_quantity, i.version
                """, (row, index) -> new StockState(row.getInt("available_quantity"),
                row.getInt("reserved_quantity"), row.getLong("version"), row.getLong("reservation_count"),
                row.getLong("active_quantity")), product);
    }

    private void assertStock(UUID product, int available, int reserved, long reservations) {
        var state = stock(product);
        assertThat(state).isNotNull();
        assertThat(state.available()).isEqualTo(available);
        assertThat(state.reserved()).isEqualTo(reserved);
        assertThat(state.reservations()).isEqualTo(reservations);
        assertThat(state.activeQuantity()).as("Reserved quantity must match active reservations").isEqualTo(reserved);
    }

    private ReservationState reservation(UUID id) {
        return jdbc.queryForObject("SELECT status, version FROM stock_reservations WHERE id = ?",
                (row, index) -> new ReservationState(row.getString("status"), row.getLong("version")), id);
    }

    private void assertKeyAbsent(String key) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM idempotency_records WHERE operation = ? AND idempotency_key = ?
                """, Long.class, OPERATION, key)).isZero();
    }

    private void assertCompleted(String key, UUID reservation) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM idempotency_records i
                JOIN stock_reservations r ON r.id = i.reservation_id
                WHERE i.operation = ? AND i.idempotency_key = ? AND r.id = ?
                    AND i.response_status = 201 AND i.response_body IS NOT NULL AND i.completed_at IS NOT NULL
                """, Long.class, OPERATION, key, reservation)).as("Completed idempotency record").isEqualTo(1L);
    }

    private record StockState(int available, int reserved, long version, long reservations, long activeQuantity) {
    }

    private record ReservationState(String status, long version) {
    }

    private static class SimulatedFailure extends RuntimeException {
    }
}
