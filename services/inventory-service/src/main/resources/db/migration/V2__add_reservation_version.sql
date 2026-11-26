ALTER TABLE stock_reservations
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
