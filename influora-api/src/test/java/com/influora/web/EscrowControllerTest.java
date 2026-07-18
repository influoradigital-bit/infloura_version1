package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.common.PageMeta;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.EscrowStatus;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.EscrowService;
import com.influora.service.EscrowService.PagedEscrowHolds;
import com.influora.service.PayoutService;
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
}
