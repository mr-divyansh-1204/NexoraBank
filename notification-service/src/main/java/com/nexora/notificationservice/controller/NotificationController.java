package com.nexora.notificationservice.controller;

import com.nexora.notificationservice.entity.Notification;
import com.nexora.notificationservice.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<Notification> myNotifications(HttpServletRequest request) {
        return notificationService.forUser(requireUserId(request));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(HttpServletRequest request) {
        return Map.of("unread", notificationService.unreadCount(requireUserId(request)));
    }

    @PatchMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(HttpServletRequest request) {
        notificationService.markAllRead(requireUserId(request));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Notification> markRead(@PathVariable("id") Long id, HttpServletRequest request) {
        notificationService.markRead(requireUserId(request), id);
        return ResponseEntity.noContent().build();
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = parseUserId(request.getHeader("X-User-Id"));
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated user is required");
        }
        return userId;
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
