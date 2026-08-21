package com.tsystems.challenge.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the external Pricing API integration (connection + retry policy). */
@ConfigurationProperties(prefix = "pricing")
public record PricingProperties(
        Api api,
        Retry retry
) {
    public PricingProperties {
        if (api == null) {
            api = new Api("http://localhost:8090", 2000, 3000);
        }
        if (retry == null) {
            retry = new Retry(5000, 5, 5000, 60000);
        }
    }

    public record Api(String url, int connectTimeoutMs, int readTimeoutMs) {
        public Api {
            if (connectTimeoutMs <= 0) connectTimeoutMs = 2000;
            if (readTimeoutMs <= 0) readTimeoutMs = 3000;
        }
    }

    /**
     * @param schedulerIntervalMs how often the background scheduler scans for orders to retry
     * @param maxAttempts         maximum number of automatic pricing attempts before giving up
     * @param initialBackoffMs    delay before the first automatic retry
     * @param maxBackoffMs        cap for the exponential backoff between retries
     */
    public record Retry(long schedulerIntervalMs, int maxAttempts, long initialBackoffMs, long maxBackoffMs) {
        public Retry {
            if (schedulerIntervalMs <= 0) schedulerIntervalMs = 5000;
            if (maxAttempts <= 0) maxAttempts = 5;
            if (initialBackoffMs <= 0) initialBackoffMs = 5000;
            if (maxBackoffMs <= 0) maxBackoffMs = 60000;
        }
    }
}
