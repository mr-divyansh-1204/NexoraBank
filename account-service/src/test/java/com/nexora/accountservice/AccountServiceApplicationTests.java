package com.nexora.accountservice;

import com.nexora.accountservice.client.UserClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class AccountServiceApplicationTests {

    @MockitoBean
    private UserClient userClient;

    @Test
    void contextLoads() {
    }
}
