package com.nexora.transaction_service.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.transaction_service.entity.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TransactionEventPublisher {

    public static final String TOPIC = "transaction-events";
    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public TransactionEventPublisher(
            ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public void publish(Transaction transaction, Long userId, Long receiverUserId) {
        if (kafkaTemplate == null) {
            log.debug("Kafka is not configured; skipping event for {}", transaction.getTransactionReference());
            return;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("transactionReference", transaction.getTransactionReference());
            payload.put("accountId", transaction.getAccountId());
            payload.put("receiverAccountId", transaction.getReceiverAccountId());
            payload.put("userId", userId);
            payload.put("receiverUserId", receiverUserId);
            payload.put("amount", transaction.getAmount());
            payload.put("type", transaction.getType().name());
            payload.put("status", transaction.getStatus().name());
            payload.put("transactionDate", transaction.getTransactionDate().toString());

            kafkaTemplate.send(TOPIC, transaction.getTransactionReference(), objectMapper.writeValueAsString(payload));
        } catch (Exception exception) {
            log.warn("Failed to publish transaction event {}: {}",
                    transaction.getTransactionReference(), exception.getMessage());
        }
    }
}
