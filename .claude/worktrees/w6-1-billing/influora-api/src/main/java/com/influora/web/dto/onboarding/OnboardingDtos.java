package com.influora.web.dto.onboarding;

import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class OnboardingDtos {

    private OnboardingDtos() {}

    public record BrandCompanyRequest(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank
                    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Invalid slug format")
                    @Size(max = 100)
                    String companySlug,
            @NotNull WorkspaceType workspaceType,
            @NotBlank @Size(max = 100) String industry,
            @NotBlank @Size(max = 50) String companySize,
            @Size(max = 500) String websiteUrl,
            @Size(max = 5000) String description,
            @Size(max = 500) String logoUrl) {}

    public record WorkspaceIdResponse(String workspaceId) {}

    public record OkResponse(boolean ok) {}

    public record KycRequest(
            @NotBlank
                    @Pattern(
                            regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
                            message = "Invalid GSTIN format")
                    String gstin,
            @NotBlank @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]{1}$", message = "Invalid PAN format")
                    String pan,
            @NotBlank @Size(max = 500) String gstinDocUrl,
            @NotBlank @Size(max = 500) String panDocUrl) {}

    public record KycResponse(String kycStatus) {}

    public record SlugCheckResponse(String slug, boolean available, java.util.List<String> suggestions) {}

    // -------------------------------------------------------------------------------------------
    // Creator onboarding (N1, Wave 6) — mirrors src/lib/api.ts `onboarding.*Creator*` exactly.
    // -------------------------------------------------------------------------------------------

    /** POST /onboarding/creator/socials — matches connectCreatorSocial(platform, oauthCode). */
    public record CreatorSocialRequest(
            @NotBlank @Size(max = 32) String platform, @NotBlank String oauthCode) {}

    /**
     * No real OAuth token exchange exists yet for any social platform (Instagram/YouTube/etc.) —
     * the frontend itself sends a hardcoded {@code mock_oauth_code} today (creator-onboarding.tsx,
     * "Real flow opens an OAuth popup; we fake the code here for the mock"). Per TECH-STACK.md rule
     * 7 (no fabricated backend contracts / no silent mock data), this endpoint honestly persists the
     * connection with zero real follower data rather than inventing a plausible-looking number —
     * {@code handle}/{@code followers} come back empty/zero until a real OAuth+Insights integration
     * lands and {@code PlatformStatsAggregationJob} (H-10) populates real values.
     */
    public record CreatorSocialResponse(String platform, String handle, long followers) {}

    /** POST /onboarding/creator/profile — matches saveCreatorProfile(payload) field-for-field. */
    public record CreatorProfileRequest(
            @NotBlank @Size(max = 100) String displayName,
            @Size(max = 5000) String bio,
            java.util.List<String> verticals,
            java.util.List<String> languages,
            @Size(max = 100) String city,
            @NotNull java.math.BigDecimal rateMin,
            @NotNull java.math.BigDecimal rateMax) {}

    public record CreatorIdResponse(String creatorId) {}

    /** POST /onboarding/creator/kyc — matches submitCreatorKyc(payload) field-for-field. */
    public record CreatorKycRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]{1}$", message = "Invalid PAN format")
                    String pan,
            @NotBlank @Pattern(regexp = "^[0-9]{4}$", message = "aadhaarLast4 must be exactly 4 digits")
                    String aadhaarLast4,
            @NotBlank @Size(max = 500) String selfieUrl) {}

    /**
     * POST /onboarding/creator/payout — matches saveCreatorPayout(payload)'s discriminated union.
     * Only one of the UPI/bank field groups is populated per {@code method}; validated in
     * {@code CreatorOnboardingService} (a discriminated union doesn't map cleanly onto
     * per-field @NotBlank without rejecting the branch that's legitimately absent).
     */
    public record CreatorPayoutRequest(
            @NotBlank String method,
            String upiId,
            String bankAccount,
            String ifsc,
            String accountName) {}

    public record CreatorPayoutResponse(String payoutId) {}
}
