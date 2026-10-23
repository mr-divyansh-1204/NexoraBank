package com.nexora.transaction_service;

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
import com.nexora.transaction_service.service.TransactionService;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionServiceTest {

    private TransactionRepository transactionRepository;
    private AccountClient accountClient;
    private TransactionEventPublisher eventPublisher;
    private com.nexora.transaction_service.service.TransactionFailureRecorder failureRecorder;
    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountClient = mock(AccountClient.class);
        eventPublisher = mock(TransactionEventPublisher.class);
        failureRecorder = mock(com.nexora.transaction_service.service.TransactionFailureRecorder.class);
        transactionService = new TransactionService(transactionRepository, accountClient, eventPublisher, failureRecorder);
    }

    private AccountResponse account(Long id, Long userId, String balance, Long version) {
        return new AccountResponse(id, "NX100000000" + id, "Holder " + id,
                new BigDecimal(balance), userId, version);
    }

    private TransactionRequest request(Long accountId, String amount, TransactionType type, String key) {
        TransactionRequest request = new TransactionRequest();
        request.setAccountId(accountId);
        request.setAmount(new BigDecimal(amount));
        request.setType(type);
        request.setIdempotencyKey(key);
        return request;
    }

    private void stubSaveReturningOriginal() {
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void depositAddsAmountToBalance() {
        when(accountClient.getAccountById(1L))
                .thenReturn(account(1L, 42L, "1000.00", 0L));
        stubSaveReturningOriginal();

        TransactionResponse response = transactionService.createTransaction(
                request(1L, "250.50", TransactionType.DEPOSIT, "key-deposit-1"), 42L, false);

        assertEquals(TransactionStatus.SUCCESS, response.getStatus());
        verify(accountClient).updateBalance(eq(1L),
                eq(new BigDecimal("1250.50")), eq(0L));
        verify(eventPublisher).publish(any(Transaction.class), eq(42L), eq(null));
    }

    @Test
    void withdrawalSubtractsAmountFromBalance() {
        when(accountClient.getAccountById(1L))
                .thenReturn(account(1L, 42L, "1000.00", 3L));
        stubSaveReturningOriginal();

        TransactionResponse response = transactionService.createTransaction(
                request(1L, "400.00", TransactionType.WITHDRAWAL, "key-wd-1"), 42L, false);

        assertEquals(TransactionStatus.SUCCESS, response.getStatus());
        verify(accountClient).updateBalance(eq(1L),
                eq(new BigDecimal("600.00")), eq(3L));
    }

    @Test
    void withdrawalBeyondBalanceIsRejectedBeforeAnyUpdate() {
        when(accountClient.getAccountById(1L))
                .thenReturn(account(1L, 42L, "100.00", 0L));

        assertThrows(InsufficientBalanceException.class,
                () -> transactionService.createTransaction(
                        request(1L, "500.00", TransactionType.WITHDRAWAL, "key-wd-2"), 42L, false));

        verify(accountClient, never()).updateBalance(anyLong(), any(), anyLong());
    }

    @Test
    void userCannotTransactOnSomeoneElsesAccount() {
        when(accountClient.getAccountById(1L))
                .thenReturn(account(1L, 42L, "100.00", 0L));

        assertThrows(ForbiddenException.class,
                () -> transactionService.createTransaction(
                        request(1L, "10.00", TransactionType.DEPOSIT, "key-fx"), 99L, false));
    }

    @Test
    void idempotencyKeyReplayReturnsOriginalResultWithoutSideEffects() {
        Transaction existing = new Transaction();
        existing.setId(11L);
        existing.setTransactionReference("TXN-original");
        existing.setAccountId(1L);
        existing.setAmount(new BigDecimal("250.50"));
        existing.setType(TransactionType.DEPOSIT);
        existing.setStatus(TransactionStatus.SUCCESS);
        existing.setTransactionDate(LocalDateTime.now().minusMinutes(5));
        existing.setIdempotencyKey("key-deposit-1");

        when(transactionRepository.findByIdempotencyKey("key-deposit-1"))
                .thenReturn(Optional.of(existing));

        TransactionResponse response = transactionService.createTransaction(
                request(1L, "250.50", TransactionType.DEPOSIT, "key-deposit-1"), 42L, false);

        assertEquals("TXN-original", response.getTransactionReference());
        verify(accountClient, never()).getAccountById(anyLong());
        verify(accountClient, never()).updateBalance(anyLong(), any(), anyLong());
    }

    @Test
    void optimisticLockConflictBecomesConcurrencyException() {
        when(accountClient.getAccountById(1L))
                .thenReturn(account(1L, 42L, "1000.00", 0L));
        stubSaveReturningOriginal();
        when(accountClient.updateBalance(anyLong(), any(), anyLong()))
                .thenThrow(mock(FeignException.Conflict.class));

        assertThrows(ConcurrencyException.class,
                () -> transactionService.createTransaction(
                        request(1L, "100.00", TransactionType.DEPOSIT, "key-cf"), 42L, false));
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {
        when(accountClient.getAccountById(10L)).thenReturn(account(10L, 42L, "1000.00", 5L));
        when(accountClient.getAccountById(20L)).thenReturn(account(20L, 77L, "500.00", 2L));
        stubSaveReturningOriginal();

        TransferRequest transfer = new TransferRequest(10L, 20L, new BigDecimal("300.00"), "key-tr-1");
        TransactionResponse response = transactionService.transfer(transfer, 42L, false);

        assertEquals(TransactionStatus.SUCCESS, response.getStatus());
        verify(accountClient).updateBalance(eq(10L), eq(new BigDecimal("700.00")), eq(5L));
        verify(accountClient).updateBalance(eq(20L), eq(new BigDecimal("800.00")), eq(2L));
        verify(eventPublisher).publish(any(Transaction.class), eq(42L), eq(77L));
    }

    @Test
    void transferRejectsSameSenderAndReceiver() {
        when(accountClient.getAccountById(10L)).thenReturn(account(10L, 42L, "1000.00", 5L));

        assertThrows(IllegalArgumentException.class,
                () -> transactionService.transfer(
                        new TransferRequest(10L, 10L, new BigDecimal("100.00"), "key-same"), 42L, false));
        verify(accountClient, never()).updateBalance(anyLong(), any(), anyLong());
    }

    @Test
    void transferCompensatesSenderWhenReceiverCreditFails() {
        when(accountClient.getAccountById(10L)).thenReturn(account(10L, 42L, "1000.00", 5L));
        when(accountClient.getAccountById(20L)).thenReturn(account(20L, 77L, "500.00", 2L));
        stubSaveReturningOriginal();
        when(accountClient.updateBalance(eq(10L), any(), anyLong())).thenReturn(null);
        when(accountClient.updateBalance(eq(20L), any(), anyLong()))
                .thenThrow(mock(FeignException.ServiceUnavailable.class));

        assertThrows(IllegalStateException.class,
                () -> transactionService.transfer(
                        new TransferRequest(10L, 20L, new BigDecimal("300.00"), "key-tr-2"), 42L, false));

        // The sender must have been re-credited exactly once (compensation)...
        verify(accountClient).updateBalance(eq(10L), eq(new BigDecimal("1300.00")), anyLong());
        // ...a failure audit row must have been recorded...
        verify(failureRecorder).recordFailedTransfer(eq("key-tr-2"), eq(10L), eq(20L),
                eq(new BigDecimal("300.00")), org.mockito.ArgumentMatchers.contains("reversed"));
        // ...and no success event may be published for a failed transfer.
        verify(eventPublisher, never()).publish(any(Transaction.class), anyLong(), anyLong());
    }

    @Test
    void transferCompensationRetriesAreCappedAtThreeAttempts() {
        when(accountClient.getAccountById(10L))
                .thenReturn(account(10L, 42L, "1000.00", 5L))
                .thenThrow(mock(FeignException.ServiceUnavailable.class));
        when(accountClient.getAccountById(20L)).thenReturn(account(20L, 77L, "500.00", 2L));
        stubSaveReturningOriginal();
        when(accountClient.updateBalance(eq(10L), any(), anyLong())).thenReturn(null);
        when(accountClient.updateBalance(eq(20L), any(), anyLong()))
                .thenThrow(mock(FeignException.ServiceUnavailable.class));

        assertThrows(IllegalStateException.class,
                () -> transactionService.transfer(
                        new TransferRequest(10L, 20L, new BigDecimal("300.00"), "key-tr-3"), 42L, false));

        // 1 initial sender read + 3 compensation re-read attempts, then it gives up loudly
        verify(accountClient, times(4)).getAccountById(10L);
        verify(failureRecorder).recordFailedTransfer(eq("key-tr-3"), eq(10L), eq(20L),
                eq(new BigDecimal("300.00")), any(String.class));
    }

    @Test
    void duplicateIdempotencyKeyRacingInsertFallsBackToExistingRow() {
        when(accountClient.getAccountById(1L)).thenReturn(account(1L, 42L, "1000.00", 0L));
        Transaction existing = new Transaction();
        existing.setId(99L);
        existing.setTransactionReference("TXN-raced");
        existing.setAccountId(1L);
        existing.setAmount(new BigDecimal("100.00"));
        existing.setType(TransactionType.DEPOSIT);
        existing.setStatus(TransactionStatus.SUCCESS);
        existing.setTransactionDate(LocalDateTime.now());
        existing.setIdempotencyKey("key-race");

        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(transactionRepository.findByIdempotencyKey("key-race"))
                .thenReturn(Optional.of(existing));

        TransactionResponse response = transactionService.createTransaction(
                request(1L, "100.00", TransactionType.DEPOSIT, "key-race"), 42L, false);

        assertEquals("TXN-raced", response.getTransactionReference());
    }
}
