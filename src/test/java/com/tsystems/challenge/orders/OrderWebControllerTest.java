package com.tsystems.challenge.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * These tests run against the real (auto-configured) Pricing API client, without a
 * running provider. That is intentional: it exercises the same "provider unreachable"
 * path a store would hit during an outage, without any mocking magic hidden in the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rendersTheOrderDashboard() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("orders"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("International Order Service")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Create an order")));
    }

    @Test
    void createsAnOrderFromTheHtmlFormAndGetsAStableIdEvenWhenPricingIsUnavailable() throws Exception {
        String location = mockMvc.perform(post("/ui/orders")
                        .param("customerId", "customer-web")
                        .param("productId", "SKU-1001")
                        .param("quantity", "2")
                        .param("country", "DE")
                        .param("currency", "EUR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/?created=")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        String orderId = extractOrderId(location);

        // No Pricing API is running for this test, so the order is accepted (stable id) but
        // pricing is pending - the dashboard must say so explicitly, not just via color.
        mockMvc.perform(get("/").param("created", orderId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(orderId)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pending pricing")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Retry now")));
    }

    @Test
    void showsValidationErrorsWithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/ui/orders")
                        .param("customerId", "")
                        .param("productId", "SKU-1001")
                        .param("quantity", "0")
                        .param("country", "de")
                        .param("currency", "eur"))
                .andExpect(status().isOk())
                .andExpect(view().name("orders"))
                // Default Jakarta Bean Validation messages don't contain the word "Invalid" -
                // assert on the actual rendered messages instead.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("must not be blank")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("must be greater than or equal to 1")));
    }

    @Test
    void retryActionRedirectsBackToTheDashboard() throws Exception {
        String location = mockMvc.perform(post("/ui/orders")
                        .param("customerId", "customer-retry")
                        .param("productId", "SKU-1002")
                        .param("quantity", "1")
                        .param("country", "DE")
                        .param("currency", "EUR"))
                .andReturn().getResponse().getRedirectedUrl();
        String orderId = extractOrderId(location);

        mockMvc.perform(post("/ui/orders/{id}/retry", orderId))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/?created=" + orderId));
    }

    private static String extractOrderId(String redirectLocation) {
        Matcher matcher = Pattern.compile("created=([0-9a-fA-F-]{36})").matcher(redirectLocation);
        if (!matcher.find()) {
            throw new IllegalStateException("Could not find order id in redirect: " + redirectLocation);
        }
        return matcher.group(1);
    }
}
