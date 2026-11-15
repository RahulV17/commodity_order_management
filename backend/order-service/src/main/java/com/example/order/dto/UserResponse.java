package com.example.order.dto;

import java.time.Instant;

import com.example.order.entity.Role;
import com.example.order.entity.User;

public record UserResponse(Long id, String username, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole());
    }
}
