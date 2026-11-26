package com.portfolio.commerce.inventory.api;

import java.util.UUID;

import com.portfolio.commerce.inventory.application.ReservationService;
import com.portfolio.commerce.inventory.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationConcurrencyResponseTest {

    @Test
    void exposesTheMachineReadableConflictCode() throws Exception {
        UUID id = UUID.randomUUID();
        ReservationService reservations = mock(ReservationService.class);
        when(reservations.confirm(id)).thenThrow(new OptimisticLockingFailureException("Stale reservation"));
        var mvc = MockMvcBuilders.standaloneSetup(new ReservationController(reservations))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/reservations/{id}/confirm", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent modification"))
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));
    }
}
