package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AffiliateSettlementBatch;
import com.influora.repository.AffiliateEarningRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * F-0641 (silent-fallback-to-noop): {@link AffiliateSettlementWriter#creditCreatorWallet} used to
 * return silently -- no log, no signal -- when the writer's wallet collaborators
 * ({@code walletLedgerService}/{@code walletService}/{@code platformWalletService}) were null,
 * which is exactly the state the class's legacy narrower constructor ({@code
 * AffiliateSettlementWriter(AffiliateEarningRepository)}, still used by {@code
 * AffiliateSettlementJobTest} and {@code CreatorAffiliateEarningSettlementTest}) leaves them in.
 * The earning is already marked SETTLED one line above the call into {@code creditCreatorWallet}
 * ({@link AffiliateSettlementWriter#doSettleCreator}), so before this fix a null collaborator here
 * left money durably recorded as paid with zero trace that the wallet credit never happened.
 *
 * <p>This test proves settling an earning through the narrower (null-collaborator) constructor now
 * logs the skip loudly (ERROR, naming the earning) instead of silently no-opping, while still not
 * throwing -- throwing would break the two locked tests above that intentionally exercise this
 * no-op path.
 */
class AffiliateSettlementWriterNullCollaboratorLogTest {

    private Logger writerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        writerLogger = (Logger) LoggerFactory.getLogger(AffiliateSettlementWriter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        writerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        writerLogger.detachAppender(logAppender);
    }

    private static AffiliateEarning pendingEarning() {
        return AffiliateEarning.builder()
                .id("01HEARNINGNULLCOLLAB01")
                .workspaceId("01HWORKSPACE12345678A")
                .campaignId("01HCAMPAIGN123456789A")
                .creatorId("01HCREATORNULLCOLLAB01")
                .redemptionId("01HREDEMPTIONNULLCOLL1")
                .commissionAmount(BigDecimal.valueOf(75))
                .currency("INR")
                .idempotencyKey("affearn:redemption-nullcollab1")
                .build();
    }

    @Test
    @DisplayName(
            "F-0641: settling with a writer built via the legacy null-collaborator constructor logs"
                    + " an ERROR naming the earning instead of silently no-opping, and still marks the"
                    + " earning SETTLED (unchanged, non-throwing status-flip-only behaviour)")
    void nullWalletCollaborators_logsLoudlyInsteadOfSilentNoOp() {
        AffiliateEarningRepository affiliateEarningRepository = mock(AffiliateEarningRepository.class);
        // Legacy narrower constructor: leaves walletLedgerService/walletService/
        // platformWalletService null -- exactly the state F-0641 is about.
        AffiliateSettlementWriter writer = new AffiliateSettlementWriter(affiliateEarningRepository);

        AffiliateEarning earning = pendingEarning();
        AffiliateSettlementBatch batch =
                AffiliateSettlementBatch.builder().id("01HBATCHNULLCOLLAB0001").periodYearMonth("2026-09").build();

        // Falsifiable core assertion: does not throw.
        writer.doSettleCreator(List.of(earning), batch);

        // Old behaviour (the bug): earning still flips to SETTLED (unchanged; this is the
        // sanctioned status-flip-only path the two locked tests rely on) --
        assertEquals(AffiliateEarning.Status.SETTLED, earning.getStatus());
        verify(affiliateEarningRepository).save(earning);

        // New behaviour (the fix): the skip is no longer silent -- an ERROR log names the earning
        // whose commission did NOT get credited, so an operator can see the money that did not
        // move and why. This is the exact assertion that fails against the pre-fix code (which
        // logs nothing at all).
        List<ILoggingEvent> errorEvents =
                logAppender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertFalse(
                errorEvents.isEmpty(),
                "expected an ERROR log when creditCreatorWallet no-ops on null wallet collaborators,"
                        + " but no ERROR was logged at all");
        boolean namesTheEarning =
                errorEvents.stream()
                        .anyMatch(
                                e ->
                                        e.getFormattedMessage().contains(earning.getId())
                                                && e.getFormattedMessage().contains("F-0641"));
        assertTrue(
                namesTheEarning,
                "expected the ERROR log to identify the affected earning (id=" + earning.getId() + ")"
                        + " and reference F-0641, but got: "
                        + errorEvents.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    @DisplayName(
            "Sanity check: a writer built via the full four-arg constructor with real wallet"
                    + " collaborators never hits the null-collaborator branch, so no F-0641 ERROR is"
                    + " logged and the (mocked) collaborators are actually exercised")
    void nonNullWalletCollaborators_doesNotLogTheNullCollaboratorError() {
        AffiliateEarningRepository affiliateEarningRepository = mock(AffiliateEarningRepository.class);
        com.influora.service.WalletLedgerService walletLedgerService =
                mock(com.influora.service.WalletLedgerService.class);
        com.influora.service.WalletService walletService = mock(com.influora.service.WalletService.class);
        com.influora.service.PlatformWalletService platformWalletService =
                mock(com.influora.service.PlatformWalletService.class);

        com.influora.domain.entity.Wallet clearingWallet = mock(com.influora.domain.entity.Wallet.class);
        com.influora.domain.entity.Wallet creatorWallet = mock(com.influora.domain.entity.Wallet.class);
        org.mockito.Mockito.when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        org.mockito.Mockito.when(walletService.requireOrCreateUserWallet(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(creatorWallet);

        AffiliateSettlementWriter writer =
                new AffiliateSettlementWriter(
                        affiliateEarningRepository, walletLedgerService, walletService, platformWalletService);

        AffiliateEarning earning = pendingEarning();
        AffiliateSettlementBatch batch =
                AffiliateSettlementBatch.builder().id("01HBATCHNULLCOLLAB0002").periodYearMonth("2026-09").build();

        writer.doSettleCreator(List.of(earning), batch);

        boolean f0641ErrorLogged =
                logAppender.list.stream()
                        .anyMatch(
                                e ->
                                        e.getLevel() == Level.ERROR
                                                && e.getFormattedMessage().contains("F-0641"));
        assertFalse(
                f0641ErrorLogged,
                "did not expect the null-collaborator ERROR when real wallet collaborators are"
                        + " supplied");
        verify(platformWalletService).requireClearingWallet();
        verify(walletService).requireOrCreateUserWallet(earning.getCreatorId());
        verify(walletLedgerService)
                .post(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(earning.getCommissionAmount()),
                        org.mockito.ArgumentMatchers.eq(earning.getCurrency()),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(earning.getId()),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(earning.getIdempotencyKey()),
                        org.mockito.ArgumentMatchers.isNull());
    }
}
