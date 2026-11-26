CREATE TABLE idempotency_records (
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reservation_id UUID,
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (operation, idempotency_key),
    CONSTRAINT fk_idempotency_reservation FOREIGN KEY (reservation_id) REFERENCES stock_reservations(id),
    CONSTRAINT chk_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_idempotency_completion CHECK (
        (
            reservation_id IS NULL
            AND response_status IS NULL
            AND response_body IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            reservation_id IS NOT NULL
            AND response_status IS NOT NULL
            AND response_status = 201
            AND response_body IS NOT NULL
            AND completed_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_idempotency_completed_at ON idempotency_records(completed_at);
