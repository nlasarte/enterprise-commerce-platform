package com.portfolio.commerce.inventory.application.idempotency;

import java.util.regex.Pattern;

import com.portfolio.commerce.inventory.exception.InvalidIdempotencyKeyException;

public record IdempotencyKey(String value) {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public IdempotencyKey {
        if (value == null || !VALID_KEY.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must contain between 1 and 128 characters using letters, "
                    + "numbers, dots, underscores, colons or hyphens.");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }
}
