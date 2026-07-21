package com.influora.service.meera.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.BrandProfile;
import com.influora.repository.BrandProfileRepository;
import com.influora.service.AuditLogService;
import com.influora.web.dto.meera.MeeraToolDtos.CalculateBudgetResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * R-tier executor (06-MEERA-PERMISSIONS-MATRIX.md row 4): "Suggest pool size + per-reel rate from
 * product price + goal." Pure computation, writes nothing to any table — the DoD explicitly
 * requires "writes nothing; returns advisory numbers" and this class has no repository
 * dependency that lets it MUTATE anything — {@link BrandProfileRepository} here is read-only,
 * used solely to re-derive {@code price_source} server-side (Kabir C1 re-confirm, see below).
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditLogService auditLogService;
    private final BrandProfileRepository brandProfileRepository;

    public CalculateBudgetExecutor(
            AuditLogService auditLogService, BrandProfileRepository brandProfileRepository) {
        this.auditLogService = auditLogService;
        this.brandProfileRepository = brandProfileRepository;
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

        // C1 re-confirm (Kabir P1-B re-audit, 2026-07-21): `price_source` is NEVER read from the
        // tool-call `input` anymore -- schemas.py no longer even declares the field. A model could
        // otherwise self-certify a guessed price as "scraped" and suppress the caveat below, which
        // is exactly the defeat Kabir demonstrated. Instead we re-derive provenance from SERVER
        // state: look up this workspace's persisted BrandProfile.productCatalogJson (the same
        // catalog Python wrote at analyze-site time, forwarded verbatim by
        // AnalyzeSiteTriggerService/BrandContextAssembler) and match the entry whose price equals
        // the product_price this tool call is computing against. Fail-safe: no BrandProfile, no
        // catalog, unparsable JSON, or no matching entry -> "inferred" (never "scraped") -- unknown
        // provenance is never assumed to be a verified fact.
        String priceSource = resolvePriceSourceFromServerState(workspaceId, productPrice);
        boolean priceIsScraped = "scraped".equalsIgnoreCase(priceSource);
        String priceConfidence = priceIsScraped ? "scraped" : "inferred";

        BigDecimal multiplier = multiplierForGoal(goal);
        BigDecimal perCreatorRate =
                productPrice == null
                        ? BigDecimal.ZERO
                        : productPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        BigDecimal poolTotal = perCreatorRate.multiply(BigDecimal.valueOf(effectiveCreatorCount));

        String rationale;
        if (productPrice == null) {
            rationale =
                    "No product price supplied — suggested figures are placeholders; ask the brand"
                            + " for a product price to refine this estimate.";
        } else {
            rationale =
                    "Suggested per-creator rate is ~"
                            + multiplier.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                            + "% of product price (goal: "
                            + (goal != null ? goal : "unspecified")
                            + "), scaled to "
                            + effectiveCreatorCount
                            + " creators. The charged amount at commit is always re-derived by Spring"
                            + " and may differ from this advisory figure.";
            if (!priceIsScraped) {
                rationale +=
                        " Note: this product price is an ESTIMATE, not a confirmed scraped price —"
                                + " phrase this to the brand as based on an estimated price, not a"
                                + " quoted fact.";
            }
        }

        auditLogService.recordToolCall(
                workspaceId,
                "calculate_budget",
                "R",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of("advisory", true, "price_confidence", priceConfidence));

        return new CalculateBudgetResult(
                poolTotal, perCreatorRate, effectiveCreatorCount, "INR", rationale, priceConfidence);
    }

    /**
     * Re-derives {@code price_source} from persisted server state instead of trusting the model's
     * tool-call input (Kabir C1 re-confirm). Matches the tool call's {@code product_price} against
     * this workspace's persisted {@code BrandProfile.productCatalogJson} entries by numeric price
     * equality (the schema carries no product name/slug to match on more precisely). Returns
     * {@code "inferred"} whenever a match can't be established with confidence — missing profile,
     * missing/blank catalog, unparsable JSON, no price on the call, or no matching entry.
     */
    @SuppressWarnings("unchecked")
    private String resolvePriceSourceFromServerState(String workspaceId, BigDecimal productPrice) {
        if (workspaceId == null || productPrice == null) {
            return "inferred";
        }
        BrandProfile brandProfile = brandProfileRepository.findByWorkspaceId(workspaceId).orElse(null);
        if (brandProfile == null) {
            return "inferred";
        }
        String catalogJson = brandProfile.getProductCatalogJson();
        if (catalogJson == null || catalogJson.isBlank()) {
            return "inferred";
        }
        List<Map<String, Object>> catalog;
        try {
            catalog = MAPPER.readValue(catalogJson, List.class);
        } catch (Exception e) {
            return "inferred";
        }
        if (catalog == null) {
            return "inferred";
        }
        for (Map<String, Object> entry : catalog) {
            if (entry == null) {
                continue;
            }
            BigDecimal entryPrice = decimalArg(entry, "price");
            if (entryPrice != null && entryPrice.compareTo(productPrice) == 0) {
                Object entrySource = entry.get("price_source");
                String entrySourceStr = entrySource == null ? null : String.valueOf(entrySource);
                return "scraped".equalsIgnoreCase(entrySourceStr) ? "scraped" : "inferred";
            }
        }
        return "inferred";
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
