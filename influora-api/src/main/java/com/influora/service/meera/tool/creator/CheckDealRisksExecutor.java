package com.influora.service.meera.tool.creator;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.risk.DealRiskService;
import com.influora.web.dto.meera.CreatorToolDtos.CheckDealRisksResult;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.6) — {@code check_deal_risks}: the reason codes
 * for one deal or one pasted brief, most severe first.
 *
 * <p>Every rule, every threshold and every rendered string lives in {@link DealRiskService}; this
 * class resolves the creator's profile, picks the entry point, and shapes the result. It holds no
 * rule of its own, so the flags Meera reads in chat and the flags the deal page renders can never
 * be two different opinions.
 *
 * <p><b>Ownership is enforced downstream, on the id space that actually owns the row.</b> Both
 * {@code DealRiskService} entry points take a {@code creator_profiles.id} and scope their lookup to
 * it ({@code findByIdAndCreatorId}, {@code findByIdAndCreatorProfileId}), so another creator's deal
 * id is a 404 rather than someone else's floors. That is why this executor passes
 * {@code profile.getId()} and never a body-supplied id.
 */
@Service
public class CheckDealRisksExecutor {

    private final CreatorAgentPreferencesService preferencesService;
    private final DealRiskService dealRiskService;

    public CheckDealRisksExecutor(
            CreatorAgentPreferencesService preferencesService, DealRiskService dealRiskService) {
        this.preferencesService = preferencesService;
        this.dealRiskService = dealRiskService;
    }

    /**
     * @param creatorUserId the {@code users.id} off the verified on-behalf JWT — never a body value
     * @param input exactly one of {@code deal_id} or {@code brief_id}
     */
    @Transactional(readOnly = true)
    public CheckDealRisksResult execute(String creatorUserId, Map<String, Object> input) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);

        String dealId = ToolInput.optionalString(input, "deal_id");
        String briefId = ToolInput.optionalString(input, "brief_id");

        // SPEC.md §3.6: "one of deal_id, brief_id". Both is refused rather than silently
        // preferring one -- a model that sent both does not know which target it meant, and
        // answering about the deal while it narrates the brief is worse than asking again.
        if (dealId != null && briefId != null) {
            throw new ApiException(
                    "AMBIGUOUS_RISK_TARGET",
                    "Pass exactly one of deal_id or brief_id, not both",
                    HttpStatus.BAD_REQUEST);
        }
        if (dealId == null && briefId == null) {
            throw new ApiException(
                    "RISK_TARGET_REQUIRED",
                    "Pass a deal_id or a brief_id to check",
                    HttpStatus.BAD_REQUEST);
        }

        boolean isDeal = dealId != null;
        String targetId = isDeal ? dealId : briefId;

        // Already sorted CRITICAL, WARN, INFO by DealRiskService.evaluate -- re-sorting here would
        // be a second ordering to keep in step with the first.
        List<RiskFlag> flags =
                isDeal
                        ? dealRiskService.evaluateDeal(profile.getId(), dealId)
                        : dealRiskService.evaluateBrief(profile.getId(), briefId);

        return new CheckDealRisksResult(
                flags,
                highestSeverity(flags),
                isDeal ? DealRiskService.TARGET_DEAL : DealRiskService.TARGET_BRIEF,
                targetId);
    }

    /**
     * The first flag's severity, which is the highest because the list arrives sorted.
     *
     * <p>Null on a clean deal, and {@code CheckDealRisksResult} is {@code NON_NULL}, so "no flags"
     * reaches the model as an absent key rather than a string it might read as a severity level of
     * its own.
     */
    private static String highestSeverity(List<RiskFlag> flags) {
        return flags == null || flags.isEmpty() ? null : flags.get(0).severity();
    }
}
