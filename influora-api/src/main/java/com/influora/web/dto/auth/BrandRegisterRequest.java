package com.influora.web.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PHONE-0904 Q8 ruling (Swapnil, {@code wiki/reports/phone-0904-signoff-qa.md}) — {@code phone} is
 * now REQUIRED for brand registration; a missing/blank value is rejected server-side. Deliberately
 * NOT enforced with {@code @NotBlank} here: that would fold the failure into Bean Validation's
 * generic {@code MethodArgumentNotValidException} handling (an unreadable field-errors body), and
 * would be indistinguishable from a malformed number. Instead {@code AuthService#brandRegister}
 * throws its own {@code PHONE_REQUIRED}/400 up front, kept deliberately separate from
 * {@code INVALID_PHONE}/400 (present but malformed) and {@code PHONE_ALREADY_EXISTS}/409
 * (duplicate) so the client can branch on the code and put the right inline message on the field
 * (Q6). PHONE-0906 (Swapnil) later applied the same rule to {@link CreatorRegisterRequest}, which
 * now carries its own required {@code phone} — the "creator phone capture is optional everywhere"
 * standing ruling this paragraph used to cite is superseded for the SIGNUP surface only; creator
 * onboarding step 2 and creator Settings stay optional as EDIT surfaces.
 *
 * <p>When present, normalized/validated server-side against the same strict Indian-mobile rule as
 * creator phone capture (never trusts the client's own regex at
 * src/components/brand/onboarding/onboarding-steps.tsx:465) — see {@code
 * AuthService#brandRegister} and {@code com.influora.common.IndianPhoneUtils}. Persisted to {@code
 * users.phone_number} (the person's own mobile at signup — same field creator phone capture
 * writes to), NOT {@code workspaces.phone} (that column is the separate business-contact number,
 * updated later via {@code PATCH /workspaces/me}).
 */
public record BrandRegisterRequest(
        @NotBlank @Size(max = 50) String firstName,
        @NotBlank @Size(max = 50) String lastName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 200) String companyName,
        @Size(max = 100) String industry,
        @Size(max = 50) String companySize,
        @AssertTrue(message = "Terms must be accepted") Boolean acceptedTerms,
        @Size(max = 20) String phone) {}
