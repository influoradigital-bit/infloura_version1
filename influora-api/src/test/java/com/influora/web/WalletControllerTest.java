package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.common.PageMeta;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.CreatorContextService;
import com.influora.service.WalletService;
import com.influora.service.WalletTopUpService;
import com.influora.service.WalletService.PagedWalletTransactions;
import com.influora.web.dto.money.MoneyDtos.CreatorWithdrawRequest;
import com.influora.web.dto.money.MoneyDtos.CreatorWithdrawResponse;
import com.influora.web.dto.money.MoneyDtos.WalletTransactionRowResponse;
import java.math.BigDecimal;
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

/** Task #18 — creator wallet withdraw + transaction history controller delegation tests. */
@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

  private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";

  @Mock private WalletService walletService;
  @Mock private WalletTopUpService walletTopUpService;
  @Mock private BrandContextService brandContext;
  @Mock private CreatorContextService creatorContext;
  @Mock private com.influora.service.payout.CreatorBankAccountService creatorBankAccountService;
  @Mock private AuthPrincipal principal;

  private WalletController controller;

  @BeforeEach
  void setUp() {
    controller =
        new WalletController(
            walletService, walletTopUpService, brandContext, creatorContext, creatorBankAccountService);
    when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
  }

  @Test
  @DisplayName("POST /wallet/withdraw delegates to service with principal userId and returns 201")
  void testWithdrawDelegatesToService() {
    CreatorWithdrawRequest body = new CreatorWithdrawRequest(new BigDecimal("2000.00"));
    CreatorWithdrawResponse serviceResponse = new CreatorWithdrawResponse("payout-abc");
    when(walletService.requestCreatorWithdrawal(CREATOR_USER_ID, body.amount(), "idem-1"))
        .thenReturn(serviceResponse);

    ResponseEntity<ApiResponse<CreatorWithdrawResponse>> response =
        controller.withdraw(principal, body, "idem-1");

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("payout-abc", response.getBody().data().payoutId());
    verify(creatorContext).requireCreator(principal);
    verify(walletService).requestCreatorWithdrawal(CREATOR_USER_ID, body.amount(), "idem-1");
  }

  @Test
  @DisplayName("GET /wallet/transactions delegates to service with paging params")
  void testTransactionsDelegatesToService() {
    WalletTransactionRowResponse row =
        new WalletTransactionRowResponse(
            "txn-1",
            "WITHDRAWAL",
            "DEBIT",
            new BigDecimal("500.00"),
            "INR",
            "Creator withdrawal",
            "COMPLETED",
            Instant.parse("2026-07-05T10:00:00Z"),
            new BigDecimal("9500.00"));
    PagedWalletTransactions paged =
        new PagedWalletTransactions(List.of(row), new PageMeta(1, 20, 1, false));
    when(walletService.getTransactionsForUser(CREATOR_USER_ID, 1, 20)).thenReturn(paged);

    ResponseEntity<ApiResponse<List<WalletTransactionRowResponse>>> response =
        controller.transactions(principal, 1, 20);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().data().size());
    assertEquals("txn-1", response.getBody().data().get(0).id());
    assertEquals(1, response.getBody().meta().page());
    verify(creatorContext).requireCreator(principal);
    verify(walletService).getTransactionsForUser(CREATOR_USER_ID, 1, 20);
  }
}
