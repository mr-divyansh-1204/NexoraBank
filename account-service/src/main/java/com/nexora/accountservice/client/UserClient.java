package com.nexora.accountservice.client;

import com.nexora.accountservice.dto.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Calls user-service via an explicitly configured URL — no client-side service
 * discovery. On AWS ECS, Service Connect / Cloud Map DNS provides the hostname
 * (e.g. http://user-service:8081) so AWS handles scaling and health-aware
 * routing; locally, docker-compose or localhost defaults apply.
 */
@FeignClient(name = "user-service", url = "${service-urls.user-service}")
public interface UserClient {

    @GetMapping("/internal/users/{id}")
    UserDto getUserById(@PathVariable Long id);
}
