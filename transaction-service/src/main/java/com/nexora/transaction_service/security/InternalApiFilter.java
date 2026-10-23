package com.nexora.transaction_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class InternalApiFilter extends OncePerRequestFilter {

    private final String internalApiKey;

    public InternalApiFilter(@Value("${internal.api-key}") String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (request.getRequestURI().contains("/internal/")) {
            String key = request.getHeader("X-Internal-Key");
            if (key == null || !internalApiKey.equals(key)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":403,\"message\":\"Internal access denied\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
