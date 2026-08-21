package com.tsystems.challenge.orders.service.pricing;

/** Base type for all failures returned while contacting the Pricing API. */
public sealed class PricingException extends RuntimeException
        permits PricingTemporarilyUnavailableException, PricingRejectedException {

    public PricingException(String message) {
        super(message);
    }

    public PricingException(String message, Throwable cause) {
        super(message, cause);
    }
}
