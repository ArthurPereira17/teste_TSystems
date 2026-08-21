package com.tsystems.challenge.orders.service;

import com.tsystems.challenge.orders.config.PricingProperties;
import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;
import com.tsystems.challenge.orders.dto.CreateOrderRequest;
import com.tsystems.challenge.orders.repository.InMemoryOrderRepository;
import com.tsystems.challenge.orders.repository.OrderRepository;
import com.tsystems.challenge.orders.service.pricing.PriceQuote;
import com.tsystems.challenge.orders.service.pricing.PricingRejectedException;
import com.tsystems.challenge.orders.service.pricing.PricingTemporarilyUnavailableException;
import com.tsystems.challenge.orders.testsupport.MutableClock;
import com.tsystems.challenge.orders.testsupport.ScriptedPricingClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceTest {

    private static final CreateOrderRequest REQUEST =
            new CreateOrderRequest("customer-42", "SKU-1001", 2, "DE", "EUR");

    // 5s / 10s / 20s backoff, capped at 60s, max 3 automatic attempts - kept small so tests read easily.
    private static final PricingProperties PROPS = new PricingProperties(
            new PricingProperties.Api("http://localhost:8090", 2000, 3000),
            new PricingProperties.Retry(5000, 3, 5000, 60000)
    );

    @Test
    void confirmsOrderImmediatelyWhenPricingSucceedsOnFirstAttempt() {
        OrderRepository repository = new InMemoryOrderRepository();
        ScriptedPricingClient pricing = new ScriptedPricingClient()
                .thenReturn(new PriceQuote("quote-1", "SKU-1001", "DE", new BigDecimal("19.99"), "EUR"));
        OrderService service = new OrderService(repository, pricing, PROPS, new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        Order order = service.create(REQUEST);

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.unitPrice()).isEqualByComparingTo("19.99");
        assertThat(order.totalPrice()).isEqualByComparingTo("39.98");
        assertThat(order.priceQuoteId()).isEqualTo("quote-1");
        assertThat(order.pricingAttempts()).isEqualTo(1);
        // The order must always have a stable id, independent of pricing outcome.
        assertThat(repository.findById(order.id())).contains(order);
    }

    @Test
    void keepsOrderPendingWithBackoffAfterATemporaryFailure() {
        OrderRepository repository = new InMemoryOrderRepository();
        ScriptedPricingClient pricing = new ScriptedPricingClient()
                .thenThrow(new PricingTemporarilyUnavailableException("provider returned 503"));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OrderService service = new OrderService(repository, pricing, PROPS, clock);

        Order order = service.create(REQUEST);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PRICING);
        assertThat(order.unitPrice()).isNull();
        assertThat(order.pricingAttempts()).isEqualTo(1);
        assertThat(order.pricingFailureReason()).contains("503");
        assertThat(order.nextPricingAttemptAt()).isEqualTo(clock.instant().plusMillis(5000));
        // Same order id is retained - the store never has to resubmit.
        assertThat(repository.findById(order.id())).isPresent();
    }

    @Test
    void marksOrderAsNeedingAttentionImmediatelyOnAPermanentRejection() {
        OrderRepository repository = new InMemoryOrderRepository();
        ScriptedPricingClient pricing = new ScriptedPricingClient()
                .thenThrow(new PricingRejectedException("unknown product SKU-9999"));
        OrderService service = new OrderService(repository, pricing, PROPS, new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        Order order = service.create(REQUEST);

        assertThat(order.status()).isEqualTo(OrderStatus.PRICING_FAILED);
        assertThat(order.pricingAttempts()).isEqualTo(1);
        assertThat(order.pricingFailureReason()).contains("unknown product");
        assertThat(pricing.callCount()).isEqualTo(1); // no point retrying a permanent rejection
    }

    @Test
    void backgroundSchedulerConfirmsAnOrderOnceTheProviderRecovers() {
        OrderRepository repository = new InMemoryOrderRepository();
        ScriptedPricingClient pricing = new ScriptedPricingClient()
                .thenThrow(new PricingTemporarilyUnavailableException("timeout"))
                .thenReturn(new PriceQuote("quote-2", "SKU-1001", "DE", new BigDecimal("19.99"), "EUR"));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        OrderService service = new OrderService(repository, pricing, PROPS, clock);

        Order created = service.create(REQUEST);
        assertThat(created.status()).isEqualTo(OrderStatus.PENDING_PRICING);

        // Scheduler runs before the backoff window elapses: nothing should happen yet.
        int processedTooEarly = service.retryPendingPricing();
        assertThat(processedTooEarly).isZero();
        assertThat(service.get(created.id()).status()).isEqualTo(OrderStatus.PENDING_PRICING);

        // Advance past the 5s backoff and run the scheduler again.
        clock.advance(Duration.ofSeconds(6));
        int processed = service.retryPendingPricing();

        assertThat(processed).isEqualTo(1);
        Order confirmed = service.get(created.id());
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.unitPrice()).isEqualByComparingTo("19.99");
        assertThat(confirmed.priceQuoteId()).isEqualTo("quote-2");
    }

    @Test
    void givesUpAfterMaxAutomaticAttemptsAndMarksTheOrderAsNeedingAttention() {
        OrderRepository repository = new InMemoryOrderRepository();
        ScriptedPricingClient pricing = new ScriptedPricingClient()
                .thenThrow(new PricingTemporarilyUnavailableException("fail-1"))
                .thenThrow(new PricingTemporarilyUnavailableException("fail-2"))
                .thenThrow(new PricingTemporarilyUnavailableException("fail-3"));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        // max-attempts = 3 in PROPS: attempt 1 happens on create(), attempts 2 and 3 via the scheduler.
        OrderService service = new OrderService(repository, pricing, PROPS, clock);

        Order created = service.create(REQUEST);
        assertThat(created.status()).isEqualTo(OrderStatus.PENDING_PRICING);

        clock.advance(Duration.ofSeconds(6));
        service.retryPendingPricing();
        assertThat(service.get(created.id()).status()).isEqualTo(OrderStatus.PENDING_PRICING);

        clock.advance(Duration.ofSeconds(11));
        service.retryPendingPricing();

        Order result = service.get(created.id());
        assertThat(result.status()).isEqualTo(OrderStatus.PRICING_FAILED);
        assertThat(result.pricingAttempts()).isEqualTo(3);
        assertThat(result.pricingFailureReason()).contains("Gave up after 3 attempts");
        assertThat(pricing.callCount()).isEqualTo(3);
    }

    @Test
    void manualRetryCanSucceedEvenAfterAutomaticRetriesWereExhausted() {
        OrderRepository repository = new InMemoryOrderRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

        Order failed = repository.save(Order.accepted(
                        java.util.UUID.randomUUID(), "customer-42", "SKU-1001", 1, "DE", "EUR", clock.instant())
                .withPricingFailed("gave up earlier"));

        // A human explicitly asks to retry a NEEDS_ATTENTION order - the provider is healthy again.
        ScriptedPricingClient recoveredPricing = new ScriptedPricingClient()
                .thenReturn(new PriceQuote("quote-3", "SKU-1001", "DE", new BigDecimal("19.99"), "EUR"));
        OrderService recoveredService = new OrderService(repository, recoveredPricing, PROPS, clock);

        Order result = recoveredService.retryPricingNow(failed.id());

        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.unitPrice()).isEqualByComparingTo("19.99");
    }
}
