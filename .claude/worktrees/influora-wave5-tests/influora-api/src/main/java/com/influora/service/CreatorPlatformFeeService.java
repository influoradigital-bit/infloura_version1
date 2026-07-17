package com.influora.service;

import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creatorplatformfee.CreatorPlatformFeeDtos.PlatformFeeResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task #27 (P0-V2, Creator Week 4) — read-only creator platform-fee transparency. Identity is gated
 * via {@link CreatorContextService}; the fee itself is the global DB-backed config — no per-creator
 * data or PII in the response.
 */
@Service
public class CreatorPlatformFeeService {

    public static final String SOURCE_GLOBAL_DEFAULT = "GLOBAL_DEFAULT";

    private final CreatorContextService creatorContext;
    private final PlatformFeeService platformFeeService;

    public CreatorPlatformFeeService(
            CreatorContextService creatorContext, PlatformFeeService platformFeeService) {
        this.creatorContext = creatorContext;
        this.platformFeeService = platformFeeService;
    }

    @Transactional(readOnly = true)
    public PlatformFeeResponse getCurrentFee(AuthPrincipal principal) {
        creatorContext.requireCreatorProfile(principal);
        int feeBps = platformFeeService.resolveCreatorFeeBps();
        double feePercent =
                BigDecimal.valueOf(feeBps)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                        .doubleValue();
        return new PlatformFeeResponse(feeBps, feePercent, SOURCE_GLOBAL_DEFAULT);
    }
}
