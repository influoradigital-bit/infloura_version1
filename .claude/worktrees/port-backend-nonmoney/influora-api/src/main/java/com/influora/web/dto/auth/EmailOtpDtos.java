package com.influora.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class EmailOtpDtos {

    private EmailOtpDtos() {}

    public record SendEmailOtpRequest(@NotBlank @Email String email) {}

    public record SendEmailOtpResponse(String message, long expiresIn, String maskedEmail) {}

    public record VerifyEmailOtpRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String otp) {}

    public record VerifyEmailOtpResponse(boolean emailVerified, String message) {}
}
