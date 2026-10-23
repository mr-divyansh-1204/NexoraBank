package com.nexora.userservice.controller;

import com.nexora.userservice.dto.UpdateProfileRequest;
import com.nexora.userservice.entity.User;
import com.nexora.userservice.exception.ForbiddenException;
import com.nexora.userservice.security.RequestActor;
import com.nexora.userservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public User me(HttpServletRequest request) {
        Long userId = RequestActor.userId(request);
        if (userId == null) {
            throw new ForbiddenException("Authenticated user is required");
        }
        return userService.getUserById(userId);
    }

    @PutMapping("/me")
    public User updateMyProfile(@Valid @RequestBody UpdateProfileRequest request, HttpServletRequest httpRequest) {
        Long userId = RequestActor.userId(httpRequest);
        if (userId == null) {
            throw new ForbiddenException("Authenticated user is required");
        }
        return userService.updateProfile(userId, request);
    }

    @GetMapping
    public List<User> getAllUsers(HttpServletRequest request) {
        requireAdmin(request);
        return userService.getAllUsers();
    }

    @GetMapping("/{id}")
    public User getUserById(@PathVariable Long id, HttpServletRequest request) {
        Long actorId = RequestActor.userId(request);
        if (!RequestActor.admin(request) && (actorId == null || !actorId.equals(id))) {
            throw new ForbiddenException("You can only view your own profile");
        }
        return userService.getUserById(id);
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!RequestActor.admin(request)) {
            throw new ForbiddenException("Admin role is required");
        }
    }
}
