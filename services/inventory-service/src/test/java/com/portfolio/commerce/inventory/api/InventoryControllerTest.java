package com.portfolio.commerce.inventory.api;

import java.util.UUID;

import com.portfolio.commerce.inventory.api.dto.InventoryResponse;
import com.portfolio.commerce.inventory.application.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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

    @Test
    void reportsQuantityValidationErrorWithoutCallingTheService() throws Exception {
        mvc.perform(post(RESTOCK, ID).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.quantity").isString());

        verifyNoInteractions(inventory);
    }
}
