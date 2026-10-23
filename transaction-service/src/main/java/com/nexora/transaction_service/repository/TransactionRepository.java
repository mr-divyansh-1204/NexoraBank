package com.nexora.transaction_service.repository;

import com.nexora.transaction_service.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByAccountIdOrReceiverAccountId(Long accountId, Long receiverAccountId);
    
    Optional<Transaction> findByTransactionReference(String transactionReference);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

}
