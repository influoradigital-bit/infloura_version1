package com.influora.service.meera.tool.creator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.influora.domain.enums.CreatorToolName;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.MeeraToolTier;
import com.influora.service.AuditLogService;
import com.influora.service.meera.tool.ToolCallValidator;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.2) — the creator-side tool-call choke point. */
@ExtendWith(MockitoExtension.class)
class CreatorToolCallValidatorTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";

    @Mock private AuditLogService auditLogService;

    private CreatorToolCallValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CreatorToolCallValidator(auditLogService);
    }

    @Test
    @DisplayName("validateAndResolve: every declared creator tool resolves and writes no audit row")
    void testEveryDeclaredToolResolves() {
        for (CreatorToolName tool : CreatorToolName.values()) {
            assertEquals(tool, validator.validateAndResolve(tool.name(), CREATOR_USER_ID));
        }
        // A successful resolution is not itself an auditable event -- the controller writes the
        // ALLOWED row after the executor runs, so a rejection audit here would double-count.
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName(
            "validateAndResolve: an unknown tool name is rejected with UNKNOWN_TOOL_NAME and an"
                    + " audit row keyed on the CREATOR's user id, with OUTCOME_REJECTED")
    void testUnknownToolNameIsRejectedAndAudited() {
        ToolCallValidator.ToolCallRejectedException ex =
                assertThrows(
                        ToolCallValidator.ToolCallRejectedException.class,
                        () -> validator.validateAndResolve("delete_everything", CREATOR_USER_ID));

        assertEquals("UNKNOWN_TOOL_NAME", ex.getReasonCode());

        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(auditLogService)
                .recordToolCall(
                        eq(CREATOR_USER_ID),
                        eq("delete_everything"),
                        eq((String) null),
                        outcome.capture(),
                        eq("UNKNOWN_TOOL_NAME"),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
        // OUTCOME_OK does not exist on AuditLogService; REJECTED is the constant this path uses.
        assertEquals(AuditLogService.OUTCOME_REJECTED, outcome.getValue());
    }

    @Test
    @DisplayName(
            "validateAndResolve: a BRAND tool name is rejected here -- the two catalogues are"
                    + " separate, so create_campaign is not reachable through a creator route")
    void testBrandToolNameIsNotAcceptedByTheCreatorValidator() {
        ToolCallValidator.ToolCallRejectedException ex =
                assertThrows(
                        ToolCallValidator.ToolCallRejectedException.class,
                        () ->
                                validator.validateAndResolve(
                                        MeeraToolName.create_campaign.name(), CREATOR_USER_ID));
        assertEquals("UNKNOWN_TOOL_NAME", ex.getReasonCode());
    }

    @Test
    @DisplayName("validateAndResolve: a null raw name rejects rather than throwing NullPointerException")
    void testNullRawNameRejects() {
        ToolCallValidator.ToolCallRejectedException ex =
                assertThrows(
                        ToolCallValidator.ToolCallRejectedException.class,
                        () -> validator.validateAndResolve(null, CREATOR_USER_ID));
        assertEquals("UNKNOWN_TOOL_NAME", ex.getReasonCode());
    }

    @Test
    @DisplayName(
            "validateAndResolve: parsing is EXACT -- a case-shifted or padded name is rejected,"
                    + " never leniently normalised into a real capability")
    void testParsingIsExact() {
        for (String raw : new String[] {"Get_My_Deals", " get_my_deals", "get_my_deals "}) {
            assertThrows(
                    ToolCallValidator.ToolCallRejectedException.class,
                    () -> validator.validateAndResolve(raw, CREATOR_USER_ID),
                    raw);
        }
    }

    @Test
    @DisplayName("tierOf: R for the six reads, D for draft_reply -- and never C or FORBIDDEN")
    void testTiers() {
        assertEquals(MeeraToolTier.R, validator.tierOf(CreatorToolName.get_my_deals));
        assertEquals(MeeraToolTier.R, validator.tierOf(CreatorToolName.get_brief));
        assertEquals(MeeraToolTier.R, validator.tierOf(CreatorToolName.estimate_my_rate));
        assertEquals(MeeraToolTier.R, validator.tierOf(CreatorToolName.get_my_metrics));
        assertEquals(MeeraToolTier.R, validator.tierOf(CreatorToolName.check_deal_risks));
        assertEquals(MeeraToolTier.R, validator.tierOf(CreatorToolName.get_todays_topics));
        assertEquals(MeeraToolTier.D, validator.tierOf(CreatorToolName.draft_reply));
    }

    @Test
    @DisplayName(
            "Every CreatorToolName has a tier -- a constant added without a TIER_BY_TOOL entry"
                    + " would otherwise be rejected at runtime as FORBIDDEN_TIER, not at build time")
    void testEveryToolHasATier() {
        for (CreatorToolName tool : CreatorToolName.values()) {
            MeeraToolTier tier = validator.tierOf(tool);
            assertNotNull(tier, "no tier mapped for " + tool);
            assertNotEquals(MeeraToolTier.FORBIDDEN, tier, "a FORBIDDEN tool must have no name at all: " + tool);
        }
    }

    @Test
    @DisplayName(
            "MeeraToolName is untouched: still exactly its six BRAND values, so"
                    + " ToolCallValidatorTest's count assertion and the schema-check CI job both"
                    + " stay green -- CreatorToolName now carries one more (get_todays_topics,"
                    + " T-CONTENT-TOPICS, outside SPEC.md 3.1), so the two catalogues are no longer"
                    + " the same size, only still disjoint")
    void testBrandToolCatalogueUntouched() {
        assertEquals(6, MeeraToolName.values().length);
        assertEquals(7, CreatorToolName.values().length);
        // Same size, entirely disjoint name sets -- the two enums must never share a constant.
        for (CreatorToolName creatorTool : CreatorToolName.values()) {
            for (MeeraToolName brandTool : MeeraToolName.values()) {
                assertNotEquals(creatorTool.name(), brandTool.name());
            }
        }
    }

    @Test
    @DisplayName("A rejection audit row is written BEFORE the throw, never skipped on the throw path")
    void testAuditPrecedesThrow() {
        assertThrows(
                ToolCallValidator.ToolCallRejectedException.class,
                () -> validator.validateAndResolve("nope", CREATOR_USER_ID));
        verify(auditLogService)
                .recordToolCall(
                        anyString(),
                        anyString(),
                        eq((String) null),
                        anyString(),
                        anyString(),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any(Map.class));
        verify(auditLogService, never()).recordAuthRejection(any(), any(), any(), any());
    }
}
