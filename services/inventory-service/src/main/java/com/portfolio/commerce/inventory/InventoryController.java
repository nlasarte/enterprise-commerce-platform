package com.portfolio.commerce.inventory;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class InventoryController {

    private static final String PRODUCTS = "/products";
    private static final String INVENTORY = "/inventory";
    private static final String RESERVATIONS = "/reservations";

    private final InventoryService service;

    InventoryController(InventoryService service) {
        this.service = service;
    }

    @PostMapping(PRODUCTS)
    ResponseEntity<Product.ProductView> createProduct(@Valid @RequestBody CreateProduct request) {
        var product = service.createProduct(request.sku(), request.name(), request.description());
        return ResponseEntity.created(location(PRODUCTS, product.id())).body(product);
    }

    @GetMapping(PRODUCTS)
    List<Product.ProductView> listProducts() {
        return service.listProducts();
    }

    @GetMapping(PRODUCTS + "/{id}")
    Product.ProductView product(@PathVariable UUID id) {
        return service.product(id);
    }

    @GetMapping(INVENTORY + "/{productId}")
    Inventory.StockView stock(@PathVariable UUID productId) {
        return service.stock(productId);
    }

    @PostMapping(INVENTORY + "/{productId}/restock")
    Inventory.StockView restock(@PathVariable UUID productId, @Valid @RequestBody Restock request) {
        return service.restock(productId, request.quantity());
    }

    @PostMapping(RESERVATIONS)
    ResponseEntity<StockReservation.ReservationView> reserve(@Valid @RequestBody Reserve request) {
        var result = service.reserve(request.productId(), request.orderId(), request.quantity());
        var reservation = result.reservation();
        if (result.created()) {
            return ResponseEntity.created(location(RESERVATIONS, reservation.id())).body(reservation);
        }
        return ResponseEntity.ok(reservation);
    }

    @GetMapping(RESERVATIONS + "/{id}")
    StockReservation.ReservationView reservation(@PathVariable UUID id) {
        return service.reservation(id);
    }

    @PostMapping(RESERVATIONS + "/{id}/confirm")
    StockReservation.ReservationView confirm(@PathVariable UUID id) {
        return service.finalizeReservation(id, StockReservation.Status.CONFIRMED);
    }

    @PostMapping(RESERVATIONS + "/{id}/release")
    StockReservation.ReservationView release(@PathVariable UUID id) {
        return service.finalizeReservation(id, StockReservation.Status.RELEASED);
    }

    @ExceptionHandler(InventoryConflictException.class)
    ProblemDetail conflict(InventoryConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    private static URI location(String path, UUID id) {
        return URI.create("/api/v1" + path + "/" + id);
    }

    record CreateProduct(@NotBlank @Size(max = 100) String sku,
                         @NotBlank @Size(max = 255) String name,
                         @Size(max = 1000) String description) {
    }

    record Restock(@NotNull @Positive Integer quantity) {
    }

    record Reserve(@NotNull UUID productId, @NotNull UUID orderId, @NotNull @Positive Integer quantity) {
    }
}
