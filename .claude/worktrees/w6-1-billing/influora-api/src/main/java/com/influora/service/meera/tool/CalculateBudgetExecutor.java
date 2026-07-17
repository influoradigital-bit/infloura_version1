package com.influora.service.meera.tool;

import com.influora.service.AuditLogService;
import com.influora.web.dto.meera.MeeraToolDtos.CalculateBudgetResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * R-tier executor (06-MEERA-PERMISSIONS-MATRIX.md row 4): "Suggest pool size + per-reel rate from
 * product price + goal." Pure computation, writes nothing to any table — the DoD explicitly
 * requires "writes nothing; returns advisory numbers" and this class has no repository
 * dependency at all, so there is no code path by which it could persist a row.
 *
 * <p>The number this method returns is advisory only. It is NEVER read back by
 * {@code RequestPaymentExecutor} or any commit-tier executor — the charged amount at commit time
 * is always re-derived independently by {@code AmountDerivationService} from
 * {@code campaign_intents.product_price}, never from this suggestion (Kabir G1).
 */
@Service
public class CalculateBudgetExecutor {

    private static final BigDecimal DEFAULT_PER_CREATOR_MULTIPLIER = new BigDecimal("0.10");
    private static final int DEFAULT_CREATOR_COUNT = 5;

    private final AuditLogService auditLogService;

    public CalculateBudgetExecutor(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public CalculateBudgetResult execute(String workspaceId, Map<String, Object> input) {
        // Field names match app/tools/schemas.py CALCULATE_BUDGET input_schema exactly:
        // product_price (required, number), goal (required enum: awareness|launch|conversion|review).
        // creator_count is NOT part of the schema but tolerated if present (extra fields ignored
        // elsewhere are simply absent here) since it lets a future schema revision widen this
        // executor without a Spring-side change.
        BigDecimal productPrice = decimalArg(input, "product_price");
        String goal = stringArg(input, "goal");
        Integer creatorCount = intArg(input, "creator_count");
        int effectiveCreatorCount = creatorCount != null && creatorCount > 0 ? creatorCount : DEFAULT_CREATOR_COUNT;

        BigDecimal multiplier = multiplierForGoal(goal);
        BigDecimal perCreatorRate =
                productPrice == null
                        ? BigDecimal.ZERO
                        : productPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        BigDecimal poolTotal = perCreatorRate.multiply(BigDecimal.valueOf(effectiveCreatorCount));

        String rationale =
                productPrice == null
                        ? "No product price supplied — suggested figures are placeholders; ask the brand"
                                + " for a product price to refine this estimate."
                        : "Suggested per-creator rate is ~"
                                + multiplier.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                                + "% of product price (goal: "
                                + (goal != null ? goal : "unspecified")
                                + "), scaled to "
                                + effectiveCreatorCount
                                + " creators. The charged amount at commit is always re-derived by Spring"
                                + " and may differ from this advisory figure.";

        auditLogService.recordToolCall(
                workspaceId,
                "calculate_budget",
                "R",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of("advisory", true));

        return new CalculateBudgetResult(poolTotal, perCreatorRate, effectiveCreatorCount, "INR", rationale);
    }

    /** Goal-based multiplier — matches the schema's enum (awareness|launch|conversion|review). */
    private static BigDecimal multiplierForGoal(String goal) {
        if (goal == null) {
            return DEFAULT_PER_CREATOR_MULTIPLIER;
        }
        return switch (goal.toLowerCase(Locale.ROOT)) {
            case "awareness" -> new BigDecimal("0.08");
            case "launch" -> new BigDecimal("0.12");
            case "conversion" -> new BigDecimal("0.15");
            case "review" -> new BigDecimal("0.06");
            default -> DEFAULT_PER_CREATOR_MULTIPLIER;
        };
    }

    private static String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimalArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
