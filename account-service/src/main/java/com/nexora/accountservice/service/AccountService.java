package com.nexora.accountservice.service;

import com.nexora.accountservice.client.UserClient;
import com.nexora.accountservice.dto.AccountRequest;
import com.nexora.accountservice.dto.AccountResponse;
import com.nexora.accountservice.dto.UserDto;
import com.nexora.accountservice.entity.Account;
import com.nexora.accountservice.exception.AccountNotFoundException;
import com.nexora.accountservice.exception.DuplicateAccountException;
import com.nexora.accountservice.exception.ForbiddenException;
import com.nexora.accountservice.exception.UserNotFoundException;
import com.nexora.accountservice.repository.AccountRepository;
import feign.FeignException;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final UserClient userClient;

    public AccountService(AccountRepository accountRepository, UserClient userClient) {
        this.accountRepository = accountRepository;
        this.userClient = userClient;
    }

    public Account getAccountById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account with id " + id + " not found"));
    }

    public AccountResponse saveAccount(AccountRequest request, Long actorUserId, boolean admin) {
        Long ownerId = request.getUserId();
        if (ownerId == null) {
            ownerId = actorUserId;
        }
        if (ownerId == null) {
            throw new ForbiddenException("Authenticated user is required to open an account");
        }
        if (!admin && actorUserId != null && !actorUserId.equals(ownerId)) {
            throw new ForbiddenException("You can only open an account for yourself");
        }

        try {
            UserDto user = userClient.getUserById(ownerId);
            if (user == null) {
                throw new UserNotFoundException("User with id " + ownerId + " not found.");
            }

            String accountNumber = request.getAccountNumber();
            if (accountNumber == null || accountNumber.isBlank()) {
                accountNumber = generateAccountNumber();
            } else if (accountRepository.existsByAccountNumber(accountNumber)) {
                throw new DuplicateAccountException("Account number already exists: " + accountNumber);
            }

            Account account = new Account();
            account.setAccountNumber(accountNumber);
            account.setAccountHolderName(request.getAccountHolderName());
            account.setBalance(request.getBalance() == null ? BigDecimal.ZERO : request.getBalance());
            account.setUserId(ownerId);
            return toResponse(saveWithUniqueAccountNumber(account));
        } catch (FeignException.NotFound exception) {
            throw new UserNotFoundException("User with id " + ownerId + " not found");
        }
    }

    public List<AccountResponse> getAllAccounts(Long actorUserId, boolean admin) {
        if (admin) {
            return accountRepository.findAll().stream().map(this::toResponse).collect(Collectors.toList());
        }
        if (actorUserId == null) {
            throw new ForbiddenException("Authenticated user is required");
        }
        return getAccountsByUserId(actorUserId);
    }

    public List<AccountResponse> getAccountsByUserId(Long userId) {
        return accountRepository.findByUserId(userId).stream().map(this::toResponse).collect(Collectors.toList());
    }

    public AccountResponse getAccountResponseById(Long id) {
        return toResponse(getAccountById(id));
    }

    public AccountResponse getVisibleAccount(Long id, Long actorUserId, boolean admin) {
        Account account = getAccountById(id);
        if (!admin && actorUserId != null && !actorUserId.equals(account.getUserId())) {
            throw new ForbiddenException("You can only view your own account");
        }
        return toResponse(account);
    }

    @Transactional
    public AccountResponse updateBalance(Long accountId, BigDecimal newBalance, Long version) {
        Account account = getAccountById(accountId);
        if (version != null && !version.equals(account.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Account.class, accountId);
        }
        account.setBalance(newBalance);
        return toResponse(accountRepository.save(account));
    }

    private String generateAccountNumber() {
        String candidate;
        do {
            candidate = "NX" + (1000000000L + Math.abs(RANDOM.nextLong()) % 9000000000L);
        } while (accountRepository.existsByAccountNumber(candidate));
        return candidate;
    }

    /**
     * Saves the account, retrying with a fresh account number if a concurrent
     * creator claimed the same number between the exists-check and the insert.
     * The unique constraint on account_number is the final authority.
     */
    private Account saveWithUniqueAccountNumber(Account account) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return accountRepository.saveAndFlush(account);
            } catch (DataIntegrityViolationException exception) {
                if (attempt == 4) {
                    throw new DuplicateAccountException(
                            "Could not allocate a unique account number, please retry");
                }
                account.setAccountNumber(generateAccountNumber());
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private AccountResponse toResponse(Account account) {
        UserDto user = null;
        try {
            user = userClient.getUserById(account.getUserId());
        } catch (Exception ignored) {
            // Account still returns even if user-service is briefly unavailable
        }
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountHolderName(),
                account.getBalance(),
                account.getUserId(),
                user,
                account.getVersion()
        );
    }
}
