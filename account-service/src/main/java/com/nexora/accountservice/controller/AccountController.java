package com.nexora.accountservice.controller;

import com.nexora.accountservice.entity.Account;
import com.nexora.accountservice.service.AccountService;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@RestController
@RequestMapping("/accounts")

public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService){
        this.accountService=accountService;
    }

    @PostMapping
    public Account saveAccount(@RequestBody Account account){
        return accountService.saveAccount(account);
    }

    @GetMapping
    public List<Account> getAllAccounts(){
        return accountService.getAllAccounts();
    }
}
