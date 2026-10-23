package com.nexora.accountservice.controller;

import com.nexora.accountservice.dto.AccountRequest;
import com.nexora.accountservice.dto.AccountResponse;
import com.nexora.accountservice.security.RequestActor;
import com.nexora.accountservice.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public AccountResponse saveAccount(@Valid @RequestBody AccountRequest request, HttpServletRequest httpRequest) {
        return accountService.saveAccount(request, RequestActor.userId(httpRequest), RequestActor.admin(httpRequest));
    }

    @GetMapping
    public List<AccountResponse> getAllAccounts(HttpServletRequest httpRequest) {
        return accountService.getAllAccounts(RequestActor.userId(httpRequest), RequestActor.admin(httpRequest));
    }

    @GetMapping("/me")
    public List<AccountResponse> myAccounts(HttpServletRequest httpRequest) {
        Long userId = RequestActor.userId(httpRequest);
        return accountService.getAllAccounts(userId, false);
    }

    @GetMapping("/{id}")
    public AccountResponse getAccountById(@PathVariable("id") Long id, HttpServletRequest httpRequest) {
        return accountService.getVisibleAccount(id, RequestActor.userId(httpRequest), RequestActor.admin(httpRequest));
    }
}
