package com.portfolio.commerce.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.commerce.inventory.api.dto.ProductRequest;
import com.portfolio.commerce.inventory.api.dto.ProductResponse;
import com.portfolio.commerce.inventory.application.ProductService;
import com.portfolio.commerce.inventory.exception.DuplicateSkuException;
import com.portfolio.commerce.inventory.exception.ProductNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final String PRODUCTS = "/api/v1/products";
    private static final UUID ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");
    private static final ProductResponse PRODUCT =
            new ProductResponse(ID, "ITEM-1", "Test product", "Description", CREATED_AT);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private ProductService products;

    @Test
    void createsProductWithLocationAndJson() throws Exception {
        when(products.createProduct(new ProductRequest("ITEM-1", "Test product", "Description"))).thenReturn(PRODUCT);

        mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"ITEM-1","name":"Test product","description":"Description"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost" + PRODUCTS + "/" + ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.sku").value("ITEM-1"))
                .andExpect(jsonPath("$.name").value("Test product"))
                .andExpect(jsonPath("$.description").value("Description"))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()));
    }

    @Test
    void listsProductsAsAJsonArray() throws Exception {
        when(products.getProducts()).thenReturn(List.of(PRODUCT));

        mvc.perform(get(PRODUCTS))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ID.toString()));
    }

    @Test
    void reportsMissingProductAsProblemJson() throws Exception {
        when(products.getProduct(ID)).thenThrow(new ProductNotFoundException(ID));

        mvc.perform(get(PRODUCTS + "/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void retrievesAProductById() throws Exception {
        when(products.getProduct(ID)).thenReturn(PRODUCT);

        mvc.perform(get(PRODUCTS + "/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.sku").value("ITEM-1"));
    }

    @Test
    void returnsAnEmptyJsonArray() throws Exception {
        when(products.getProducts()).thenReturn(List.of());

        mvc.perform(get(PRODUCTS)).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @MethodSource("invalidProducts")
    void reportsProductValidationErrorsWithoutCallingTheService(ProductRequest request, String field) throws Exception {
        mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors." + field).isString());

        verifyNoInteractions(products);
    }

    static Stream<Arguments> invalidProducts() {
        return Stream.of(
                Arguments.of(new ProductRequest("", "Test product", null), "sku"),
                Arguments.of(new ProductRequest("ITEM-1", " ", null), "name"),
                Arguments.of(new ProductRequest("A".repeat(101), "Test product", null), "sku"),
                Arguments.of(new ProductRequest("ITEM-1", "Test product", "A".repeat(1001)), "description"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content("{invalid-json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(products);
    }

    @Test
    void rejectsInvalidProductId() throws Exception {
        mvc.perform(get(PRODUCTS + "/invalid-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(products);
    }

    @Test
    void returnsConflictForDuplicateSku() throws Exception {
        var request = new ProductRequest("ITEM-1", "Test product", null);
        when(products.createProduct(request)).thenThrow(new DuplicateSkuException("ITEM-1"));

        mvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Duplicate SKU"));
    }
}
