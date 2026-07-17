package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.PlatformFeeConfig;
import com.influora.domain.enums.MemberRole;
import com.influora.repository.PlatformFeeConfigRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.brandplatformfee.BrandPlatformFeeDtos.PlatformFeeResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task B2 (wiki/tech/BRAND_EXECUTION_PLAN.md) — read-only brand platform-fee transparency.
 * Mirrors {@link CreatorPlatformFeeService}: identity is gated via {@link BrandContextService}
 * (OWNER/ADMIN/MANAGER, same role check as {@code CampaignService.update}), the fee itself is the
 * global DB-backed {@link PlatformFeeConfig} singleton — no per-brand data or PII in the response.
 * Reads {@link PlatformFeeConfigRepository} directly rather than going through {@code
 * PlatformFeeService} (that service is scoped to the creator-side escrow-release deduction path
 * and is being touched concurrently by other in-flight work) to keep this addition isolated.
 */
@Service
public class BrandPlatformFeeService {

    public static final String SOURCE_GLOBAL_DEFAULT = "GLOBAL_DEFAULT";

    /**
     * CEO-mandated brand-facing copy (leadership ruling item 5) — a fixed literal, not derived
     * from {@code brandFeeBps} at runtime. Mirrors the creator-side convention where this kind of
     * user-facing string is server-supplied only where the backlog explicitly scopes it that way;
     * here B2's backlog item explicitly calls for "bps + copy" in the response.
     */
    public static final String BRAND_FEE_COPY =
            "Platform fee (10%) — charged only when your campaign goes live.";

    private final BrandContextService brandContext;
    private final PlatformFeeConfigRepository configRepository;

    public BrandPlatformFeeService(
            BrandContextService brandContext, PlatformFeeConfigRepository configRepository) {
        this.brandContext = brandContext;
        this.configRepository = configRepository;
    }

    @Transactional(readOnly = true)
    public PlatformFeeResponse getCurrentFee(AuthPrincipal principal) {
        var workspace = brandContext.requireBrandWorkspace(principal);
        var member = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);

        int feeBps = requireConfig().getBrandFeeBps();
        double feePercent =
                BigDecimal.valueOf(feeBps)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                        .doubleValue();
        return new PlatformFeeResponse(feeBps, feePercent, SOURCE_GLOBAL_DEFAULT, BRAND_FEE_COPY);
    }

    private PlatformFeeConfig requireConfig() {
        return configRepository
                .findById(PlatformFeeConfig.SINGLETON_ID)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "PLATFORM_FEE_CONFIG_MISSING",
                                        "Platform fee configuration is not initialized",
                                        HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
