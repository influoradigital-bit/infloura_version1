package com.influora.domain.enums;

import com.influora.domain.entity.Plan;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Closed registry of every plan limit Influora sells, per {@code
 * wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md} §3.1 (Phase 1). This enum — not a scattering of
 * boolean/int columns read by ad-hoc getters — is now the one place a plan limit is declared to
 * exist at all.
 *
 * <p><b>Deliberately closed.</b> This covers exactly the six limits enforced in production today:
 * {@link #SEATS}, {@link #CREATOR_ANALYTICS_VIEWS}, {@link #AI_CREDITS}, {@link #EXPORT}, {@link
 * #CAMPAIGN_TEMPLATES}, {@link #BRAND_FEE_BPS}. {@code SAVED_CREATORS} — the one entitlement the
 * redesign doc's diagnosis (§1) found sold, metered, and never enforced — is deliberately NOT
 * here yet: SM-0.1 ruled it should be enforced, but Phase 2 (the {@code toggleSaved} write-gate
 * itself) has not landed. Adding it to this enum before that write gate exists would make it just
 * another entitlement with a probe and no enforcement — exactly the failure mode this registry
 * exists to make impossible. See {@code EntitlementConformanceTest}.
 *
 * <p><b>Each non-RATE constant names the HTTP boundary that must enforce it</b> ({@link
 * #httpMethod()} + {@link #endpoint()}) — enforced by the constructor, at class-load time: a
 * constant that names {@code null} for either fails immediately, for every caller, the first time
 * anything touches this enum. That is the practical equivalent of "does not compile" available to
 * an {@code enum} constant (Java gives no way to fail an enum body at {@code javac} time based on
 * a value passed to its own constructor). {@link #BRAND_FEE_BPS} is the one documented exception:
 * it is a {@link Shape#RATE}, resolved on the money path rather than gated at an HTTP boundary
 * (see {@code BrandCampaignFeeService}), so it declares no endpoint by design, not by omission.
 *
 * <p><b>What this enum does NOT prove by itself.</b> Naming a probe endpoint is necessary, not
 * sufficient — it is metadata a human wrote down, and metadata does not enforce anything. The
 * thing that actually closes the hole this redesign diagnosed is {@code
 * EntitlementConformanceTest}, which inspects the compiled bytecode of the real production
 * classes for an actual call site that references each constant. An {@code Entitlement} value
 * that exists here with a plausible-looking endpoint but no real caller anywhere is exactly the
 * {@code SAVED_CREATORS} failure mode, and that test is what is supposed to catch it.
 */
public enum Entitlement {
    SEATS(Shape.CAPACITY, "POST", "/workspace/members/invite"),
    CREATOR_ANALYTICS_VIEWS(Shape.METERED, "GET", "/analytics/creators/{creatorId}/metrics"),
    // POST /meera/chat does not exist and never did: MeeraController is @RequestMapping("/meera")
    // and the turn that actually spends an AI credit (MeeraSessionService.doSendTurn ->
    // AICreditService.tryConsumeForTurn) is reached through the send-message endpoint below.
    // Corrected 2026-09-12 together with the two {id} placeholders above/below, which named path
    // variables ({id}) that no handler declares. All five are now checked against the real
    // @RequestMapping/@GetMapping/@PostMapping routes by EntitlementConformanceTest
    // #everyDeclaredEndpointResolvesToARealHandler, so this metadata can no longer drift into
    // fiction unnoticed.
    AI_CREDITS(Shape.METERED, "POST", "/meera/sessions/{conversationId}/messages"),
    EXPORT(Shape.FLAG, "GET", "/campaigns/{campaignId}/export"),
    CAMPAIGN_TEMPLATES(Shape.FLAG, "POST", "/campaign-templates"),
    // Money path, not a gate (redesign doc §3.1) — resolved per-charge by
    // BrandCampaignFeeService.resolveBrandFeeBps, never blocks a request at an HTTP boundary the
    // way the other five do. No endpoint to name; see class javadoc.
    BRAND_FEE_BPS(Shape.RATE, null, null);

    /**
     * The mechanical shape of a plan limit — determines which {@code EntitlementService} (or
     * {@code @RequiresPlan}) primitive enforces it. See {@code
     * wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md} §3.2.
     */
    public enum Shape {
        /** Boolean feature toggle — enforced declaratively via {@code @RequiresPlan}. */
        FLAG,
        /** Per-billing-period usage counter with a numeric cap — enforced via {@code consume}. */
        METERED,
        /** Point-in-time count-vs-limit check on a write — enforced via {@code requireCapacity}. */
        CAPACITY,
        /** A resolved numeric rate, not a pass/fail gate — enforced via {@code resolveRate}. */
        RATE
    }

    private final Shape shape;
    private final String httpMethod;
    private final String endpoint;

    Entitlement(Shape shape, String httpMethod, String endpoint) {
        this.shape = Objects.requireNonNull(shape, "shape");
        if (shape == Shape.RATE) {
            this.httpMethod = httpMethod;
            this.endpoint = endpoint;
        } else {
            this.httpMethod =
                    Objects.requireNonNull(
                            httpMethod,
                            () ->
                                    name()
                                            + " is "
                                            + shape
                                            + "-shaped and must name the HTTP method it is enforced"
                                            + " on (only RATE entitlements may omit it)");
            this.endpoint =
                    Objects.requireNonNull(
                            endpoint,
                            () ->
                                    name()
                                            + " is "
                                            + shape
                                            + "-shaped and must name the endpoint it is enforced on"
                                            + " (only RATE entitlements may omit it)");
        }
    }

    public Shape shape() {
        return shape;
    }

    /** HTTP method of the endpoint that must enforce this entitlement; {@code null} only for RATE. */
    public String httpMethod() {
        return httpMethod;
    }

    /** Endpoint (path template) that must enforce this entitlement; {@code null} only for RATE. */
    public String endpoint() {
        return endpoint;
    }

    /**
     * Resolves THIS entitlement's numeric limit out of {@code plan} — {@code empty} means
     * unlimited. This is the single place a limit-shaped entitlement is mapped to the plan state
     * that backs it, and it is what makes the {@code Entitlement} argument of {@code
     * EntitlementService.requireCapacity}/{@code consume} load-bearing rather than decorative:
     * change the constant at a call site and a different limit is enforced.
     *
     * <p><b>Why the switch lives here and not in {@link Plan#limitFor}</b> (which now just
     * delegates to this, so the two can never disagree): {@code WorkspaceMemberServiceTest} and
     * {@code PlanGateWiringTest} drive the two production call sites against a Mockito {@code
     * mock(Plan.class)} that stubs the per-field getters. A {@code plan.limitFor(entitlement)}
     * call from production would be intercepted by the mock and return {@code null}; an enum
     * instance method cannot be stubbed, so the resolution really runs and really reads the
     * stubbed getters. Same code either way — this is a placement decision, not a second
     * implementation.
     *
     * @throws UnsupportedOperationException for FLAG/RATE entitlements, which are not limit-shaped
     */
    public OptionalInt limitIn(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        return switch (this) {
            case SEATS -> OptionalInt.of(plan.getSeatLimit());
            case CREATOR_ANALYTICS_VIEWS ->
                    plan.getCreatorAnalyticsMonthlyLimit() == null
                            ? OptionalInt.empty()
                            : OptionalInt.of(plan.getCreatorAnalyticsMonthlyLimit());
            // Plan-level sync source only (SubscriptionService.reconcileAiCreditAllotment writes
            // this into BrandAiCredit.monthlyAllotment) — the live, possibly loyalty-bumped
            // remaining-credits ledger is BrandAiCredit itself, read via AICreditService, not this
            // column. Never null/unlimited: every plan carries a concrete monthly allotment.
            case AI_CREDITS -> OptionalInt.of(plan.getAiMonthlyAllotment());
            case EXPORT ->
                    throw new UnsupportedOperationException(
                            "EXPORT is a FLAG entitlement, not limit-shaped — use isExportEnabled()");
            case CAMPAIGN_TEMPLATES ->
                    throw new UnsupportedOperationException(
                            "CAMPAIGN_TEMPLATES is a FLAG entitlement, not limit-shaped — use"
                                    + " isCampaignTemplatesEnabled()");
            case BRAND_FEE_BPS ->
                    throw new UnsupportedOperationException(
                            "BRAND_FEE_BPS is a RATE entitlement, not limit-shaped — use getFeeBps()");
        };
    }
}
