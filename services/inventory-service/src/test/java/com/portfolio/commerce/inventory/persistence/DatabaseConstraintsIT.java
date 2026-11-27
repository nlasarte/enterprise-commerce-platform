package com.portfolio.commerce.inventory.persistence;

import java.util.UUID;

import com.portfolio.commerce.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
@ActiveProfiles("test")
class DatabaseConstraintsIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void enforcesUniqueProductSku() {
        UUID product = createProduct();

        assertViolation("23505", "products_sku_key", () -> jdbc.update(
                "INSERT INTO products (id, sku, name) VALUES (?, ?, 'Duplicate SKU')",
                UUID.randomUUID(), "CONSTRAINT-" + product));
    }

    @Test
    void enforcesOneInventoryRowPerProduct() {
        UUID product = createProduct();
        insertInventory(product, 0, 0);

        assertViolation("23505", "inventory_product_id_key", () -> insertInventory(product, 0, 0));
    }

    @Test
    void requiresAnExistingProductForInventory() {
        assertViolation("23503", "fk_inventory_product", () -> insertInventory(UUID.randomUUID(), 0, 0));
    }

    @ParameterizedTest
    @CsvSource({"-1,0,chk_available_quantity", "0,-1,chk_reserved_quantity"})
    void rejectsNegativeStock(int available, int reserved, String constraint) {
        UUID product = createProduct();

        assertViolation("23514", constraint, () -> insertInventory(product, available, reserved));
    }

    @ParameterizedTest
    @CsvSource({"0,RESERVED,chk_reservation_quantity", "1,UNKNOWN,chk_reservation_status"})
    void rejectsInvalidReservationRows(int quantity, String status, String constraint) {
        UUID product = createProduct();

        assertViolation("23514", constraint, () -> insertReservation(product, UUID.randomUUID(), quantity, status));
    }

    @Test
    void enforcesUniqueOrderAndProduct() {
        UUID order = UUID.randomUUID();
        UUID product = createProduct();
        insertReservation(product, order, 20, "RESERVED");
        insertReservation(createProduct(), order, 30, "RESERVED");
        insertReservation(product, UUID.randomUUID(), 30, "RESERVED");

        assertViolation("23505", "uq_order_product", () -> insertReservation(product, order, 30, "RESERVED"));
    }

    @Test
    void requiresAnExistingProductForReservations() {
        assertViolation("23503", "fk_reservation_product",
                () -> insertReservation(UUID.randomUUID(), UUID.randomUUID(), 20, "RESERVED"));
    }

    @Test
    void enforcesKeyUniquenessWithinAnOperation() {
        String key = UUID.randomUUID().toString();
        insertClaim("reservation:create", key, "a".repeat(64));
        insertClaim("another:operation", key, "b".repeat(64));

        assertViolation("23505", "pk_idempotency_records",
                () -> insertClaim("reservation:create", key, "b".repeat(64)));
    }

    @Test
    void rejectsMalformedFingerprints() {
        assertViolation("23514", "chk_idempotency_hash",
                () -> insertClaim("reservation:create", UUID.randomUUID().toString(), "g".repeat(64)));
    }

    @Test
    void rejectsPartiallyCompletedIdempotencyRecords() {
        String key = UUID.randomUUID().toString();
        insertClaim("reservation:create", key, "a".repeat(64));

        assertViolation("23514", "chk_idempotency_completion", () -> jdbc.update("""
                UPDATE idempotency_records
                SET response_status = 201
                WHERE operation = 'reservation:create' AND idempotency_key = ?
                """, key));
    }

    @Test
    void requiresACreatedResponseStatusForCompletedRecords() {
        UUID reservation = insertReservation(createProduct(), UUID.randomUUID(), 20, "RESERVED");

        assertViolation("23514", "chk_idempotency_completion", () -> insertCompletedRecord(reservation, 200));
    }

    @Test
    void rejectsCompletionWithAnUnknownReservation() {
        assertViolation("23503", "fk_idempotency_reservation", () -> insertCompletedRecord(UUID.randomUUID(), 201));
    }

    private UUID createProduct() {
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, sku, name) VALUES (?, ?, 'Constraint test')",
                product, "CONSTRAINT-" + product);
        return product;
    }

    private void insertInventory(UUID product, int available, int reserved) {
        jdbc.update("""
                INSERT INTO inventory (id, product_id, available_quantity, reserved_quantity)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), product, available, reserved);
    }

    private UUID insertReservation(UUID product, UUID order, int quantity, String status) {
        UUID reservation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO stock_reservations (id, product_id, order_id, quantity, status)
                VALUES (?, ?, ?, ?, ?)
                """, reservation, product, order, quantity, status);
        return reservation;
    }

    private void insertClaim(String operation, String key, String hash) {
        jdbc.update("""
                INSERT INTO idempotency_records (operation, idempotency_key, request_hash)
                VALUES (?, ?, ?)
                """, operation, key, hash);
    }

    private void insertCompletedRecord(UUID reservation, int status) {
        jdbc.update("""
                INSERT INTO idempotency_records (operation, idempotency_key, request_hash,
                    reservation_id, response_status, response_body, completed_at)
                VALUES ('reservation:create', ?, ?, ?, ?, '{}', CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), "a".repeat(64), reservation, status);
    }

    private static void assertViolation(String sqlState, String constraint, Runnable insert) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo(sqlState);
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                });
    }
}
