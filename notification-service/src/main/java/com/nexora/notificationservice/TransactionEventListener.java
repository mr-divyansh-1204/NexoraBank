package com.nexora.notificationservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.notificationservice.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TransactionEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventListener.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public TransactionEventListener(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "transaction-events", groupId = "notification-service")
    public void onTransactionEvent(String payload) {
        log.info("Notification event received: {}", payload);
        try {
            JsonNode node = objectMapper.readTree(payload);
            String type = text(node, "type");
            String status = text(node, "status");
            String amount = text(node, "amount");
            String reference = text(node, "transactionReference");
            Long accountId = longValue(node, "accountId");
            Long receiverAccountId = longValue(node, "receiverAccountId");
            Long userId = longValue(node, "userId");
            Long receiverUserId = longValue(node, "receiverUserId");

            if (userId != null) {
                notificationService.save(
                        userId,
                        accountId,
                        type,
                        "Your " + type + " of " + amount + " is " + status + " (" + reference + ")",
                        reference
                );
            }
            if (receiverUserId != null && !receiverUserId.equals(userId)) {
                notificationService.save(
                        receiverUserId,
                        receiverAccountId,
                        type,
                        "You received " + amount + " via " + type + " (" + reference + ")",
                        reference
                );
            }
        } catch (Exception exception) {
            log.warn("Could not persist notification: {}", exception.getMessage());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asLong();
    }
}
