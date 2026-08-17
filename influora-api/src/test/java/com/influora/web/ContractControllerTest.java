package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.ContractService;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.ContractSignRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ContractController#sign} -- regression coverage for brand-feature-audit.md
 * #2 ("Contract brand-signing" BROKEN finding).
 *
 * <p>Before the fix, the brand branch of {@code /contracts/{id}/sign} REQUIRED {@code body.role()}
 * and 400'd with {@code INVALID_SIGNER_ROLE} whenever it was absent -- exactly what the real FE
 * call path sends ({@code api.ts:1466} -> {@code signContract} -> {@code {name, agreedAt}}, no
 * {@code role} field at all). The fix server-derives the signer role from the authenticated
 * principal instead: a BRAND principal defaults to {@code role=BRAND} when the body omits it, and
 * a CREATOR principal is routed to {@link ContractService#recordSignatureForCreator} exactly as
 * before (unchanged by this fix -- covered here for regression insurance).
 */
@ExtendWith(MockitoExtension.class)
class ContractControllerTest {

    private static final String USER_ID = "user-1";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String CONTRACT_ID = "01HCONTRACT123456789A";

    private static final AuthPrincipal BRAND_PRINCIPAL =
            new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, WORKSPACE_ID);
    private static final AuthPrincipal CREATOR_PRINCIPAL =
            new AuthPrincipal("creator-1", "creator@example.com", UserType.CREATOR, null);

    @Mock private ContractService contractService;
    @Mock private BrandContextService brandContext;

    private ContractController controller;

    @BeforeEach
    void setUp() {
        controller = new ContractController(contractService, brandContext);
    }

    private ContractResponse fakeResponse(ContractStatus status) {
        return new ContractResponse(
                CONTRACT_ID, "collab-1", WORKSPACE_ID, 1, status,
                null, "INR", null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("brand sign with {name, agreedAt} and no role now succeeds (was 400 INVALID_SIGNER_ROLE)")
    void brandSign_withoutRole_defaultsToBrandRole() {
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(brandContext.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(workspace);
        when(contractService.recordSignature(
                        BRAND_PRINCIPAL, WORKSPACE_ID, CONTRACT_ID, "BRAND", "Priya Brand Owner"))
                .thenReturn(fakeResponse(ContractStatus.PENDING_SIGNATURES));

        // The real FE payload: `{name, agreedAt}`, never `role` (api.ts:1466 -> signContract ->
        // POST /contracts/:id/sign) -- so `body.role()` is null exactly like production, but
        // `body.name()` (F-0292) IS the typed signer name and must reach the service call.
        ContractSignRequest body = new ContractSignRequest(null, "Priya Brand Owner", null);

        var response = controller.sign(BRAND_PRINCIPAL, CONTRACT_ID, body);

        assertEquals(ContractStatus.PENDING_SIGNATURES, response.data().status());
        verify(contractService)
                .recordSignature(BRAND_PRINCIPAL, WORKSPACE_ID, CONTRACT_ID, "BRAND", "Priya Brand Owner");
    }

    @Test
    @DisplayName("brand sign with a null body (no JSON at all) also defaults to BRAND, not a 400")
    void brandSign_withNullBody_defaultsToBrandRole() {
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(brandContext.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(workspace);
        when(contractService.recordSignature(BRAND_PRINCIPAL, WORKSPACE_ID, CONTRACT_ID, "BRAND", null))
                .thenReturn(fakeResponse(ContractStatus.PENDING_SIGNATURES));

        var response = controller.sign(BRAND_PRINCIPAL, CONTRACT_ID, null);

        assertEquals(ContractStatus.PENDING_SIGNATURES, response.data().status());
        verify(contractService).recordSignature(BRAND_PRINCIPAL, WORKSPACE_ID, CONTRACT_ID, "BRAND", null);
    }

    @Test
    @DisplayName("brand sign still honors an explicit role (elevated-member CREATOR relay path, unchanged)")
    void brandSign_withExplicitRole_isPassedThroughUnchanged() {
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(brandContext.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(workspace);
        when(contractService.recordSignature(
                        BRAND_PRINCIPAL, WORKSPACE_ID, CONTRACT_ID, "CREATOR", "Real Creator Name"))
                .thenReturn(fakeResponse(ContractStatus.ACTIVE));

        ContractSignRequest body = new ContractSignRequest("CREATOR", "Real Creator Name", null);

        var response = controller.sign(BRAND_PRINCIPAL, CONTRACT_ID, body);

        assertEquals(ContractStatus.ACTIVE, response.data().status());
        verify(contractService)
                .recordSignature(BRAND_PRINCIPAL, WORKSPACE_ID, CONTRACT_ID, "CREATOR", "Real Creator Name");
    }

    @Test
    @DisplayName("creator sign is unaffected -- still routes to recordSignatureForCreator, name now passed through")
    void creatorSign_stillRoutesToRecordSignatureForCreator() {
        when(contractService.recordSignatureForCreator(CREATOR_PRINCIPAL, CONTRACT_ID, "Real Creator Name"))
                .thenReturn(fakeResponse(ContractStatus.ACTIVE));

        var response =
                controller.sign(
                        CREATOR_PRINCIPAL,
                        CONTRACT_ID,
                        new ContractSignRequest(null, "Real Creator Name", null));

        assertEquals(ContractStatus.ACTIVE, response.data().status());
        verify(contractService).recordSignatureForCreator(CREATOR_PRINCIPAL, CONTRACT_ID, "Real Creator Name");
        verify(contractService, never())
                .recordSignature(eq(CREATOR_PRINCIPAL), eq(WORKSPACE_ID), eq(CONTRACT_ID), eq("CREATOR"), anyString());
        verify(brandContext, never()).requireBrandWorkspace(CREATOR_PRINCIPAL);
    }
}
