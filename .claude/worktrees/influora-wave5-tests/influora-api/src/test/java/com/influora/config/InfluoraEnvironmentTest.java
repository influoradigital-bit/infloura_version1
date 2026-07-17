package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Kabir H1 — influora.env gate for dev-only secret logging. */
class InfluoraEnvironmentTest {

    private InfluoraEnvironment environment;

    @BeforeEach
    void setUp() {
        environment = new InfluoraEnvironment();
    }

    @Test
    @DisplayName("isDev: true only when influora.env is dev (case-insensitive)")
    void testIsDev() throws Exception {
        setEnv("dev");
        assertTrue(environment.isDev());

        setEnv("DEV");
        assertTrue(environment.isDev());

        setEnv("staging");
        assertFalse(environment.isDev());

        setEnv("production");
        assertFalse(environment.isDev());
    }

    private void setEnv(String value) throws Exception {
        Field f = InfluoraEnvironment.class.getDeclaredField("env");
        f.setAccessible(true);
        f.set(environment, value);
    }
}
