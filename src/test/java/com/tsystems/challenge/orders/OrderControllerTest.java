package com.tsystems.challenge.orders;

import com.tsystems.challenge.orders.service.pricing.PriceQuote;
import com.tsystems.challenge.orders.service.pricing.PricingClient;
import com.tsystems.challenge.orders.service.pricing.PricingTemporarilyUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PricingClient is mocked here so these tests are deterministic regardless of whether the real
 * Pricing API container happens to be running (it responds inconsistently by design, since it is
 * the whole point of CR-002) - see OrderServiceTest for the unit-level coverage of the actual
 * retry/backoff/classification logic against scripted provider behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PricingClient pricingClient;

    @Test
    void creatingAnOrderReturns201WithAStableIdEvenWhenPricingIsPending() throws Exception {
        when(pricingClient.fetchPrice(any(), any(), any()))
                .thenThrow(new PricingTemporarilyUnavailableException("provider unreachable"));

        String body = """
                {"customerId":"customer-42","productId":"SKU-1001","quantity":2,"country":"DE","currency":"EUR"}
                """;

        String orderId = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                // 201 means "accepted", not "priced": status reflects the real pricing outcome.
                .andExpect(jsonPath("$.status").value("PENDING_PRICING"))
                .andExpect(jsonPath("$.unitPrice").doesNotExist())
                .andExpect(jsonPath("$.pricingAttempts").value(1))
                .andReturn().getResponse().getContentAsString();

        // GET must return the exact same order, addressable by the id returned on creation.
        String id = orderId.replaceAll(".*\"id\":\"([0-9a-fA-F-]{36})\".*", "$1");
        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PRICING"));
    }

    @Test
    void retryPricingEndpointReturnsTheUpdatedOrder() throws Exception {
        when(pricingClient.fetchPrice(any(), any(), any()))
                .thenThrow(new PricingTemporarilyUnavailableException("provider unreachable"))
                .thenReturn(new PriceQuote("quote-1", "SKU-1002", "DE", new BigDecimal("9.99"), "EUR"));

        String body = """
                {"customerId":"customer-42","productId":"SKU-1002","quantity":1,"country":"DE","currency":"EUR"}
                """;
        String created = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":\"([0-9a-fA-F-]{36})\".*", "$1");

        mockMvc.perform(post("/api/orders/{id}/retry-pricing", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.pricingAttempts").value(2));
    }

    @Test
    void gettingAnUnknownOrderReturns404() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}