package com.tsystems.challenge.orders.service.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;

/**
 * Talks to the external Pricing API described in {@code docs/pricing-api.openapi.yaml}.
 * <p>
 * The provider is treated as a black box: only its documented, observable HTTP behavior
 * (status codes, timeouts) is used to decide whether a failure is worth retrying.
 */
@Component
public class RestClientPricingClient implements PricingClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientPricingClient.class);

    private final RestClient restClient;

    public RestClientPricingClient(RestClient pricingRestClient) {
        this.restClient = pricingRestClient;
    }

    @Override
    public PriceQuote fetchPrice(String productId, String country, String currency) {
        try {
            PriceQuoteResponse response = restClient.get()
                    .uri("/v1/prices/{productId}?country={country}&currency={currency}",
                            productId, country, currency)
                    .retrieve()
                    .body(PriceQuoteResponse.class);

            if (response == null || response.amount() == null) {
                // The provider answered 2xx but with a body we cannot use. Treat as transient:
                // it is not something a store user caused, and a retry may hit a healthy instance.
                throw new PricingTemporarilyUnavailableException(
                        "Pricing API returned an empty or incomplete quote for product " + productId);
            }

            return new PriceQuote(
                    response.quoteId(),
                    response.productId(),
                    response.country(),
                    new BigDecimal(response.amount()),
                    response.currency()
            );
        } catch (HttpClientErrorException.TooManyRequests ex) {
            // 429 is a client error by HTTP category, but it signals the caller should slow down
            // and try again later, not that the request itself is invalid.
            throw new PricingTemporarilyUnavailableException(
                    "Pricing API is rate limiting requests (429)", ex);
        } catch (HttpClientErrorException ex) {
            // 400 (malformed request), 404 (unknown product), etc: retrying the same input will
            // not produce a different outcome.
            throw new PricingRejectedException(
                    "Pricing API rejected the request (" + ex.getStatusCode().value() + "): "
                            + safeBody(ex));
        } catch (HttpServerErrorException ex) {
            throw new PricingTemporarilyUnavailableException(
                    "Pricing API returned a server error (" + ex.getStatusCode().value() + ")", ex);
        } catch (ResourceAccessException ex) {
            // Connection refused, timeout, DNS failure, etc.
            throw new PricingTemporarilyUnavailableException(
                    "Pricing API was not reachable: " + ex.getMostSpecificCause().getMessage(), ex);
        } catch (RestClientException ex) {
            // Anything else unexpected (e.g. unparsable body): default to retryable rather than
            // permanently failing an order because of a provider quirk we did not anticipate.
            log.warn("Unexpected error calling Pricing API for product {}: {}", productId, ex.getMessage());
            throw new PricingTemporarilyUnavailableException(
                    "Unexpected error calling Pricing API: " + ex.getMessage(), ex);
        }
    }

    private static String safeBody(HttpClientErrorException ex) {
        String body = ex.getResponseBodyAsString();
        return (body == null || body.isBlank()) ? ex.getStatusText() : body;
    }

    /** Wire-format mirror of the provider's PriceQuote schema. */
    private record PriceQuoteResponse(
            String quoteId,
            String productId,
            String country,
            String amount,
            String currency,
            String validUntil
    ) {
    }
}
