package com.example.order.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.order.entity.Role;
import com.example.order.entity.User;
import com.example.order.repository.UserRepository;

/**
 * Creates the two demo accounts once. Passwords are BCrypt hashes and can be
 * overridden with environment variables. Disable seeding with SEED_DATA=false.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class UserDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserDataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;
    private final String operatorPassword;

    public UserDataInitializer(UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               @Value("${app.seed.admin-password:Admin@123}") String adminPassword,
                               @Value("${app.seed.operator-password:Operator@123}") String operatorPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
        this.operatorPassword = operatorPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        createIfMissing("admin", adminPassword, Role.ADMIN);
        createIfMissing("operator", operatorPassword, Role.OPERATIONS);
    }

    private void createIfMissing(String username, String rawPassword, Role role) {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setEnabled(true);
        userRepository.save(user);
        log.info("Seeded demo user username={} role={}. Change the password outside local demos.", username, role);
    }
}
