package com.nexora.accountservice;

import com.nexora.accountservice.entity.Account;
import com.nexora.accountservice.exception.ForbiddenException;
import com.nexora.accountservice.repository.AccountRepository;
import com.nexora.accountservice.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Proves that concurrent balance updates cannot silently corrupt an account:
 * a write submitted with a stale {@code version} must fail with
 * {@link ObjectOptimisticLockingFailureException} (mapped to 409 Conflict),
 * and the entity version must advance after every successful update.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountConcurrencyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private com.nexora.accountservice.client.UserClient userClient;

    private Account newAccount() {
        Account account = new Account();
        account.setAccountNumber("NX" + System.nanoTime());
        account.setAccountHolderName("Test User");
        account.setBalance(new BigDecimal("1000.00"));
        account.setUserId(42L);
        return accountRepository.saveAndFlush(account);
    }

    private void stubUserClient() {
        lenient().when(userClient.getUserById(anyLong())).thenReturn(new com.nexora.accountservice.dto.UserDto());
    }

    @Test
    void staleVersionUpdateIsRejectedWith409() throws Exception {
        stubUserClient();
        Account account = newAccount();
        Long staleVersion = account.getVersion();

        // A concurrent write advances the version behind this client's back
        accountService.updateBalance(account.getId(), new BigDecimal("900.00"), account.getVersion());

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Internal-Key", "nexora-internal-dev-key");

        ResponseEntity<Map> response = restTemplate.exchange(
                "http://localhost:" + port
                        + "/internal/accounts/" + account.getId()
                        + "/balance?balance=500.00&version=" + staleVersion,
                HttpMethod.PUT,
                new HttpEntity<>(null, headers),
                Map.class
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(new BigDecimal("900.00"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
    }

    @Test
    void currentVersionUpdateSucceedsAndBumpsVersion() {
        stubUserClient();
        Account account = newAccount();
        Long versionBefore = account.getVersion();

        accountService.updateBalance(account.getId(), new BigDecimal("777.77"), versionBefore);

        Account updated = accountRepository.findById(account.getId()).orElseThrow();
        assertEquals(new BigDecimal("777.77"), updated.getBalance());
        assertTrue(updated.getVersion() > versionBefore, "version must advance after update");
    }

    @Test
    void serviceLayerRejectsStaleVersionDirectly() {
        stubUserClient();
        Account account = newAccount();

        Account detached = accountRepository.findById(account.getId()).orElseThrow();
        accountService.updateBalance(account.getId(), new BigDecimal("800.00"), detached.getVersion());

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> accountService.updateBalance(account.getId(), new BigDecimal("700.00"), detached.getVersion()));
    }

    @Test
    void concurrentUpdatesWithSameVersionYieldExactlyOneWinner() throws Exception {
        stubUserClient();
        Account account = newAccount();
        Long sharedStaleVersion = account.getVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> winnerA = pool.submit(() -> {
            start.await();
            try {
                accountService.updateBalance(account.getId(), new BigDecimal("111.11"), sharedStaleVersion);
                return true;
            } catch (ObjectOptimisticLockingFailureException e) {
                return false;
            }
        });
        Future<Boolean> winnerB = pool.submit(() -> {
            start.await();
            try {
                accountService.updateBalance(account.getId(), new BigDecimal("222.22"), sharedStaleVersion);
                return true;
            } catch (ObjectOptimisticLockingFailureException e) {
                return false;
            }
        });

        start.countDown();
        boolean aWon = winnerA.get(10, TimeUnit.SECONDS);
        boolean bWon = winnerB.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(aWon ^ bWon, "exactly one of the two racing updates must win");
        Account after = accountRepository.findById(account.getId()).orElseThrow();
        BigDecimal expected = aWon ? new BigDecimal("111.11") : new BigDecimal("222.22");
        assertEquals(expected, after.getBalance());
    }

    @Test
    void userCannotReadSomeoneElsesAccount() {
        Account account = newAccount(); // owned by user 42

        assertThrows(ForbiddenException.class,
                () -> accountService.getVisibleAccount(account.getId(), 99L, false));
    }
}
