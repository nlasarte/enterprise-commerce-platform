package com.portfolio.commerce.inventory.application;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.domain.Inventory;
import com.portfolio.commerce.inventory.domain.Product;
import com.portfolio.commerce.inventory.exception.DuplicateSkuException;
import com.portfolio.commerce.inventory.exception.ProductNotFoundException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import com.portfolio.commerce.inventory.persistence.ProductRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository products;

    @Mock
    private InventoryRepository inventory;

    @InjectMocks
    private ProductService service;

    @Test
    void normalizesProductAndCreatesEmptyInventory() {
        var result = service.createProduct(new ProductRequest(" item-1 ", " Test product ", "Description"));

        var product = ArgumentCaptor.forClass(Product.class);
        var stock = ArgumentCaptor.forClass(Inventory.class);
        var writes = inOrder(products, inventory);
        writes.verify(products).existsBySkuIgnoreCase("ITEM-1");
        writes.verify(products).saveAndFlush(product.capture());
        writes.verify(inventory).save(stock.capture());
        assertThat(result.sku()).isEqualTo("ITEM-1");
        assertThat(result.name()).isEqualTo("Test product");
        assertThat(result.description()).isEqualTo("Description");
        assertThat(stock.getValue().getProduct()).isSameAs(product.getValue());
        assertThat(stock.getValue().getAvailableQuantity()).isZero();
        assertThat(stock.getValue().getReservedQuantity()).isZero();
    }

    @Test
    void rejectsSkuThatExceedsTheLimitAfterNormalization() {
        var request = new ProductRequest("ß".repeat(51), "Test product", null);

        assertThatThrownBy(() -> service.createProduct(request)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(products, inventory);
    }

    @Test
    void rejectsAnExistingSkuBeforeWritingInventory() {
        when(products.existsBySkuIgnoreCase("ITEM-1")).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(new ProductRequest("item-1", "Test product", null)))
                .isInstanceOf(DuplicateSkuException.class);

        verifyNoInteractions(inventory);
    }

    @Test
    void translatesTheSkuConstraintWhenConcurrentCreationWins() {
        var violation = new ConstraintViolationException("Duplicate", new SQLException("duplicate", "23505"),
                "products_sku_key");
        when(products.saveAndFlush(any(Product.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate", violation));

        assertThatThrownBy(() -> service.createProduct(new ProductRequest("item-1", "Test product", null)))
                .isInstanceOf(DuplicateSkuException.class);

        verifyNoInteractions(inventory);
    }

    @Test
    void propagatesUnrelatedWriteFailures() {
        var failure = new DataIntegrityViolationException("Unexpected write failure");
        when(products.saveAndFlush(any(Product.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.createProduct(new ProductRequest("item-1", "Test product", null)))
                .isSameAs(failure);

        verifyNoInteractions(inventory);
    }

    @Test
    void requestsSkuOrderAndMapsTheProductList() {
        when(products.findAll(Sort.by("sku")))
                .thenReturn(List.of(new Product("A", "First", null), new Product("B", "Second", null)));

        assertThat(service.getProducts()).extracting(result -> result.sku()).containsExactly("A", "B");
    }

    @Test
    void rejectsAnUnknownProduct() {
        UUID id = UUID.randomUUID();
        when(products.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProduct(id)).isInstanceOf(ProductNotFoundException.class);
    }
}
