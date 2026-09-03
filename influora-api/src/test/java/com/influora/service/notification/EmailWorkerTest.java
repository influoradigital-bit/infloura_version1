package com.influora.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.enums.EmailOutboxStatus;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.EmailOutboxRepository;
import com.influora.repository.EmailPreferenceRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * T-ADMINMAIL-0903 REVIEW-R1.md round 2 — C2 (transactional priority lane) and item 8 (unsubscribe
 * re-check at dispatch). A bare {@link PlatformTransactionManager} mock is sufficient to exercise
 * {@link EmailWorker}'s {@link org.springframework.transaction.support.TransactionTemplate} calls:
 * {@code getTransaction(...)} returns {@code null} by default and {@code commit(null)}/{@code
 * rollback(null)} are no-op void mocks, so {@code TransactionTemplate.execute}/{@code
 * executeWithoutResult} simply run the callback and return — no real DB/connection needed, same
 * pattern as {@code SubscriptionDunningJobTest}.
 */
@ExtendWith(MockitoExtension.class)
class EmailWorkerTest {

    @Mock private EmailOutboxRepository emailOutboxRepository;
    @Mock private EmailPreferenceRepository emailPreferenceRepository;
    @Mock private Msg91EmailClient msg91Client;
    @Mock private PlatformTransactionManager transactionManager;

    private EmailWorker worker;

    @BeforeEach
    void setUp() {
        worker =
                new EmailWorker(
                        emailOutboxRepository, emailPreferenceRepository, msg91Client, transactionManager);
    }

    private static EmailOutbox row(String id, String userId, String templateKey) {
        return EmailOutbox.builder()
                .id(id)
                .userId(userId)
                .toEmail(userId + "@example.com")
                .templateKey(templateKey)
                .templateData("{}")
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // C2 — transactional priority lane
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "C2: processOutbox() asks the repository to prioritize auth.otp/otpman/auth.password_reset"
                    + " ahead of everything else")
    void claimPassesTransactionalPriorityKeys() {
        when(emailOutboxRepository.findPendingForSend(
                        eq(EmailOutboxStatus.PENDING), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());

        worker.processOutbox();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(emailOutboxRepository)
                .findPendingForSend(eq(EmailOutboxStatus.PENDING), any(), captor.capture(), any(Pageable.class));

        Collection<String> priorityKeys = captor.getValue();
        assertTrue(priorityKeys.contains("auth.otp"), "auth.otp must be a priority key: " + priorityKeys);
        assertTrue(
                priorityKeys.contains("auth.password_reset"),
                "auth.password_reset must be a priority key: " + priorityKeys);
        assertTrue(priorityKeys.contains("otpman"), "otpman must be a priority key: " + priorityKeys);
        assertFalse(
                priorityKeys.contains("admin.custom"),
                "admin.custom must NOT be a priority key (it's exactly what OTP must never queue behind): "
                        + priorityKeys);
    }

    // ------------------------------------------------------------------------------------------
    // item 8 — unsubscribe re-checked at dispatch, admin.custom only
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "item 8: an admin.custom row whose recipient unsubscribed AFTER enqueue is skipped at"
                    + " dispatch — never sent, marked terminal (no retry)")
    void adminCustomRowSkippedWhenUnsubscribedBeforeDispatch() {
        EmailOutbox pending = row("row-1", "u-unsub", "admin.custom");
        when(emailOutboxRepository.findPendingForSend(
                        eq(EmailOutboxStatus.PENDING), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(emailOutboxRepository.findById("row-1")).thenReturn(Optional.of(pending));
        when(emailPreferenceRepository.findUnsubscribedUserIds(List.of("u-unsub"), "admin.custom"))
                .thenReturn(Set.of("u-unsub"));

        worker.processOutbox();

        verify(msg91Client, never()).sendTemplateEmail(anyString(), anyString(), anyString(), anyString());

        ArgumentCaptor<EmailOutbox> savedCaptor = ArgumentCaptor.forClass(EmailOutbox.class);
        verify(emailOutboxRepository, times(1)).save(savedCaptor.capture());
        EmailOutbox saved = savedCaptor.getValue();
        assertEquals(EmailOutboxStatus.FAILED, saved.getStatus());
        assertFalse(saved.canRetry(), "a skipped-unsubscribed row must not be retried");
    }

    @Test
    @DisplayName("item 8: an admin.custom row whose recipient is still subscribed sends normally")
    void adminCustomRowStillSubscribedIsSent() {
        EmailOutbox pending = row("row-2", "u-sub", "admin.custom");
        when(emailOutboxRepository.findPendingForSend(
                        eq(EmailOutboxStatus.PENDING), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(emailOutboxRepository.findById("row-2")).thenReturn(Optional.of(pending));
        when(emailPreferenceRepository.findUnsubscribedUserIds(List.of("u-sub"), "admin.custom"))
                .thenReturn(Set.of());
        // A3: still PENDING (not cancelled) -- must proceed to send.
        when(emailOutboxRepository.existsByIdAndStatus("row-2", EmailOutboxStatus.PENDING))
                .thenReturn(true);
        when(msg91Client.sendTemplateEmail("u-sub@example.com", "admin.custom", "{}", "u-sub"))
                .thenReturn(true);

        worker.processOutbox();

        verify(msg91Client).sendTemplateEmail("u-sub@example.com", "admin.custom", "{}", "u-sub");
    }

    // ------------------------------------------------------------------------------------------
    // A3 (REVIEW-R3.md) — cancellation re-checked at dispatch, admin.custom only
    // ------------------------------------------------------------------------------------------

    /**
     * A3 (REVIEW-R3.md): {@code AdminCustomEmailService#cancel} can mark a row terminal (CANCELLED,
     * reusing {@code EmailOutboxStatus.FAILED}) WHILE it is already claimed and sitting in this
     * worker's in-memory batch — claiming only advances {@code nextRetryAt}, never {@code status},
     * so the row this test seeds is genuinely still {@code PENDING} in memory even though the DB
     * row (simulated by the {@code existsByIdAndStatus} stub) has already moved on. Before this
     * fix, {@code processOne} would send anyway and {@code applyResult}'s {@code markSent()} would
     * silently overwrite the CANCELLED status back to SENT — the exact "cancel reports success for
     * mail it did not stop" bug REVIEW-R3.md flagged as the round's most important RISK item.
     *
     * <p>Falsification: removing the {@code existsByIdAndStatus} re-check from {@code processOne}
     * turns this red — {@code msg91Client.sendTemplateEmail} gets invoked despite the row no longer
     * being PENDING. Verified directly.
     */
    @Test
    @DisplayName(
            "A3: an admin.custom row cancelled AFTER being claimed (no longer PENDING) is never sent,"
                    + " and its terminal status is left untouched")
    void adminCustomRowSkippedWhenCancelledAfterClaim() {
        EmailOutbox pending = row("row-4", "u-cancelled", "admin.custom");
        when(emailOutboxRepository.findPendingForSend(
                        eq(EmailOutboxStatus.PENDING), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(emailPreferenceRepository.findUnsubscribedUserIds(List.of("u-cancelled"), "admin.custom"))
                .thenReturn(Set.of());
        // Simulates AdminCustomEmailService#cancel having already marked this row terminal in the
        // DB, between claim and dispatch -- the row is no longer PENDING there, even though the
        // detached in-memory copy `pending` (read at claim time) still says PENDING.
        when(emailOutboxRepository.existsByIdAndStatus("row-4", EmailOutboxStatus.PENDING))
                .thenReturn(false);

        worker.processOutbox();

        verify(msg91Client, never()).sendTemplateEmail(anyString(), anyString(), anyString(), anyString());
        // Must not touch the row at all -- no markResult/markSkipped save -- so the CANCELLED
        // status cancel() already wrote is never overwritten back to SENT/FAILED-retry.
        verify(emailOutboxRepository, never()).save(any(EmailOutbox.class));
        verify(emailOutboxRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName(
            "A3 cost check: a NON-admin.custom row never triggers the cancellation re-check query at"
                    + " all (no added cost for ordinary transactional/notification email)")
    void nonAdminCustomRowSkipsCancellationRecheckEntirely() {
        EmailOutbox pending = row("row-5", "u-otp2", "auth.otp");
        when(emailOutboxRepository.findPendingForSend(
                        eq(EmailOutboxStatus.PENDING), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(emailOutboxRepository.findById("row-5")).thenReturn(Optional.of(pending));
        when(msg91Client.sendTemplateEmail("u-otp2@example.com", "auth.otp", "{}", "u-otp2"))
                .thenReturn(true);

        worker.processOutbox();

        verify(emailOutboxRepository, never()).existsByIdAndStatus(anyString(), any(EmailOutboxStatus.class));
        verify(msg91Client).sendTemplateEmail("u-otp2@example.com", "auth.otp", "{}", "u-otp2");
    }

    @Test
    @DisplayName(
            "item 8 cost check: a NON-admin.custom row never triggers the unsubscribe re-check query"
                    + " at all (no added cost for ordinary transactional/notification email)")
    void nonAdminCustomRowSkipsUnsubscribeRecheckEntirely() {
        EmailOutbox pending = row("row-3", "u-otp", "auth.otp");
        when(emailOutboxRepository.findPendingForSend(
                        eq(EmailOutboxStatus.PENDING), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(emailOutboxRepository.findById("row-3")).thenReturn(Optional.of(pending));
        when(msg91Client.sendTemplateEmail("u-otp@example.com", "auth.otp", "{}", "u-otp"))
                .thenReturn(true);

        worker.processOutbox();

        verify(emailPreferenceRepository, never()).findUnsubscribedUserIds(any(), any());
        verify(msg91Client).sendTemplateEmail("u-otp@example.com", "auth.otp", "{}", "u-otp");
    }

    // ------------------------------------------------------------------------------------------
    // A4 (REVIEW-R4.md) — a batch that runs long stops dispatching once it risks outliving its
    // own claim lease, instead of sending every claimed row regardless of elapsed wall-clock.
    // ------------------------------------------------------------------------------------------

    /**
     * A4 (REVIEW-R4.md): under degraded SMTP, a full 50-row batch that all time out at
     * spring.mail's 10s timeout takes ~500s -- longer than the 3-minute claim lease -- so a
     * second poll can re-claim and re-send rows this run already dispatched. This test cannot
     * wait out the real ~2.5 minute default budget, so it uses the package-private constructor
     * to inject a 40ms budget and makes the first row's "send" (the mocked {@code
     * msg91Client.sendTemplateEmail} call) actually take 80ms via {@code Thread.sleep} -- a real
     * elapsed-wall-clock overrun, not a stubbed clock. Two rows are claimed; row 1 is genuinely
     * slow enough to blow the tiny budget, so row 2 must never be dispatched or touched at all.
     *
     * <p>Falsification: removing the deadline check from {@code processOutbox}'s loop turns this
     * red -- {@code msg91Client.sendTemplateEmail} for row 2 gets invoked despite the budget
     * having already elapsed. Verified directly.
     */
    @Test
    @DisplayName(
            "A4: a batch that runs past its wall-clock budget stops dispatching further rows,"
                    + " leaving them untouched for a later poll")
    void batchStopsDispatchingOnceWallClockBudgetExceeded() throws InterruptedException {
        EmailOutbox slow = row("row-slow", "u-slow", "auth.otp");
        EmailOutbox rest = row("row-rest", "u-rest", "auth.otp");
        EmailWorker budgetedWorker =
                new EmailWorker(
                        emailOutboxRepository,
                        emailPreferenceRepository,
                        msg91Client,
                        transactionManager,
                        Duration.ofMillis(40));

        when(emailOutboxRepository.findPendingForSend(
                        eq(EmailOutboxStatus.PENDING), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(slow, rest));
        when(emailOutboxRepository.findById("row-slow")).thenReturn(Optional.of(slow));
        when(msg91Client.sendTemplateEmail("u-slow@example.com", "auth.otp", "{}", "u-slow"))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(80);
                            return true;
                        });

        budgetedWorker.processOutbox();

        verify(msg91Client).sendTemplateEmail("u-slow@example.com", "auth.otp", "{}", "u-slow");
        verify(msg91Client, never())
                .sendTemplateEmail("u-rest@example.com", "auth.otp", "{}", "u-rest");
        verify(emailOutboxRepository, never()).findById("row-rest");
    }

    @Test
    @DisplayName(
            "A4: a batch that stays well inside its wall-clock budget dispatches every claimed row,"
                    + " same as before the fix")
    void batchDispatchesEveryRowWhenWellInsideBudget() {
        EmailOutbox first = row("row-a", "u-a", "auth.otp");
        EmailOutbox second = row("row-b", "u-b", "auth.otp");
        EmailWorker budgetedWorker =
                new EmailWorker(
                        emailOutboxRepository,
                        emailPreferenceRepository,
                        msg91Client,
                        transactionManager,
                        Duration.ofSeconds(30));

        when(emailOutboxRepository.findPendingForSend(
                        eq(EmailOutboxStatus.PENDING), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(emailOutboxRepository.findById("row-a")).thenReturn(Optional.of(first));
        when(emailOutboxRepository.findById("row-b")).thenReturn(Optional.of(second));
        when(msg91Client.sendTemplateEmail("u-a@example.com", "auth.otp", "{}", "u-a"))
                .thenReturn(true);
        when(msg91Client.sendTemplateEmail("u-b@example.com", "auth.otp", "{}", "u-b"))
                .thenReturn(true);

        budgetedWorker.processOutbox();

        verify(msg91Client).sendTemplateEmail("u-a@example.com", "auth.otp", "{}", "u-a");
        verify(msg91Client).sendTemplateEmail("u-b@example.com", "auth.otp", "{}", "u-b");
    }
}
