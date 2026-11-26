package com.portfolio.commerce.inventory.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (row, rowNumber) -> {
        OffsetDateTime completedAt = row.getObject("completed_at", OffsetDateTime.class);
        return new IdempotencyRecord(
                row.getString("operation"),
                row.getString("idempotency_key"),
                row.getString("request_hash"),
                row.getObject("reservation_id", UUID.class),
                row.getObject("response_status", Integer.class),
                row.getString("response_body"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    };

    private final JdbcTemplate jdbc;

    public boolean tryClaim(String operation, String key, String requestHash) {
        try {
            return jdbc.update("""
                    INSERT INTO idempotency_records (operation, idempotency_key, request_hash)
                    VALUES (?, ?, ?)
                    ON CONFLICT (operation, idempotency_key) DO NOTHING
                    """, operation, key, requestHash) == 1;
        } catch (UncategorizedSQLException exception) {
            // PostgreSQL uses lock_not_available for lock_timeout on a contested key.
            if ("55P03".equals(exception.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("Timed out acquiring the idempotency key", exception);
            }
            throw exception;
        }
    }

    public Optional<IdempotencyRecord> find(String operation, String key) {
        return jdbc.query("""
                SELECT operation, idempotency_key, request_hash, reservation_id, response_status,
                       response_body, created_at, completed_at
                FROM idempotency_records
                WHERE operation = ? AND idempotency_key = ?
                """, ROW_MAPPER, operation, key).stream().findFirst();
    }

    public void complete(String operation, String key, String requestHash, UUID reservationId,
                         int responseStatus, String responseBody) {
        int updated = jdbc.update("""
                UPDATE idempotency_records
                SET reservation_id = ?,
                    response_status = ?,
                    response_body = ?,
                    completed_at = clock_timestamp()
                WHERE operation = ? AND idempotency_key = ? AND request_hash = ? AND completed_at IS NULL
                """, reservationId, responseStatus, responseBody, operation, key, requestHash);

        if (updated != 1) {
            throw new IllegalStateException("Unable to complete idempotency record");
        }
    }
}
