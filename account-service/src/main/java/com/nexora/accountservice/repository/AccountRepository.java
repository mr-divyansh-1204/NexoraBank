package com.nexora.accountservice.repository;

import com.nexora.accountservice.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
