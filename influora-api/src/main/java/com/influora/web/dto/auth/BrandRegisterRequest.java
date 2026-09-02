package com.influora.web.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PHONE-0829 Gap A — {@code phone} is OPTIONAL (older clients that don't send it, and
 * {@code creatorRegister}'s sibling DTO, are unaffected) and normalized/validated server-side
 * against the same strict Indian-mobile rule as creator phone capture (never trusts the client's
 * own regex at src/components/brand/onboarding/onboarding-steps.tsx:465) — see {@code
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
