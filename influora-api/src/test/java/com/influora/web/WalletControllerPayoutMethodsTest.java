package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.CreatorContextService;
import com.influora.service.WalletService;
import com.influora.service.WalletTopUpService;
import com.influora.service.payout.CreatorBankAccountService;
import com.influora.web.dto.wallet.BankAccountDtos.AddBankAccountRequest;
import com.influora.web.dto.wallet.BankAccountDtos.BankAccountResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * N3 (Wave 6) — the encrypted {@code CreatorBankAccountService} existed with zero controller
 * callers before this pass (unreachable from any HTTP route). These tests lock in the new
 * WalletController delegation for GET/POST /wallet/payout-methods and PUT
 * /wallet/payout-methods/{id}/primary, matching src/lib/api.ts's wallet.getPayoutMethods /
 * addPayoutMethod / setPrimaryPayoutMethod exactly.
 */
@ExtendWith(MockitoExtension.class)
class WalletControllerPayoutMethodsTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";

    @Mock private WalletService walletService;
    @Mock private WalletTopUpService walletTopUpService;
    @Mock private BrandContextService brandContext;
    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorBankAccountService creatorBankAccountService;
    @Mock private AuthPrincipal principal;

    private WalletController controller;

    @BeforeEach
    void setUp() {
        controller =
                new WalletController(
                        walletService,
                        walletTopUpService,
                        brandContext,
                        creatorContext,
                        creatorBankAccountService);
    }

    private CreatorBankAccount account(String id, boolean primary) {
        return CreatorBankAccount.createEncrypted(
                id,
                CREATOR_USER_ID,
                "cipher-account",
                null,
                "UPI",
                "****1234",
                primary,
                Instant.now(),
                Instant.now());
    }

    @Test
    @DisplayName("GET /wallet/payout-methods returns masked list, never plaintext")
    void testGetPayoutMethodsDelegatesToService() {
        when(creatorBankAccountService.listForCreator(principal))
                .thenReturn(List.of(account("pm_1", true), account("pm_2", false)));

        ResponseEntity<ApiResponse<List<BankAccountResponse>>> response =
                controller.getPayoutMethods(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        List<BankAccountResponse> body = response.getBody().data();
        assertEquals(2, body.size());
        assertEquals("pm_1", body.get(0).id());
        assertEquals("****1234", body.get(0).displayMask());
        verify(creatorContext).requireCreator(principal);
    }

    @Test
    @DisplayName("POST /wallet/payout-methods delegates to the encrypted add-instrument path and returns 201")
    void testAddPayoutMethodDelegatesToService() {
        AddBankAccountRequest body = new AddBankAccountRequest("UPI", "creator@upi", null, null);
        when(creatorBankAccountService.addInstrument(principal, "UPI", "creator@upi", null, null))
                .thenReturn(account("pm_new", true));

        ResponseEntity<ApiResponse<BankAccountResponse>> response =
                controller.addPayoutMethod(principal, body);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("pm_new", response.getBody().data().id());
        assertEquals(true, response.getBody().data().isPrimary());
        verify(creatorContext).requireCreator(principal);
        verify(creatorBankAccountService)
                .addInstrument(principal, "UPI", "creator@upi", null, null);
    }

    @Test
    @DisplayName("PUT /wallet/payout-methods/{id}/primary delegates to service and returns the now-primary instrument")
    void testSetPrimaryPayoutMethodDelegatesToService() {
        when(creatorBankAccountService.setPrimary(principal, "pm_2")).thenReturn(account("pm_2", true));

        ResponseEntity<ApiResponse<BankAccountResponse>> response =
                controller.setPrimaryPayoutMethod(principal, "pm_2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("pm_2", response.getBody().data().id());
        assertEquals(true, response.getBody().data().isPrimary());
        verify(creatorContext).requireCreator(principal);
        verify(creatorBankAccountService).setPrimary(principal, "pm_2");
    }

    // ===================================================================================
    // CR-77 — GET /wallet/payouts. Creator-only by design: a payout is a disbursement to a
    // creator's own bank account and has no brand/workspace analogue. The role gate is the only
    // thing standing between a brand principal and an endpoint whose contract says creator-only,
    // and it is one deletable line — so it gets a test rather than trust.
    // ===================================================================================

    @Test
    @DisplayName("CR-77 GET /wallet/payouts: enforces the creator role gate before doing any work")
    void payouts_enforcesCreatorRoleGate() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(walletService.getPayoutsForCreator(CREATOR_USER_ID, 1, 20))
                .thenReturn(
                        new WalletService.PagedCreatorPayouts(
                                List.of(), new com.influora.common.PageMeta(1, 20, 0, false)));

        controller.payouts(principal, 1, 20);

        // The gate must actually be invoked — deleting it left every other test in this module
        // green, which is precisely why this assertion exists.
        verify(creatorContext).requireCreator(principal);
    }

    @Test
    @DisplayName(
            "CR-77 GET /wallet/payouts: a principal the creator gate rejects gets the gate's error"
                    + " — never a silent empty list")
    void payouts_rejectedPrincipalPropagatesTheGateError() {
        AuthPrincipal principal = org.mockito.Mockito.mock(AuthPrincipal.class);
        org.mockito.Mockito.doThrow(
                        new com.influora.common.ApiException(
                                "FORBIDDEN", "Creator account required", HttpStatus.FORBIDDEN))
                .when(creatorContext)
                .requireCreator(principal);

        com.influora.common.ApiException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.influora.common.ApiException.class, () -> controller.payouts(principal, 1, 20));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        // An empty list would read as "you have no payouts" to a caller who can never have any.
        org.mockito.Mockito.verify(walletService, org.mockito.Mockito.never())
                .getPayoutsForCreator(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}
