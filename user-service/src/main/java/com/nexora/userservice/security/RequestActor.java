package com.nexora.userservice.security;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestActor {

    private RequestActor() {
    }

    public static Long userId(HttpServletRequest request) {
        String value = request.getHeader("X-User-Id");
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static boolean admin(HttpServletRequest request) {
        return "ADMIN".equalsIgnoreCase(request.getHeader("X-User-Role"));
    }
}
