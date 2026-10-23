package com.nexora.userservice;

import com.nexora.userservice.dto.AuthResponse;
import com.nexora.userservice.dto.LoginRequest;
import com.nexora.userservice.dto.RegisterRequest;
import com.nexora.userservice.dto.UpdateProfileRequest;
import com.nexora.userservice.entity.User;
import com.nexora.userservice.exception.DuplicateEmailException;
import com.nexora.userservice.exception.InvalidCredentialsException;
import com.nexora.userservice.exception.UserNotFoundException;
import com.nexora.userservice.repository.UserRepository;
import com.nexora.userservice.security.JwtService;
import com.nexora.userservice.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        userService = new UserService(userRepository, passwordEncoder, jwtService);
    }

    private RegisterRequest registerRequest(String email) {
        RegisterRequest request = new RegisterRequest();
        request.setName("Elena");
        request.setEmail(email);
        request.setMobile("9999999999");
        request.setPassword("secret1");
        return request;
    }

    @Test
    void registerHashesPasswordAndReturnsToken() {
        when(userRepository.existsByEmail("elena@nexora.com")).thenReturn(false);
        when(passwordEncoder.encode("secret1")).thenReturn("$bcrypt-hash$");
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = userService.register(registerRequest("elena@nexora.com"));

        assertEquals("jwt-token", response.getToken());
        assertEquals("elena@nexora.com", response.getEmail());
        assertEquals("USER", response.getRole());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void registerDuplicateEmailIsRejected() {
        when(userRepository.existsByEmail("elena@nexora.com")).thenReturn(true);

        assertThrows(DuplicateEmailException.class,
                () -> userService.register(registerRequest("elena@nexora.com")));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginWithCorrectPasswordReturnsToken() {
        User user = new User();
        user.setId(7L);
        user.setName("Elena");
        user.setEmail("elena@nexora.com");
        user.setMobile("9999999999");
        user.setPassword("$bcrypt-hash$");
        user.setRole("USER");

        when(userRepository.findByEmail("elena@nexora.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret1", "$bcrypt-hash$")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        LoginRequest request = new LoginRequest();
        request.setEmail("elena@nexora.com");
        request.setPassword("secret1");

        AuthResponse response = userService.login(request);
        assertEquals("jwt-token", response.getToken());
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        User user = new User();
        user.setEmail("elena@nexora.com");
        user.setPassword("$bcrypt-hash$");

        when(userRepository.findByEmail("elena@nexora.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong!", "$bcrypt-hash$")).thenReturn(false);

        LoginRequest request = new LoginRequest();
        request.setEmail("elena@nexora.com");
        request.setPassword("wrong!");

        assertThrows(InvalidCredentialsException.class, () -> userService.login(request));
    }

    @Test
    void loginWithUnknownEmailIsRejected() {
        when(userRepository.findByEmail("ghost@nexora.com")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest();
        request.setEmail("ghost@nexora.com");
        request.setPassword("secret1");

        assertThrows(InvalidCredentialsException.class, () -> userService.login(request));
    }

    @Test
    void updateProfileChangesFieldsAndReencodesPassword() {
        User user = new User();
        user.setId(7L);
        user.setName("Elena");
        user.setMobile("1111111111");
        user.setPassword("$bcrypt-hash$");

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newsecret")).thenReturn("$bcrypt-new$");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setName("Elena N.");
        request.setMobile("2222222222");
        request.setPassword("newsecret");

        User updated = userService.updateProfile(7L, request);

        assertEquals("Elena N.", updated.getName());
        assertEquals("2222222222", updated.getMobile());
        assertEquals("$bcrypt-new$", updated.getPassword());
        assertNotEquals("$bcrypt-hash$", updated.getPassword());
    }

    @Test
    void updateProfileWithBlankPasswordKeepsOldPassword() {
        User user = new User();
        user.setId(7L);
        user.setName("Elena");
        user.setMobile("1111111111");
        user.setPassword("$bcrypt-hash$");

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setName("Elena N.");
        request.setMobile("2222222222");
        request.setPassword("");

        User updated = userService.updateProfile(7L, request);

        assertEquals("$bcrypt-hash$", updated.getPassword());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void getUnknownUserThrows() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.getUserById(404L));
    }
}
