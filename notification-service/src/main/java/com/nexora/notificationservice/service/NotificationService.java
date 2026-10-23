package com.nexora.notificationservice.service;

import com.nexora.notificationservice.entity.Notification;
import com.nexora.notificationservice.exception.NotificationNotFoundException;
import com.nexora.notificationservice.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public Notification save(Long userId, Long accountId, String type, String message, String reference) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setAccountId(accountId);
        notification.setType(type);
        notification.setMessage(message);
        notification.setTransactionReference(reference);
        notification.setCreatedAt(LocalDateTime.now());
        return notificationRepository.save(notification);
    }

    public List<Notification> forUser(Long userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public long unreadCount(Long userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(notification -> !notification.isReadFlag())
                .count();
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllReadForUser(userId);
    }

    @Transactional
    public void markRead(Long userId, Long id) {
        int updated = notificationRepository.markOneReadForUser(id, userId);
        if (updated == 0) {
            throw new NotificationNotFoundException(
                    "Notification " + id + " not found for user " + userId);
        }
    }
}
