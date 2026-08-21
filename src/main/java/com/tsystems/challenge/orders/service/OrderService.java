package com.tsystems.challenge.orders.service;

import com.tsystems.challenge.orders.config.PricingProperties;
import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;
import com.tsystems.challenge.orders.dto.CreateOrderRequest;
import com.tsystems.challenge.orders.repository.OrderRepository;
import com.tsystems.challenge.orders.service.pricing.PriceQuote;
import com.tsystems.challenge.orders.service.pricing.PricingClient;
import com.tsystems.challenge.orders.service.pricing.PricingRejectedException;
import com.tsystems.challenge.orders.service.pricing.PricingTemporarilyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Order creation and pricing lifecycle (CR-002).
 * <p>
 * An order always gets a stable id and is persisted before pricing is attempted.
 * The first pricing attempt happens synchronously during {@link #create}, so the
 * common case (provider healthy) confirms the order in the same request. If that
 * attempt fails, the order is stored as {@link OrderStatus#PENDING_PRICING} and
 * left for the background scheduler ({@code PricingRetryScheduler}) or an explicit
 * {@link #retryPricingNow(UUID)} call to resolve later - the store never has to
 * resubmit the order.
 */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final PricingClient pricingClient;
    private final PricingProperties pricingProperties;
    private final Clock clock;

    @Autowired
    public OrderService(OrderRepository orderRepository, PricingClient pricingClient,
                         PricingProperties pricingProperties) {
        this(orderRepository, pricingClient, pricingProperties, Clock.systemUTC());
    }

    OrderService(OrderRepository orderRepository, PricingClient pricingClient,
                 PricingProperties pricingProperties, Clock clock) {
        this.orderRepository = orderRepository;
        this.pricingClient = pricingClient;
        this.pricingProperties = pricingProperties;
        this.clock = clock;
    }

    public Order create(CreateOrderRequest request) {
        Instant now = Instant.now(clock);
        Order order = Order.accepted(
                UUID.randomUUID(),
                request.customerId(),
                request.productId(),
                request.quantity(),
                request.country(),
                request.currency(),
                now
        );
        // Persist immediately: the store gets a stable id even if pricing never succeeds.
        order = orderRepository.save(order);

        Order priced = attemptPricing(order, false);
        return orderRepository.save(priced);
    }

    public Order get(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> list() {
        return orderRepository.findAll();
    }

    /**
     * Explicit, user-triggered retry (support/store action from the dashboard or API).
     * Allowed for any order that is not yet CONFIRMED; unlike the automatic scheduler,
     * it is not blocked by a backoff timer or the max-attempts cap, since a human
     * deliberately asked for it.
     */
    public Order retryPricingNow(UUID id) {
        Order order = get(id);
        if (order.status() == OrderStatus.CONFIRMED) {
            return order;
        }
        Order result = attemptPricing(order, true);
        return orderRepository.save(result);
    }

    /**
     * Called periodically by the scheduler. Retries every PENDING_PRICING order whose
     * backoff window has elapsed. Returns how many orders were processed, for logging.
     */
    public int retryPendingPricing() {
        Instant now = Instant.now(clock);
        List<Order> due = orderRepository.findByStatus(OrderStatus.PENDING_PRICING).stream()
                .filter(order -> order.nextPricingAttemptAt() != null
                        && !order.nextPricingAttemptAt().isAfter(now))
                .toList();

        for (Order order : due) {
            orderRepository.save(attemptPricing(order, false));
        }
        return due.size();
    }

    private Order attemptPricing(Order order, boolean manual) {
        try {
            PriceQuote quote = pricingClient.fetchPrice(order.productId(), order.country(), order.currency());
            return order.withConfirmedPrice(quote.amount(), quote.quoteId(), Instant.now(clock));
        } catch (PricingRejectedException ex) {
            log.info("Pricing permanently rejected for order {}: {}", order.id(), ex.getMessage());
            return order.withPricingFailed(ex.getMessage());
        } catch (PricingTemporarilyUnavailableException ex) {
            int attemptsSoFar = order.pricingAttempts() + 1;
            int maxAttempts = pricingProperties.retry().maxAttempts();
            if (!manual && attemptsSoFar >= maxAttempts) {
                log.info("Order {} exhausted {} pricing attempts, marking as needing attention",
                        order.id(), attemptsSoFar);
                return order.withPricingFailed(
                        "Gave up after " + attemptsSoFar + " attempts: " + ex.getMessage());
            }
            Instant nextAttempt = Instant.now(clock).plusMillis(backoffMillis(attemptsSoFar));
            log.info("Order {} pricing attempt {} failed temporarily, next attempt at {}: {}",
                    order.id(), attemptsSoFar, nextAttempt, ex.getMessage());
            return order.withPricingRetryScheduled(ex.getMessage(), nextAttempt);
        }
    }

    private long backoffMillis(int attemptNumber) {
        long initial = pricingProperties.retry().initialBackoffMs();
        long cap = pricingProperties.retry().maxBackoffMs();
        long exponential = initial * (1L << Math.min(attemptNumber - 1, 20));
        return Math.min(exponential, cap);
    }
}
