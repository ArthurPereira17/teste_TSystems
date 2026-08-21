package com.tsystems.challenge.orders.service.pricing;

/** Abstraction over the external Pricing API so the rest of the app does not depend on the HTTP client used. */
public interface PricingClient {

    /**
     * Requests a price quote.
     *
     * @throws PricingTemporarilyUnavailableException on transient failures (timeout, 5xx, 429)
     * @throws PricingRejectedException                on permanent failures (4xx other than 429)
     */
    PriceQuote fetchPrice(String productId, String country, String currency);
}
