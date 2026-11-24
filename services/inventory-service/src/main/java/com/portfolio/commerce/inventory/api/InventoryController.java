package com.portfolio.commerce.inventory.api;

import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.InventoryResponse;
import com.portfolio.commerce.inventory.api.dto.RestockRequest;
import com.portfolio.commerce.inventory.application.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventory;

    @GetMapping("/{productId}")
    public InventoryResponse getInventory(@PathVariable UUID productId) {
        return inventory.getInventory(productId);
    }

    @PostMapping("/{productId}/restock")
    public InventoryResponse restock(@PathVariable UUID productId, @Valid @RequestBody RestockRequest request) {
        return inventory.restock(productId, request.quantity());
    }
}
