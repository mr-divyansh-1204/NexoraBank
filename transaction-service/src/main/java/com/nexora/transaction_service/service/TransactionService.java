package com.nexora.transaction_service.service;

import com.nexora.transaction_service.client.AccountClient;
import com.nexora.transaction_service.dto.AccountResponse;
import com.nexora.transaction_service.dto.TransactionRequest;
import com.nexora.transaction_service.dto.TransactionResponse;
import com.nexora.transaction_service.dto.TransferRequest;
import com.nexora.transaction_service.entity.Transaction;
import com.nexora.transaction_service.entity.TransactionStatus;
import com.nexora.transaction_service.entity.TransactionType;
import com.nexora.transaction_service.exception.ConcurrencyException;
import com.nexora.transaction_service.exception.ForbiddenException;
import com.nexora.transaction_service.exception.InsufficientBalanceException;
import com.nexora.transaction_service.kafka.TransactionEventPublisher;
import com.nexora.transaction_service.repository.TransactionRepository;
import feign.FeignException;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountClient accountClient;
    private final TransactionEventPublisher eventPublisher;
    private final TransactionFailureRecorder failureRecorder;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountClient accountClient,
            TransactionEventPublisher eventPublisher,
            TransactionFailureRecorder failureRecorder) {
        this.transactionRepository = transactionRepository;
        this.accountClient = accountClient;
        this.eventPublisher = eventPublisher;
        this.failureRecorder = failureRecorder;
    }

    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request, Long actorUserId, boolean admin) {
        Optional<Transaction> existingTransaction =
                transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existingTransaction.isPresent()) {
            return mapToResponse(existingTransaction.get());
        }

        AccountResponse account = accountClient.getAccountById(request.getAccountId());
        assertAccountOwner(account, actorUserId, admin);

        Transaction transaction = new Transaction();
        transaction.setTransactionReference("TXN-" + UUID.randomUUID());
        transaction.setIdempotencyKey(request.getIdempotencyKey());
        transaction.setAccountId(request.getAccountId());
        transaction.setReceiverAccountId(request.getReceiverAccountId());
        transaction.setAmount(request.getAmount());
        transaction.setType(request.getType());
        transaction.setTransactionDate(LocalDateTime.now());

        try {
            BigDecimal newBalance;
            if (request.getType() == TransactionType.DEPOSIT) {
                newBalance = account.getBalance().add(request.getAmount());
            } else if (request.getType() == TransactionType.WITHDRAWAL) {
                if (account.getBalance().compareTo(request.getAmount()) < 0) {
                    throw new InsufficientBalanceException(
                            "Insufficient balance. Available balance is "
                                    + account.getBalance()
                                    + " but requested amount is "
                                    + request.getAmount()
                    );
                }
                newBalance = account.getBalance().subtract(request.getAmount());
            } else {
                throw new IllegalArgumentException(
                        "Unsupported transaction type. Use DEPOSIT, WITHDRAWAL, or /transactions/transfer");
            }

            try {
                accountClient.updateBalance(account.getId(), newBalance, account.getVersion());
            } catch (FeignException.Conflict exception) {
                throw new ConcurrencyException("Account was updated by another request. Please retry.");
            }

            transaction.setStatus(TransactionStatus.SUCCESS);
            Transaction savedTransaction = saveIdempotent(transaction);
            eventPublisher.publish(savedTransaction, account.getUserId(), null);
            return mapToResponse(savedTransaction);
        } catch (ConcurrencyException | InsufficientBalanceException | ForbiddenException | IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(exception.getMessage());
            Transaction failedTransaction = saveIdempotent(transaction);
            return mapToResponse(failedTransaction);
        }
    }

    @Transactional
    public TransactionResponse transfer(TransferRequest request, Long actorUserId, boolean admin) {
        Optional<Transaction> existingTransaction =
                transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existingTransaction.isPresent()) {
            return mapToResponse(existingTransaction.get());
        }

        AccountResponse sender = accountClient.getAccountById(request.getSenderAccountId());
        AccountResponse receiver = accountClient.getAccountById(request.getReceiverAccountId());
        assertAccountOwner(sender, actorUserId, admin);

        if (sender.getId().equals(receiver.getId())) {
            throw new IllegalArgumentException("Sender and receiver accounts cannot be the same");
        }

        if (sender.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException(
                    "Insufficient balance. Available balance is "
                            + sender.getBalance()
                            + " but requested amount is "
                            + request.getAmount()
            );
        }

        BigDecimal senderNewBalance = sender.getBalance().subtract(request.getAmount());
        try {
            accountClient.updateBalance(sender.getId(), senderNewBalance, sender.getVersion());
        } catch (FeignException.Conflict exception) {
            throw new ConcurrencyException("Sender account was updated by another request. Please retry.");
        }

        try {
            AccountResponse latestReceiver = accountClient.getAccountById(request.getReceiverAccountId());
            accountClient.updateBalance(
                    latestReceiver.getId(),
                    latestReceiver.getBalance().add(request.getAmount()),
                    latestReceiver.getVersion()
            );
        } catch (Exception creditFailure) {
            String reason;
            try {
                compensateSender(sender.getId(), request.getAmount());
                reason = "Transfer failed while crediting receiver; sender debit was reversed: "
                        + creditFailure.getMessage();
            } catch (RuntimeException compensationFailure) {
                reason = "Transfer failed while crediting receiver; compensation also failed: "
                        + compensationFailure.getMessage();
                failureRecorder.recordFailedTransfer(
                        request.getIdempotencyKey(),
                        sender.getId(),
                        receiver.getId(),
                        request.getAmount(),
                        reason);
                throw new IllegalStateException(
                        "Transfer failed while crediting receiver, and reversing the sender debit also failed. "
                                + compensationFailure.getMessage(),
                        creditFailure);
            }
            failureRecorder.recordFailedTransfer(
                    request.getIdempotencyKey(),
                    sender.getId(),
                    receiver.getId(),
                    request.getAmount(),
                    reason);
            throw new IllegalStateException(
                    "Transfer failed while crediting receiver. Sender debit was reversed. "
                            + creditFailure.getMessage()
            );
        }

        Transaction transaction = new Transaction();
        transaction.setTransactionReference("TXN-" + UUID.randomUUID());
        transaction.setIdempotencyKey(request.getIdempotencyKey());
        transaction.setAccountId(sender.getId());
        transaction.setReceiverAccountId(receiver.getId());
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.SUCCESS);
        transaction.setTransactionDate(LocalDateTime.now());

        Transaction savedTransaction = saveIdempotent(transaction);
        eventPublisher.publish(savedTransaction, sender.getUserId(), receiver.getUserId());
        return mapToResponse(savedTransaction);
    }

    public List<TransactionResponse> getAllTransactions(Long actorUserId, boolean admin) {
        if (admin) {
            return transactionRepository.findAll().stream().map(this::mapToResponse).toList();
        }
        throw new ForbiddenException("Admin role is required to list all transactions");
    }

    public List<TransactionResponse> getTransactionsByAccountId(Long accountId, Long actorUserId, boolean admin) {
        AccountResponse account = accountClient.getAccountById(accountId);
        assertAccountOwner(account, actorUserId, admin);
        return transactionRepository
                .findByAccountIdOrReceiverAccountId(accountId, accountId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    private void assertAccountOwner(AccountResponse account, Long actorUserId, boolean admin) {
        if (admin || actorUserId == null) {
            return;
        }
        if (!actorUserId.equals(account.getUserId())) {
            throw new ForbiddenException("You can only transact on your own account");
        }
    }

    private Transaction saveIdempotent(Transaction transaction) {
        try {
            return transactionRepository.save(transaction);
        } catch (DataIntegrityViolationException exception) {
            return transactionRepository.findByIdempotencyKey(transaction.getIdempotencyKey())
                    .orElseThrow(() -> exception);
        }
    }

    private void compensateSender(Long senderAccountId, BigDecimal amount) {
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                AccountResponse latestSender = accountClient.getAccountById(senderAccountId);
                accountClient.updateBalance(
                        latestSender.getId(),
                        latestSender.getBalance().add(amount),
                        latestSender.getVersion()
                );
                return;
            } catch (RuntimeException exception) {
                lastError = exception;
                try {
                    Thread.sleep(150L * (attempt + 1));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Compensation interrupted", interruptedException);
                }
            }
        }
        throw new IllegalStateException("Could not reverse sender debit after credit failure", lastError);
    }

    private TransactionResponse mapToResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getTransactionReference(),
                transaction.getAccountId(),
                transaction.getReceiverAccountId(),
                transaction.getAmount(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getTransactionDate(),
                transaction.getFailureReason()
        );
    }
}
