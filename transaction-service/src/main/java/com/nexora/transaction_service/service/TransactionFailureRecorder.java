package com.nexora.transaction_service.service;

import com.nexora.transaction_service.entity.Transaction;
import com.nexora.transaction_service.entity.TransactionStatus;
import com.nexora.transaction_service.entity.TransactionType;
import com.nexora.transaction_service.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persists an audit row for failed transfers. Runs in its own transaction
 * (REQUIRES_NEW) so the record survives the rollback of the surrounding
 * failed transfer transaction. Audit is best-effort: it must never mask the
 * original business failure.
 */
@Component
public class TransactionFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(TransactionFailureRecorder.class);

    private final TransactionRepository transactionRepository;

    public TransactionFailureRecorder(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedTransfer(
            String idempotencyKey,
            Long senderAccountId,
            Long receiverAccountId,
            BigDecimal amount,
            String reason) {
        try {
            Transaction transaction = new Transaction();
            transaction.setTransactionReference("TXN-" + UUID.randomUUID());
            transaction.setIdempotencyKey(idempotencyKey);
            transaction.setAccountId(senderAccountId);
            transaction.setReceiverAccountId(receiverAccountId);
            transaction.setAmount(amount);
            transaction.setType(TransactionType.TRANSFER);
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setTransactionDate(LocalDateTime.now());
            transaction.setFailureReason(reason);
            transactionRepository.save(transaction);
        } catch (Exception exception) {
            log.warn("Could not record failed transfer for key {}: {}",
                    idempotencyKey, exception.getMessage());
        }
    }
}
