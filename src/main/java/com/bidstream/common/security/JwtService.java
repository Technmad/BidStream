package com.bidstream.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies RS256 JWTs (PDR §17). Access tokens are short-lived (~15 min); refresh
 * tokens are longer-lived and carry a {@code type=refresh} claim so they can't be replayed as
 * access tokens even though both are signed with the same key.
 */
@Service
public class JwtService {

    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public JwtService(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public String issueAccessToken(UUID userId, String username, Set<String> roles) {
        return issue(userId, username, roles, TYPE_ACCESS, ACCESS_TOKEN_TTL);
    }

    public String issueRefreshToken(UUID userId, String username, Set<String> roles) {
        return issue(userId, username, roles, TYPE_REFRESH, REFRESH_TOKEN_TTL);
    }

    private String issue(UUID userId, String username, Set<String> roles, String type,
                          Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public DecodedToken verifyAccessToken(String token) {
        DecodedToken decoded = verify(token);
        if (!TYPE_ACCESS.equals(decoded.type())) {
            throw new JwtException("Expected an access token, got: " + decoded.type());
        }
        return decoded;
    }

    public DecodedToken verifyRefreshToken(String token) {
        DecodedToken decoded = verify(token);
        if (!TYPE_REFRESH.equals(decoded.type())) {
            throw new JwtException("Expected a refresh token, got: " + decoded.type());
        }
        return decoded;
    }

    private DecodedToken verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            @SuppressWarnings("unchecked")
            List<String> roles = claims.get(CLAIM_ROLES, List.class);

            return new DecodedToken(
                    UUID.fromString(claims.getSubject()),
                    claims.get("username", String.class),
                    Set.copyOf(roles),
                    claims.get(CLAIM_TYPE, String.class));
        } catch (SignatureException | IllegalArgumentException e) {
            throw new JwtException("Invalid JWT signature or claims", e);
        }
    }

    public record DecodedToken(UUID userId, String username, Set<String> roles, String type) {
    }
}
