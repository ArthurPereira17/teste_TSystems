package com.tsystems.challenge.orders.service.pricing;

/**
 * The Pricing API rejected the request in a way that will not change on retry:
 * unknown product, malformed request, etc. (4xx other than 429).
 */
public final class PricingRejectedException extends PricingException {
    public PricingRejectedException(String message) {
        super(message);
    }
}
