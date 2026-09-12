package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.enums.CreatorToolName;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.3) — the on-behalf scope ceiling and the offered set. */
class CreatorToolScopesTest {

    private static Set<String> namesIn(String scope) {
        return Set.of(scope.trim().split("\\s+"));
    }

    @Test
    @DisplayName("SCOPE_LEVEL_0 carries all EIGHT level-0 names from SPEC.md 3.3, verbatim")
    void testLevelZeroCarriesAllEightNames() {
        Set<String> names = namesIn(CreatorToolScopes.SCOPE_LEVEL_0);
        assertEquals(8, names.size(), "SPEC.md 3.3 lists exactly eight level-0 tool names");
        assertEquals(
                Set.of(
                        "get_my_deals",
                        "get_brief",
                        "estimate_my_rate",
                        "get_my_metrics",
                        "check_deal_risks",
                        "draft_reply",
                        "rank_open_campaigns",
                        "draft_application"),
                names);
    }

    @Test
    @DisplayName(
            "No creator scope at any level ever names a money tool -- request_payment and"
                    + " confirm_launch are absent, not blocked")
    void testNoMoneyToolInAnyScope() {
        for (String scope :
                List.of(
                        CreatorToolScopes.SCOPE_LEVEL_0,
                        CreatorToolScopes.SCOPE_LEVEL_1,
                        CreatorToolScopes.SCOPE_LEVEL_2,
                        CreatorToolScopes.SCOPE_REPRESENTED)) {
            Set<String> names = namesIn(scope);
            assertFalse(names.contains("request_payment"), scope);
            assertFalse(names.contains("confirm_launch"), scope);
        }
    }

    @Test
    @DisplayName("SCOPE_LEVEL_1 is level 0 plus send_routine_reply; SCOPE_LEVEL_2 adds nothing")
    void testLevelOneAddsOnlySendRoutineReply() {
        Set<String> levelOne = namesIn(CreatorToolScopes.SCOPE_LEVEL_1);
        assertTrue(levelOne.containsAll(namesIn(CreatorToolScopes.SCOPE_LEVEL_0)));
        assertTrue(levelOne.contains("send_routine_reply"));
        assertEquals(9, levelOne.size());
        assertEquals(levelOne, namesIn(CreatorToolScopes.SCOPE_LEVEL_2));
    }

    /**
     * [QA Wave 2 nit] SCOPE_LEVEL_2 is byte-identical to SCOPE_LEVEL_1 on purpose — level 2 grants
     * no tool in this phase, because Phase E's auto-decline is a server-side job and not a
     * capability the model invokes.
     *
     * <p><b>Read this before writing a level-2 test.</b> While the two are equal, an assertion of
     * the form "level 2 contains everything level 1 does" is vacuously true and proves nothing. So
     * is {@code assertSame}: both constants are constant expressions (JLS 15.29), so javac interns
     * them, and re-declaring SCOPE_LEVEL_2 as a hand-typed identical literal still satisfies
     * {@code assertSame} — confirmed with a standalone probe, not assumed. Note also that javac
     * inlines such a constant into this test class, so after changing it in the main source you
     * must recompile the tests (a clean build) before believing what this file reports.
     *
     * <p>This test therefore pins level 2's grant as an <b>explicit literal set</b> rather than by
     * reference to level 1. It is the tripwire: a wave that adds a level-2-only tool turns it red
     * and has to state the added tool by name here.
     */
    @Test
    @DisplayName(
            "SCOPE_LEVEL_2 grants exactly the nine level-1 names, pinned as a literal set -- the"
                    + " equality with level 1 is deliberate, and superset/assertSame checks on it"
                    + " are vacuous")
    void testLevelTwoGrantsExactlyTheLevelOneSet() {
        assertEquals(
                Set.of(
                        "get_my_deals",
                        "get_brief",
                        "estimate_my_rate",
                        "get_my_metrics",
                        "check_deal_risks",
                        "draft_reply",
                        "rank_open_campaigns",
                        "draft_application",
                        "send_routine_reply"),
                namesIn(CreatorToolScopes.SCOPE_LEVEL_2),
                "level 2 grants no tool of its own in this phase; if that changed, assert the ADDED"
                        + " tool by name here -- a superset assertion against level 1 cannot fail");
        assertEquals(
                CreatorToolScopes.SCOPE_LEVEL_1,
                CreatorToolScopes.scopeFor(2, false),
                "a level-2 creator is granted exactly the level-1 scope, no more");
    }

    @Test
    @DisplayName(
            "SCOPE_REPRESENTED is reads only -- no draft_reply, no draft_application, no"
                    + " send_routine_reply")
    void testRepresentedScopeHasNoWriteTool() {
        Set<String> names = namesIn(CreatorToolScopes.SCOPE_REPRESENTED);
        assertFalse(names.contains("draft_reply"));
        assertFalse(names.contains("draft_application"));
        assertFalse(names.contains("send_routine_reply"));
        assertEquals(6, names.size());
    }

    @Test
    @DisplayName("scopeFor: representation wins over approval level at every level")
    void testRepresentationWinsOverLevel() {
        assertEquals(CreatorToolScopes.SCOPE_REPRESENTED, CreatorToolScopes.scopeFor(0, true));
        assertEquals(CreatorToolScopes.SCOPE_REPRESENTED, CreatorToolScopes.scopeFor(1, true));
        assertEquals(CreatorToolScopes.SCOPE_REPRESENTED, CreatorToolScopes.scopeFor(2, true));
    }

    @Test
    @DisplayName("scopeFor: the three defined levels map to their own scopes")
    void testScopeForDefinedLevels() {
        assertEquals(CreatorToolScopes.SCOPE_LEVEL_0, CreatorToolScopes.scopeFor(0, false));
        assertEquals(CreatorToolScopes.SCOPE_LEVEL_1, CreatorToolScopes.scopeFor(1, false));
        assertEquals(CreatorToolScopes.SCOPE_LEVEL_2, CreatorToolScopes.scopeFor(2, false));
    }

    @Test
    @DisplayName(
            "[SEC: Kabir Wave 2, finding 2] an out-of-range level clamps to the MOST RESTRICTIVE"
                    + " scope -- it used to fall through to level 2, the widest, handing a bad ops"
                    + " row the send tool")
    void testOutOfRangeLevelClampsToMostRestrictive() {
        for (int level : new int[] {-1, Integer.MIN_VALUE, 3, 99, Integer.MAX_VALUE}) {
            assertEquals(
                    CreatorToolScopes.SCOPE_LEVEL_0,
                    CreatorToolScopes.scopeFor(level, false),
                    "level " + level + " must degrade DOWN, not up");
        }
    }

    @Test
    @DisplayName(
            "[SEC: Kabir Wave 2, finding 2] no out-of-range level can reach send_routine_reply --"
                    + " the concrete grant the old fall-through handed out")
    void testOutOfRangeLevelNeverGrantsTheSendTool() {
        for (int level : new int[] {-1, 3, 99, Integer.MAX_VALUE}) {
            assertFalse(
                    namesIn(CreatorToolScopes.scopeFor(level, false)).contains("send_routine_reply"),
                    "level " + level + " was granted the brand-facing send tool");
        }
        // The tool is still reachable at the level that legitimately earns it, so this is a clamp
        // and not a blanket removal.
        assertTrue(
                namesIn(CreatorToolScopes.scopeFor(1, false)).contains("send_routine_reply"));
    }

    @Test
    @DisplayName(
            "toolNamesForLevel offers ONLY tools with a live route -- the four wired in Waves 2 and"
                    + " 3, never the four names the level-0 scope also mints")
    void testOnlyWiredToolsAreOffered() {
        List<String> offered = CreatorToolScopes.toolNamesForLevel(0, false, false);
        assertEquals(
                List.of("get_my_deals", "estimate_my_rate", "get_my_metrics", "check_deal_risks"),
                offered);
        // The gap between the ceiling and the offered set is the point: the scope names eight
        // tools, but a tool the model can call and the server cannot answer is worse than one it
        // cannot see.
        assertTrue(namesIn(CreatorToolScopes.SCOPE_LEVEL_0).size() > offered.size());
    }

    @Test
    @DisplayName("toolNamesForLevel: the holdout arm is offered exactly the same tools")
    void testHoldoutRemovesNothing() {
        assertEquals(
                CreatorToolScopes.toolNamesForLevel(0, false, false),
                CreatorToolScopes.toolNamesForLevel(0, false, true));
    }

    @Test
    @DisplayName(
            "Every offered tool name is a declared CreatorToolName -- so a route exists to answer"
                    + " it and the validator can resolve it")
    void testEveryOfferedNameResolvesToADeclaredTool() {
        for (String name : CreatorToolScopes.toolNamesForLevel(0, false, false)) {
            assertTrue(
                    CreatorToolName.parse(name).isPresent(),
                    "offered tool is not a declared CreatorToolName: " + name);
        }
    }
}
