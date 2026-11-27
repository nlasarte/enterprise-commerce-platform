package com.portfolio.commerce.inventory.api;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.commerce.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReservationHttpIT {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper json;

    @Test
    void replaysCreationAfterConfirmationOverHttp() throws Exception {
        var product = http.postForEntity("/api/v1/products", jsonRequest("""
                {"sku":"HTTP-%s","name":"HTTP test product"}
                """.formatted(UUID.randomUUID())), JsonNode.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String productId = product.getBody().required("id").asText();

        var restock = http.postForEntity("/api/v1/inventory/" + productId + "/restock",
                jsonRequest("{\"quantity\":100}"), JsonNode.class);
        assertThat(restock.getStatusCode()).isEqualTo(HttpStatus.OK);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        String orderId = UUID.randomUUID().toString();
        var keyedRequest = new HttpEntity<>("""
                {"orderId":"%s","productId":"%s","quantity":20}
                """.formatted(orderId, productId), headers);

        var created = http.postForEntity("/api/v1/reservations", keyedRequest, String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        assertThat(json.readTree(created.getBody()).required("status").asText()).isEqualTo("RESERVED");
        var location = created.getHeaders().getLocation();
        assertThat(location).isNotNull();
        String reservationPath = location.getPath();

        var confirmed = http.postForEntity(reservationPath + "/confirm", null, JsonNode.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().required("status").asText()).isEqualTo("CONFIRMED");

        var replay = http.postForEntity("/api/v1/reservations", keyedRequest, String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getLocation()).isEqualTo(location);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(created.getBody());

        var changedRequest = new HttpEntity<>("""
                {"orderId":"%s","productId":"%s","quantity":21}
                """.formatted(orderId, productId), headers);
        var conflict = http.postForEntity("/api/v1/reservations", changedRequest, JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(conflict.getBody().required("errorCode").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

        var stock = http.getForEntity("/api/v1/inventory/" + productId, JsonNode.class);
        assertThat(stock.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stock.getBody().required("availableQuantity").asInt()).isEqualTo(80);
        assertThat(stock.getBody().required("reservedQuantity").asInt()).isZero();
    }

    private static HttpEntity<String> jsonRequest(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
