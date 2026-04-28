package com.bidstream.application;

import com.bidstream.common.ConflictException;
import com.bidstream.common.security.JwtService;
import com.bidstream.domain.model.User;
import com.bidstream.domain.port.UserRepository;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String adminBootstrapUsername;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        @Value("${bidstream.admin.bootstrap-username:}") String adminBootstrapUsername) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.adminBootstrapUsername = adminBootstrapUsername;
    }

    @Transactional
    public User register(String username, String email, String rawPassword) {
        if (userRepository.existsByUsernameOrEmail(username, email)) {
            throw new ConflictException("Username or email already in use");
        }
        // The PDR's auth model (§17) specs ROLE_USER/ROLE_SELLER/ROLE_ADMIN but never describes
        // a separate seller-application/approval flow - every registered user can both buy and
        // sell, matching how real marketplaces (eBay included) actually work.
        Set<String> roles = new HashSet<>(Set.of("ROLE_USER", "ROLE_SELLER"));
        // PDR §17.1: a configured bootstrap username is the only way any account can ever be
        // granted ROLE_ADMIN. Unset (the default) means this branch never fires, so no account
        // can be promoted through this path until an environment explicitly configures it -
        // matching JwtKeyConfig's "absent means the safe/inert default" posture. Registration's
        // own unique-username constraint means at most one account can ever claim it.
        if (!adminBootstrapUsername.isBlank() && adminBootstrapUsername.equals(username)) {
            roles.add("ROLE_ADMIN");
        }
        User user = new User(UUID.randomUUID(), username, email,
                passwordEncoder.encode(rawPassword), roles, Instant.now());
        return userRepository.save(user);
    }

    public TokenPair login(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new JwtException("Invalid username or password"));
        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            throw new JwtException("Invalid username or password");
        }
        return issueTokens(user);
    }

    public TokenPair refresh(String refreshToken) {
        JwtService.DecodedToken decoded = jwtService.verifyRefreshToken(refreshToken);
        User user = userRepository.findById(decoded.userId())
                .orElseThrow(() -> new JwtException("User no longer exists"));
        return issueTokens(user);
    }

    private TokenPair issueTokens(User user) {
        String access = jwtService.issueAccessToken(user.id(), user.username(), user.roles());
        String refresh = jwtService.issueRefreshToken(user.id(), user.username(), user.roles());
        return new TokenPair(access, refresh);
    }

    public record TokenPair(String accessToken, String refreshToken) {
    }
}
