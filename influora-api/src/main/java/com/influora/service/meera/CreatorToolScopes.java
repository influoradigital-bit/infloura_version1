package com.influora.service.meera;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.3) — the space-delimited {@code scope} claim minted into
 * a CREATOR turn's on-behalf JWT, and the list of tool names the model is actually told about.
 *
 * <p><b>Scope is a ceiling; dispatch is the gate.</b> These two things are deliberately not the same
 * list. {@code OnBehalfAuthResolver#requireScope} only asserts that the tool being called is present
 * in the claim — it never validates the claim's entries against a registry — so a scope naming a
 * tool with no route is inert: nothing can call it, and it 404s if anything tries. That is why
 * {@link #SCOPE_LEVEL_0} ships all eight level-0 names from SPEC.md &sect;3.3 verbatim while only
 * five of them are wired. Minting the full ceiling now means a later wave adds a route and an
 * executor without also having to re-mint tokens or migrate a claim shape.
 *
 * <p><b>[D-02] Minting the ceiling is free only while no route exists. Read this before you add
 * one.</b> The sentence above is true and is also the whole hazard, so it does not get to stand
 * alone. "Inert" is a property of the <i>absence of a route</i>, not a property of the claim — the
 * claim is a standing grant. Because {@code requireScope} is bare string membership, <b>there is no
 * intermediate state in which a route exists and the grant does not</b>: from the instant a
 * {@code @PostMapping} for a scoped-but-unwired name reaches production, every token already
 * authorises it, and the next token every level-1 and level-2 creator is minted authorises it too.
 * The 120-second TTL on those tokens ({@code OnBehalfTokenService.MAX_TTL_SECONDS}) bounds the
 * <i>pre-existing</i> tokens to a two-minute tail; it does nothing about the standing grant, which is
 * the part that matters.
 *
 * <p><b>And be precise about what that 120-second tail is, because it is not a confined credential.</b>
 * The on-behalf token is returned to the creator's browser in the HTTP body of the turn response
 * ({@code CreatorMeeraController#sendTurn} puts {@code onBehalfToken} into {@code
 * MeeraDtos.SendTurnResponse}), and {@code OnBehalfAuthResolver}'s own javadoc records that {@code
 * jti} single-use/replay enforcement is <b>not implemented</b>. So each token is a browser-visible
 * bearer credential, replayable any number of times until {@code exp}. Two minutes is short; it is not
 * server-side, and it is not single-use. Do not shorten this to "the token is server-side" or "the
 * token is spent on use" — both are false, and the next author will size the risk from whatever this
 * comment says.
 *
 * <p>For {@link #SCOPE_LEVEL_1}'s {@code send_routine_reply} that would mean a message reaching a
 * brand under the creator's name, enabled by a code deploy rather than by a reviewable decision. So
 * the deploy-order rule, which is the thing this javadoc previously left unwritten:
 *
 * <ol>
 *   <li>The send route ships behind {@code influora.meera.creator-send-enabled} (env {@code
 *       MEERA_CREATOR_SEND_ENABLED}), <b>default false</b> —
 *       {@link com.influora.config.MeeraCreatorFeatureProperties#isCreatorSendEnabled()}, checked in
 *       {@code CreatorMeeraToolController} alongside {@code requireFeatureEnabled}. Separate from
 *       {@code creator-enabled} so pulling sends back does not take down every creator read.
 *   <li>Deploy influora-api first, influora-ai second, flag false across both. The flag makes the
 *       order harmless either way.
 *   <li>A test must prove the grant is inert with the flag off: a level-1 token whose scope contains
 *       the tool is refused at the route with a code that is <b>not</b>
 *       {@code ON_BEHALF_SCOPE_INSUFFICIENT}, so the audit trail distinguishes "capability switched
 *       off" from "scope insufficient". {@code CreatorMeeraToolControllerTest} has the shape for it.
 * </ol>
 *
 * <p>{@code com.influora.architecture.CreatorSendGateTest} enforces rule 1 mechanically: it pins the
 * send-capable routes on the controller as an empty set and requires any entry to be gated on that
 * flag, so the commit that adds the route cannot pass without coming back here. Do not satisfy it by
 * widening the pinned set alone. Note also that
 * {@code MeeraContextServiceTest#testCreatorContextCarriesWiredToolNames} goes <b>green</b> on that
 * commit by design — it is a wiring-consistency check between {@code WIRED_TOOL_NAMES} and the
 * route set, not a capability gate, and it will not tell anyone a send grant just went live.
 *
 * <p>{@link #toolNamesForLevel} is the opposite: it is what the assembler puts in front of the
 * model, so it must contain <b>only</b> tools whose endpoints exist. A tool the model can call but
 * the server cannot answer is worse than a tool it cannot see — the model spends a turn, gets a 404,
 * and narrates a failure to the creator.
 */
public final class CreatorToolScopes {

    /**
     * SPEC.md &sect;3.3, verbatim. Five reads, one draft, plus the two campaign tools that arrive in
     * B7. See the class javadoc for why the un-wired names are safe to mint.
     */
    public static final String SCOPE_LEVEL_0 =
            "get_my_deals get_brief estimate_my_rate get_my_metrics check_deal_risks draft_reply"
                    + " rank_open_campaigns draft_application"
                    // T-CONTENT-TOPICS -- get_todays_topics is wired (has a route and an executor)
                    // from the moment it is added here, unlike the two names above it.
                    + " get_todays_topics"
                    // T-PLAN-MY-WEEK -- plan_my_week is wired (has a route and an executor) from
                    // the moment it is added here, same as get_todays_topics above it.
                    + " plan_my_week"
                    // Meera intelligence v1 -- get_my_content_patterns is wired (route +
                    // GetMyContentPatternsExecutor) from the moment it is added here.
                    + " get_my_content_patterns"
                    // Swapnil 2026-09-26 -- get_my_audience is wired (route +
                    // GetMyAudienceExecutor) from the moment it is added here.
                    + " get_my_audience";

    /**
     * Level 1 adds the one commit-like tool: a routine reply actually reaches the brand.
     *
     * <p><b>[D-02] This name is a standing grant, not a plan.</b> {@code send_routine_reply} has no
     * route today, which is the only reason it is inert — see the class javadoc's deploy-order rule
     * before adding one. The moment a {@code @PostMapping("/send_routine_reply")} exists, this string
     * authorises it for every level-1 and level-2 creator, with no separate step in between. The
     * route must therefore be gated on {@code influora.meera.creator-send-enabled} (default false);
     * {@code com.influora.architecture.CreatorSendGateTest} fails if it is not.
     *
     * <p>Do <b>not</b> "fix" that by deleting the name from this constant — but for one reason, not
     * two. The reason that holds: the scope is read at <b>mint time</b>, so every token issued after
     * the route deploys names whatever this constant says at that moment; removing the name today buys
     * no window, because the name and the route would go live in the same deploy either way.
     *
     * <p>The reason that does <b>not</b> hold, corrected here because an earlier draft of this javadoc
     * asserted it: that a populated level-1 constant is what makes {@link #scopeFor}'s degrade path
     * work. It is not. {@code scopeFor} clamps an unexpected approval level by returning {@link
     * #SCOPE_LEVEL_0} directly, whatever this constant happens to contain, so emptying level 1 would
     * not weaken the clamp by one name. The ceiling is not self-justifying.
     *
     * <p>So keeping {@code send_routine_reply} here is acceptable on exactly one condition: <b>the flag
     * gate holds</b>. "The ceiling stays; the flag is the gate" is conditional on {@code
     * com.influora.architecture.CreatorSendGateTest} continuing to fail when a send-capable route
     * appears without {@code influora.meera.creator-send-enabled} behind it. If that class is deleted,
     * weakened, or found bypassable again, the condition is broken and this name must come out of the
     * constant until it is restored.
     */
    public static final String SCOPE_LEVEL_1 = SCOPE_LEVEL_0 + " send_routine_reply";

    /**
     * Level 2 adds no tool, <b>deliberately</b>. Auto-decline is a server-side job in Phase E, not a
     * capability the model invokes, so there is nothing here for it to be granted.
     *
     * <p><b>The equality with {@link #SCOPE_LEVEL_1} is intentional, not an accident or an
     * unfinished edit.</b> It is assigned from that constant rather than repeating the literal so
     * the identity is visible in the source and stays true if level 1 changes.
     *
     * <p><b>Warning for whoever widens this next.</b> While the two are equal, any test of the form
     * "level 2 contains everything level 1 does" passes <b>vacuously</b> and proves nothing about
     * widening — and so does {@code assertSame}. Both constants are constant expressions (JLS
     * 15.29), so javac interns them: re-declaring this field as a hand-typed identical literal
     * still satisfies {@code assertSame}, which was confirmed with a standalone probe rather than
     * assumed. Worse, javac <b>inlines</b> a constant like this into every class that reads it, so
     * a test asserting on it can go on reporting a stale value until the test class itself is
     * recompiled. The tripwire that actually works is {@code
     * CreatorToolScopesTest#testLevelTwoGrantsExactlyTheLevelOneSet}, which pins level 2's names as
     * an explicit literal set: adding a level-2-only tool turns it red and forces the author to
     * assert the ADDED tool by name instead of a superset relation that cannot fail.
     */
    public static final String SCOPE_LEVEL_2 = SCOPE_LEVEL_1;

    /**
     * A creator represented by an agency gets reads only — no drafts, no sends — regardless of her
     * approval level. Meera warns and explains; the agency negotiates. Note this is applied
     * <b>instead of</b> the level scope, not intersected with it, so raising the approval level of a
     * represented creator cannot widen it.
     */
    public static final String SCOPE_REPRESENTED =
            "get_my_deals get_brief estimate_my_rate get_my_metrics check_deal_risks"
                    + " rank_open_campaigns"
                    // Meera intelligence v1 (spec Q1) -- a read of her OWN post results, like
                    // get_my_metrics; it never touches a brand, so representation does not bar it.
                    + " get_my_content_patterns"
                    // Swapnil 2026-09-26 -- her OWN audience, same reasoning as the line above.
                    + " get_my_audience";

    /**
     * The tools that have a route AND an executor today, in SPEC.md &sect;3.1 catalogue order.
     *
     * <p>Wave 3 added {@code estimate_my_rate} and {@code check_deal_risks} alongside
     * {@code RateQuoteService} and {@code DealRiskService}; {@code get_brief} followed with
     * {@code GetBriefExecutor} over {@code CreatorBriefService}, in the same change as its route.
     * Still to come: {@code draft_reply} with the draft surface, then {@code send_routine_reply}
     * (B1/B5) and {@code rank_open_campaigns} / {@code draft_application} (B7).
     *
     * <p><b>This list and {@code CreatorMeeraToolController}'s {@code @PostMapping} set are one
     * change, never two.</b> A name here with no route costs the creator a turn and a narrated
     * failure; a route with no name here is dead code that no production traffic can reach — which
     * is exactly what happened in Wave 2, when the class landed and the call site did not, and
     * every test on both sides stayed green because the Java tests called
     * {@link #toolNamesForLevel} directly and the Python tests injected fixtures. The tripwire is
     * {@code MeeraContextServiceTest#testCreatorContextCarriesWiredToolNames}, which asserts
     * through the assembled context and reflects over the controller's route set, so it fails on
     * either half alone.
     *
     * <p>Deliberately {@code List.of(...)} and not a {@code String} constant: javac inlines a
     * constant {@code String} into every class that reads it, so a test could go on asserting a
     * stale value until it was itself recompiled (see {@link #SCOPE_LEVEL_2}). A {@code List} is
     * read at runtime and cannot go stale that way.
     */
    private static final List<String> WIRED_TOOL_NAMES =
            List.of(
                    "get_my_deals",
                    "get_brief",
                    "estimate_my_rate",
                    "get_my_metrics",
                    "check_deal_risks",
                    // T-CONTENT-TOPICS -- route + GetTodaysTopicsExecutor added in the same change.
                    "get_todays_topics",
                    // T-PLAN-MY-WEEK -- route + GetPlanMyWeekExecutor added in the same change.
                    "plan_my_week",
                    // Meera intelligence v1 -- route + GetMyContentPatternsExecutor, same change.
                    "get_my_content_patterns",
                    // Swapnil 2026-09-26 -- route + GetMyAudienceExecutor, same change.
                    "get_my_audience");

    private CreatorToolScopes() {}

    /**
     * [SEC: Kabir Wave 2, finding 2] Only the three levels this class actually defines widen the
     * scope. Everything else — negative, above 2, or a level a future migration adds without
     * updating this method — falls to {@link #SCOPE_LEVEL_0}, the most restrictive scope, which is
     * what the second half of this javadoc has always promised.
     *
     * <p>It previously fell to {@link #SCOPE_LEVEL_2}, the <b>widest</b>, via a
     * {@code level == 1 ? LEVEL_1 : LEVEL_2} ternary — so a stored {@code 99} from a hand-run ops
     * UPDATE was granted {@code send_routine_reply}, a tool that puts a message in front of a
     * brand under the creator's name. That was inert only because the write path validates 0-2 and
     * the send route does not exist yet; it would have become a real send grant the day B1 lands
     * that route, which is exactly the kind of defect that ships unnoticed because nothing fails
     * until the capability behind it is wired.
     *
     * <p>Degrading rather than throwing is still deliberate: a bad row in the database must not
     * turn into an exception on the send path. It now degrades DOWN.
     *
     * @param approvalLevel the creator's stored approval level; any value that is not 0, 1 or 2
     *     yields the most restrictive scope
     */
    public static String scopeFor(int approvalLevel, boolean represented) {
        if (represented) {
            return SCOPE_REPRESENTED;
        }
        return switch (approvalLevel) {
            case 1 -> SCOPE_LEVEL_1;
            case 2 -> SCOPE_LEVEL_2;
            default -> SCOPE_LEVEL_0;
        };
    }

    /**
     * The tool names the model is offered for this creator: the intersection of the wired tools and
     * this creator's scope, in {@link #WIRED_TOOL_NAMES} order.
     *
     * <p>{@code holdout} removes nothing. The B6 negotiation holdout is a withholding decision made
     * <em>inside</em> an executor, on the result — the creator in the control arm still calls the
     * tool and still gets an answer, just without the anchor. Dropping the tool from the offered set
     * instead would make the two arms behave differently in ways the creator can see, which is
     * exactly what an experiment must not do. The parameter is accepted so callers do not have to
     * know that, and so the signature does not change when a future tool genuinely is gated on it.
     */
    public static List<String> toolNamesForLevel(
            int approvalLevel, boolean represented, boolean holdout) {
        Set<String> inScope =
                new LinkedHashSet<>(List.of(scopeFor(approvalLevel, represented).split("\\s+")));
        return WIRED_TOOL_NAMES.stream().filter(inScope::contains).toList();
    }
}
