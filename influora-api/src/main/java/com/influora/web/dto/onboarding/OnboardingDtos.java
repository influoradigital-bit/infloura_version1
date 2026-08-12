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

    /**
     * OB-2 (BrandF.md §102) — server-authoritative onboarding state, read fresh from the
     * {@code User} row rather than trusted from a client-cached token/localStorage flag. Backs
     * {@code GET /onboarding/brand/status}, the guard `/brand/dashboard` previously had no
     * server-side equivalent of.
     *
     * <p>OB-1 (BrandF.md §105/§91) extension: {@code kycPromptDismissed} is the server-side
     * mirror of the KYC prompt's "skip for now" state ({@code User.kycPromptDismissed}), read
     * here so a frontend can hide the prompt across devices instead of relying on its
     * per-browser localStorage flag alone. This is deliberately a separate field from workspace
     * KYC/verification status ({@code GET /workspaces/me} → {@code verificationStatus}) — a
     * brand can be {@code UNVERIFIED} and have still explicitly dismissed the nag; the frontend
     * is expected to hide the prompt when EITHER {@code kycPromptDismissed} is true OR
     * {@code verificationStatus != UNVERIFIED}.
     */
    public record OnboardingStatusResponse(boolean onboardingCompleted, boolean kycPromptDismissed) {}

    /** POST /onboarding/brand/kyc-prompt-dismissed — no request body, principal-scoped. */
    public record KycPromptDismissedResponse(boolean kycPromptDismissed) {}

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

    /**
     * POST /onboarding/creator/socials — matches connectCreatorSocial(platform, oauthCode) in
     * src/lib/api.ts, but nothing in the live frontend calls it any more: Instagram now goes
     * through the real Meta OAuth flow (MetaOAuthController, CR-120) and creator-onboarding.tsx
     * shows an honest "coming soon" toast for YouTube/TikTok/Twitter without ever posting here.
     * {@code oauthCode} stays required/non-blank as the hook point for a future real exchange
     * (CR-119's scope), but the service (CR-108) now always rejects the call with a typed
     * NOT_IMPLEMENTED rather than faking a successful connection.
     */
    public record CreatorSocialRequest(
            @NotBlank @Size(max = 32) String platform, @NotBlank String oauthCode) {}

    /**
     * CR-108: this response shape is retained for the day real per-platform OAuth lands (CR-119),
     * but {@code CreatorOnboardingService.connectSocial} never actually returns it today — it
     * throws a typed {@code SOCIAL_OAUTH_NOT_IMPLEMENTED} instead of fabricating {@code
     * followers(0)}/{@code verified(false)} the way it used to. See that method's javadoc for why.
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
