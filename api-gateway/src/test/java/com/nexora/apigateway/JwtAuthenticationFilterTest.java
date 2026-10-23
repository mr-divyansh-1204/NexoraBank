package com.nexora.apigateway;

import com.nexora.apigateway.security.JwtAuthenticationFilter;
import com.nexora.apigateway.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the gateway's JWT perimeter: public paths, /internal blocking,
 * identity header injection, and — critically — that client-supplied identity
 * headers can never spoof a user.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "NexoraBankJwtSecretKeyThatIsAtLeast32CharsLong!";

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;
    private GatewayFilterChain chain;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private String validToken(Long userId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject("elena@nexora.com")
                .claim("userId", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000))
                .signWith(key())
                .compact();
    }

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET);
        filter = new JwtAuthenticationFilter(jwtService);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchangeOf(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        return exchange;
    }

    @Test
    void authPathsArePublic() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/login").build();
        MockServerWebExchange exchange = exchangeOf(request);

        filter.filter(exchange, chain).block();

        // The filter must not respond itself: it defers to the downstream chain.
        verify(chain).filter(any(ServerWebExchange.class));
        assertEquals(null, exchange.getResponse().getStatusCode(),
                "no error status may be set for a public path");
    }

    @Test
    void internalPathsAreBlockedAtTheGateway() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/internal/accounts/1")
                .header("X-Internal-Key", "nexora-internal-dev-key")
                .build();
        MockServerWebExchange exchange = exchangeOf(request);

        filter.filter(exchange, chain).block();

        assertEquals(403, exchange.getResponse().getStatusCode().value());
    }

    @Test
    void missingTokenIsUnauthorized() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/accounts").build();
        MockServerWebExchange exchange = exchangeOf(request);

        filter.filter(exchange, chain).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
    }

    @Test
    void tamperedTokenIsUnauthorized() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/accounts")
                .header("Authorization", "Bearer not.a.jwt")
                .build();
        MockServerWebExchange exchange = exchangeOf(request);

        filter.filter(exchange, chain).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
    }

    @Test
    void validTokenInjectsIdentityHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/accounts")
                .header("Authorization", "Bearer " + validToken(42L, "USER"))
                .build();
        MockServerWebExchange exchange = exchangeOf(request);
        AtomicBoolean downstreamRan = new AtomicBoolean(false);

        GatewayFilterChain capturingChain = mock(GatewayFilterChain.class);
        when(capturingChain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange downstream = invocation.getArgument(0);
            assertEquals("42", downstream.getRequest().getHeaders().getFirst("X-User-Id"));
            assertEquals("USER", downstream.getRequest().getHeaders().getFirst("X-User-Role"));
            downstreamRan.set(true);
            return Mono.empty();
        });

        filter.filter(exchange, capturingChain).block();

        assertTrue(downstreamRan.get());
    }

    @Test
    void clientSuppliedIdentityHeadersAreOverwrittenNotTrusted() {
        // A client tries to impersonate user 999 with role ADMIN while presenting
        // a perfectly valid token that belongs to user 42 with role USER.
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/accounts")
                .header("Authorization", "Bearer " + validToken(42L, "USER"))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN")
                .build();
        MockServerWebExchange exchange = exchangeOf(request);

        AtomicBoolean downstreamRan = new AtomicBoolean(false);
        GatewayFilterChain capturingChain = mock(GatewayFilterChain.class);
        when(capturingChain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange downstream = invocation.getArgument(0);
            assertEquals("42", downstream.getRequest().getHeaders().getFirst("X-User-Id"),
                    "spoofed X-User-Id must be replaced by the token identity");
            assertEquals("USER", downstream.getRequest().getHeaders().getFirst("X-User-Role"),
                    "spoofed X-User-Role must be replaced by the token role");
            downstreamRan.set(true);
            return Mono.empty();
        });

        filter.filter(exchange, capturingChain).block();

        assertTrue(downstreamRan.get());
    }

    @Test
    void spoofedHeadersWithoutTokenAreRejected() {
        // No token at all, but fake identity headers: must be 401 and never routed.
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/accounts")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN")
                .build();
        MockServerWebExchange exchange = exchangeOf(request);

        filter.filter(exchange, chain).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }
}
