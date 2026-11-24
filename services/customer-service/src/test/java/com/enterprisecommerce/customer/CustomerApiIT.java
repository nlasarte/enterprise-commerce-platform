package com.enterprisecommerce.customer;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CustomerApiIT {

    private static final String CUSTOMERS = CustomerController.PATH;

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository repository;

    @BeforeEach
    void clearCustomers() {
        repository.deleteAllInBatch();
    }

    @Test
    void supportsCustomerLifecycle() throws Exception {
        mvc.perform(get(CUSTOMERS))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        String customerPath = createCustomer("ada@example.com");
        String id = customerPath.substring(CUSTOMERS.length() + 1);

        mvc.perform(get(CUSTOMERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get(customerPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"));

        mvc.perform(put(customerPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerRequest(" Augusta ", "Lovelace", "augusta@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.firstName").value("Augusta"))
                .andExpect(jsonPath("$.lastName").value("Lovelace"))
                .andExpect(jsonPath("$.email").value("augusta@example.com"));
        mvc.perform(get(customerPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("augusta@example.com"));

        mvc.perform(delete(customerPath))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(get(customerPath)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateEmailOnCreateRegardlessOfCase() throws Exception {
        createCustomer("Ada@example.com");

        postCustomer("ada@EXAMPLE.COM")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));
        mvc.perform(get(CUSTOMERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("Ada@example.com"));
    }

    @Test
    void preservesExistingCustomerWhenUpdateConflicts() throws Exception {
        createCustomer("ada@example.com");
        String customerPath = createCustomer("grace@example.com");

        mvc.perform(put(customerPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerRequest("Grace", "Hopper", "ADA@example.com"))))
                .andExpect(status().isConflict());
        mvc.perform(get(customerPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.email").value("grace@example.com"));
    }

    @Test
    void returnsNotFoundForMissingCustomer() throws Exception {
        String customerPath = CUSTOMERS + "/" + UUID.randomUUID();

        mvc.perform(get(customerPath))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(put(customerPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CustomerRequest("Ada", "Lovelace", "ada@example.com"))))
                .andExpect(status().isNotFound());
        mvc.perform(delete(customerPath)).andExpect(status().isNotFound());
    }

    private String createCustomer(String email) throws Exception {
        var response = postCustomer(email)
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Lovelace"))
                .andExpect(jsonPath("$.email").value(email))
                .andReturn().getResponse();
        var customer = objectMapper.readValue(response.getContentAsByteArray(), CustomerResponse.class);
        String customerPath = CUSTOMERS + "/" + customer.id();
        assertEquals(customerPath, response.getHeader(HttpHeaders.LOCATION));
        return customerPath;
    }

    private ResultActions postCustomer(String email) throws Exception {
        return mvc.perform(post(CUSTOMERS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CustomerRequest("Ada", "Lovelace", email))));
    }

    private byte[] json(CustomerRequest request) throws Exception {
        return objectMapper.writeValueAsBytes(request);
    }
}
