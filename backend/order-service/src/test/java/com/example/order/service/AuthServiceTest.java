package com.example.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.order.dto.LoginRequest;
import com.example.order.dto.LoginResponse;
import com.example.order.entity.Role;
import com.example.order.entity.User;
import com.example.order.exception.InvalidCredentialsException;
import com.example.order.repository.UserRepository;
import com.example.order.security.JwtService;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void loginReturnsTokenForValidCredentials() {
        User user = user(true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Admin@123", "bcrypt-hash")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("signed-token");
        when(jwtService.getExpirationSeconds()).thenReturn(7200L);

        LoginResponse response = authService.login(new LoginRequest("admin", "Admin@123"));

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(7200L);
        assertThat(response.user().role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = user(true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsDisabledUser() {
        User user = user(false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "Admin@123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private User user(boolean enabled) {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("bcrypt-hash");
        user.setRole(Role.ADMIN);
        user.setEnabled(enabled);
        return user;
    }
}
