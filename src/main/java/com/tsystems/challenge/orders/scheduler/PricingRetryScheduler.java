package com.tsystems.challenge.orders.scheduler;

import com.tsystems.challenge.orders.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically retries pricing for orders stuck in PENDING_PRICING because of a
 * temporary Pricing API failure, so a store never has to resubmit an order to get
 * it priced (CR-002, item 1).
 * <p>
 * A fixed-delay scheduler is intentionally simple for the scope of this exercise;
 * see DECISIONS.md for what a production system would use instead (a durable job
 * queue instead of an in-JVM timer).
 */
@Component
public class PricingRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PricingRetryScheduler.class);

    private final OrderService orderService;

    public PricingRetryScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${pricing.retry.scheduler-interval-ms:5000}")
    public void retryPendingOrders() {
        int processed = orderService.retryPendingPricing();
        if (processed > 0) {
            log.debug("Pricing retry scheduler processed {} order(s)", processed);
        }
    }
}
