package com.bidstream.config;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the RSA key pair used to sign/verify JWTs (RS256, PDR §17 — asymmetric so verifiers
 * never hold the signing key).
 *
 * <p>QA-REVIEW.md Medium finding: the Kubernetes manifest runs three replicas of this image, but
 * a key generated fresh per instance can't be verified by a different pod, and every restart
 * invalidates every outstanding session. When {@code bidstream.jwt.private-key-pem}/
 * {@code public-key-pem} are configured (from a Secret shared across all replicas — see
 * {@code k8s/secret.example.yaml}), that fixed key pair is loaded instead. Falling back to an
 * ephemeral in-memory key pair when they're absent keeps local/single-instance dev friction-free.
 */
@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    private final String privateKeyPem;
    private final String publicKeyPem;

    public JwtKeyConfig(@Value("${bidstream.jwt.private-key-pem:}") String privateKeyPem,
                         @Value("${bidstream.jwt.public-key-pem:}") String publicKeyPem) {
        this.privateKeyPem = privateKeyPem;
        this.publicKeyPem = publicKeyPem;
    }

    @Bean
    public KeyPair jwtSigningKeyPair() {
        if (!privateKeyPem.isBlank() && !publicKeyPem.isBlank()) {
            return loadFromPem();
        }
        log.warn("bidstream.jwt.private-key-pem/public-key-pem not set — generating an ephemeral "
                + "RSA key pair for this instance only. Fine for a single local instance; running "
                + "multiple replicas (or restarting) without a shared configured key breaks token "
                + "verification across pods and invalidates every session on restart.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA algorithm unavailable", e);
        }
    }

    private KeyPair loadFromPem() {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(decodePem(privateKeyPem)));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(decodePem(publicKeyPem)));
            return new KeyPair(publicKey, privateKey);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Configured bidstream.jwt.private-key-pem/public-key-pem could not be parsed as "
                            + "PKCS8/X509 RSA keys", e);
        }
    }

    private static byte[] decodePem(String pem) {
        String stripped = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(stripped);
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
