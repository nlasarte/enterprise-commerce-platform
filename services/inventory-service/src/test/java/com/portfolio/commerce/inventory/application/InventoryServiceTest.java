package com.portfolio.commerce.inventory.application;

import java.util.Optional;
import java.util.UUID;

import com.portfolio.commerce.inventory.domain.Inventory;
import com.portfolio.commerce.inventory.domain.Product;
import com.portfolio.commerce.inventory.exception.InventoryNotFoundException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private final UUID productId = UUID.randomUUID();
    private final Inventory stock = new Inventory(new Product("ITEM-1", "Test product", null));

    @Mock
    private InventoryRepository inventory;

    @InjectMocks
    private InventoryService service;

    @Test
    void returnsAvailableAndReservedStock() {
        stock.restock(10);
        stock.reserve(3);
        when(inventory.findByProduct_Id(productId)).thenReturn(Optional.of(stock));

        var result = service.getInventory(productId);

        assertThat(result.sku()).isEqualTo("ITEM-1");
        assertThat(result.availableQuantity()).isEqualTo(7);
        assertThat(result.reservedQuantity()).isEqualTo(3);
    }

    @Test
    void restocksTheManagedInventory() {
        stock.restock(10);
        stock.reserve(3);
        when(inventory.findByProduct_Id(productId)).thenReturn(Optional.of(stock));

        var result = service.restock(productId, 5);

        assertThat(result.availableQuantity()).isEqualTo(12);
        assertThat(result.reservedQuantity()).isEqualTo(3);
        assertThat(stock.getAvailableQuantity()).isEqualTo(12);
        verify(inventory).findByProduct_Id(productId);
        verifyNoMoreInteractions(inventory);
    }

    @Test
    void rejectsAnUnknownInventory() {
        when(inventory.findByProduct_Id(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restock(productId, 5)).isInstanceOf(InventoryNotFoundException.class);
    }
}
