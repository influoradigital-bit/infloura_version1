package com.influora.service.meera.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.MeeraToolTier;
import com.influora.service.AuditLogService;
import com.influora.service.meera.tool.ToolCallValidator.ToolCallRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P7: Unit tests for ToolCallValidator (16-VIKRAM-REMAINING-TASKS.md).
 * Priority: whitelist enforcement ([SEC: 5.3 schema + matrix], [LB-6]).
 */
@ExtendWith(MockitoExtension.class)
class ToolCallValidatorTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";

    @Mock private AuditLogService auditLogService;

    private ToolCallValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ToolCallValidator(auditLogService);
    }

    @Test
    @DisplayName("Unknown tool name: rejected with UNKNOWN_TOOL_NAME, audit logged")
    void testUnknownToolNameRejected() {
        String unknownTool = "hallucinated_money_transfer";

        ToolCallRejectedException ex = assertThrows(ToolCallRejectedException.class, () ->
                validator.validateAndResolve(unknownTool, WORKSPACE_ID));

        assertEquals("UNKNOWN_TOOL_NAME", ex.getReasonCode());

        // Verify audit log was written
        verify(auditLogService).recordToolCall(
                eq(WORKSPACE_ID),
                eq(unknownTool),
                eq(null),
                eq(AuditLogService.OUTCOME_REJECTED),
                eq("UNKNOWN_TOOL_NAME"),
                any(),
                any(),
                any());
    }

    @Test
    @DisplayName("Null tool name: rejected with UNKNOWN_TOOL_NAME")
    void testNullToolNameRejected() {
        ToolCallRejectedException ex = assertThrows(ToolCallRejectedException.class, () ->
                validator.validateAndResolve(null, WORKSPACE_ID));

        assertEquals("UNKNOWN_TOOL_NAME", ex.getReasonCode());
    }

    @ParameterizedTest
    @DisplayName("Injected/hallucinated tool names: all rejected")
    @ValueSource(strings = {
            "send_money_to_attacker",
            "modify_payment_amount",
            "bypass_escrow",
            "update_bank_account",
            "execute_payout",
            "admin_override",
            "delete_campaign",
            "../../../etc/passwd",
            "show_creators; DROP TABLE wallets;--",
            ""
    })
    void testInjectedToolNamesRejected(String injectedTool) {
        ToolCallRejectedException ex = assertThrows(ToolCallRejectedException.class, () ->
                validator.validateAndResolve(injectedTool, WORKSPACE_ID));

        assertEquals("UNKNOWN_TOOL_NAME", ex.getReasonCode());
    }

    @Test
    @DisplayName("Valid R-tier tool (show_creators): accepted")
    void testValidReadTierToolAccepted() {
        MeeraToolName result = validator.validateAndResolve("show_creators", WORKSPACE_ID);

        assertNotNull(result);
        assertEquals(MeeraToolName.show_creators, result);
        assertEquals(MeeraToolTier.R, validator.tierOf(result));
    }

    @Test
    @DisplayName("Valid R-tier tool (calculate_budget): accepted")
    void testValidCalculateBudgetAccepted() {
        MeeraToolName result = validator.validateAndResolve("calculate_budget", WORKSPACE_ID);

        assertNotNull(result);
        assertEquals(MeeraToolName.calculate_budget, result);
        assertEquals(MeeraToolTier.R, validator.tierOf(result));
    }

    @Test
    @DisplayName("Valid D-tier tool (create_campaign): accepted")
    void testValidDraftTierToolAccepted() {
        MeeraToolName result = validator.validateAndResolve("create_campaign", WORKSPACE_ID);

        assertNotNull(result);
        assertEquals(MeeraToolName.create_campaign, result);
        assertEquals(MeeraToolTier.D, validator.tierOf(result));
    }

    @Test
    @DisplayName("Valid C-tier tool (request_payment): accepted")
    void testValidCommitTierRequestPaymentAccepted() {
        MeeraToolName result = validator.validateAndResolve("request_payment", WORKSPACE_ID);

        assertNotNull(result);
        assertEquals(MeeraToolName.request_payment, result);
        assertEquals(MeeraToolTier.C, validator.tierOf(result));
    }

    @Test
    @DisplayName("Valid C-tier tool (confirm_launch): accepted")
    void testValidCommitTierConfirmLaunchAccepted() {
        MeeraToolName result = validator.validateAndResolve("confirm_launch", WORKSPACE_ID);

        assertNotNull(result);
        assertEquals(MeeraToolName.confirm_launch, result);
        assertEquals(MeeraToolTier.C, validator.tierOf(result));
    }

    @Test
    @DisplayName("Exactly 5 tools in whitelist, no more")
    void testExactlyFiveToolsInWhitelist() {
        // The enum should have exactly 5 values
        assertEquals(5, MeeraToolName.values().length,
                "MeeraToolName should have exactly 5 tools (the whitelist). " +
                "Adding a 6th tool requires explicit security review and matrix update.");

        // All 5 should have valid tiers
        for (MeeraToolName tool : MeeraToolName.values()) {
            MeeraToolTier tier = validator.tierOf(tool);
            assertNotNull(tier, "Tool " + tool + " must have a tier mapping");
        }
    }
}
