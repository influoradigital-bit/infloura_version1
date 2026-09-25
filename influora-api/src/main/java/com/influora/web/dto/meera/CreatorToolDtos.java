package com.influora.web.dto.meera;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.influora.web.dto.brief.BriefDtos;
import com.influora.web.dto.deal.DealDtos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Result DTOs for the creator Meera tool executors (SPEC.md 3.5/3.6, Phase B0/B1). Creator
 * payloads follow {@link MeeraContextDtos}'s convention, NOT {@link MeeraToolDtos}'s: every
 * component is {@code snake_case} {@code @JsonProperty} so influora-ai's Python tool loop can
 * read these directly, and every record is {@code @JsonInclude(NON_NULL)}. Numbers the model
 * reads are pre-rendered strings (via {@code Rendered.money}/{@code Rendered.date}); numbers the
 * frontend needs are numeric siblings suffixed {@code _value}.
 *
 * <p>Phase B0 wires only {@code get_my_deals}, {@code get_brief}, {@code estimate_my_rate},
 * {@code get_my_metrics}, {@code check_deal_risks} and {@code draft_reply} (3.1); the remaining
 * records here (routine-reply send, campaign ranking, application drafting) are shipped now so a
 * later wave does not have to re-touch this file.
 */
public final class CreatorToolDtos {

    private CreatorToolDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DealSummary(
            @JsonProperty("deal_id") String dealId,
            @JsonProperty("brand_name") String brandName,
            @JsonProperty("campaign_title") String campaignTitle,
            @JsonProperty("status") String status,
            @JsonProperty("status_label") String statusLabel,
            @JsonProperty("amount") String amount,
            @JsonProperty("amount_value") BigDecimal amountValue,
            @JsonProperty("currency") String currency,
            @JsonProperty("next_action") String nextAction,
            @JsonProperty("next_deadline") String nextDeadline,
            @JsonProperty("secured") boolean secured,
            @JsonProperty("unread_count") int unreadCount,
            @JsonProperty("has_pending_offer") boolean hasPendingOffer,
            @JsonProperty("brief_id") String briefId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetMyDealsResult(
            @JsonProperty("deals") List<DealSummary> deals,
            @JsonProperty("active_count") int activeCount,
            @JsonProperty("completed_count") int completedCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuoteLine(
            @JsonProperty("type") String type,
            @JsonProperty("qty") int qty,
            @JsonProperty("unit_price") String unitPrice,
            @JsonProperty("unit_price_value") BigDecimal unitPriceValue,
            @JsonProperty("line_total") String lineTotal,
            @JsonProperty("line_total_value") BigDecimal lineTotalValue,
            @JsonProperty("below_floor") boolean belowFloor) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AddOnLine(
            @JsonProperty("code") String code,
            @JsonProperty("label") String label,
            @JsonProperty("amount") String amount,
            @JsonProperty("amount_value") BigDecimal amountValue,
            @JsonProperty("basis") String basis) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PackageQuote(
            @JsonProperty("lines") List<QuoteLine> lines,
            @JsonProperty("add_ons") List<AddOnLine> addOns,
            @JsonProperty("bundle_discount") String bundleDiscount,
            @JsonProperty("bundle_discount_value") BigDecimal bundleDiscountValue,
            @JsonProperty("total") String total,
            @JsonProperty("total_value") BigDecimal totalValue,
            @JsonProperty("anchor") String anchor,
            @JsonProperty("anchor_value") BigDecimal anchorValue,
            @JsonProperty("floor_total") String floorTotal,
            @JsonProperty("floor_total_value") BigDecimal floorTotalValue,
            @JsonProperty("range_min") String rangeMin,
            @JsonProperty("range_max") String rangeMax,
            @JsonProperty("currency") String currency,
            @JsonProperty("payment_schedule") String paymentSchedule,
            @JsonProperty("revision_rounds") int revisionRounds,
            @JsonProperty("provenance") String provenance,
            @JsonProperty("provenance_sample_size") int provenanceSampleSize,
            @JsonProperty("recommended_move") String recommendedMove,
            @JsonProperty("scope_down_offer") String scopeDownOffer,
            @JsonProperty("withheld") boolean withheld,
            @JsonProperty("withheld_reason") String withheldReason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EstimateMyRateResult(@JsonProperty("quote") PackageQuote quote) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricsResult(
            @JsonProperty("connected") boolean connected,
            @JsonProperty("followers") String followers,
            @JsonProperty("reach_30d") String reach30d,
            @JsonProperty("engagement_rate") String engagementRate,
            @JsonProperty("avg_reach_per_post") String avgReachPerPost,
            @JsonProperty("verified_at") String verifiedAt,
            @JsonProperty("data_source") String dataSource,
            @JsonProperty("tier") String tier,
            @JsonProperty("quality_score") String qualityScore) {}

    /**
     * The creator's account numbers over the last 28 full days (2026-09-24), pre-formatted like
     * every other creator tool number. {@code available=false} with every figure null when
     * nothing has been fetched yet; a single figure Meta did not return is null, never "0".
     */
    public record AccountLast28Days(
            @JsonProperty("available") boolean available,
            @JsonProperty("period") String period,
            @JsonProperty("accounts_reached") String accountsReached,
            @JsonProperty("views") String views,
            @JsonProperty("interactions") String interactions,
            @JsonProperty("accounts_engaged") String accountsEngaged,
            @JsonProperty("profile_link_taps") String profileLinkTaps) {

        public static AccountLast28Days notAvailable() {
            return new AccountLast28Days(false, null, null, null, null, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetMyMetricsResult(
            @JsonProperty("metrics") MetricsResult metrics,
            @JsonProperty("account_last_28_days") AccountLast28Days accountLast28Days) {

        public GetMyMetricsResult(MetricsResult metrics) {
            this(metrics, AccountLast28Days.notAvailable());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskFlag(
            @JsonProperty("code") String code,
            @JsonProperty("severity") String severity,
            @JsonProperty("title") String title,
            @JsonProperty("detail") String detail,
            @JsonProperty("cost") String cost,
            @JsonProperty("action") String action,
            @JsonProperty("data") Map<String, String> data,
            @JsonProperty("dismissible") boolean dismissible) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckDealRisksResult(
            @JsonProperty("flags") List<RiskFlag> flags,
            @JsonProperty("highest_severity") String highestSeverity,
            @JsonProperty("target") String target,
            @JsonProperty("target_id") String targetId) {}

    /**
     * T-MEERA-CREATOR-PHASE-B &sect;3.6 — {@code get_brief}'s result.
     *
     * <p><b>No {@code degraded_reason} field, deliberately (Priya last-call UF-3,
     * PRIYA-LASTCALL-U1-K4-0917.md).</b> Kavya's original ask was for one, and it cannot be met
     * honestly without new storage. The reason a paste-time AI extraction was skipped ({@code cap}
     * vs {@code ai_unavailable}, {@code CreatorBriefService.analyse}) is never persisted — the
     * migration keeps only {@code extraction_source} ({@code V20260910100100__creator_briefs.sql}),
     * not the reason behind it — and on a deal's first read {@code ensurePlatformBrief} discards
     * its own {@code BriefAnalysisResponse} before {@code get} re-reads the row from its stored
     * snapshot. A field populated only on the rare call that happens to re-analyse would give the
     * SAME brief a different reason on back-to-back reads depending on which one you asked, which
     * is a worse signal than none at all. {@link #extractionSource} is the honest degraded marker
     * this result carries: when it reads {@code FALLBACK}, the {@code get_brief} tool description
     * tells the model to say the summary was read by rules, not by it. Persisting a real {@code
     * degraded_reason} next to {@code extraction_source} is a follow-up (ticket against B0-52), not
     * a B0 condition.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetBriefResult(
            @JsonProperty("brief_id") String briefId,
            @JsonProperty("source") String source,
            @JsonProperty("status") String status,
            @JsonProperty("deal_id") String dealId,
            @JsonProperty("extraction") BriefDtos.BriefExtraction extraction,
            @JsonProperty("flags") List<RiskFlag> flags,
            @JsonProperty("quote") PackageQuote quote,
            @JsonProperty("extraction_source") String extractionSource) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DraftReplyResult(
            @JsonProperty("draft_id") String draftId,
            @JsonProperty("kind") String kind,
            @JsonProperty("text") String text,
            @JsonProperty("proposed_amount") String proposedAmount,
            @JsonProperty("proposed_amount_value") BigDecimal proposedAmountValue,
            @JsonProperty("deal_terms") DealDtos.DealTermsDto dealTerms,
            @JsonProperty("target_deal_id") String targetDealId,
            @JsonProperty("target_brief_id") String targetBriefId,
            @JsonProperty("withheld") boolean withheld,
            @JsonProperty("withheld_reason") String withheldReason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendRoutineReplyResult(
            @JsonProperty("send_log_id") String sendLogId,
            @JsonProperty("status") String status,
            @JsonProperty("send_at") String sendAt,
            @JsonProperty("cancel_window_seconds") int cancelWindowSeconds,
            @JsonProperty("refused_code") String refusedCode,
            @JsonProperty("refused_reason") String refusedReason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampaignFit(
            @JsonProperty("campaign_id") String campaignId,
            @JsonProperty("title") String title,
            @JsonProperty("brand_name") String brandName,
            @JsonProperty("budget_band") String budgetBand,
            @JsonProperty("fit_score") int fitScore,
            @JsonProperty("fit_reasons") List<String> fitReasons,
            @JsonProperty("application_deadline") String applicationDeadline,
            @JsonProperty("already_applied") boolean alreadyApplied,
            @JsonProperty("below_floor") boolean belowFloor) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RankOpenCampaignsResult(@JsonProperty("campaigns") List<CampaignFit> campaigns) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DraftApplicationResult(
            @JsonProperty("draft_id") String draftId,
            @JsonProperty("campaign_id") String campaignId,
            @JsonProperty("text") String text) {}

    /**
     * T-CONTENT-TOPICS -- one matched, safety-screened row from {@code content_topics}. {@code
     * category} and {@code sensitivity} are passed through from the row untouched; {@code angles}
     * is already split into lines by {@code ContentTopicService#splitAngles}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopicResult(
            @JsonProperty("id") Long id,
            @JsonProperty("category") String category,
            @JsonProperty("title") String title,
            @JsonProperty("angles") List<String> angles,
            @JsonProperty("live_until") String liveUntil,
            @JsonProperty("sensitivity") String sensitivity) {}

    /**
     * T-CONTENT-TOPICS -- {@code get_todays_topics}'s result. {@code today}/{@code weekday} exist
     * because the model has no other way to know the date: nothing in its prompt states it, and it
     * must not be left to infer one from the client or from its own training data. Both are
     * computed server-side in IST by {@code GetTodaysTopicsExecutor}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetTodaysTopicsResult(
            @JsonProperty("today") String today,
            @JsonProperty("weekday") String weekday,
            @JsonProperty("topics") List<TopicResult> topics) {}

    /**
     * T-PLAN-MY-WEEK -- one calendar day of {@code plan_my_week}'s 7-day span. {@code weekday} is
     * rendered in English (e.g. {@code "Wednesday"}), matching {@link GetTodaysTopicsResult}'s
     * convention.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanDay(@JsonProperty("date") String date, @JsonProperty("weekday") String weekday) {}

    /**
     * T-PLAN-MY-WEEK -- the wire shape of {@code CreatorPostingPatternService.PostingPattern},
     * snake_case for influora-ai's Python tool loop. {@code windows} mirrors {@code PatternWindow}
     * field-for-field; {@code engagement_rate} is a pre-formatted string (e.g. {@code "4.8%"}) the
     * model must only display, never compute against.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PatternWindowResult(
            @JsonProperty("label") String label,
            @JsonProperty("posts") int posts,
            @JsonProperty("engagement_rate") String engagementRate) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PatternResult(
            @JsonProperty("enough_data") boolean enoughData,
            @JsonProperty("posts_counted") int postsCounted,
            @JsonProperty("best_post_type") String bestPostType,
            @JsonProperty("windows") List<PatternWindowResult> windows,
            @JsonProperty("note") String note) {}

    /**
     * T-PLAN-MY-WEEK -- {@code plan_my_week}'s result. {@code today}/{@code days} exist for the same
     * reason {@link GetTodaysTopicsResult#today} does: the model has no other way to know the date,
     * and nothing in its prompt states it. {@code days} is always exactly 7 entries, {@code today}
     * plus the next 6, computed server-side in IST by {@code GetPlanMyWeekExecutor}. {@code topics}
     * reuses {@code ContentTopicService#topicsFor} (the same rows {@code get_todays_topics} serves);
     * {@code pattern} is {@code CreatorPostingPatternService.PostingPattern} rendered onto the wire.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanMyWeekResult(
            @JsonProperty("today") String today,
            @JsonProperty("days") List<PlanDay> days,
            /**
             * The creator's own categories, exactly as stored. influora-ai's {@code
             * app/planner/week_plan.py} filters the festival and season calendar against these:
             * 50 of its 59 rows are category-specific, so without this field only the 9 rows
             * marked {@code ALL} could ever attach and a food creator would never be told World
             * Food Day is coming. Empty when the creator has no categories on file, which is a
             * real state, not an error.
             */
            @JsonProperty("categories") List<String> categories,
            @JsonProperty("topics") List<TopicResult> topics,
            @JsonProperty("pattern") PatternResult pattern) {}

    // =============================================================================================
    // Meera intelligence v1 -- get_my_content_patterns (spec 4.2)
    // =============================================================================================

    /**
     * Meera intelligence v1 -- the evidence every claim carries: its source ({@code
     * EvidenceType} name), how many posts it rests on, and their {@code media_metrics.media_id}
     * values, never truncated. There is no confidence number, ever. influora-ai's model copy drops
     * {@code post_ids} (token control); this record and the UI payload keep them.
     *
     * @param baselineSampleSize set on per-post claims only: how many posts the usual they are
     *     compared with rests on
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evidence(
            @JsonProperty("type") String type,
            @JsonProperty("sample_size") int sampleSize,
            @JsonProperty("post_ids") List<String> postIds,
            @JsonProperty("baseline_sample_size") Integer baselineSampleSize) {}

    /**
     * One metric of the creator's usual post: REACH | VIEWS | INTERACTIONS | ENGAGEMENT_RATE. The
     * median is pre-rendered ("12,400", or "6.2%" for the engagement rate, which is per REACH).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BaselineMetric(
            @JsonProperty("metric") String metric,
            @JsonProperty("median") String median,
            @JsonProperty("evidence") Evidence evidence) {}

    /**
     * One best or weak post against the creator's usual reach. {@code post_type} is REEL | CAROUSEL
     * | POST | OTHER; date and time are IST; {@code engagement_rate} is null when interactions are
     * unknown (never "0.0%").
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PostReading(
            @JsonProperty("post_id") String postId,
            @JsonProperty("post_type") String postType,
            @JsonProperty("posted_date") String postedDate,
            @JsonProperty("posted_time") String postedTime,
            @JsonProperty("window") String window,
            @JsonProperty("permalink") String permalink,
            @JsonProperty("reach") String reach,
            @JsonProperty("reach_vs_usual") String reachVsUsual,
            @JsonProperty("engagement_rate") String engagementRate,
            @JsonProperty("evidence") Evidence evidence) {}

    /**
     * A post type (POST_TYPE) or posting window (POSTING_WINDOW) whose median beat the creator's
     * own usual by at least 20% on reach and/or engagement ({@code beats_on}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkingPattern(
            @JsonProperty("kind") String kind,
            @JsonProperty("label") String label,
            @JsonProperty("posts") int posts,
            @JsonProperty("median_reach") String medianReach,
            @JsonProperty("reach_vs_usual") String reachVsUsual,
            @JsonProperty("median_engagement_rate") String medianEngagementRate,
            @JsonProperty("engagement_vs_usual") String engagementVsUsual,
            @JsonProperty("beats_on") List<String> beatsOn,
            @JsonProperty("evidence") Evidence evidence) {}

    /**
     * Meera intelligence v1 -- {@code get_my_content_patterns}'s result, rendered by {@code
     * GetMyContentPatternsExecutor} from {@code CreatorIntelligenceProfile}. The four lists are
     * always present ({@code []}, never null); only the nullable strings are dropped by NON_NULL.
     * {@code available=false, reason=NOT_CONNECTED} carries no post data at all.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetMyContentPatternsResult(
            @JsonProperty("available") boolean available,
            @JsonProperty("reason") String reason,
            @JsonProperty("enough_data") boolean enoughData,
            @JsonProperty("settled_posts") int settledPosts,
            @JsonProperty("unsettled_posts") int unsettledPosts,
            @JsonProperty("min_posts_needed") int minPostsNeeded,
            @JsonProperty("lookback_days") int lookbackDays,
            @JsonProperty("as_of") String asOf,
            @JsonProperty("baseline") List<BaselineMetric> baseline,
            @JsonProperty("best_posts") List<PostReading> bestPosts,
            @JsonProperty("weak_posts") List<PostReading> weakPosts,
            @JsonProperty("what_works") List<WorkingPattern> whatWorks,
            @JsonProperty("note") String note) {}
}
