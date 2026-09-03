package com.influora.web.dto.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.web.dto.admin.AdminCreatorConnectionDtos.InviteRequest;
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
 * T-CREATORCONNECT-0902 — {@code POST /admin/creator-connections/{id}/invite} requires a valid
 * email. Same bean-validation-direct pattern as {@code DealControllerTest} (no MockMvc harness in
 * this codebase; this exercises the exact {@link Validator} Spring's {@code @Valid} delegates to).
 */
class AdminCreatorConnectionDtosTest {

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
    @DisplayName("InviteRequest: blank email fails validation (@NotBlank)")
    void inviteRequest_blankEmail_failsValidation() {
        InviteRequest request = new InviteRequest("", "please join");
        Set<ConstraintViolation<InviteRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("InviteRequest: null email fails validation (@NotBlank)")
    void inviteRequest_nullEmail_failsValidation() {
        InviteRequest request = new InviteRequest(null, null);
        Set<ConstraintViolation<InviteRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("InviteRequest: malformed email fails validation (@Email)")
    void inviteRequest_malformedEmail_failsValidation() {
        InviteRequest request = new InviteRequest("not-an-email", null);
        Set<ConstraintViolation<InviteRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("InviteRequest: valid email passes validation")
    void inviteRequest_validEmail_passesValidation() {
        InviteRequest request = new InviteRequest("creator@example.com", "please join");
        assertTrue(validator.validate(request).isEmpty());
    }
}
