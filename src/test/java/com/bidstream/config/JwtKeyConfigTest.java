package com.bidstream.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test - no Spring context needed, this class is plain PEM parsing plus a fallback.
 * Covers the QA-REVIEW.md Medium finding: without a configured key pair, every instance mints its
 * own, which breaks cross-replica token verification; configuring one must make every instance
 * agree on the exact same key material.
 */
class JwtKeyConfigTest {

    @Test
    void withNoConfiguredKeysAnEphemeralPairIsGenerated() {
        KeyPair keyPair = new JwtKeyConfig("", "").jwtSigningKeyPair();

        assertThat(keyPair.getPublic()).isNotNull();
        assertThat(keyPair.getPrivate()).isNotNull();
    }

    @Test
    void twoInstancesConfiguredWithTheSamePemLoadTheIdenticalKeyMaterial() throws Exception {
        KeyPair generated = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String privatePem = toPem("PRIVATE KEY", generated.getPrivate().getEncoded());
        String publicPem = toPem("PUBLIC KEY", generated.getPublic().getEncoded());

        KeyPair first = new JwtKeyConfig(privatePem, publicPem).jwtSigningKeyPair();
        KeyPair second = new JwtKeyConfig(privatePem, publicPem).jwtSigningKeyPair();

        assertThat(first.getPrivate()).isEqualTo(second.getPrivate());
        assertThat(first.getPublic()).isEqualTo(second.getPublic());
        assertThat(first.getPrivate()).isEqualTo(generated.getPrivate());
    }

    @Test
    void malformedPemFailsFastRatherThanSilentlyFallingBackToAnEphemeralKey() {
        assertThatThrownBy(() -> new JwtKeyConfig("not-a-valid-key", "not-a-valid-key")
                .jwtSigningKeyPair())
                .isInstanceOf(IllegalStateException.class);
    }

    private static String toPem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getEncoder().encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
