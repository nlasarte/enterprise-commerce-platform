package com.portfolio.commerce.inventory.application;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.api.dto.ProductResponse;
import com.portfolio.commerce.inventory.domain.Inventory;
import com.portfolio.commerce.inventory.domain.Product;
import com.portfolio.commerce.inventory.exception.DuplicateSkuException;
import com.portfolio.commerce.inventory.exception.ProductNotFoundException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import com.portfolio.commerce.inventory.persistence.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository products;
    private final InventoryRepository inventory;

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        String sku = request.sku().strip().toUpperCase(Locale.ROOT);
        if (sku.length() > 100) {
            throw new IllegalArgumentException("Normalized SKU must be at most 100 characters");
        }
        if (products.existsBySkuIgnoreCase(sku)) {
            throw new DuplicateSkuException(sku);
        }

        Product product = new Product(sku, request.name().strip(), request.description());
        try {
            products.saveAndFlush(product);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && "products_sku_key".equals(violation.getConstraintName())) {
                throw new DuplicateSkuException(sku, exception);
            }
            throw exception;
        }
        inventory.save(new Inventory(product));
        return ProductResponse.from(product);
    }

    public List<ProductResponse> getProducts() {
        return products.findAll(Sort.by("sku")).stream().map(ProductResponse::from).toList();
    }

    public ProductResponse getProduct(UUID id) {
        return products.findById(id).map(ProductResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }
}
