package com.tsystems.challenge.orders.testsupport;

import com.tsystems.challenge.orders.service.pricing.PriceQuote;
import com.tsystems.challenge.orders.service.pricing.PricingClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/** Test double for PricingClient: a queue of scripted responses, one per call, consumed in order. */
public class ScriptedPricingClient implements PricingClient {
    private final Deque<Supplier<PriceQuote>> script = new ArrayDeque<>();
    private int callCount = 0;

    public ScriptedPricingClient thenReturn(PriceQuote quote) {
        script.add(() -> quote);
        return this;
    }

    public ScriptedPricingClient thenThrow(RuntimeException exception) {
        script.add(() -> {
            throw exception;
        });
        return this;
    }

    @Override
    public PriceQuote fetchPrice(String productId, String country, String currency) {
        callCount++;
        if (script.isEmpty()) {
            throw new IllegalStateException("ScriptedPricingClient called more times than scripted");
        }
        return script.poll().get();
    }

    public int callCount() {
        return callCount;
    }
}
