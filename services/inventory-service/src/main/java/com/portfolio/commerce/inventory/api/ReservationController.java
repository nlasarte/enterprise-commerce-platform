package com.portfolio.commerce.inventory.api;

import java.net.URI;
import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationResponse;
import com.portfolio.commerce.inventory.application.ReservationService;
import com.portfolio.commerce.inventory.domain.StockReservation.Status;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ReservationController.PATH)
@RequiredArgsConstructor
public class ReservationController {

    static final String PATH = "/api/v1/reservations";
    private final ReservationService reservations;

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReservationRequest request) {
        var result = reservations.reserve(request);
        if (result.created()) {
            return ResponseEntity.created(URI.create(PATH + "/" + result.reservation().id())).body(result.reservation());
        }
        return ResponseEntity.ok(result.reservation());
    }

    @GetMapping("/{id}")
    public ReservationResponse getReservation(@PathVariable UUID id) {
        return reservations.getReservation(id);
    }

    @PostMapping("/{id}/confirm")
    public ReservationResponse confirm(@PathVariable UUID id) {
        return reservations.finalizeReservation(id, Status.CONFIRMED);
    }

    @PostMapping("/{id}/release")
    public ReservationResponse release(@PathVariable UUID id) {
        return reservations.finalizeReservation(id, Status.RELEASED);
    }
}
