package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * INV-2 (2026-07-15, Priya): proves the GSTIN boot-time validator actually rejects the exact
 * placeholder/blank/malformed shapes that previously let a real deploy boot silently mistaxing
 * every intra-state invoice as IGST (see {@link CompanyTaxStartupValidator} javadoc for the full
 * failure mode). Fails closed outside {@code dev} (gated on {@link InfluoraEnvironment#isDev()});
 * {@code dev} only warns.
 */
@ExtendWith(MockitoExtension.class)
class CompanyTaxStartupValidatorTest {

    @Mock private InfluoraEnvironment environment;

    private CompanyTaxProperties properties;

    @BeforeEach
    void setUp() {
        properties = new CompanyTaxProperties();
    }

    private CompanyTaxStartupValidator validator() {
        return new CompanyTaxStartupValidator(properties, environment);
    }

    @Test
    @DisplayName("validate: blank GSTIN fails closed outside dev")
    void testBlankGstinFailsClosedInProd() {
        when(environment.isDev()).thenReturn(false);
        properties.setGstin("");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator().validate());
        assertTrue(ex.getMessage().contains("influora.company.gstin"));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    @DisplayName("validate: null GSTIN fails closed outside dev")
    void testNullGstinFailsClosedInProd() {
        when(environment.isDev()).thenReturn(false);
        properties.setGstin(null);

        assertThrows(IllegalStateException.class, () -> validator().validate());
    }

    @Test
    @DisplayName("validate: committed placeholder GSTIN fails closed outside dev")
    void testPlaceholderGstinFailsClosedInProd() {
        when(environment.isDev()).thenReturn(false);
        properties.setGstin("REPLACE_WITH_REAL_GSTIN");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator().validate());
        assertTrue(ex.getMessage().contains("influora.company.gstin"));
        assertTrue(ex.getMessage().contains("placeholder"));
    }

    @Test
    @DisplayName("validate: malformed (wrong length / bad shape) GSTIN fails closed outside dev")
    void testMalformedGstinFailsClosedInProd() {
        when(environment.isDev()).thenReturn(false);
        properties.setGstin("NOT-A-REAL-GSTIN");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator().validate());
        assertTrue(ex.getMessage().contains("influora.company.gstin"));
        assertTrue(ex.getMessage().contains("not a valid"));
    }

    @Test
    @DisplayName("validate: lowercase GSTIN (wrong case) fails closed outside dev")
    void testLowercaseGstinFailsClosedInProd() {
        when(environment.isDev()).thenReturn(false);
        properties.setGstin("27aabcu9603r1zm");

        assertThrows(IllegalStateException.class, () -> validator().validate());
    }

    @Test
    @DisplayName("validate: well-formed 15-char GSTIN boots clean (isDev() never even consulted)")
    void testWellFormedGstinBootsCleanInProd() {
        properties.setGstin("27AABCU9603R1ZM");

        assertDoesNotThrow(() -> validator().validate());
    }

    @Test
    @DisplayName("validate: placeholder GSTIN only WARNS (does not throw) in dev")
    void testPlaceholderGstinOnlyWarnsInDev() {
        when(environment.isDev()).thenReturn(true);
        properties.setGstin("REPLACE_WITH_REAL_GSTIN");

        assertDoesNotThrow(() -> validator().validate());
    }

    @Test
    @DisplayName("validate: blank GSTIN only WARNS (does not throw) in dev")
    void testBlankGstinOnlyWarnsInDev() {
        when(environment.isDev()).thenReturn(true);
        properties.setGstin("");

        assertDoesNotThrow(() -> validator().validate());
    }
}
