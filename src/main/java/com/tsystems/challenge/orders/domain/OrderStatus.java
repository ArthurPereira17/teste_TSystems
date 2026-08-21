package com.tsystems.challenge.orders.domain;

/**
 * Lifecycle of an order with respect to pricing.
 * <p>
 * An order is created with a stable id before a price is known (CR-002, item 2).
 * It only becomes {@link #CONFIRMED} once a real price was obtained from the
 * external Pricing API (CR-002, item 7: a successful HTTP response from our own
 * application must never be confused with a successful pricing outcome).
 */
public enum OrderStatus {
    /** Order accepted, price not yet obtained. May still be retried automatically or manually. */
    PENDING_PRICING,
    /** Price successfully obtained from the Pricing API. */
    CONFIRMED,
    /** Pricing did not succeed and the order needs human attention (permanent error or retries exhausted). */
    PRICING_FAILED
}
