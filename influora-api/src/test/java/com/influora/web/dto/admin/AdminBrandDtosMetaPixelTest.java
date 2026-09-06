package com.influora.web.dto.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.web.dto.admin.AdminBrandDtos.UpdateMetaPixelRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-FESTIVALBOX-0905 phase 6 — {@code PATCH /admin/brands/{id}/meta-pixel}. Same bean-validation-
 * direct pattern as {@code AdminCreatorConnectionDtosTest} (no MockMvc harness in this codebase;
 * this exercises the exact {@link Validator} Spring's {@code @Valid} delegates to).
 */
class AdminBrandDtosMetaPixelTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("UpdateMetaPixelRequest: null metaPixelId passes validation (explicit clear)")
    void nullMetaPixelId_passesValidation() {
        UpdateMetaPixelRequest request = new UpdateMetaPixelRequest(null);
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    @DisplayName("UpdateMetaPixelRequest: 16 digits passes validation")
    void sixteenDigits_passesValidation() {
        UpdateMetaPixelRequest request = new UpdateMetaPixelRequest("1234567890123456");
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    @DisplayName("UpdateMetaPixelRequest: 8 digits (lower bound) passes validation")
    void eightDigits_passesValidation() {
        UpdateMetaPixelRequest request = new UpdateMetaPixelRequest("12345678");
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    @DisplayName("UpdateMetaPixelRequest: 20 digits (upper bound) passes validation")
    void twentyDigits_passesValidation() {
        UpdateMetaPixelRequest request = new UpdateMetaPixelRequest("12345678901234567890");
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    @DisplayName("UpdateMetaPixelRequest: 7 digits (below lower bound) fails validation")
    void sevenDigits_failsValidation() {
        UpdateMetaPixelRequest request = new UpdateMetaPixelRequest("1234567");
        Set<ConstraintViolation<UpdateMetaPixelRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("UpdateMetaPixelRequest: 21 digits (above upper bound) fails validation")
    void twentyOneDigits_failsValidation() {
        UpdateMetaPixelRequest request = new UpdateMetaPixelRequest("123456789012345678901");
        Set<ConstraintViolation<UpdateMetaPixelRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("UpdateMetaPixelRequest: non-digit characters fail validation")
    void nonDigitCharacters_failValidation() {
        UpdateMetaPixelRequest request = new UpdateMetaPixelRequest("1234abcd90");
        Set<ConstraintViolation<UpdateMetaPixelRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("UpdateMetaPixelRequest: blank string fails validation")
    void blankString_failsValidation() {
        UpdateMetaPixelRequest request = new UpdateMetaPixelRequest("");
        Set<ConstraintViolation<UpdateMetaPixelRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }
}
