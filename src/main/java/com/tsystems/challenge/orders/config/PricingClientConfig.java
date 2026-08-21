package com.tsystems.challenge.orders.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PricingProperties.class)
public class PricingClientConfig {

    @Bean
    public RestClient pricingRestClient(PricingProperties properties) {
        // SimpleClientHttpRequestFactory (JDK HttpURLConnection based) is used instead of a
        // Boot-version-specific factory builder API, to keep this stable across Spring Boot
        // minor versions. Fine for the scope of this exercise; a production system would likely
        // pick a connection-pooling client (Apache HttpClient 5 / JDK HttpClient) instead.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.api().connectTimeoutMs());
        requestFactory.setReadTimeout(properties.api().readTimeoutMs());

        return RestClient.builder()
                .baseUrl(properties.api().url())
                .requestFactory(requestFactory)
                .build();
    }
}
