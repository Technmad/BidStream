package com.bidstream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Throwaway probe checking whether Testcontainers can provision a container on THIS runner.
 * Locally (Windows + Docker Desktop) it fails with "Could not find a valid Docker environment" -
 * Docker Desktop's npipe proxy layer returns a stubbed response Testcontainers' client can't
 * parse (see docs/adr/0003). GitHub's ubuntu-latest runners use a native Linux Docker daemon
 * directly, not Docker Desktop, so this may simply not reproduce there. Not part of the real
 * suite - delete once the experiment answers the question either way.
 */
class TestcontainersProbeTest {

    @Test
    void canStartAPostgresContainer() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            assertThat(postgres.isRunning()).isTrue();
        }
    }
}
