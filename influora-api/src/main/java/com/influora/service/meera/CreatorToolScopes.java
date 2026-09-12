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
 * four of them are wired. Minting the full ceiling now means a later wave adds a route and an
 * executor without also having to re-mint tokens or migrate a claim shape.
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
                    + " rank_open_campaigns draft_application";

    /** Level 1 adds the one commit-like tool: a routine reply actually reaches the brand. */
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
                    + " rank_open_campaigns";

    /**
     * The tools that have a route AND an executor today, in SPEC.md &sect;3.1 catalogue order.
     *
     * <p>Wave 3 added {@code estimate_my_rate} and {@code check_deal_risks} alongside
     * {@code RateQuoteService} and {@code DealRiskService}. Still to come: {@code get_brief} with
     * Wave 4's {@code CreatorBriefService}, {@code draft_reply} with the draft surface, then
     * {@code send_routine_reply} (B1/B5) and {@code rank_open_campaigns} /
     * {@code draft_application} (B7).
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
            List.of("get_my_deals", "estimate_my_rate", "get_my_metrics", "check_deal_risks");

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
