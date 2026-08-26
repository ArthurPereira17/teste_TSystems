package com.tsystems.challenge.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An order and its pricing lifecycle.
 * <p>
 * The record is intentionally kept immutable (as in the starter code); state
 * transitions are expressed as factory-style {@code with...} methods that
 * return
 * a new instance, which the caller persists via {@code OrderRepository#save}.
 */
public record Order(
                UUID id,
                String customerId,
                String productId,
                int quantity,
                String country,
                String currency,
                BigDecimal unitPrice,
                BigDecimal totalPrice,
                OrderStatus status,
                Instant createdAt,
                int pricingAttempts,
                Instant nextPricingAttemptAt,
                String pricingFailureReason,
                String priceQuoteId,
                Instant pricedAt) {

        /**
         * Creates a freshly accepted order, not yet priced.
         */
        public static Order accepted(UUID id, String customerId, String productId, int quantity,
                        String country, String currency, Instant createdAt) {
                return new Order(
                                id, customerId, productId, quantity, country, currency,
                                null, null, OrderStatus.PENDING_PRICING, createdAt,
                                0, createdAt, null, null, null);
        }

        /** Transition to CONFIRMED after a successful price quote. */
        public Order withConfirmedPrice(BigDecimal unitPrice, String quoteId, Instant pricedAt) {
                BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));
                return new Order(
                                id, customerId, productId, quantity, country, currency,
                                unitPrice, total, OrderStatus.CONFIRMED, createdAt,
                                pricingAttempts + 1, null, null, quoteId, pricedAt);
        }

        /**
         * Transition after a temporary pricing failure: stays PENDING_PRICING, eligible
         * for another attempt later.
         */
        public Order withPricingRetryScheduled(String reason, Instant nextAttemptAt) {
                return new Order(
                                id, customerId, productId, quantity, country, currency,
                                unitPrice, totalPrice, OrderStatus.PENDING_PRICING, createdAt,
                                pricingAttempts + 1, nextAttemptAt, reason, priceQuoteId, pricedAt);
        }

        /**
         * Transition to PRICING_FAILED: either a permanent provider error or retries
         * exhausted.
         */
        public Order withPricingFailed(String reason) {
                return new Order(
                                id, customerId, productId, quantity, country, currency,
                                unitPrice, totalPrice, OrderStatus.PRICING_FAILED, createdAt,
                                pricingAttempts + 1, null, reason, priceQuoteId, pricedAt);
        }
}
