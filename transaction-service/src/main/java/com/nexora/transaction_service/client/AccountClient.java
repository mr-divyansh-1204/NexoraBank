package com.nexora.transaction_service.client;

import com.nexora.transaction_service.dto.AccountResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * Calls account-service via an explicitly configured URL — no client-side service
 * discovery. On AWS ECS, Service Connect / Cloud Map DNS provides the hostname
 * (e.g. http://account-service:8082); locally, docker-compose or localhost defaults apply.
 */
@FeignClient(name = "account-service", url = "${service-urls.account-service}")
public interface AccountClient {

    @GetMapping("/internal/accounts/{id}")
    AccountResponse getAccountById(@PathVariable Long id);

    @PutMapping("/internal/accounts/{id}/balance")
    AccountResponse updateBalance(
            @PathVariable("id") Long id,
            @RequestParam("balance") BigDecimal balance,
            @RequestParam("version") Long version
    );
}
