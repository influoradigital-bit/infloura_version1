package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.SlugUtils;
import com.influora.config.R2Properties;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.onboarding.OnboardingDtos.BrandCompanyRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycPromptDismissedResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.KycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OkResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OnboardingStatusResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.WorkspaceIdResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingService {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final BrandContextService brandContext;
    private final WorkspaceSlugService slugService;
    private final com.influora.service.brand.AnalyzeSiteTriggerService analyzeSiteTrigger;

    /** [F-0390 D4] KYC-doc read-path key resolution — see {@link #resolveKycDocUrl}. */
    private final R2StorageService r2StorageService;

    private final R2Properties r2Properties;

    public OnboardingService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            BrandContextService brandContext,
            WorkspaceSlugService slugService,
            com.influora.service.brand.AnalyzeSiteTriggerService analyzeSiteTrigger,
            R2StorageService r2StorageService,
            R2Properties r2Properties) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.brandContext = brandContext;
        this.slugService = slugService;
        this.analyzeSiteTrigger = analyzeSiteTrigger;
        this.r2StorageService = r2StorageService;
        this.r2Properties = r2Properties;
    }

    @Transactional
    public WorkspaceIdResponse saveBrandCompany(AuthPrincipal principal, BrandCompanyRequest req) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        requireOwnerOrAdmin(principal, workspace);
        String slug = SlugUtils.slugify(req.companySlug());
        slugService.ensureSlugAvailable(slug, workspace.getId());

        String oldWebsiteUrl = workspace.getWebsiteUrl();
        workspace.applyCompanyDetails(
                req.companyName().trim(),
                slug,
                req.workspaceType(),
                req.industry(),
                req.companySize(),
                req.websiteUrl(),
                req.description(),
                req.logoUrl());

        workspaceRepository.save(workspace);

        // W4-2 / A10 / TrendSpark — trigger brand website analysis when URL is provided/changed
        if (hasChanged(oldWebsiteUrl, req.websiteUrl())) {
            analyzeSiteTrigger.trigger(workspace.getId(), req.websiteUrl());
        }

        return new WorkspaceIdResponse(workspace.getId());
    }

    @Transactional
    public OkResponse completeBrand(AuthPrincipal principal) {
        brandContext.requireBrand(principal);
        User user =
                userRepository
                        .findById(principal.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));

        user.setOnboardingCompleted(true);
        userRepository.save(user);
        return new OkResponse(true);
    }

    /**
     * OB-2 (BrandF.md §102) — server-side read of onboarding status. Previously
     * {@code user.isOnboardingCompleted()} was read in exactly two places
     * ({@code AuthService}/{@code CreatorProfileService}), both only to copy the value into a
     * login-time response DTO; nothing let a caller re-check it fresh, per request, without a new
     * login. This is that read: it exists so a route guard (or any other caller) can ask the
     * server for the current, authoritative value instead of trusting a stale client-side copy.
     */
    @Transactional(readOnly = true)
    public OnboardingStatusResponse getBrandOnboardingStatus(AuthPrincipal principal) {
        User user = requireBrandUser(principal);
        return new OnboardingStatusResponse(
                user.isOnboardingCompleted() || isGuestInCurrentWorkspace(principal),
                user.isKycPromptDismissed());
    }

    /**
     * {@code onboarding_completed} lives on the USER and records whether they finished setting up
     * the workspace they OWN. It says nothing about a workspace they were invited into: that one
     * was set up by its owner. Reporting the raw flag there sent an invitee whose own onboarding
     * was unfinished to the onboarding wizard while their session was scoped to the INVITED
     * workspace — and the wizard's company step writes to whatever workspace the session names.
     * A member who is not the OWNER of the current workspace therefore has nothing to onboard in
     * it. (The write itself is independently refused by {@link #requireOwnerOrAdmin}.)
     */
    private boolean isGuestInCurrentWorkspace(AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        WorkspaceMember member = brandContext.requireMember(principal, workspace.getId());
        return member.getRole() != MemberRole.OWNER;
    }

    /**
     * The onboarding company step and the KYC submission both rewrite the WORKSPACE (its name and
     * slug; its verification status, which gates campaign publishing). They used to check only
     * that the caller's session named the workspace, so any member — a VIEWER, or an invitee who
     * had just been switched into it — could rename the brand or knock a VERIFIED workspace back
     * to PENDING. Same gate {@code WorkspaceService#updateMyWorkspace} applies to the settings
     * page's edit of the very same fields. A brand-new signup is the OWNER of their own
     * workspace, so the signup wizard is unaffected.
     */
    private void requireOwnerOrAdmin(AuthPrincipal principal, Workspace workspace) {
        WorkspaceMember member = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);
    }

    /**
     * OB-1 (BrandF.md §105/§91) — persists "brand skipped the KYC prompt" server-side so the
     * dismissal survives across devices instead of living only in the client's localStorage flag.
     * Idempotent: dismissing an already-dismissed prompt is a no-op write, not an error.
     */
    @Transactional
    public KycPromptDismissedResponse dismissBrandKycPrompt(AuthPrincipal principal) {
        User user = requireBrandUser(principal);
        if (!user.isKycPromptDismissed()) {
            user.dismissKycPrompt();
            userRepository.save(user);
        }
        return new KycPromptDismissedResponse(true);
    }

    private User requireBrandUser(AuthPrincipal principal) {
        brandContext.requireBrand(principal);
        return userRepository
                .findById(principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public KycResponse submitBrandKyc(AuthPrincipal principal, KycRequest req) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        requireOwnerOrAdmin(principal, workspace);

        workspace.applyKyc(
                req.gstin().toUpperCase(),
                req.pan().toUpperCase(),
                req.gstinDocUrl(),
                req.panDocUrl());

        workspaceRepository.save(workspace);
        return new KycResponse(VerificationStatus.PENDING.name());
    }

    /**
     * [F-0390 D4] Resolves a stored {@code Workspace.kycGstinDocUrl}/{@code kycPanDocUrl} value —
     * bare R2 key (new, post-D4 uploads via {@code uploadForPurpose(..., "brand_kyc_gstin_doc"/
     * "brand_kyc_pan_doc")}) OR a legacy permanent public URL (pre-D4 rows, still world-readable if
     * the bucket is public-read) — to a short-lived presigned GET. Mirrors {@code
     * PortfolioService#resolveCoverUrl}/{@code #toCoverObjectKey} exactly (same codebase convention
     * of a private, per-service copy rather than a shared util — see also {@code
     * CreatorDeliverableService#resolveDownloadUrl}/{@code #toObjectKey}).
     *
     * <p><b>Not called anywhere in this codebase yet.</b> An exhaustive search (every {@code
     * @RestController}/response DTO under {@code web/dto/}, {@code AdminBrandService}, {@code
     * AdminCreatorService}) turned up NO existing endpoint that ever serves {@code
     * kycGstinDocUrl}/{@code kycPanDocUrl}/{@code selfieUrl} back to any client — {@code
     * KycResponse} (this class's {@code submitBrandKyc} return value) carries only {@code
     * kycStatus}. Implemented and unit-tested per the brief (a real read path must not regress a
     * bare key back into a permanent public URL, whenever one is added), but wiring it into a
     * specific response is a product decision this fix does not make unasked — reported, not
     * guessed.
     */
    String resolveKycDocUrl(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String key = toKycDocObjectKey(stored);
        if (key == null || !r2StorageService.isAvailable()) {
            return stored;
        }
        try {
            return r2StorageService.presignGet(key).uploadUrl();
        } catch (RuntimeException e) {
            return stored;
        }
    }

    private String toKycDocObjectKey(String stored) {
        if (!stored.startsWith("http://") && !stored.startsWith("https://")) {
            return stored;
        }
        String base = r2Properties.getPublicUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (stored.startsWith(base + "/")) {
            return stored.substring(base.length() + 1);
        }
        return null;
    }

    private static boolean hasChanged(String oldValue, String newValue) {
        String oldNormalized = oldValue != null && !oldValue.isBlank() ? oldValue.trim() : null;
        String newNormalized = newValue != null && !newValue.isBlank() ? newValue.trim() : null;
        // Both blank → no change
        if (oldNormalized == null && newNormalized == null) {
            return false;
        }
        // One blank, one non-blank → changed ONLY if newValue is non-blank (setting URL for first
        // time or changing it); clearing to blank does NOT count as a trigger-worthy change.
        if (oldNormalized == null) {
            return newNormalized != null;
        }
        if (newNormalized == null) {
            return false;
        }
        // Both non-blank → check equality
        return !oldNormalized.equals(newNormalized);
    }
}
