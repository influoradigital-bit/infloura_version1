package com.influora.service.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ExclusivityScope;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.risk.RiskContext.ActiveDeal;
import com.influora.service.risk.rules.BarterRule;
import com.influora.service.risk.rules.BelowFloorRule;
import com.influora.service.risk.rules.BlockedBrandRule;
import com.influora.service.risk.rules.CalendarOverloadRule;
import com.influora.service.risk.rules.CompetitorConflictRule;
import com.influora.service.risk.rules.ExcludedCategoryRule;
import com.influora.service.risk.rules.ExclusivityLongRule;
import com.influora.service.risk.rules.HideDisclosureRule;
import com.influora.service.risk.rules.OffPlatformPaymentRule;
import com.influora.service.risk.rules.PartnershipAdsRequestRule;
import com.influora.service.risk.rules.RegulatedCategoryRule;
import com.influora.service.risk.rules.UsageLongRule;
import com.influora.service.risk.rules.UsagePerpetualRule;
import com.influora.service.risk.rules.VagueDeliverablesRule;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.2/&sect;5.3, B4) — the fourteen rules, each with one
 * test that makes it fire and one that makes it stay quiet.
 *
 * <p><b>Why both halves are mandatory.</b> A firing test alone cannot tell a working rule from a
 * rule that returns its flag unconditionally: both go green. Every {@code does_not_fire} case below
 * is therefore a NEAR MISS rather than a blank context — the exclusivity window that expired
 * yesterday, four channels of five, a category that is not on the excluded list — so each one also
 * pins the threshold it sits just outside of.
 *
 * <p>These tests drive {@link DealRiskService#evaluate(RiskContext, String)} directly, with a
 * {@link RiskContext} built in memory. That is the whole engine minus the two repository loaders,
 * which have their own suite ({@code DealRiskServiceEvaluateDealTest}) — the rules are pure
 * functions and do not need a database to be tested against.
 */
@ExtendWith(MockitoExtension.class)
class DealRiskServiceTest {

    private static final String CREATOR_PROFILE_ID = "01CREATORPROFILE0000000000";
    private static final String CREATOR_USER_ID = "01CREATORUSER00000000000000";
    private static final String BRAND_WORKSPACE_ID = "01BRANDWORKSPACE0000000000";

    /** A fixed clock, so every window/date assertion below is deterministic. */
    private static final Instant NOW = Instant.parse("2026-09-10T09:00:00Z");

    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private CreatorBriefRepository creatorBriefRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private RateQuoteService rateQuoteService;

    private DealRiskService service;

    @BeforeEach
    void setUp() {
        service =
                new DealRiskService(
                        creatorProfileRepository,
                        preferencesService,
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        dealMessageRepository,
                        deliverableRepository,
                        creatorBriefRepository,
                        auditLogService,
                        rateQuoteService);
    }

    // ==================================================================
    // 1. BELOW_FLOOR
    // ==================================================================

    @Test
    @DisplayName("BELOW_FLOOR fires when the offer is under the floor total of the deliverables")
    void belowFloor_fires() {
        Ctx ctx = ctx();
        ctx.ex.deliverables = List.of(new DeliverableLine("REEL", 2)); // 2 x 5,000 floor = 10,000
        ctx.ex.budgetInr = new BigDecimal("8000");

        RiskFlag flag = requireFlag(ctx, BelowFloorRule.CODE);

        assertThat(flag.detail()).isEqualTo("Offer is 8,000 against your floor of 10,000.");
        assertThat(flag.data()).containsEntry("floor_total", "10,000").containsEntry("value", "8,000");
    }

    @Test
    @DisplayName("BELOW_FLOOR stays quiet when the offer exactly meets the floor")
    void belowFloor_doesNotFire() {
        Ctx ctx = ctx();
        ctx.ex.deliverables = List.of(new DeliverableLine("REEL", 2));
        ctx.ex.budgetInr = new BigDecimal("10000"); // exactly the floor: meeting it is not breaching it

        assertSilent(ctx, BelowFloorRule.CODE);
    }

    @Test
    @DisplayName("BELOW_FLOOR tells a NANO creator to scope down and a MACRO creator to counter")
    void belowFloor_actionSplitsOnTier() {
        Ctx nano = ctx();
        nano.ex.budgetInr = new BigDecimal("1000");
        assertThat(requireFlag(nano, BelowFloorRule.CODE).data())
                .containsEntry("tier", "NANO")
                .containsEntry("recommended_move", "SCOPE_DOWN");

        Ctx macro = ctx();
        macro.profile = profileWithFollowers(600_000L);
        macro.ex.budgetInr = new BigDecimal("1000");
        assertThat(requireFlag(macro, BelowFloorRule.CODE).data())
                .containsEntry("tier", "MACRO")
                .containsEntry("recommended_move", "COUNTER_AT_FLOOR");
    }

    // ==================================================================
    // 2. USAGE_PERPETUAL
    // ==================================================================

    @Test
    @DisplayName("USAGE_PERPETUAL fires when the brief claims every one of the five usage channels")
    void usagePerpetual_fires() {
        Ctx ctx = ctx();
        ctx.ex.usageChannels = List.of("ORGANIC", "PAID_ADS", "WHITELISTING", "WEBSITE", "OFFLINE");

        RiskFlag flag = requireFlag(ctx, UsagePerpetualRule.CODE);

        assertThat(flag.detail()).isEqualTo("Brand can use this content forever.");
        assertThat(flag.data()).containsEntry("basis", "ALL_CHANNELS");
    }

    @Test
    @DisplayName("USAGE_PERPETUAL stays quiet on four channels of five")
    void usagePerpetual_doesNotFire() {
        Ctx ctx = ctx();
        ctx.ex.usageChannels = List.of("ORGANIC", "PAID_ADS", "WHITELISTING", "WEBSITE");

        assertSilent(ctx, UsagePerpetualRule.CODE);
    }

    // ==================================================================
    // 3. USAGE_LONG
    // ==================================================================

    @Test
    @DisplayName("USAGE_LONG fires as WARN above twelve months")
    void usageLong_fires() {
        Ctx ctx = ctx();
        ctx.ex.usageMonths = 18;

        RiskFlag flag = requireFlag(ctx, UsageLongRule.CODE);

        assertThat(flag.severity()).isEqualTo("WARN");
        assertThat(flag.detail()).isEqualTo("Usage window 18 months.");
    }

    @Test
    @DisplayName("USAGE_LONG stays quiet when the deal is perpetual — USAGE_PERPETUAL owns that case")
    void usageLong_doesNotFire() {
        Ctx ctx = ctx();
        ctx.ex.usageMonths = 24;
        ctx.ex.usagePerpetual = true;

        assertSilent(ctx, UsageLongRule.CODE);
        assertFires(ctx, UsagePerpetualRule.CODE);
    }

    // ==================================================================
    // 4. EXCLUDED_CATEGORY
    // ==================================================================

    @Test
    @DisplayName("EXCLUDED_CATEGORY fires on a category the creator opted out of, ignoring case")
    void excludedCategory_fires() {
        Ctx ctx = ctx();
        ctx.prefs = prefs().excluded(List.of("gambling", "TOBACCO")).build();
        ctx.ex.category = "Gambling";

        RiskFlag flag = requireFlag(ctx, ExcludedCategoryRule.CODE);

        assertThat(flag.severity()).isEqualTo("CRITICAL");
        assertThat(flag.data()).containsEntry("recommended_move", "DECLINE");
    }

    @Test
    @DisplayName("EXCLUDED_CATEGORY stays quiet on a category that is merely adjacent to an excluded one")
    void excludedCategory_doesNotFire() {
        Ctx ctx = ctx();
        ctx.prefs = prefs().excluded(List.of("GAMBLING")).build();
        ctx.ex.category = "GAMING";

        assertSilent(ctx, ExcludedCategoryRule.CODE);
    }

    // ==================================================================
    // 5. BLOCKED_BRAND
    // ==================================================================

    @Test
    @DisplayName("BLOCKED_BRAND fires on a blocked brand name, trimmed and case-insensitive")
    void blockedBrand_fires() {
        Ctx ctx = ctx();
        ctx.prefs = prefs().blocked(List.of("  Glow Cosmetics ")).build();
        ctx.brandName = "glow cosmetics";

        assertThat(requireFlag(ctx, BlockedBrandRule.CODE).severity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("BLOCKED_BRAND is not a substring match: blocking Glow does not block Glowworm Books")
    void blockedBrand_doesNotFire() {
        Ctx ctx = ctx();
        ctx.prefs = prefs().blocked(List.of("Glow")).build();
        ctx.brandName = "Glowworm Books";

        assertSilent(ctx, BlockedBrandRule.CODE);
    }

    // ==================================================================
    // 6. OFF_PLATFORM_PAYMENT
    // ==================================================================

    @Test
    @DisplayName("OFF_PLATFORM_PAYMENT fires on a UPI mention paired with a pay request, and is not dismissible")
    void offPlatformPayment_fires() {
        // K-2b round 5, Ruling 1: a wallet name alone (e.g. "settle this over UPI") is Influora's
        // own payout vocabulary and no longer a signal by itself — see
        // OffPlatformPaymentRule.WALLET_NAME/.SEND_REQUEST's javadoc. This fixture now pairs the
        // wallet name with an explicit pay request, which is the actual signal.
        Ctx ctx = ctx();
        ctx.text = "Please pay us via UPI once the reel is live.";

        RiskFlag flag = requireFlag(ctx, OffPlatformPaymentRule.CODE);

        assertThat(flag.severity()).isEqualTo("WARN");
        assertThat(flag.dismissible()).isFalse();
        assertThat(flag.detail()).isEqualTo("Paying outside Secure Payments loses dispute cover.");
        assertThat(flag.data()).containsEntry("blocks", "false");
    }

    @Test
    @DisplayName("OFF_PLATFORM_PAYMENT stays quiet on 'impressions' — the word boundary around imps holds")
    void offPlatformPayment_doesNotFire() {
        Ctx ctx = ctx();
        ctx.text = "Share the impressions and reach numbers after 48 hours.";

        assertSilent(ctx, OffPlatformPaymentRule.CODE);
    }

    @Test
    @DisplayName("OFF_PLATFORM_PAYMENT stays quiet on a bare wallet name with no pay request (K-2b round 5, Ruling 1)")
    void offPlatformPayment_bareWalletNameDoesNotFire() {
        Ctx ctx = ctx();
        ctx.text = "We can settle this over UPI once the reel is live.";

        assertSilent(ctx, OffPlatformPaymentRule.CODE);
    }

    // ==================================================================
    // 7. COMPETITOR_CONFLICT
    // ==================================================================

    @Test
    @DisplayName("COMPETITOR_CONFLICT fires while a named-brand exclusivity window is still open")
    void competitorConflict_fires() {
        Ctx ctx = ctx();
        ctx.brandName = "Glow Cosmetics";
        ctx.activeDeals =
                List.of(
                        activeDeal(
                                CollaborationStatus.CONTRACTED,
                                NOW.minus(29, ChronoUnit.DAYS), // day 29 of a 30-day window
                                30,
                                ExclusivityScope.NAMED_BRANDS,
                                List.of("Glow Cosmetics"),
                                "Dewy Skin",
                                "BEAUTY"));

        RiskFlag flag = requireFlag(ctx, CompetitorConflictRule.CODE);

        // appliedAt (now - 29d) + 30d lands one day into the future: still binding, just.
        // "Sept", not "Sep": that is what CLDR's en-IN gives for MMM, and Rendered.date is
        // the one formatter every creator-facing date in Phase B goes through.
        assertThat(flag.detail()).isEqualTo("Conflicts with Dewy Skin until 11 Sept 2026.");
        assertThat(flag.action()).isEqualTo("Propose a start date after 11 Sept 2026.");
    }

    @Test
    @DisplayName("COMPETITOR_CONFLICT stays quiet the moment the window has elapsed, not the next day")
    void competitorConflict_doesNotFire() {
        Ctx ctx = ctx();
        ctx.brandName = "Glow Cosmetics";
        ctx.activeDeals =
                List.of(
                        activeDeal(
                                CollaborationStatus.CONTRACTED,
                                NOW.minus(30, ChronoUnit.DAYS), // appliedAt + 30 days == now, so it is over
                                30,
                                ExclusivityScope.NAMED_BRANDS,
                                List.of("Glow Cosmetics"),
                                "Dewy Skin",
                                "BEAUTY"));

        assertSilent(ctx, CompetitorConflictRule.CODE);
    }

    @Test
    @DisplayName("COMPETITOR_CONFLICT ignores a cancelled deal even inside its window")
    void competitorConflict_ignoresCancelled() {
        Ctx ctx = ctx();
        ctx.brandName = "Glow Cosmetics";
        ctx.activeDeals =
                List.of(
                        activeDeal(
                                CollaborationStatus.CANCELLED,
                                NOW.minus(1, ChronoUnit.DAYS),
                                30,
                                ExclusivityScope.NAMED_BRANDS,
                                List.of("Glow Cosmetics"),
                                "Dewy Skin",
                                "BEAUTY"));

        assertSilent(ctx, CompetitorConflictRule.CODE);
    }

    // ==================================================================
    // 8. EXCLUSIVITY_LONG
    // ==================================================================

    @Test
    @DisplayName("EXCLUSIVITY_LONG fires as WARN at 60 days and prices the lost income")
    void exclusivityLong_fires() {
        Ctx ctx = ctx();
        ctx.ex.exclusivityDays = 60;
        ctx.ex.exclusivityScope = "CATEGORY";

        RiskFlag flag = requireFlag(ctx, ExclusivityLongRule.CODE);

        assertThat(flag.severity()).isEqualTo("WARN");
        // 5,000 reel floor x 60/30 months = 10,000
        assertThat(flag.detail()).isEqualTo("60 days exclusivity " + RiskText.APPROX + " 10,000 at your usual rate.");
        assertThat(flag.cost()).isEqualTo(RiskText.APPROX + " 10,000 of lost income");
    }

    @Test
    @DisplayName("EXCLUSIVITY_LONG is only INFO at 30 days when the creator has no recent work in the category")
    void exclusivityLong_doesNotFireAsWarn() {
        Ctx ctx = ctx();
        ctx.ex.exclusivityDays = 30;
        ctx.ex.exclusivityScope = "CATEGORY";
        ctx.ex.category = "BEAUTY";

        RiskFlag flag = requireFlag(ctx, ExclusivityLongRule.CODE);

        assertThat(flag.severity()).isEqualTo("INFO");
        assertThat(flag.data()).containsEntry("recent_deals_in_category", "false");
    }

    @Test
    @DisplayName("EXCLUSIVITY_LONG stays quiet entirely when no exclusivity was asked for")
    void exclusivityLong_doesNotFire() {
        Ctx ctx = ctx();
        ctx.ex.exclusivityDays = 0;
        ctx.ex.exclusivityScope = "NONE";

        assertSilent(ctx, ExclusivityLongRule.CODE);
    }

    // ==================================================================
    // 9. VAGUE_DELIVERABLES
    // ==================================================================

    @Test
    @DisplayName("VAGUE_DELIVERABLES fires when no deliverable line carries a quantity")
    void vagueDeliverables_fires() {
        Ctx ctx = ctx();
        ctx.ex.deliverables = List.of(new DeliverableLine("REEL", 0));

        RiskFlag flag = requireFlag(ctx, VagueDeliverablesRule.CODE);

        assertThat(flag.data()).containsEntry("basis", "NO_QUANTITIES");
        assertThat(flag.detail())
                .as("a pasted brief is the source on this path, so the sentence names the brief")
                .isEqualTo("The brief does not say how many pieces of content you owe.");
    }

    @Test
    @DisplayName("VAGUE_DELIVERABLES stays quiet on a counted list with no vague phrasing")
    void vagueDeliverables_doesNotFire() {
        Ctx ctx = ctx();
        ctx.ex.deliverables = List.of(new DeliverableLine("REEL", 1), new DeliverableLine("STORY_SET", 1));
        ctx.text = "One reel and one story set, delivered by the fifth.";

        assertSilent(ctx, VagueDeliverablesRule.CODE);
    }

    // ==================================================================
    // 10. HIDE_DISCLOSURE
    // ==================================================================

    @Test
    @DisplayName("HIDE_DISCLOSURE fires on a curly-apostrophe \"don't disclose\" and is not dismissible")
    void hideDisclosure_fires() {
        Ctx ctx = ctx();
        // U+2019, not a typed apostrophe: this is the form every brand pasting from a document
        // sends, and the spec's own don'?t alternative does not match it without RiskText.norm.
        ctx.text = "Please don\u2019t disclose this as a paid partnership.";

        RiskFlag flag = requireFlag(ctx, HideDisclosureRule.CODE);

        assertThat(flag.dismissible()).isFalse();
        assertThat(flag.detail()).isEqualTo("Breaks ASCI guidelines.");
    }

    @Test
    @DisplayName("HIDE_DISCLOSURE stays quiet when the brand ASKS for the disclosure")
    void hideDisclosure_doesNotFire() {
        Ctx ctx = ctx();
        ctx.text = "Please add the paid partnership label and #ad as usual.";

        assertSilent(ctx, HideDisclosureRule.CODE);
    }

    // ==================================================================
    // 11. BARTER
    // ==================================================================

    @Test
    @DisplayName("BARTER fires with MRP, the 40 percent real value, and the cash gap to the floor")
    void barter_fires() {
        Ctx ctx = ctx();
        ctx.ex.barterOnly = true;
        ctx.ex.barterMrpInr = new BigDecimal("12000");

        RiskFlag flag = requireFlag(ctx, BarterRule.CODE);

        // 40% of 12,000 = 4,800; floor for one reel is 5,000; gap = 200
        assertThat(flag.detail()).isEqualTo("Product worth 12,000, about 4,800 real value; cash gap 200.");
        assertThat(flag.cost()).isEqualTo(RiskText.APPROX + " 200 short of your floor");
    }

    @Test
    @DisplayName("BARTER stays quiet when cash is on the table, even with a product on top")
    void barter_doesNotFire() {
        Ctx ctx = ctx();
        ctx.ex.barterOnly = false;
        ctx.ex.barterMrpInr = new BigDecimal("12000");
        ctx.ex.budgetInr = new BigDecimal("20000");

        assertSilent(ctx, BarterRule.CODE);
    }

    @Test
    @DisplayName("BARTER fires for a creator with no followers at all — it reads no metric")
    void barter_firesForUnconnectedCreator() {
        Ctx ctx = ctx();
        ctx.profile = profileWithFollowers(0L);
        ctx.ex.barterOnly = true;
        ctx.ex.barterMrpInr = new BigDecimal("3000");

        assertFires(ctx, BarterRule.CODE);
    }

    // ==================================================================
    // 12. REGULATED_CATEGORY
    // ==================================================================

    @Test
    @DisplayName("REGULATED_CATEGORY fires on FINANCE with the SEBI sub-code and is not dismissible")
    void regulatedCategory_fires() {
        Ctx ctx = ctx();
        ctx.ex.regulatedCategory = "FINANCE";

        RiskFlag flag = requireFlag(ctx, RegulatedCategoryRule.CODE);

        assertThat(flag.dismissible()).isFalse();
        assertThat(flag.data()).containsEntry("sub_code", "SEBI_DISCLOSURE");
    }

    @Test
    @DisplayName("REGULATED_CATEGORY stays quiet on an unregulated category with no claims")
    void regulatedCategory_doesNotFire() {
        Ctx ctx = ctx();
        ctx.ex.regulatedCategory = null;
        ctx.ex.category = "BEAUTY";
        ctx.ex.claims = List.of();

        assertSilent(ctx, RegulatedCategoryRule.CODE);
    }

    @Test
    @DisplayName("REGULATED_CATEGORY falls back to CLAIMS_SUBSTANTIATION for a category with no sub-code")
    void regulatedCategory_alcoholWithClaims() {
        Ctx ctx = ctx();
        ctx.ex.regulatedCategory = "ALCOHOL";
        ctx.ex.claims = List.of("award winning");

        assertThat(requireFlag(ctx, RegulatedCategoryRule.CODE).data())
                .containsEntry("sub_code", RegulatedCategoryRule.SUB_CODE_CLAIMS);
    }

    // ==================================================================
    // 13. CALENDAR_OVERLOAD
    // ==================================================================

    @Test
    @DisplayName("CALENDAR_OVERLOAD fires when the deadline's ISO week is already at the weekly limit")
    void calendarOverload_fires() {
        Ctx ctx = ctx();
        ctx.prefs = prefs().weeklyLimit(2).build();
        ctx.ex.deadline = "2026-10-08"; // Thursday of ISO week 41
        ctx.activeDeals =
                List.of(
                        bookedWeek(CollaborationStatus.CONTRACTED, LocalDate.parse("2026-10-05"), 1),
                        bookedWeek(CollaborationStatus.IN_PROGRESS, LocalDate.parse("2026-10-11"), 1));

        RiskFlag flag = requireFlag(ctx, CalendarOverloadRule.CODE);

        assertThat(flag.severity()).isEqualTo("INFO");
        assertThat(flag.data()).containsEntry("weekly_limit", "2").containsEntry("already_booked", "2");
    }

    @Test
    @DisplayName("CALENDAR_OVERLOAD stays quiet for a deal in the NEXT ISO week")
    void calendarOverload_doesNotFire() {
        Ctx ctx = ctx();
        ctx.prefs = prefs().weeklyLimit(2).build();
        ctx.ex.deadline = "2026-10-08"; // ISO week 41
        ctx.activeDeals =
                List.of(
                        bookedWeek(CollaborationStatus.CONTRACTED, LocalDate.parse("2026-10-12"), 5),
                        bookedWeek(CollaborationStatus.IN_PROGRESS, LocalDate.parse("2026-10-13"), 5));

        assertSilent(ctx, CalendarOverloadRule.CODE);
    }

    // ==================================================================
    // 14. PARTNERSHIP_ADS_REQUEST
    // ==================================================================

    @Test
    @DisplayName("PARTNERSHIP_ADS_REQUEST fires on a contracted deal whose rights exclude paid ads")
    void partnershipAdsRequest_fires() {
        Ctx ctx = ctx();
        ctx.collaboration = collaboration(CollaborationStatus.CONTRACTED);
        ctx.lastBrandMessage = "Can we whitelist this reel for a two-week boost?";
        ctx.ex.usageChannels = List.of("ORGANIC");

        RiskFlag flag = requireFlag(ctx, PartnershipAdsRequestRule.CODE);

        assertThat(flag.detail())
                .isEqualTo("Do not approve the partnership-ads request until the paid-ads add-on is paid.");
    }

    @Test
    @DisplayName("PARTNERSHIP_ADS_REQUEST stays quiet when the deal already granted PAID_ADS")
    void partnershipAdsRequest_doesNotFire() {
        Ctx ctx = ctx();
        ctx.collaboration = collaboration(CollaborationStatus.CONTRACTED);
        ctx.lastBrandMessage = "Can we whitelist this reel for a two-week boost?";
        ctx.ex.usageChannels = List.of("ORGANIC", "PAID_ADS");

        assertSilent(ctx, PartnershipAdsRequestRule.CODE);
    }

    @Test
    @DisplayName("PARTNERSHIP_ADS_REQUEST never fires on a brief, only inside evaluateDeal")
    void partnershipAdsRequest_briefPathIsSilent() {
        Ctx ctx = ctx();
        ctx.collaboration = null;
        ctx.lastBrandMessage = null;
        ctx.text = "Can we whitelist this reel for a two-week boost?";
        ctx.ex.usageChannels = List.of("ORGANIC");

        assertSilent(ctx, PartnershipAdsRequestRule.CODE);
    }

    // ==================================================================
    // Severity scaling across the three value bands (SPEC.md 5.2)
    // ==================================================================

    @Nested
    @DisplayName("Severity scales with deal value")
    class SeverityScaling {

        /** Six months of usage is a base INFO — the cleanest probe for the escalation. */
        private Ctx sixMonthUsage(String budget) {
            Ctx ctx = ctx();
            ctx.ex.usageMonths = 6;
            ctx.ex.budgetInr = new BigDecimal(budget);
            return ctx;
        }

        @Test
        @DisplayName("small (under 10,000) leaves the rule's own severity alone")
        void smallBandDoesNotEscalate() {
            assertThat(requireFlag(sixMonthUsage("9999"), UsageLongRule.CODE).severity()).isEqualTo("INFO");
        }

        @Test
        @DisplayName("mid (10,000 to 25,000 inclusive) also leaves it alone")
        void midBandDoesNotEscalate() {
            assertThat(requireFlag(sixMonthUsage("10000"), UsageLongRule.CODE).severity()).isEqualTo("INFO");
            assertThat(requireFlag(sixMonthUsage("25000"), UsageLongRule.CODE).severity()).isEqualTo("INFO");
        }

        @Test
        @DisplayName("large (above 25,000) escalates INFO to WARN")
        void largeBandEscalates() {
            assertThat(requireFlag(sixMonthUsage("25001"), UsageLongRule.CODE).severity()).isEqualTo("WARN");
        }

        @Test
        @DisplayName("escalation saturates: a CRITICAL on a large deal stays CRITICAL")
        void criticalSaturates() {
            Ctx ctx = ctx();
            ctx.ex.budgetInr = new BigDecimal("40000");
            ctx.ex.usagePerpetual = true;

            assertThat(requireFlag(ctx, UsagePerpetualRule.CODE).severity()).isEqualTo("CRITICAL");
        }

        @Test
        @DisplayName("the value falls back to the agreed rate when the brief states no budget")
        void agreedRateIsTheFallbackValue() {
            Ctx ctx = ctx();
            ctx.ex.usageMonths = 6;
            ctx.ex.budgetInr = null;
            Collaboration collaboration = collaboration(CollaborationStatus.IN_NEGOTIATION);
            collaboration.updateAgreedRate(new BigDecimal("40000"));
            ctx.collaboration = collaboration;

            assertThat(requireFlag(ctx, UsageLongRule.CODE).severity()).isEqualTo("WARN");
        }

        @Test
        @DisplayName("and to the quote total when there is neither a budget nor an agreed rate")
        void quoteTotalIsTheLastFallbackValue() {
            Ctx ctx = ctx();
            ctx.ex.usageMonths = 6;
            ctx.ex.budgetInr = null;
            ctx.quote = quoteWithTotal(new BigDecimal("40000"));

            assertThat(requireFlag(ctx, UsageLongRule.CODE).severity()).isEqualTo("WARN");
        }
    }

    // ==================================================================
    // Shadow-mode audit row (SPEC.md 5.2)
    // ==================================================================

    @Test
    @DisplayName("OFF_PLATFORM_PAYMENT writes one audit row carrying the brand id and nothing else")
    void offPlatformPayment_writesShadowAuditRow() {
        Ctx ctx = ctx();
        ctx.text = "Send me your GPay number and I will transfer after the post goes live.";

        service.evaluate(ctx.build(), DealRiskService.TARGET_DEAL);

        verify(auditLogService)
                .recordRiskSignal(
                        eq(BRAND_WORKSPACE_ID),
                        eq(DealRiskService.OFF_PLATFORM_AUDIT_EVENT),
                        eq(DealRiskService.OFF_PLATFORM_AUDIT_REASON),
                        eq(Map.of("target", DealRiskService.TARGET_DEAL, "blocking", false)));
    }

    @Test
    @DisplayName("no audit row is written when the off-platform rule does not fire")
    void noOffPlatformHint_writesNoAuditRow() {
        Ctx ctx = ctx();
        ctx.text = "Let us keep everything on the platform, funds secured up front.";

        service.evaluate(ctx.build(), DealRiskService.TARGET_DEAL);

        verifyNoInteractions(auditLogService);
    }

    // ==================================================================
    // Contract guards that apply to every flag (SPEC.md 3.5 / 5.2)
    // ==================================================================

    @Test
    @DisplayName("every flag: title at most 60 chars, no arrows, no 'escrow' anywhere the creator reads")
    void everyFlagHonoursTheCopyContract() {
        List<RiskFlag> flags = service.evaluate(everythingFires().build(), DealRiskService.TARGET_DEAL);

        assertThat(flags).hasSizeGreaterThanOrEqualTo(10);
        for (RiskFlag flag : flags) {
            assertThat(flag.title()).as("title of %s", flag.code()).hasSizeLessThanOrEqualTo(60);
            String creatorFacing =
                    String.join(" ", flag.title(), flag.detail(), String.valueOf(flag.cost()), flag.action());
            assertThat(creatorFacing.toLowerCase(java.util.Locale.ROOT))
                    .as("banned vocabulary in %s", flag.code())
                    .doesNotContain("escrow");
            assertThat(creatorFacing).as("arrows in %s", flag.code()).doesNotContain("\u2192").doesNotContain("->");
            assertThat(flag.data().values()).allSatisfy(value -> assertThat(value).isNotNull());
        }
    }

    @Test
    @DisplayName("the three non-dismissible flags are exactly the three the spec names")
    void nonDismissibleFlagsAreTheSpecifiedThree() {
        List<RiskFlag> flags = service.evaluate(everythingFires().build(), DealRiskService.TARGET_DEAL);

        assertThat(flags.stream().filter(f -> !f.dismissible()).map(RiskFlag::code))
                .containsExactlyInAnyOrder(
                        HideDisclosureRule.CODE, OffPlatformPaymentRule.CODE, RegulatedCategoryRule.CODE);
    }

    @Test
    @DisplayName("flags come back most severe first, and highest_severity agrees with them")
    void flagsAreSortedMostSevereFirst() {
        List<RiskFlag> flags = service.evaluate(everythingFires().build(), DealRiskService.TARGET_DEAL);

        List<Integer> ranks = flags.stream().map(f -> RiskSeverity.parse(f.severity()).ordinal()).toList();
        assertThat(ranks).isSortedAccordingTo((a, b) -> Integer.compare(b, a));
        assertThat(RiskSeverity.highest(flags)).isEqualTo(flags.get(0).severity());
    }

    @Test
    @DisplayName("a clean deal produces no flags and no highest_severity at all")
    void cleanDealProducesNothing() {
        List<RiskFlag> flags = service.evaluate(ctx().build(), DealRiskService.TARGET_BRIEF);

        assertThat(flags).isEmpty();
        assertThat(RiskSeverity.highest(flags)).isNull();
    }

    // ==================================================================
    // Fixtures
    // ==================================================================

    /** A brief that trips most of the table at once — used by the cross-cutting contract guards. */
    private Ctx everythingFires() {
        Ctx ctx = ctx();
        ctx.prefs = prefs().excluded(List.of("RMG")).blocked(List.of("Bet Now")).weeklyLimit(1).build();
        ctx.brandName = "Bet Now";
        ctx.ex.category = "RMG";
        ctx.ex.regulatedCategory = "RMG";
        ctx.ex.budgetInr = new BigDecimal("1000");
        ctx.ex.usageMonths = 24;
        ctx.ex.exclusivityDays = 90;
        ctx.ex.exclusivityScope = "CATEGORY";
        ctx.ex.vagueDeliverables = true;
        ctx.ex.deadline = "2026-10-08";
        ctx.ex.usageChannels = List.of("ORGANIC");
        ctx.text = "No #ad please, and we will pay by NEFT after the post.";
        ctx.collaboration = collaboration(CollaborationStatus.CONTRACTED);
        ctx.lastBrandMessage = "Also we want to boost this post.";
        ctx.activeDeals =
                List.of(
                        bookedWeek(CollaborationStatus.CONTRACTED, LocalDate.parse("2026-10-07"), 3),
                        activeDeal(
                                CollaborationStatus.IN_PROGRESS,
                                NOW.minus(2, ChronoUnit.DAYS),
                                45,
                                ExclusivityScope.CATEGORY,
                                List.of(),
                                "Rival Games",
                                "RMG"));
        return ctx;
    }

    private RiskFlag requireFlag(Ctx ctx, String code) {
        return find(service.evaluate(ctx.build(), DealRiskService.TARGET_BRIEF), code)
                .orElseThrow(() -> new AssertionError("expected a " + code + " flag, got none"));
    }

    private void assertFires(Ctx ctx, String code) {
        assertThat(find(service.evaluate(ctx.build(), DealRiskService.TARGET_BRIEF), code))
                .as("%s should have fired", code)
                .isPresent();
    }

    private void assertSilent(Ctx ctx, String code) {
        assertThat(find(service.evaluate(ctx.build(), DealRiskService.TARGET_BRIEF), code))
                .as("%s should have stayed quiet", code)
                .isEmpty();
    }

    private static Optional<RiskFlag> find(List<RiskFlag> flags, String code) {
        return flags.stream().filter(flag -> code.equals(flag.code())).findFirst();
    }

    private Ctx ctx() {
        return new Ctx();
    }

    /**
     * A deliberately CLEAN context: every rule stays quiet against it (asserted by {@link
     * #cleanDealProducesNothing}). Each test then changes the one thing its rule is about, so a
     * flag appearing is attributable to that change and nothing else.
     */
    private final class Ctx {
        private CreatorProfile profile = profileWithFollowers(0L);
        private PreferencesResponse prefs = DealRiskServiceTest.prefs().build();
        private final Ex ex = new Ex();
        private Collaboration collaboration;
        private List<ActiveDeal> activeDeals = List.of();
        private PackageQuote quote;
        private String brandName;
        private String text;
        private String lastBrandMessage;

        private RiskContext build() {
            return new RiskContext(
                    profile,
                    prefs,
                    ex.build(),
                    collaboration,
                    activeDeals,
                    quote,
                    brandName,
                    BRAND_WORKSPACE_ID,
                    text,
                    lastBrandMessage,
                    null,
                    NOW,
                    // Deliberately null: RiskContext.target is the engine's to stamp, and every
                    // assertion below reaches the rules through service.evaluate(ctx, target), so a
                    // target set here could only ever disagree with the one under test.
                    null);
        }
    }

    /** Mutable stand-in for the 23-component {@link BriefExtraction} record. */
    private static final class Ex {
        private String brandName;
        private String category;
        private List<DeliverableLine> deliverables = List.of(new DeliverableLine("REEL", 1));
        private BigDecimal budgetInr;
        private boolean barterOnly;
        private BigDecimal barterMrpInr;
        private String deadline;
        private Integer usageMonths;
        private boolean usagePerpetual;
        private List<String> usageChannels = List.of();
        private Integer exclusivityDays;
        private String exclusivityScope;
        private boolean offPlatformPaymentHint;
        private boolean disclosureHiddenHint;
        private List<String> claims = List.of();
        private String regulatedCategory;
        private boolean vagueDeliverables;

        private BriefExtraction build() {
            return new BriefExtraction(
                    brandName,
                    null,
                    category,
                    deliverables,
                    budgetInr,
                    budgetInr != null,
                    barterOnly,
                    barterMrpInr,
                    deadline,
                    usageMonths,
                    usagePerpetual,
                    usageChannels,
                    exclusivityDays,
                    exclusivityScope,
                    List.of(),
                    2,
                    null,
                    offPlatformPaymentHint,
                    disclosureHiddenHint,
                    claims,
                    regulatedCategory,
                    vagueDeliverables,
                    List.of());
        }
    }

    private static PrefsBuilder prefs() {
        return new PrefsBuilder();
    }

    /** Floors of 5,000 / 2,000 / 3,000 — the numbers every assertion above is arithmetic against. */
    private static final class PrefsBuilder {
        private List<String> excluded = List.of();
        private List<String> blocked = List.of();
        private Integer weeklyLimit;

        private PrefsBuilder excluded(List<String> value) {
            this.excluded = value;
            return this;
        }

        private PrefsBuilder blocked(List<String> value) {
            this.blocked = value;
            return this;
        }

        private PrefsBuilder weeklyLimit(Integer value) {
            this.weeklyLimit = value;
            return this;
        }

        private PreferencesResponse build() {
            return new PreferencesResponse(
                    new BigDecimal("5000"),
                    new BigDecimal("2000"),
                    new BigDecimal("3000"),
                    "INR",
                    excluded,
                    blocked,
                    0,
                    "en-IN",
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    weeklyLimit,
                    false,
                    null,
                    true,
                    null,
                    false,
                    null,
                    false,
                    0,
                    false);
        }
    }

    private static CreatorProfile profileWithFollowers(long followers) {
        CreatorProfile profile =
                CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Priya Shah");
        profile.applyAggregatedStats(followers, new BigDecimal("3.20"));
        return profile;
    }

    private static Collaboration collaboration(CollaborationStatus status) {
        Collaboration collaboration =
                Collaboration.invite("01DEAL0000000000000000000", "01CAMPAIGN000000000000000", CREATOR_USER_ID, "hi", "INR");
        collaboration.transitionTo(status);
        return collaboration;
    }

    private static ActiveDeal activeDeal(
            CollaborationStatus status,
            Instant appliedAt,
            Integer exclusivityDays,
            ExclusivityScope scope,
            List<String> exclusivityBrands,
            String brandName,
            String category) {
        return new ActiveDeal(
                "01OTHERDEAL0000000000000",
                status,
                appliedAt,
                exclusivityDays,
                scope,
                exclusivityBrands,
                brandName,
                category,
                LocalDate.ofInstant(appliedAt, ZoneOffset.UTC).plusDays(20),
                new BigDecimal("15000"),
                1);
    }

    /** An occupied slot in the calendar: a deal whose campaign ends on {@code endDate}. */
    private static ActiveDeal bookedWeek(CollaborationStatus status, LocalDate endDate, int deliverables) {
        return new ActiveDeal(
                "01BOOKED" + endDate,
                status,
                NOW.minus(5, ChronoUnit.DAYS),
                null,
                ExclusivityScope.NONE,
                List.of(),
                "Other Brand",
                "FOOD",
                endDate,
                new BigDecimal("15000"),
                deliverables);
    }

    private static PackageQuote quoteWithTotal(BigDecimal total) {
        return new PackageQuote(
                List.of(), List.of(), null, null, null, total, null, null, null, null, null, null,
                "INR", null, 2, null, 0, null, null, false, null);
    }

    /** Guards the fixture's own assumption that {@link Ctx} really is clean. */
    @Test
    @DisplayName("the shared fixture itself trips nothing — every firing test above is attributable")
    void fixtureIsClean() {
        assertThat(service.evaluate(ctx().build(), DealRiskService.TARGET_BRIEF)).isEmpty();
        verify(auditLogService, org.mockito.Mockito.never())
                .recordRiskSignal(any(), any(), any(), any());
    }
}
