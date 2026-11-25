package com.portfolio.commerce.inventory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.portfolio.commerce.inventory.application.InventoryService;
import com.portfolio.commerce.inventory.persistence.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InventoryServiceApplicationTests {

    private static final String PRODUCTS = "/api/v1/products";
    private static final String INVENTORY = "/api/v1/inventory";
    private static final String RESERVATIONS = "/api/v1/reservations";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE stock_reservations, inventory, products");
    }

    @Test
    void createsProductsWithEmptyStockAndRejectsDuplicateSku() throws Exception {
        String id = createProduct();

        mvc.perform(get(PRODUCTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get(PRODUCTS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("ITEM-001"));
        assertStock(id, 0, 0);
        mvc.perform(get(INVENTORY + "/" + id)).andExpect(jsonPath("$.sku").value("ITEM-001"));
        postJson(PRODUCTS, """
                {"sku":" item-001 ","name":"Another product"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate SKU"))
                .andExpect(jsonPath("$.detail").value("A product with SKU ITEM-001 already exists"));
        restock(id, 100);
        assertStock(id, 100, 0);
        assertThat(jdbc.queryForObject("SELECT version FROM inventory WHERE product_id = ?", Long.class,
                UUID.fromString(id))).isEqualTo(1L);
    }

    @Test
    void rollsBackProductCreationWhenInventoryInitializationFails() throws Exception {
        jdbc.execute("ALTER TABLE inventory ADD CONSTRAINT test_reject_inventory CHECK (false)");
        try {
            postJson(PRODUCTS, """
                    {"sku":"ROLLBACK-001","name":"Product"}
                    """)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Data integrity conflict"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE inventory DROP CONSTRAINT test_reject_inventory");
        }
    }

    @Test
    void rejectsAStaleInventoryUpdate() throws Exception {
        UUID productId = UUID.fromString(createProduct());
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> outer.executeWithoutResult(outerStatus -> {
            var staleStock = inventory.findByProduct_Id(productId).orElseThrow();
            inner.executeWithoutResult(innerStatus -> inventoryService.restock(productId, 100));
            staleStock.restock(50);
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertStock(productId.toString(), 100, 0);
    }

    @Test
    void validatesInputAndReportsMissingResources() throws Exception {
        String missing = UUID.randomUUID().toString();
        mvc.perform(get(PRODUCTS + "/" + missing)).andExpect(status().isNotFound());
        mvc.perform(get(INVENTORY + "/" + missing)).andExpect(status().isNotFound());
        mvc.perform(get(RESERVATIONS + "/" + missing)).andExpect(status().isNotFound());
        mvc.perform(post(RESERVATIONS + "/" + missing + "/confirm")).andExpect(status().isNotFound());
        postJson(PRODUCTS, """
                {"sku":" ","name":"Product"}
                """).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sku").value("SKU is required"));
        postJson(INVENTORY + "/" + missing + "/restock", """
                {"quantity":0}
                """).andExpect(status().isBadRequest());
        postJson(INVENTORY + "/" + missing + "/restock", """
                {"quantity":1.5}
                """).andExpect(status().isBadRequest());
        postJson(RESERVATIONS, """
                {"quantity":-1}
                """).andExpect(status().isBadRequest());
    }

    @Test
    void reservesAndConfirmsStockIdempotently() throws Exception {
        String productId = createProduct();
        String orderId = UUID.randomUUID().toString();
        restock(productId, 10);
        String id = reserve(productId, orderId, 4);
        assertStock(productId, 6, 4);

        requestReservation(productId, orderId, 4)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate reservation"));
        requestReservation(productId, orderId, 5).andExpect(status().isConflict());
        requestReservation(productId, UUID.randomUUID().toString(), 7)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"))
                .andExpect(jsonPath("$.detail").value("Insufficient stock. Requested: 7, available: 6"));

        mvc.perform(get(RESERVATIONS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(RESERVATIONS + "/" + id + "/confirm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
        mvc.perform(post(RESERVATIONS + "/" + id + "/release")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid reservation state"));
        assertStock(productId, 6, 0);
    }

    @Test
    void releasesStockExactlyOnce() throws Exception {
        String productId = createProduct();
        restock(productId, 10);
        String id = reserve(productId, UUID.randomUUID().toString(), 4);

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(RESERVATIONS + "/" + id + "/release"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RELEASED"));
        }
        mvc.perform(post(RESERVATIONS + "/" + id + "/confirm")).andExpect(status().isConflict());
        assertStock(productId, 10, 0);
    }

    @Test
    void rejectsRestockThatWouldOverflowWhenReservedStockIsReleased() throws Exception {
        String productId = createProduct();
        restock(productId, Integer.MAX_VALUE);
        String reservationId = reserve(productId, UUID.randomUUID().toString(), 1);

        postJson(INVENTORY + "/" + productId + "/restock", """
                {"quantity":1}
                """).andExpect(status().isConflict());
        mvc.perform(post(RESERVATIONS + "/" + reservationId + "/release")).andExpect(status().isOk());
        assertStock(productId, Integer.MAX_VALUE, 0);
    }

    @Test
    void serializesConcurrentReservationsForTheSameProduct() throws Exception {
        String productId = createProduct();
        restock(productId, 5);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = List.of(
                    executor.submit(() -> reserveWhenReady(start, productId)),
                    executor.submit(() -> reserveWhenReady(start, productId)));
            start.countDown();
            assertThat(List.of(requests.get(0).get(15, TimeUnit.SECONDS),
                    requests.get(1).get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertStock(productId, 1, 4);
    }

    @Test
    void resolvesConcurrentConfirmAndReleaseWithOneFinalState() throws Exception {
        String productId = createProduct();
        restock(productId, 5);
        String id = reserve(productId, UUID.randomUUID().toString(), 4);
        String reservation = RESERVATIONS + "/" + id;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var confirm = executor.submit(() -> finalizeWhenReady(start, reservation + "/confirm"));
            var release = executor.submit(() -> finalizeWhenReady(start, reservation + "/release"));
            start.countDown();
            int confirmed = confirm.get(15, TimeUnit.SECONDS);
            int released = release.get(15, TimeUnit.SECONDS);
            assertThat(List.of(confirmed, released)).containsExactlyInAnyOrder(200, 409);
            mvc.perform(get(reservation)).andExpect(jsonPath("$.status")
                    .value(confirmed == 200 ? "CONFIRMED" : "RELEASED"));
            assertStock(productId, confirmed == 200 ? 1 : 5, 0);
        }
    }

    private int finalizeWhenReady(CountDownLatch start, String path) throws Exception {
        start.await();
        return mvc.perform(post(path)).andReturn().getResponse().getStatus();
    }

    private int reserveWhenReady(CountDownLatch start, String productId) throws Exception {
        start.await();
        return requestReservation(productId, UUID.randomUUID().toString(), 4)
                .andReturn().getResponse().getStatus();
    }

    private String createProduct() throws Exception {
        JsonNode product = responseBody(postJson(PRODUCTS, """
                {"sku":" item-001 ","name":"Product","description":"A test product"}
                """).andExpect(status().isCreated()).andExpect(header().exists("Location")));
        return product.get("id").asText();
    }

    private void restock(String productId, int quantity) throws Exception {
        postJson(INVENTORY + "/" + productId + "/restock", """
                {"quantity":%d}
                """.formatted(quantity))
                .andExpect(status().isOk());
    }

    private String reserve(String productId, String orderId, int quantity) throws Exception {
        return responseBody(requestReservation(productId, orderId, quantity)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("RESERVED"))).get("id").asText();
    }

    private ResultActions requestReservation(String productId, String orderId, int quantity) throws Exception {
        return postJson(RESERVATIONS, """
                {"productId":"%s","orderId":"%s","quantity":%d}
                """.formatted(productId, orderId, quantity));
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode responseBody(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private void assertStock(String productId, int available, int reserved) throws Exception {
        mvc.perform(get(INVENTORY + "/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(available))
                .andExpect(jsonPath("$.reservedQuantity").value(reserved));
    }

}
