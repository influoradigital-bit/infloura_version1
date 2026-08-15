package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiErrorBody;
import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.common.GlobalExceptionHandler;
import com.influora.common.InsufficientFundsException;
import com.influora.common.PageMeta;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.EscrowStatus;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.ErrorLogService;
import com.influora.service.EscrowService;
import com.influora.service.EscrowService.PagedEscrowHolds;
import com.influora.service.PayoutService;
import com.influora.web.dto.money.MoneyDtos.EscrowReleaseRequest;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Task N4 — GET /wallet/escrow controller delegation tests (brand-wallet escrow-items panel). */
@ExtendWith(MockitoExtension.class)
class EscrowControllerTest {

  private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
  private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";

  @Mock private EscrowService escrowService;
  @Mock private PayoutService payoutService;
  @Mock private BrandContextService brandContext;
  @Mock private AuthPrincipal principal;
  @Mock private Workspace workspace;

  private EscrowController controller;

  @BeforeEach
  void setUp() {
    controller = new EscrowController(escrowService, payoutService, brandContext);
  }

  @Test
  @DisplayName("GET /wallet/escrow delegates to the brand's own workspace with paging params")
  void testListDelegatesToServiceForBrandWorkspace() {
    when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
    when(workspace.getId()).thenReturn(WORKSPACE_ID);

    EscrowStatusResponse row =
        new EscrowStatusResponse(
            ESCROW_HOLD_ID,
            WORKSPACE_ID,
            "01HCAMPAIGN1234567AB",
            null,
            new BigDecimal("5000.00"),
            "INR",
            EscrowStatus.FUNDED,
            null,
            null);
    PagedEscrowHolds paged = new PagedEscrowHolds(List.of(row), new PageMeta(1, 20, 1, false));
    when(escrowService.listForWorkspace(principal, WORKSPACE_ID, 1, 20)).thenReturn(paged);

    ResponseEntity<ApiResponse<List<EscrowStatusResponse>>> response =
        controller.list(principal, 1, 20);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().data().size());
    assertEquals(ESCROW_HOLD_ID, response.getBody().data().get(0).escrowHoldId());
    assertEquals(1, response.getBody().meta().page());
    verify(brandContext).requireBrandWorkspace(principal);
    verify(escrowService).listForWorkspace(principal, WORKSPACE_ID, 1, 20);
  }

  @Test
  @DisplayName("GET /wallet/escrow defaults to page 1 / limit 20 when no query params supplied")
  void testListDefaultsPaging() {
    when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
    when(workspace.getId()).thenReturn(WORKSPACE_ID);
    when(escrowService.listForWorkspace(principal, WORKSPACE_ID, 1, 20))
        .thenReturn(new PagedEscrowHolds(List.of(), new PageMeta(1, 20, 0, false)));

    ResponseEntity<ApiResponse<List<EscrowStatusResponse>>> response =
        controller.list(principal, 1, 20);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(0, response.getBody().data().size());
    verify(escrowService).listForWorkspace(principal, WORKSPACE_ID, 1, 20);
  }

  // --------------------------------------------------------------------------------------------
  // [P-1' fix, BrandF.md §47a] POST /wallet/escrow/release now accepts EITHER milestoneId (routes
  // to EscrowService#release) OR escrowHoldId (routes to the new EscrowService#releaseByHoldId).
  // --------------------------------------------------------------------------------------------

  private static final String MILESTONE_ID = "01HMILESTONE123456789";

  @Test
  @DisplayName("POST /wallet/escrow/release with milestoneId routes to EscrowService#release")
  void testReleaseRoutesToMilestonePath() {
    when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
    when(workspace.getId()).thenReturn(WORKSPACE_ID);
    EscrowStatusResponse expected =
        new EscrowStatusResponse(
            ESCROW_HOLD_ID, WORKSPACE_ID, "campaign", MILESTONE_ID, new BigDecimal("100"), "INR",
            EscrowStatus.RELEASED, null, null);
    when(escrowService.release(principal, WORKSPACE_ID, MILESTONE_ID)).thenReturn(expected);

    ApiResponse<EscrowStatusResponse> response =
        controller.release(principal, new EscrowReleaseRequest(MILESTONE_ID, null));

    assertEquals(expected, response.data());
    verify(escrowService).release(principal, WORKSPACE_ID, MILESTONE_ID);
    verify(escrowService, org.mockito.Mockito.never())
        .releaseByHoldId(any(), any(), any());
  }

  @Test
  @DisplayName(
      "POST /wallet/escrow/release with escrowHoldId routes to the new"
          + " EscrowService#releaseByHoldId path (P-1' fix)")
  void testReleaseRoutesToHoldIdPath() {
    when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
    when(workspace.getId()).thenReturn(WORKSPACE_ID);
    EscrowStatusResponse expected =
        new EscrowStatusResponse(
            ESCROW_HOLD_ID, WORKSPACE_ID, "campaign", null, new BigDecimal("100"), "INR",
            EscrowStatus.RELEASED, null, null);
    when(escrowService.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID)).thenReturn(expected);

    ApiResponse<EscrowStatusResponse> response =
        controller.release(principal, new EscrowReleaseRequest(null, ESCROW_HOLD_ID));

    assertEquals(expected, response.data());
    verify(escrowService).releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID);
    verify(escrowService, org.mockito.Mockito.never()).release(any(), any(), any());
  }

  @Test
  @DisplayName("POST /wallet/escrow/release with NEITHER milestoneId nor escrowHoldId is rejected")
  void testReleaseRejectsWhenNeitherTargetSupplied() {
    when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);

    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> controller.release(principal, new EscrowReleaseRequest(null, null)));

    assertEquals("ESCROW_RELEASE_TARGET_REQUIRED", ex.getCode());
    verify(escrowService, org.mockito.Mockito.never()).release(any(), any(), any());
    verify(escrowService, org.mockito.Mockito.never()).releaseByHoldId(any(), any(), any());
  }

  @Test
  @DisplayName("POST /wallet/escrow/release with BOTH milestoneId and escrowHoldId is rejected (ambiguous)")
  void testReleaseRejectsWhenBothTargetsSupplied() {
    when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);

    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                controller.release(
                    principal, new EscrowReleaseRequest(MILESTONE_ID, ESCROW_HOLD_ID)));

    assertEquals("ESCROW_RELEASE_TARGET_REQUIRED", ex.getCode());
    verify(escrowService, org.mockito.Mockito.never()).release(any(), any(), any());
    verify(escrowService, org.mockito.Mockito.never()).releaseByHoldId(any(), any(), any());
  }

  // --------------------------------------------------------------------------------------------
  // [SEC: MF-1 follow-up, 2026-07-21] Web-layer: GlobalExceptionHandler's dedicated
  // InsufficientFundsException handler must actually put requiredAmount/walletBalance/
  // shortfallAmount/currency on the wire as camelCase JSON, and NON_NULL must still omit those
  // four fields for every other error (ApiErrorBody.of path), so the 402 shape is additive-only.
  // --------------------------------------------------------------------------------------------

  @Mock private ErrorLogService errorLogService;

  // findAndRegisterModules() picks up jackson-datatype-jsr310 (on the classpath via Spring Boot's
  // starter) so ApiResponse's java.time.Instant field serializes -- a bare `new ObjectMapper()`
  // would throw on Instant without it.
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  @DisplayName(
      "GlobalExceptionHandler: INSUFFICIENT_FUNDS 402 body serializes requiredAmount/walletBalance/"
          + "shortfallAmount/currency as camelCase JSON")
  void insufficientFundsExceptionSerializesShortfallFieldsOnTheWire() throws Exception {
    GlobalExceptionHandler handler = new GlobalExceptionHandler(errorLogService);
    InsufficientFundsException ex =
        new InsufficientFundsException(
            "Wallet balance is insufficient for this escrow amount",
            new BigDecimal("50000"),
            new BigDecimal("20000"),
            new BigDecimal("30000"),
            "INR");

    ResponseEntity<ApiResponse<Void>> response = handler.handleInsufficientFunds(ex);

    assertEquals(HttpStatus.PAYMENT_REQUIRED, response.getStatusCode());
    String json = objectMapper.writeValueAsString(response.getBody());
    assertTrue(json.contains("\"code\":\"INSUFFICIENT_FUNDS\""));
    assertTrue(json.contains("\"requiredAmount\":50000"));
    assertTrue(json.contains("\"walletBalance\":20000"));
    assertTrue(json.contains("\"shortfallAmount\":30000"));
    assertTrue(json.contains("\"currency\":\"INR\""));
  }

  @Test
  @DisplayName(
      "GlobalExceptionHandler: a non-insufficient-funds ApiException omits requiredAmount/"
          + "walletBalance/shortfallAmount/currency entirely (NON_NULL, additive-only shape)")
  void genericApiExceptionOmitsShortfallFields() throws Exception {
    GlobalExceptionHandler handler = new GlobalExceptionHandler(errorLogService);
    ApiException ex =
        new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND);

    ResponseEntity<ApiResponse<Void>> response = handler.handleApi(ex);

    String json = objectMapper.writeValueAsString(response.getBody());
    assertFalse(json.contains("requiredAmount"));
    assertFalse(json.contains("walletBalance"));
    assertFalse(json.contains("shortfallAmount"));
    ApiErrorBody body = objectMapper.readTree(json).has("error")
        ? objectMapper.treeToValue(objectMapper.readTree(json).get("error"), ApiErrorBody.class)
        : null;
    assertTrue(body != null);
    assertEquals("CAMPAIGN_NOT_FOUND", body.code());
  }
}
