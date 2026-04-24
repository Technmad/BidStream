package com.bidstream.application;

import com.bidstream.common.ConflictException;
import com.bidstream.common.security.JwtService;
import com.bidstream.domain.model.User;
import com.bidstream.domain.port.UserRepository;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public User register(String username, String email, String rawPassword) {
        if (userRepository.existsByUsernameOrEmail(username, email)) {
            throw new ConflictException("Username or email already in use");
        }
        // The PDR's auth model (§17) specs ROLE_USER/ROLE_SELLER/ROLE_ADMIN but never describes
        // a separate seller-application/approval flow - every registered user can both buy and
        // sell, matching how real marketplaces (eBay included) actually work.
        User user = new User(UUID.randomUUID(), username, email,
                passwordEncoder.encode(rawPassword), Set.of("ROLE_USER", "ROLE_SELLER"), Instant.now());
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
