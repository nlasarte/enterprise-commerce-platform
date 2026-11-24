package com.portfolio.commerce.inventory;

import java.util.List;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
class InventoryService {

    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final StockReservationRepository reservations;

    InventoryService(ProductRepository products, InventoryRepository inventory, StockReservationRepository reservations) {
        this.products = products;
        this.inventory = inventory;
        this.reservations = reservations;
    }

    @Transactional
    Product.ProductView createProduct(String sku, String name, String description) {
        Product product = new Product(sku, name, description);
        try {
            products.saveAndFlush(product);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && "products_sku_key".equals(violation.getConstraintName())) {
                throw new InventoryConflictException("The SKU already exists.", exception);
            }
            throw exception;
        }
        inventory.save(new Inventory(product.id()));
        return product.view();
    }

    List<Product.ProductView> listProducts() {
        return products.findAll(Sort.by("sku")).stream().map(Product::view).toList();
    }

    Product.ProductView product(UUID id) {
        return products.findById(id).orElseThrow(() -> missing("Product", id)).view();
    }

    Inventory.StockView stock(UUID productId) {
        return inventory.findByProductId(productId).orElseThrow(() -> missing("Stock for product", productId)).view();
    }

    @Transactional
    Inventory.StockView restock(UUID productId, int quantity) {
        Inventory stock = lockStock(productId);
        stock.restock(quantity);
        return stock.view();
    }

    @Transactional
    ReservationResult reserve(UUID productId, UUID orderId, int quantity) {
        Inventory stock = lockStock(productId);
        var existing = reservations.findByProductIdAndOrderId(productId, orderId);
        if (existing.isPresent()) {
            StockReservation reservation = existing.get();
            reservation.checkQuantity(quantity);
            return new ReservationResult(reservation.view(), false);
        }
        stock.reserve(quantity);
        StockReservation reservation = reservations.save(new StockReservation(productId, orderId, quantity));
        return new ReservationResult(reservation.view(), true);
    }

    StockReservation.ReservationView reservation(UUID id) {
        return findReservation(id).view();
    }

    @Transactional
    StockReservation.ReservationView finalizeReservation(UUID id, StockReservation.Status target) {
        // Read the reservation state only after acquiring the product's inventory lock.
        UUID productId = reservations.findProductId(id).orElseThrow(() -> missing("Reservation", id));
        Inventory stock = lockStock(productId);
        StockReservation reservation = findReservation(id);
        reservation.apply(target, stock);
        return reservation.view();
    }

    private Inventory lockStock(UUID productId) {
        return inventory.lockByProductId(productId).orElseThrow(() -> missing("Stock for product", productId));
    }

    private StockReservation findReservation(UUID id) {
        return reservations.findById(id).orElseThrow(() -> missing("Reservation", id));
    }

    private static ResponseStatusException missing(String resource, UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "%s %s was not found.".formatted(resource, id));
    }

    record ReservationResult(StockReservation.ReservationView reservation, boolean created) {
    }
}
