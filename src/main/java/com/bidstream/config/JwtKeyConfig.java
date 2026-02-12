package com.bidstream.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Generates the RSA key pair used to sign/verify JWTs (RS256, PDR §17 — asymmetric so verifiers
 * never hold the signing key). Generated in-memory on startup for local/dev; production should
 * load a persistent key pair from a secret manager instead so tokens survive a restart.
 */
@Configuration
public class JwtKeyConfig {

    @Bean
    public KeyPair jwtSigningKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA algorithm unavailable", e);
        }
    }

    @Bean
    public RSAPublicKey jwtPublicKey(KeyPair keyPair) {
        return (RSAPublicKey) keyPair.getPublic();
    }

    @Bean
    public RSAPrivateKey jwtPrivateKey(KeyPair keyPair) {
        return (RSAPrivateKey) keyPair.getPrivate();
    }
}
