package com.portfolio.commerce.inventory.api;

import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.InventoryResponse;
import com.portfolio.commerce.inventory.application.InventoryService;
import com.portfolio.commerce.inventory.exception.InventoryNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    private static final String INVENTORY = "/api/v1/inventory/{id}";
    private static final String RESTOCK = INVENTORY + "/restock";
    private static final UUID ID = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InventoryService inventory;

    @Test
    void returnsStockForProduct() throws Exception {
        when(inventory.getInventory(ID)).thenReturn(new InventoryResponse(ID, "ITEM-1", 8, 2));

        mvc.perform(get(INVENTORY, ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.productId").value(ID.toString()))
                .andExpect(jsonPath("$.sku").value("ITEM-1"))
                .andExpect(jsonPath("$.availableQuantity").value(8))
                .andExpect(jsonPath("$.reservedQuantity").value(2));
    }

    @Test
    void returnsUpdatedStockAfterRestock() throws Exception {
        when(inventory.restock(ID, 5)).thenReturn(new InventoryResponse(ID, "ITEM-1", 13, 2));

        mvc.perform(post(RESTOCK, ID).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(13))
                .andExpect(jsonPath("$.reservedQuantity").value(2));
    }

    @Test
    void rejectsFractionalRestocksWithoutCallingTheService() throws Exception {
        mvc.perform(post(RESTOCK, ID).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(inventory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":-10}", "{\"quantity\":0}", "{}"})
    void reportsQuantityValidationErrorWithoutCallingTheService(String body) throws Exception {
        mvc.perform(post(RESTOCK, ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.quantity").isString());

        verifyNoInteractions(inventory);
    }

    @Test
    void rejectsInvalidProductId() throws Exception {
        mvc.perform(get(INVENTORY, "invalid-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(inventory);
    }

    @Test
    void reportsMissingInventoryOnRead() throws Exception {
        when(inventory.getInventory(ID)).thenThrow(new InventoryNotFoundException(ID));

        mvc.perform(get(INVENTORY, ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").isString());
    }

    @Test
    void reportsMissingInventoryOnRestock() throws Exception {
        when(inventory.restock(ID, 5)).thenThrow(new InventoryNotFoundException(ID));

        mvc.perform(post(RESTOCK, ID).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void reportsRestockOverflow() throws Exception {
        when(inventory.restock(ID, Integer.MAX_VALUE)).thenThrow(new ArithmeticException("integer overflow"));

        mvc.perform(post(RESTOCK, ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2147483647}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Inventory limit exceeded"));
    }

    @Test
    void reportsRetryableRestockConflict() throws Exception {
        when(inventory.restock(ID, 5)).thenThrow(new OptimisticLockingFailureException("Stale inventory"));

        mvc.perform(post(RESTOCK, ID).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));
    }
}
