package com.nexora.accountservice.controller;

import com.nexora.accountservice.dto.AccountResponse;
import com.nexora.accountservice.service.AccountService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

    private final AccountService accountService;

    public InternalAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{id}")
    public AccountResponse getAccountById(@PathVariable("id") Long id) {
        return accountService.getAccountResponseById(id);
    }

    @PutMapping("/{id}/balance")
    public AccountResponse updateBalance(
            @PathVariable("id") Long id,
            @RequestParam("balance") BigDecimal balance,
            @RequestParam("version") Long version) {
        return accountService.updateBalance(id, balance, version);
    }
}
