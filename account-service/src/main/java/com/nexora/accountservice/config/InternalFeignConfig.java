package com.nexora.accountservice.config;

import feign.Request;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InternalFeignConfig {

    @Bean
    public RequestInterceptor internalKeyInterceptor(@Value("${internal.api-key}") String internalApiKey) {
        return template -> template.header("X-Internal-Key", internalApiKey);
    }

    /**
     * Production-grade timeouts. Without these, Feign defaults to infinite
     * connect/read timeouts — under load, a stuck downstream call can pile up
     * threads and take this service down with it. Also disables the legacy
     * follow-redirects default and keeps body buffering explicit.
     */
    @Bean
    public Request.Options feignRequestOptions(
            @Value("${feign.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${feign.read-timeout-ms:5000}") int readTimeoutMs) {
        return new Request.Options(
                connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS,
                readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS,
                true);
    }
}
