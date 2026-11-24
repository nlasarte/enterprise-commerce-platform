package com.portfolio.commerce.inventory.application;

import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.InventoryResponse;
import com.portfolio.commerce.inventory.domain.Inventory;
import com.portfolio.commerce.inventory.exception.InventoryNotFoundException;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final InventoryRepository inventory;

    public InventoryResponse getInventory(UUID productId) {
        return InventoryResponse.from(findInventory(productId));
    }

    @Transactional
    public InventoryResponse restock(UUID productId, int quantity) {
        Inventory stock = findInventory(productId);
        stock.restock(quantity);
        return InventoryResponse.from(stock);
    }

    private Inventory findInventory(UUID productId) {
        return inventory.findByProduct_Id(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId));
    }
}
