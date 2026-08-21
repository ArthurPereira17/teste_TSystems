package com.tsystems.challenge.orders.service.pricing;

/**
 * The Pricing API failed in a way that is expected to be transient:
 * network/timeout errors, 5xx responses, or 429 (rate limited).
 * Retrying later is reasonable.
 */
public final class PricingTemporarilyUnavailableException extends PricingException {
    public PricingTemporarilyUnavailableException(String message) {
        super(message);
    }

    public PricingTemporarilyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
