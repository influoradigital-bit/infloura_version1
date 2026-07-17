package com.influora.web.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BrandRegisterRequest(
        @NotBlank @Size(max = 50) String firstName,
        @NotBlank @Size(max = 50) String lastName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 200) String companyName,
        @Size(max = 100) String industry,
        @Size(max = 50) String companySize,
        @AssertTrue(message = "Terms must be accepted") Boolean acceptedTerms) {}
