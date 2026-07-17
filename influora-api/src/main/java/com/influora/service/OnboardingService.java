package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.SlugUtils;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.VerificationStatus;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.onboarding.OnboardingDtos.BrandCompanyRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OkResponse;
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

    public OnboardingService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            BrandContextService brandContext,
            WorkspaceSlugService slugService,
            com.influora.service.brand.AnalyzeSiteTriggerService analyzeSiteTrigger) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.brandContext = brandContext;
        this.slugService = slugService;
        this.analyzeSiteTrigger = analyzeSiteTrigger;
    }

    @Transactional
    public WorkspaceIdResponse saveBrandCompany(AuthPrincipal principal, BrandCompanyRequest req) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
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

    @Transactional
    public KycResponse submitBrandKyc(AuthPrincipal principal, KycRequest req) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);

        workspace.applyKyc(
                req.gstin().toUpperCase(),
                req.pan().toUpperCase(),
                req.gstinDocUrl(),
                req.panDocUrl());

        workspaceRepository.save(workspace);
        return new KycResponse(VerificationStatus.PENDING.name());
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
