package com.portfolio.commerce.inventory.api;

import java.net.URI;
import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.ReservationRequest;
import com.portfolio.commerce.inventory.api.dto.ReservationResponse;
import com.portfolio.commerce.inventory.application.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping(ReservationController.PATH)
@RequiredArgsConstructor
public class ReservationController {

    static final String PATH = "/api/v1/reservations";
    private final ReservationService reservations;

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReservationRequest request) {
        ReservationResponse reservation = reservations.reserve(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(reservation.id()).toUri();
        return ResponseEntity.created(location).body(reservation);
    }

    @GetMapping("/{id}")
    public ReservationResponse getReservation(@PathVariable UUID id) {
        return reservations.getReservation(id);
    }

    @PostMapping("/{id}/confirm")
    public ReservationResponse confirm(@PathVariable UUID id) {
        return reservations.confirm(id);
    }

    @PostMapping("/{id}/release")
    public ReservationResponse release(@PathVariable UUID id) {
        return reservations.release(id);
    }
}
