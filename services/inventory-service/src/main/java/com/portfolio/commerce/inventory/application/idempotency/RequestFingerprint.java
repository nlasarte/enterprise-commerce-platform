package com.portfolio.commerce.inventory.application.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import com.portfolio.commerce.inventory.api.dto.ReservationRequest;

public final class RequestFingerprint {

    private static final String VERSION = "reservation-create:v1";

    private RequestFingerprint() {
    }

    public static String forReservation(ReservationRequest request) {
        Objects.requireNonNull(request, "request");
        String canonical = String.join("\n", VERSION, request.orderId().toString(),
                request.productId().toString(), Integer.toString(request.quantity()));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
