package com.influora.service.meera.tool.creator;

import com.influora.common.Rendered;
import com.influora.domain.entity.CreatorProfile;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.rates.RateAddOns;
import com.influora.service.rates.RateQuoteService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.meera.CreatorToolDtos.EstimateMyRateResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.6) — {@code estimate_my_rate}: what this
 * package should cost, and where that number came from.
 *
 * <p>All the pricing lives in {@link RateQuoteService}; this class is the thin adapter between a
 * model-proposed {@code Map<String, Object>} and that service's typed signature. It deliberately
 * holds no arithmetic of its own — a second opinion on the creator's rate computed here would be a
 * second source of truth for the one number she is about to quote a brand.
 *
 * <p><b>{@code deal_id} / {@code brief_id} choose the audit context and nothing else.</b> SPEC.md
 * &sect;3.6 calls them "for context", and &sect;14.1.f's {@code RATE_QUOTE_ISSUED} row carries which
 * surface a quote was asked from so quoted-versus-realised is measurable per surface. They are
 * <b>not</b> resolved, ownership-checked, or read: nothing about the quote depends on them, so
 * loading a deal to honour them would be a repository read that can only fail. Only the label
 * reaches the audit row — never the caller-supplied id, which is unverified by construction.
 */
@Service
public class EstimateMyRateExecutor {

    /** A model asked for "a quote" with no list at all still gets the smallest real package. */
    private static final List<DeliverableSlot> ONE_REEL = List.of(new DeliverableSlot("REEL", 1));

    /**
     * A package the creator could actually deliver. Ten lines is already more than any real brief
     * asks for; beyond that the model has looped, and pricing 400 slots is a way to spend a turn
     * building a card nobody can read.
     */
    private static final int MAX_DELIVERABLE_LINES = 10;

    /** Per line. Matches the cap {@code DealDtos.DeliverableSlot} carries on the brand side. */
    private static final int MAX_QTY = 50;

    private final CreatorAgentPreferencesService preferencesService;
    private final RateQuoteService rateQuoteService;

    public EstimateMyRateExecutor(
            CreatorAgentPreferencesService preferencesService, RateQuoteService rateQuoteService) {
        this.preferencesService = preferencesService;
        this.rateQuoteService = rateQuoteService;
    }

    /**
     * @param creatorUserId the {@code users.id} off the verified on-behalf JWT — never a body value
     * @param input {@code deliverables} (list of {@code {type, qty}}), optional {@code add_ons}
     *     (codes from {@link RateAddOns#CODES}), optional {@code brand_budget_inr}, optional
     *     {@code deal_id} or {@code brief_id}
     */
    @Transactional(readOnly = true)
    public EstimateMyRateResult execute(String creatorUserId, Map<String, Object> input) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);
        PreferencesResponse prefs = preferencesService.getOrCreatePreferences(creatorUserId);

        return new EstimateMyRateResult(
                rateQuoteService.quote(
                        profile,
                        prefs,
                        deliverablesOf(input),
                        addOnCodesOf(input),
                        budgetOf(input),
                        localeFor(prefs),
                        contextOf(input)));
    }

    /**
     * Reads the {@code deliverables} array.
     *
     * <p>The {@code type} is passed through as the free string the wire carries;
     * {@code QuoteDeliverableType.parse} is what understands it, and it maps anything unknown to a
     * neutral weight rather than throwing (SPEC.md &sect;4.1). Re-validating the vocabulary here
     * would turn "the model said {@code IG reel}" into a failed turn instead of a priced one.
     *
     * <p>An absent, empty or entirely unusable list becomes one reel rather than a 400: a creator
     * who typed "what should I charge?" has asked a real question, and the smallest real package is
     * a better answer than a validation error she has to be told about. That is also exactly what
     * {@code RateQuoteService} does internally with an empty list, so this only makes the behaviour
     * visible at the boundary.
     */
    private static List<DeliverableSlot> deliverablesOf(Map<String, Object> input) {
        List<Map<String, Object>> raw = ToolInput.mapList(input, "deliverables");
        List<DeliverableSlot> slots = new ArrayList<>();
        for (Map<String, Object> line : raw) {
            if (slots.size() >= MAX_DELIVERABLE_LINES) {
                break;
            }
            String type = ToolInput.optionalString(line, "type");
            if (type == null) {
                continue;
            }
            slots.add(new DeliverableSlot(type, ToolInput.intOr(line, "qty", 1, 1, MAX_QTY)));
        }
        return slots.isEmpty() ? ONE_REEL : List.copyOf(slots);
    }

    /**
     * Reads {@code add_ons}, keeping only codes {@link RateAddOns} actually prices, upper-cased and
     * de-duplicated.
     *
     * <p>Filtering rather than rejecting: an unknown code is a model guessing at a rider name, and
     * the right answer is a quote without that rider, not a refused tool call. A duplicate would
     * otherwise charge the same rider twice on one package.
     */
    private static List<String> addOnCodesOf(Map<String, Object> input) {
        Set<String> codes = new LinkedHashSet<>();
        for (String raw : ToolInput.stringList(input, "add_ons")) {
            String code = raw.toUpperCase(Locale.ROOT);
            if (RateAddOns.CODES.contains(code)) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /**
     * The brand's stated budget, which is what turns a quote into a recommended move (SPEC.md
     * &sect;4.3 step 7).
     *
     * <p>A non-positive or unparseable figure is dropped to null rather than passed on: zero is not
     * a budget, and {@code recommendedMove} branches on "is there a budget at all", so a zero would
     * read as an offer of nothing and recommend {@code DECLINE} on a brief where the brand simply
     * had not said.
     */
    private static BigDecimal budgetOf(Map<String, Object> input) {
        BigDecimal budget = ToolInput.decimal(input, "brand_budget_inr");
        return budget != null && budget.signum() > 0 ? budget : null;
    }

    /** See the class javadoc: the label only, never the caller-supplied id. */
    private static String contextOf(Map<String, Object> input) {
        if (ToolInput.optionalString(input, "deal_id") != null) {
            return RateQuoteService.CONTEXT_DEAL;
        }
        if (ToolInput.optionalString(input, "brief_id") != null) {
            return RateQuoteService.CONTEXT_BRIEF;
        }
        return RateQuoteService.CONTEXT_CHAT;
    }

    private static Locale localeFor(PreferencesResponse prefs) {
        String tag = prefs == null ? null : prefs.creatorLanguage();
        return tag == null || tag.isBlank() ? Rendered.DEFAULT_LOCALE : Locale.forLanguageTag(tag);
    }
}
