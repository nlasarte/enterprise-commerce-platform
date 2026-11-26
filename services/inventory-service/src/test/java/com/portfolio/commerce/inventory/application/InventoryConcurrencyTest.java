package com.portfolio.commerce.inventory.application;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.portfolio.commerce.inventory.TestcontainersConfiguration;
import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.domain.ReservationStatus;
import com.portfolio.commerce.inventory.domain.StockReservation;
import com.portfolio.commerce.inventory.exception.DuplicateReservationException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import com.portfolio.commerce.inventory.persistence.StockReservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InventoryConcurrencyTest {

    private enum Outcome {
        SUCCESS, OPTIMISTIC_CONFLICT, DUPLICATE
    }

    @Autowired
    private ProductService products;

    @Autowired
    private InventoryService stock;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void shouldPreventOverselling() throws Exception {
        UUID product = stockedProduct(10);
        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();

        var outcomes = race(() -> loadInventory(product),
                () -> reserve(orderA, product, 8), () -> reserve(orderB, product, 8));

        assertOptimisticRace(outcomes);
        assertStock(product, 2, 8);
        assertWinningReservation(product, orderA, orderB, outcomes);
    }

    @Test
    void shouldDetectConflictEvenWhenStockIsSufficient() throws Exception {
        UUID product = stockedProduct(10);
        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();

        var outcomes = race(() -> loadInventory(product),
                () -> reserve(orderA, product, 3), () -> reserve(orderB, product, 4));

        assertOptimisticRace(outcomes);
        int allocated = outcomes.getFirst() == Outcome.SUCCESS ? 3 : 4;
        assertStock(product, 10 - allocated, allocated);
        assertWinningReservation(product, orderA, orderB, outcomes);
    }

    @Test
    void shouldPreventConcurrentDoubleConfirmation() throws Exception {
        UUID product = stockedProduct(100);
        UUID id = reserve(UUID.randomUUID(), product, 20);

        assertOptimisticRace(race(() -> loadBoth(id, product),
                () -> reservations.confirm(id), () -> reservations.confirm(id)));
        assertStock(product, 80, 0);
        assertTerminalVersion(id, ReservationStatus.CONFIRMED);

        reservations.confirm(id);
        assertStock(product, 80, 0);
        assertTerminalVersion(id, ReservationStatus.CONFIRMED);
    }

    @Test
    void shouldPreventConcurrentDoubleRelease() throws Exception {
        UUID product = stockedProduct(100);
        UUID id = reserve(UUID.randomUUID(), product, 20);

        assertOptimisticRace(race(() -> loadBoth(id, product),
                () -> reservations.release(id), () -> reservations.release(id)));
        assertStock(product, 100, 0);
        assertTerminalVersion(id, ReservationStatus.RELEASED);

        reservations.release(id);
        assertStock(product, 100, 0);
        assertTerminalVersion(id, ReservationStatus.RELEASED);
    }

    @Test
    void shouldPreventConflictingTerminalTransitions() throws Exception {
        UUID product = stockedProduct(100);
        UUID id = reserve(UUID.randomUUID(), product, 20);

        var outcomes = race(() -> loadBoth(id, product),
                () -> reservations.confirm(id), () -> reservations.release(id));

        assertOptimisticRace(outcomes);
        boolean confirmed = outcomes.getFirst() == Outcome.SUCCESS;
        assertTerminalVersion(id, confirmed ? ReservationStatus.CONFIRMED : ReservationStatus.RELEASED);
        assertStock(product, confirmed ? 80 : 100, 0);
    }

    @Test
    void shouldRejectStaleReservationEvenWithFreshInventory() throws Exception {
        UUID product = stockedProduct(100);
        UUID reservationA = reserve(UUID.randomUUID(), product, 20);
        UUID reservationB = reserve(UUID.randomUUID(), product, 20);
        var reservationLoaded = new CountDownLatch(1);
        var confirmationFinished = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var release = workers.submit(() -> execute(() -> {
                reservationRepository.findById(reservationA).orElseThrow();
                reservationLoaded.countDown();
                await(confirmationFinished);
                reservations.release(reservationA);
            }));
            var confirm = workers.submit(() -> {
                try {
                    await(reservationLoaded);
                    return execute(() -> reservations.confirm(reservationA));
                } finally {
                    confirmationFinished.countDown();
                }
            });
            assertThat(confirm.get(30, TimeUnit.SECONDS)).isEqualTo(Outcome.SUCCESS);
            assertThat(release.get(30, TimeUnit.SECONDS)).isEqualTo(Outcome.OPTIMISTIC_CONFLICT);
        }

        assertTerminalVersion(reservationA, ReservationStatus.CONFIRMED);
        assertThat(reservations.getReservation(reservationB).status()).isEqualTo(ReservationStatus.RESERVED);
        assertStock(product, 60, 20);
    }

    @Test
    void shouldPreventLostUpdatesBetweenRestockAndReserve() throws Exception {
        UUID product = stockedProduct(100);
        UUID order = UUID.randomUUID();

        var outcomes = race(() -> loadInventory(product),
                () -> stock.restock(product, 50), () -> reserve(order, product, 30));

        assertOptimisticRace(outcomes);
        boolean restocked = outcomes.getFirst() == Outcome.SUCCESS;
        assertStock(product, restocked ? 150 : 70, restocked ? 0 : 30);
        assertThat(reservationRepository.existsByOrderIdAndProduct_Id(order, product)).isEqualTo(!restocked);
    }

    @Test
    void shouldPreventConcurrentDuplicateReservations() throws Exception {
        UUID product = stockedProduct(100);
        UUID order = UUID.randomUUID();
        Runnable reserve = () -> reserve(order, product, 20);

        var outcomes = race(() -> {
            loadInventory(product);
            assertThat(reservationRepository.existsByOrderIdAndProduct_Id(order, product)).isFalse();
        }, reserve, reserve);

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.SUCCESS, Outcome.DUPLICATE);
        assertStock(product, 80, 20);
        assertThat(reservationRepository.findByOrderIdAndProduct_Id(order, product).orElseThrow().getQuantity())
                .isEqualTo(20);
    }

    private List<Outcome> race(Runnable preload, Runnable operationA, Runnable operationB) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> runWorker(preload, operationA, ready, start));
            var second = workers.submit(() -> runWorker(preload, operationB, ready, start));
            try {
                assertTrue(ready.await(15, TimeUnit.SECONDS), "Both transactions must load their original state");
            } finally {
                start.countDown();
            }
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private Outcome runWorker(Runnable preload, Runnable operation, CountDownLatch ready, CountDownLatch start) {
        return execute(() -> {
            preload.run();
            ready.countDown();
            await(start);
            operation.run();
        });
    }

    private Outcome execute(Runnable operation) {
        try {
            transaction.executeWithoutResult(status -> operation.run());
            return Outcome.SUCCESS;
        } catch (OptimisticLockingFailureException exception) {
            return Outcome.OPTIMISTIC_CONFLICT;
        } catch (DuplicateReservationException exception) {
            return Outcome.DUPLICATE;
        }
    }

    private void loadInventory(UUID product) {
        inventoryRepository.findByProduct_Id(product).orElseThrow();
    }

    private void loadBoth(UUID id, UUID product) {
        reservationRepository.findById(id).orElseThrow();
        loadInventory(product);
    }

    private UUID stockedProduct(int quantity) {
        UUID product = products.createProduct(new ProductRequest("CONCURRENCY-" + UUID.randomUUID(),
                "Concurrency Test Product", null)).id();
        stock.restock(product, quantity);
        return product;
    }

    private UUID reserve(UUID order, UUID product, int quantity) {
        return reservations.reserve(new ReservationRequest(order, product, quantity)).id();
    }

    private void assertStock(UUID product, int available, int reserved) {
        var current = stock.getInventory(product);
        assertThat(current.availableQuantity()).isEqualTo(available);
        assertThat(current.reservedQuantity()).isEqualTo(reserved);
    }

    private void assertWinningReservation(UUID product, UUID orderA, UUID orderB, List<Outcome> outcomes) {
        boolean firstWon = outcomes.getFirst() == Outcome.SUCCESS;
        assertThat(reservationRepository.existsByOrderIdAndProduct_Id(orderA, product)).isEqualTo(firstWon);
        assertThat(reservationRepository.existsByOrderIdAndProduct_Id(orderB, product)).isEqualTo(!firstWon);
    }

    private void assertTerminalVersion(UUID id, ReservationStatus status) {
        StockReservation reservation = reservationRepository.findById(id).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(status);
        assertThat(reservation.getVersion()).isEqualTo(1L);
    }

    private static void assertOptimisticRace(List<Outcome> outcomes) {
        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.SUCCESS, Outcome.OPTIMISTIC_CONFLICT);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for concurrent transaction");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent worker interrupted", exception);
        }
    }
}
