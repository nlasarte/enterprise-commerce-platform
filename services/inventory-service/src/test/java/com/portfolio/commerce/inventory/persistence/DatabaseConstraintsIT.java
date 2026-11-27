package com.portfolio.commerce.inventory.persistence;

import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.commerce.inventory.TestcontainersConfiguration;
import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.application.InventoryService;
import com.portfolio.commerce.inventory.application.ProductService;
import com.portfolio.commerce.inventory.application.ReservationService;
import com.portfolio.commerce.inventory.application.idempotency.IdempotentReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static com.portfolio.commerce.inventory.application.idempotency.IdempotentReservationService.OPERATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DatabaseConstraintsIT {

    private static final String UNIQUE = "23505";
    private static final String FOREIGN_KEY = "23503";
    private static final String CHECK = "23514";
    private static final String NOT_NULL = "23502";
    private static final String STRING_TOO_LONG = "22001";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProductService products;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private IdempotentReservationService idempotency;

    @Autowired
    private ObjectMapper json;

    @Test
    void shouldRejectDuplicateProductSku() {
        String sku = newSku();
        products.createProduct(new ProductRequest(sku, "Constraint test", null));

        assertViolation(UNIQUE, "products_sku_key", () -> insertProduct(sku, "Duplicate SKU"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE sku = ?", Long.class, sku)).isEqualTo(1L);
    }

    @Test
    void shouldRejectNullProductName() {
        assertViolation(NOT_NULL, "name", () -> insertProduct(newSku(), null));
    }

    @Test
    void shouldRejectSkuExceedingMaximumLength() {
        assertViolation(STRING_TOO_LONG, null, () -> insertProduct("X".repeat(101), "Overlong SKU"));
    }

    @Test
    void shouldRejectDuplicateInventoryForProduct() {
        UUID product = stockedProduct();

        assertViolation(UNIQUE, "inventory_product_id_key", () -> insertInventory(product));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory WHERE product_id = ?",
                Long.class, product)).isEqualTo(1L);
    }

    @Test
    void shouldRejectInventoryForNonexistentProduct() {
        assertViolation(FOREIGN_KEY, "fk_inventory_product", () -> insertInventory(UUID.randomUUID()));
    }

    @Test
    void shouldRejectNegativeAvailableQuantity() {
        UUID product = stockedProduct();

        assertViolation(CHECK, "chk_available_quantity", () -> jdbc.update(
                "UPDATE inventory SET available_quantity = -1 WHERE product_id = ?", product));

        assertStock(product, 100, 0, 0);
    }

    @Test
    void shouldRejectNegativeReservedQuantity() {
        UUID product = stockedProduct();

        assertViolation(CHECK, "chk_reserved_quantity", () -> jdbc.update(
                "UPDATE inventory SET reserved_quantity = -1 WHERE product_id = ?", product));

        assertStock(product, 100, 0, 0);
    }

    @Test
    void shouldRejectDuplicateOrderProductReservation() {
        UUID order = UUID.randomUUID();
        UUID product = stockedProduct();
        reservations.reserve(new ReservationRequest(order, product, 20));

        assertViolation(UNIQUE, "uq_order_product", () -> insertReservation(product, order, 30, "RESERVED"));
        assertStock(product, 80, 20, 1);

        reservations.reserve(new ReservationRequest(order, stockedProduct(), 30));
        reservations.reserve(new ReservationRequest(UUID.randomUUID(), product, 30));
        assertStock(product, 50, 50, 2);
    }

    @Test
    void shouldRejectReservationForNonexistentProduct() {
        assertViolation(FOREIGN_KEY, "fk_reservation_product",
                () -> insertReservation(UUID.randomUUID(), UUID.randomUUID(), 20, "RESERVED"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -10})
    void shouldRejectNonpositiveReservationQuantity(int quantity) {
        UUID product = stockedProduct();

        assertViolation(CHECK, "chk_reservation_quantity",
                () -> insertReservation(product, UUID.randomUUID(), quantity, "RESERVED"));

        assertStock(product, 100, 0, 0);
    }

    @Test
    void shouldRejectInvalidReservationStatus() {
        UUID product = stockedProduct();

        assertViolation(CHECK, "chk_reservation_status",
                () -> insertReservation(product, UUID.randomUUID(), 20, "UNKNOWN"));

        assertStock(product, 100, 0, 0);
    }

    @Test
    void shouldRejectNullOrderId() {
        UUID product = stockedProduct();

        assertViolation(NOT_NULL, "order_id", () -> insertReservation(product, null, 20, "RESERVED"));

        assertStock(product, 100, 0, 0);
    }

    @Test
    void shouldRejectDuplicateIdempotencyKeyWithinOperation() {
        UUID product = stockedProduct();
        String key = UUID.randomUUID().toString();
        String otherKey = UUID.randomUUID().toString();
        var reservation = idempotency.reserve(key, new ReservationRequest(UUID.randomUUID(), product, 20));
        var otherReservation = idempotency.reserve(otherKey, new ReservationRequest(UUID.randomUUID(), product, 30));

        assertViolation(UNIQUE, "pk_idempotency_records", () -> jdbc.update("""
                UPDATE idempotency_records SET idempotency_key = ?
                WHERE operation = ? AND idempotency_key = ?
                """, key, OPERATION, otherKey));

        assertThat(keyReservation(key)).isEqualTo(reservation.reservationId());
        assertThat(keyReservation(otherKey)).isEqualTo(otherReservation.reservationId());
        assertStock(product, 50, 50, 2);

        assertThat(jdbc.update("""
                INSERT INTO idempotency_records (operation, idempotency_key, request_hash,
                    reservation_id, response_status, response_body, completed_at)
                SELECT 'another:operation', idempotency_key, request_hash,
                    reservation_id, response_status, response_body, completed_at
                FROM idempotency_records WHERE operation = ? AND idempotency_key = ?
                """, OPERATION, key)).isEqualTo(1);
    }

    @Test
    void shouldRejectNullIdempotencyKey() {
        UUID product = stockedProduct();
        String key = UUID.randomUUID().toString();
        var reservation = idempotency.reserve(key, new ReservationRequest(UUID.randomUUID(), product, 20));

        assertViolation(NOT_NULL, "idempotency_key", () -> jdbc.update("""
                UPDATE idempotency_records SET idempotency_key = NULL
                WHERE operation = ? AND idempotency_key = ?
                """, OPERATION, key));

        assertThat(keyReservation(key)).isEqualTo(reservation.reservationId());
        assertStock(product, 80, 20, 1);
    }

    @Test
    void shouldPersistCompleteIdempotencyRecord() throws JsonProcessingException {
        UUID product = stockedProduct();
        UUID order = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var result = idempotency.reserve(key, new ReservationRequest(order, product, 20));

        // Each rejected write runs independently, leaving the completed row available for inspection.
        assertAll("Completed idempotency record constraints",
                () -> assertViolation(CHECK, "chk_idempotency_completion", () -> jdbc.update("""
                        INSERT INTO idempotency_records (operation, idempotency_key, request_hash, response_status)
                        VALUES (?, ?, ?, 201)
                        """, OPERATION, UUID.randomUUID().toString(), "a".repeat(64))),
                () -> assertViolation(CHECK, "chk_idempotency_hash", () -> jdbc.update("""
                        UPDATE idempotency_records SET request_hash = ?
                        WHERE operation = ? AND idempotency_key = ?
                        """, "g".repeat(64), OPERATION, key)),
                () -> assertViolation(CHECK, "chk_idempotency_completion", () -> jdbc.update("""
                        UPDATE idempotency_records SET response_body = NULL
                        WHERE operation = ? AND idempotency_key = ?
                        """, OPERATION, key)),
                () -> assertViolation(CHECK, "chk_idempotency_completion", () -> jdbc.update("""
                        UPDATE idempotency_records SET response_status = 200
                        WHERE operation = ? AND idempotency_key = ?
                        """, OPERATION, key)),
                () -> assertViolation(FOREIGN_KEY, "fk_idempotency_reservation", () -> jdbc.update("""
                        UPDATE idempotency_records SET reservation_id = ?
                        WHERE operation = ? AND idempotency_key = ?
                        """, UUID.randomUUID(), OPERATION, key)));

        var record = jdbc.queryForObject("""
                SELECT request_hash, reservation_id, response_status, response_body,
                       completed_at IS NOT NULL AS completed
                FROM idempotency_records WHERE operation = ? AND idempotency_key = ?
                """, (row, index) -> new IdempotencySnapshot(row.getString("request_hash"),
                row.getObject("reservation_id", UUID.class), row.getInt("response_status"),
                row.getString("response_body"), row.getBoolean("completed")), OPERATION, key);

        assertThat(record).isNotNull();
        assertThat(record.hash()).matches("[0-9a-f]{64}");
        assertThat(record.completed()).isTrue();
        assertThat(record.status()).isEqualTo(201);
        assertThat(record.reservation()).isEqualTo(result.reservationId());
        assertThat(record.body()).isEqualTo(result.responseBody());
        var body = json.readTree(record.body());
        assertThat(body.required("id").asText()).isEqualTo(record.reservation().toString());
        assertThat(body.required("orderId").asText()).isEqualTo(order.toString());
        assertThat(body.required("productId").asText()).isEqualTo(product.toString());
        assertThat(body.required("quantity").asInt()).isEqualTo(20);
        assertThat(body.required("status").asText()).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_reservations
                WHERE id = ? AND order_id = ? AND product_id = ? AND quantity = 20 AND status = 'RESERVED'
                """, Long.class, record.reservation(), order, product)).isEqualTo(1L);
        assertStock(product, 80, 20, 1);
    }

    private UUID stockedProduct() {
        UUID product = products.createProduct(new ProductRequest(newSku(), "Constraint test", null)).id();
        inventory.restock(product, 100);
        return product;
    }

    private static String newSku() {
        return "CONSTRAINT-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
    }

    private void insertProduct(String sku, String name) {
        jdbc.update("INSERT INTO products (id, sku, name) VALUES (?, ?, ?)", UUID.randomUUID(), sku, name);
    }

    private void insertInventory(UUID product) {
        jdbc.update("INSERT INTO inventory (id, product_id) VALUES (?, ?)", UUID.randomUUID(), product);
    }

    private void insertReservation(UUID product, UUID order, int quantity, String status) {
        jdbc.update("""
                INSERT INTO stock_reservations (id, product_id, order_id, quantity, status)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), product, order, quantity, status);
    }

    private UUID keyReservation(String key) {
        return jdbc.queryForObject("""
                SELECT reservation_id FROM idempotency_records WHERE operation = ? AND idempotency_key = ?
                """, UUID.class, OPERATION, key);
    }

    private void assertStock(UUID product, int available, int reserved, long reservations) {
        var stock = jdbc.queryForObject("""
                SELECT i.available_quantity, i.reserved_quantity, count(r.id) AS reservation_count
                FROM inventory i LEFT JOIN stock_reservations r ON r.product_id = i.product_id
                WHERE i.product_id = ?
                GROUP BY i.available_quantity, i.reserved_quantity
                """, (row, index) -> new StockSnapshot(row.getInt("available_quantity"),
                row.getInt("reserved_quantity"), row.getLong("reservation_count")), product);
        assertThat(stock).isEqualTo(new StockSnapshot(available, reserved, reservations));
    }

    private static void assertViolation(String state, String databaseField, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo(state);
                    if (databaseField != null) {
                        var details = exception.getServerErrorMessage();
                        assertThat(NOT_NULL.equals(state) ? details.getColumn() : details.getConstraint())
                                .isEqualTo(databaseField);
                    }
                });
    }

    private record IdempotencySnapshot(String hash, UUID reservation, int status, String body, boolean completed) {
    }

    private record StockSnapshot(int available, int reserved, long reservations) {
    }
}
