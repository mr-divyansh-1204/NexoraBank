package com.nexora.notificationservice.repository;

import com.nexora.notificationservice.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("update Notification n set n.readFlag = true where n.userId = :userId and n.readFlag = false")
    int markAllReadForUser(@Param("userId") Long userId);

    @Modifying
    @Query("update Notification n set n.readFlag = true where n.id = :id and n.userId = :userId and n.readFlag = false")
    int markOneReadForUser(@Param("id") Long id, @Param("userId") Long userId);
}
