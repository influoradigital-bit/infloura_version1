package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Kabir H1 — dev-only secret logging / mock-payment gate.
 *
 * <p>W0-3: reworked to assert against the active Spring profile (via {@link MockEnvironment})
 * instead of the old {@code influora.env} property — see {@link InfluoraEnvironment} javadoc.
 */
class InfluoraEnvironmentTest {

    @Test
    @DisplayName("isDev: true only when the 'dev' Spring profile is active")
    void testIsDev() {
        assertTrue(new InfluoraEnvironment(withProfiles("dev")).isDev());
    }

    @Test
    @DisplayName("isDev: false when the 'prod' profile is active")
    void testIsDevFalseForProd() {
        assertFalse(new InfluoraEnvironment(withProfiles("prod")).isDev());
    }

    @Test
    @DisplayName("isDev: false when no profile is active (fail-closed, not fail-open)")
    void testIsDevFalseForNoActiveProfile() {
        assertFalse(new InfluoraEnvironment(new MockEnvironment()).isDev());
    }

    @Test
    @DisplayName("isDev: false for an unrelated/misspelled profile")
    void testIsDevFalseForOtherProfile() {
        assertFalse(new InfluoraEnvironment(withProfiles("staging")).isDev());
    }

    private static MockEnvironment withProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }
}
