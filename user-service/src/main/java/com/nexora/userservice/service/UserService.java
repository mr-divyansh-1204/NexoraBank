package com.nexora.userservice.service;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException("Email already registered: " + request.getEmail());
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setMobile(request.getMobile());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole("USER");
        User saved = userRepository.save(user);
        return new AuthResponse(
                jwtService.generateToken(saved),
                saved.getId(),
                saved.getName(),
                saved.getEmail(),
                saved.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (user.getPassword() == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        return new AuthResponse(
                jwtService.generateToken(user),
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole());
    }

    public User updateProfile(Long id, UpdateProfileRequest request) {
        User user = getUserById(id);
        user.setName(request.getName());
        user.setMobile(request.getMobile());
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        return userRepository.save(user);
    }

    public User saveUser(User user) {
        if (user.getEmail() != null && userRepository.existsByEmail(user.getEmail())) {
            throw new DuplicateEmailException("Email already registered: " + user.getEmail());
        }
        if (user.getRole() == null) {
            user.setRole("USER");
        }
        return userRepository.save(user);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
    }
}
