package com.influora.web.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.web.dto.FestivalCouponCopyDtos.RecordCouponCopyRequest;
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
 * T-FESTIVALBOX-0905 phase 6 — {@code POST /festival/coupon-copied} bean validation. Same
 * validator-direct pattern as {@code AdminCreatorConnectionDtosTest}/{@code DealControllerTest}
 * (no MockMvc harness in this codebase).
 */
class FestivalCouponCopyDtosTest {

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

    private static RecordCouponCopyRequest valid() {
        return new RecordCouponCopyRequest("MUMBAI_FESTIVE_2026", "acme-corp", "FEST15");
    }

    @Test
    @DisplayName("well-formed request passes validation")
    void wellFormed_passes() {
        assertTrue(validator.validate(valid()).isEmpty());
    }

    @Test
    @DisplayName("blank edition fails validation")
    void blankEdition_fails() {
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest("", "acme-corp", "FEST15"));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("lower-case edition fails the strict upper-snake-case charset")
    void lowerCaseEdition_fails() {
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest("mumbai_festive_2026", "acme-corp", "FEST15"));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("edition with a script-injection payload fails the charset")
    void editionWithScriptTag_fails() {
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(
                        new RecordCouponCopyRequest("<script>alert(1)</script>", "acme-corp", "FEST15"));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("edition longer than 64 chars fails")
    void editionTooLong_fails() {
        String tooLong = "A".repeat(65);
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest(tooLong, "acme-corp", "FEST15"));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("sponsorSlug with an upper-case letter fails the lower-kebab-case charset")
    void sponsorSlugUpperCase_fails() {
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest("MUMBAI_FESTIVE_2026", "Acme-Corp", "FEST15"));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("sponsorSlug with a leading hyphen fails")
    void sponsorSlugLeadingHyphen_fails() {
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest("MUMBAI_FESTIVE_2026", "-acme-corp", "FEST15"));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("sponsorSlug with a space fails")
    void sponsorSlugWithSpace_fails() {
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest("MUMBAI_FESTIVE_2026", "acme corp", "FEST15"));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("couponCode with a lower-case letter fails the charset")
    void couponCodeLowerCase_fails() {
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest("MUMBAI_FESTIVE_2026", "acme-corp", "fest15"));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("couponCode longer than 50 chars fails")
    void couponCodeTooLong_fails() {
        String tooLong = "A".repeat(51);
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest("MUMBAI_FESTIVE_2026", "acme-corp", tooLong));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("null fields all fail validation (@NotBlank)")
    void nullFields_fail() {
        Set<ConstraintViolation<RecordCouponCopyRequest>> violations =
                validator.validate(new RecordCouponCopyRequest(null, null, null));
        assertFalse(violations.isEmpty());
    }
}
