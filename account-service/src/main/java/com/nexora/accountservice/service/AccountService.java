package com.nexora.accountservice.service;


import com.nexora.accountservice.entity.Account;
import com.nexora.accountservice.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AccountService {
    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository){
        this.accountRepository=accountRepository;
    }

    public Account saveAccount(Account account){
        return accountRepository.save(account);
    }

    public List<Account> getAllAccounts(){
        return accountRepository.findAll();
    }
}
