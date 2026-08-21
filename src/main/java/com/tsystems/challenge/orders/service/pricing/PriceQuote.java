package com.tsystems.challenge.orders.service.pricing;

import java.math.BigDecimal;

/** A price quote returned by the external Pricing API. */
public record PriceQuote(
        String quoteId,
        String productId,
        String country,
        BigDecimal amount,
        String currency
) {
}
