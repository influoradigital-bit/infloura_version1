package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.domain.entity.AdminEmailCampaign;
import com.influora.domain.entity.AdminEmailSendLock;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.entity.User;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.EmailOutboxStatus;
import com.influora.domain.enums.UserType;
import com.influora.integration.msg91.EmailPreviewResult;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.AdminEmailCampaignRepository;
import com.influora.repository.AdminEmailSendLockRepository;
import com.influora.repository.EmailOutboxRepository;
import com.influora.repository.EmailPreferenceRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.AuditLogService;
import com.influora.web.dto.admin.AdminCustomEmailDtos.AudienceDto;
import com.influora.web.dto.admin.AdminCustomEmailDtos.CancelResponse;
import com.influora.web.dto.admin.AdminCustomEmailDtos.PreviewRequest;
import com.influora.web.dto.admin.AdminCustomEmailDtos.PreviewResponse;
import com.influora.web.dto.admin.AdminCustomEmailDtos.SendRequest;
import com.influora.web.dto.admin.AdminCustomEmailDtos.SendResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

/**
 * T-ADMINMAIL-0903 — covers all five abuse controls the SPEC names as non-negotiable: the 409
 * recipient-count-mismatch path, unsubscribe skipping, the recipient-cap refusal, the persisted
 * rate limit, unknown-token rejection (both entry points), and that personalization actually
 * substitutes per recipient (two recipients getting two different rendered bodies). Every control
 * test asserts BOTH the thrown/returned outcome AND that no side effect (outbox row, campaign row)
 * happened when the control refuses the send — a control that throws late, after already writing
 * rows, would still "pass" a test that only checks the exception.
 *
 * <p>Round 3 additions (REVIEW-R2.md): B2's server-side sampling and preview audit, B3's cancel
 * endpoint, B4's widened malformed-token rejection, B5's stored {@code queuedCount}, and the two
 * documentation defects (the {@code findById} short-circuit's real position; {@code
 * SendResponse.replay}). B1 (the real serialization fix) is proven separately by {@code
 * AdminEmailSendLockRepositoryConcurrencyTest} — a real Hibernate+H2 test, not a Mockito one,
 * since no mock can prove a {@code SELECT ... FOR UPDATE} actually blocks a second transaction;
 * this class only verifies that {@code send()} is WIRED to take that lock.
 */
@ExtendWith(MockitoExtension.class)
class AdminCustomEmailServiceTest {

    private static final String ADMIN_ID = "01HADMIN0000000000000001";
    private static final AuthPrincipal PRINCIPAL =
            new AuthPrincipal(ADMIN_ID, "admin@influora.in", UserType.ADMIN, null);

    @Mock private AdminContextService adminContext;
    @Mock private UserRepository userRepository;
    @Mock private EmailOutboxRepository emailOutboxRepository;
    @Mock private EmailPreferenceRepository emailPreferenceRepository;
    @Mock private AdminEmailCampaignRepository adminEmailCampaignRepository;
    @Mock private AdminEmailSendLockRepository adminEmailSendLockRepository;
    @Mock private Msg91EmailClient msg91EmailClient;
    @Mock private AuditLogService auditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdminUser superAdmin;

    @BeforeEach
    void setUp() {
        superAdmin = AdminUser.create(ADMIN_ID, "admin@influora.in", "hash", AdminRole.SUPER_ADMIN);
    }

    private AdminCustomEmailService service(int recipientCap, int minIntervalMinutes) {
        // A5 (REVIEW-R4.md): previewMinIntervalMillis=0 disables the new preview rate limit for
        // every test in this class EXCEPT the two A5-specific ones below, which construct the
        // service directly with a real, controllable value instead of going through this helper.
        return service(recipientCap, minIntervalMinutes, 0L);
    }

    private AdminCustomEmailService service(
            int recipientCap, int minIntervalMinutes, long previewMinIntervalMillis) {
        return new AdminCustomEmailService(
                adminContext,
                userRepository,
                emailOutboxRepository,
                emailPreferenceRepository,
                adminEmailCampaignRepository,
                adminEmailSendLockRepository,
                msg91EmailClient,
                auditLogService,
                objectMapper,
                recipientCap,
                minIntervalMinutes,
                previewMinIntervalMillis);
    }

    private static User creator(String id, String email, String firstName, String displayName) {
        return User.newCreator(id, email, "hash", firstName, "Last", displayName);
    }

    private static AudienceDto allAudience() {
        return new AudienceDto("ALL", false, null);
    }

    private void stubAdmin() {
        when(adminContext.requireRoleWithMfaSatisfied(PRINCIPAL, AdminRole.SUPER_ADMIN))
                .thenReturn(superAdmin);
        // send() always tries to take the singleton lock before/after its idempotency
        // short-circuit (B1, class javadoc "Serialization") — stubbed leniently since the
        // token-rejection and preview-only tests below call stubAdmin() indirectly through no
        // path that reaches the lock, and MockitoExtension's strict stubbing would otherwise flag
        // it as unused in those tests.
        lenient()
                .when(adminEmailSendLockRepository.lockForUpdate(AdminEmailSendLock.SINGLETON_ID))
                .thenReturn(Optional.of(AdminEmailSendLock.singleton()));
    }

    // ------------------------------------------------------------------------------------------
    // Personalization: two recipients get two different rendered bodies
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("send() personalizes {{first_name}}/{{name}}/{{email}} per recipient, and enqueues one outbox row each")
    void sendPersonalizesPerRecipient() throws Exception {
        stubAdmin();
        User asha = creator("u-asha", "asha@example.com", "Asha", null);
        User vikram = creator("u-vikram", "vik@example.com", "Vik", "Vikram R");

        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(2L);
        when(userRepository.findForCustomEmailAudience(eq(null), eq(false), eq(null), any(Limit.class)))
                .thenReturn(List.of(asha, vikram));
        when(emailPreferenceRepository.findUnsubscribedUserIds(anyCollection(), eq("admin.custom")))
                .thenReturn(Set.of());

        SendRequest req =
                new SendRequest(
                        "Hi {{first_name}}",
                        "Hello {{name}}, your email on file is {{email}}.",
                        null,
                        null,
                        allAudience(),
                        2L);

        AdminCustomEmailService svc = service(5000, 10);
        SendResponse resp = svc.send(PRINCIPAL, req);

        assertEquals(2, resp.queued());
        assertEquals(0, resp.skippedUnsubscribed());
        assertNotEquals(null, resp.campaignId());
        assertFalse(resp.replay(), "a fresh send must not be marked as a replay");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailOutboxRepository).saveAll(captor.capture());
        List<EmailOutbox> saved = captor.getValue();
        assertEquals(2, saved.size());

        EmailOutbox ashaRow = saved.stream().filter(o -> o.getUserId().equals("u-asha")).findFirst().orElseThrow();
        EmailOutbox vikramRow =
                saved.stream().filter(o -> o.getUserId().equals("u-vikram")).findFirst().orElseThrow();

        JsonNode ashaData = objectMapper.readTree(ashaRow.getTemplateData());
        JsonNode vikramData = objectMapper.readTree(vikramRow.getTemplateData());

        assertEquals("Hi Asha", ashaData.get("subject").asText());
        assertEquals("Hello Asha, your email on file is asha@example.com.", ashaData.get("bodyText").asText());
        assertEquals("Hi Vik", vikramData.get("subject").asText());
        assertEquals(
                "Hello Vikram R, your email on file is vik@example.com.", vikramData.get("bodyText").asText());
        assertNotEquals(ashaData.get("bodyText").asText(), vikramData.get("bodyText").asText());

        assertEquals("admin.custom", ashaRow.getTemplateKey());
        assertTrue(ashaRow.getIdempotencyKey().startsWith("admin.custom:" + resp.campaignId() + ":"));

        verify(adminEmailCampaignRepository).save(any(AdminEmailCampaign.class));
        verify(auditLogService)
                .recordAdminAction(eq(ADMIN_ID), eq("ADMIN_CUSTOM_EMAIL_SENT"), eq(AuditLogService.OUTCOME_ALLOWED), any());
    }

    // ------------------------------------------------------------------------------------------
    // A1 (REVIEW-R3.md ship-blocker) — defensive skip of a null/blank-email recipient at enqueue
    // ------------------------------------------------------------------------------------------

    /**
     * A1 belt-and-braces (REVIEW-R3.md): {@code UserRepository}'s audience queries now exclude
     * soft-deleted users at the SQL level (see {@code UserRepositoryDeletedUserExclusionTest}), but
     * this proves the SERVICE-level backstop independently — even if the repository were mocked (as
     * here) or a future gap let a null-email row slip through, {@code send()} must never let one
     * recipient's null email fail {@code EmailOutbox.to_email VARCHAR(255) NOT NULL} for the whole
     * batch. Falsification: removing the {@code recipient.getEmail() == null || .isBlank()} guard
     * from {@code send()}'s enqueue loop turns this red two ways — {@code saved.size()} comes back
     * {@code 2} instead of {@code 1} (the null-email row is enqueued), and the row's {@code
     * toEmail} is null, which is exactly what used to blow up {@code flush()} against a real DB.
     */
    @Test
    @DisplayName(
            "A1: send() skips a recipient with a null/blank email at enqueue instead of trusting the"
                    + " audience query alone, and never fails the rest of the batch")
    void sendSkipsRecipientWithNullEmailDefensively() {
        stubAdmin();
        User goodUser = creator("u-good", "good@example.com", "Good", null);
        // A real soft-deleted User, via the entity's own softDelete() -- email null, status
        // untouched (see that method's javadoc). Stubbing the repository to still return this row
        // stands in for "a future predicate gap let a soft-deleted user reach findForCustomEmail
        // Audience anyway" -- exactly the belt-and-braces scenario this defensive skip guards.
        User softDeletedUser = creator("u-deleted", "deleted@example.com", "Deleted", null);
        softDeletedUser.softDelete();

        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(2L);
        when(userRepository.findForCustomEmailAudience(eq(null), eq(false), eq(null), any(Limit.class)))
                .thenReturn(List.of(goodUser, softDeletedUser));
        when(emailPreferenceRepository.findUnsubscribedUserIds(anyCollection(), eq("admin.custom")))
                .thenReturn(Set.of());

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 2L);

        SendResponse resp = service(5000, 10).send(PRINCIPAL, req);

        assertEquals(1, resp.queued(), "only the recipient with a real email must be queued");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailOutboxRepository).saveAll(captor.capture());
        List<EmailOutbox> saved = captor.getValue();
        assertEquals(1, saved.size(), "the null-email recipient must never reach the outbox: " + saved);
        assertEquals("u-good", saved.get(0).getUserId());
        assertNotEquals(null, saved.get(0).getToEmail());

        // The send must still SUCCEED (no exception, campaign row still written) -- a bad recipient
        // must cost only itself, never the whole batch.
        verify(adminEmailCampaignRepository).save(any(AdminEmailCampaign.class));
        verify(auditLogService)
                .recordAdminAction(eq(ADMIN_ID), eq("ADMIN_CUSTOM_EMAIL_SENT"), eq(AuditLogService.OUTCOME_ALLOWED), any());
    }

    // ------------------------------------------------------------------------------------------
    // C1 (REVIEW-R1.md) — retry/double-click never mails a recipient twice
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "C1: two send() calls with byte-identical content produce the SAME campaignId/idempotencyKey"
                    + " (a retry collapses, it does not mint a fresh key set)")
    void sendProducesDeterministicCampaignIdForIdenticalRequests() {
        stubAdmin();
        User asha = creator("u-asha", "asha@example.com", "Asha", null);

        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(1L);
        when(userRepository.findForCustomEmailAudience(eq(null), eq(false), eq(null), any(Limit.class)))
                .thenReturn(List.of(asha));
        when(emailPreferenceRepository.findUnsubscribedUserIds(anyCollection(), eq("admin.custom")))
                .thenReturn(Set.of());
        when(adminEmailCampaignRepository.findById(anyString())).thenReturn(Optional.empty());

        SendRequest req =
                new SendRequest("Hi {{first_name}}", "Body for {{first_name}}.", null, null, allAudience(), 1L);

        // Two INDEPENDENT AdminCustomEmailService instances (as two concurrent request threads
        // would each get their own service bean invocation) building the SAME logical send.
        SendResponse first = service(5000, 10).send(PRINCIPAL, req);
        SendResponse second = service(5000, 10).send(PRINCIPAL, req);

        assertEquals(
                first.campaignId(),
                second.campaignId(),
                "same admin+subject+body+audience+confirmRecipientCount must hash to the same campaignId");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailOutboxRepository, times(2)).saveAll(captor.capture());
        String firstKey = captor.getAllValues().get(0).get(0).getIdempotencyKey();
        String secondKey = captor.getAllValues().get(1).get(0).getIdempotencyKey();
        assertEquals(
                firstKey,
                secondKey,
                "idempotencyKey must be identical across the retry so the outbox's"
                        + " UNIQUE(idempotency_key) actually collapses the second attempt");
    }

    @Test
    @DisplayName(
            "C1: send() short-circuits to the ORIGINAL result (no new outbox rows, no new campaign"
                    + " row) when campaignId already exists — the sequential retry / double-click case")
    void sendIsIdempotentOnRetryAfterFirstCommitted() {
        stubAdmin();
        User asha = creator("u-asha", "asha@example.com", "Asha", null);

        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(1L);
        when(userRepository.findForCustomEmailAudience(eq(null), eq(false), eq(null), any(Limit.class)))
                .thenReturn(List.of(asha));
        when(emailPreferenceRepository.findUnsubscribedUserIds(anyCollection(), eq("admin.custom")))
                .thenReturn(Set.of());

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 1L);

        // First call: no prior campaign exists yet.
        when(adminEmailCampaignRepository.findById(anyString())).thenReturn(Optional.empty());
        SendResponse first = service(5000, 10).send(PRINCIPAL, req);
        verify(emailOutboxRepository, times(1)).saveAll(any());
        verify(adminEmailCampaignRepository, times(1)).save(any());
        assertFalse(first.replay(), "the first, originating send must not be marked as a replay");

        // Retry: simulate the campaign row now existing (as it would after the first call's
        // transaction committed) under the SAME deterministic id. Deliberately uses INCONSISTENT
        // recipientCount/skippedUnsubscribed/queuedCount (B5, REVIEW-R2.md): if replaySendResponse
        // ever regressed to reconstructing "queued" as recipientCount - skippedUnsubscribed
        // instead of reading the stored column, it would return 1 - 0 = 1 here, not 5 — so this
        // also falsifies B5's fix, not just the idempotency short-circuit.
        AdminEmailCampaign committed =
                AdminEmailCampaign.builder()
                        .id(first.campaignId())
                        .adminUserId(ADMIN_ID)
                        .subject("Hi {{first_name}}")
                        .bodyText("Body text.")
                        .audienceUserType("ALL")
                        .audienceOnlyVerified(false)
                        .recipientCount(1)
                        .skippedUnsubscribed(0)
                        .queuedCount(5)
                        .build();
        when(adminEmailCampaignRepository.findById(first.campaignId())).thenReturn(Optional.of(committed));

        SendResponse retry = service(5000, 10).send(PRINCIPAL, req);

        assertEquals(first.campaignId(), retry.campaignId());
        assertEquals(
                5,
                retry.queued(),
                "replay must read the STORED queuedCount column, not reconstruct"
                        + " recipientCount - skippedUnsubscribed (B5)");
        assertEquals(0, retry.skippedUnsubscribed());
        assertTrue(retry.replay(), "a short-circuited retry must be marked replay=true (doc defect #2)");

        // Still only ONE saveAll/save from the whole test — the retry enqueued nothing new.
        verify(emailOutboxRepository, times(1)).saveAll(any());
        verify(adminEmailCampaignRepository, times(1)).save(any());
    }

    @Test
    @DisplayName(
            "doc defect #1 (REVIEW-R2.md): a double-click of an ALREADY-COMMITTED send never even"
                    + " reaches the rate limit or the send lock — the short-circuit really is"
                    + " \"cheap\", not just documented as such")
    void sendDoubleClickReplayNeverHitsRateLimitOrLock() {
        stubAdmin();
        AdminEmailCampaign committed =
                AdminEmailCampaign.builder()
                        .id("cc-existing-campaign")
                        .adminUserId(ADMIN_ID)
                        .subject("Hi {{first_name}}")
                        .bodyText("Body text.")
                        .audienceUserType("ALL")
                        .audienceOnlyVerified(false)
                        .recipientCount(3)
                        .skippedUnsubscribed(1)
                        .queuedCount(2)
                        .build();
        // Match ANY campaignId -- this test only cares that the short-circuit fires before
        // anything downstream is touched, not the exact hash.
        when(adminEmailCampaignRepository.findById(anyString())).thenReturn(Optional.of(committed));
        // If the short-circuit did NOT fire before enforceRateLimit() (reverting doc defect #1),
        // this stub would make the send throw 429 RATE_LIMITED instead of returning cleanly --
        // that is the falsification. Deliberately never expected to be consulted when the fix is
        // in place (lenient, since a strict-stub failure here would be the WRONG kind of red).
        lenient()
                .when(adminEmailCampaignRepository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.of(committed));

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 2L);

        SendResponse resp = service(5000, 10).send(PRINCIPAL, req);

        assertEquals("cc-existing-campaign", resp.campaignId());
        assertEquals(2, resp.queued());
        assertTrue(resp.replay());

        verify(adminEmailCampaignRepository, never()).findTopByOrderByCreatedAtDesc();
        verify(adminEmailSendLockRepository, never()).lockForUpdate(anyString());
        verify(userRepository, never()).countForCustomEmailAudience(any(), anyBoolean(), any());
        verify(emailOutboxRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------------------------------
    // Control #3 — 409 RECIPIENT_COUNT_CHANGED
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("control #3: send() 409s with RECIPIENT_COUNT_CHANGED when the recomputed count doesn't match confirmRecipientCount")
    void sendRejectsRecipientCountMismatch() {
        stubAdmin();
        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(7L);

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 5L);

        ApiException ex = assertThrows(ApiException.class, () -> service(5000, 10).send(PRINCIPAL, req));
        assertEquals("RECIPIENT_COUNT_CHANGED", ex.getCode());
        assertEquals(409, ex.getStatus().value());

        verify(emailOutboxRepository, never()).saveAll(any());
        verify(adminEmailCampaignRepository, never()).save(any());
    }

    /**
     * A6 (REVIEW-R3.md): the OLD message interpolated the live recomputed count ("confirmed 0, now
     * 4312"), which let two requests defeat control #3 entirely — send with {@code
     * confirmRecipientCount=0} (guaranteed to mismatch), read the real count straight out of the
     * error body, then resend with that number. The mismatch must still be reported, just never
     * with either number in it. Falsification: reverting the message to interpolate {@code
     * recipientCount}/{@code confirmRecipientCount} turns this red — the message contains a digit.
     */
    @Test
    @DisplayName("A6: the RECIPIENT_COUNT_CHANGED message never leaks the confirmed or recomputed count")
    void recipientCountMismatchMessageNeverLeaksEitherCount() {
        stubAdmin();
        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(4312L);

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 0L);

        ApiException ex = assertThrows(ApiException.class, () -> service(5000, 10).send(PRINCIPAL, req));

        assertFalse(
                ex.getMessage().matches(".*\\d.*"),
                "the 409 message must not contain any digit (it would be the live recipient count"
                        + " an attacker is trying to read out): "
                        + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------
    // Control #5 — unsubscribe enforcement
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("control #5: send() skips an unsubscribed recipient, counts it, and never enqueues an outbox row for them")
    void sendSkipsUnsubscribedRecipients() {
        stubAdmin();
        User subscribed = creator("u-sub", "sub@example.com", "Sub", null);
        User unsubscribed = creator("u-unsub", "unsub@example.com", "Unsub", null);

        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(2L);
        when(userRepository.findForCustomEmailAudience(eq(null), eq(false), eq(null), any(Limit.class)))
                .thenReturn(List.of(subscribed, unsubscribed));
        when(emailPreferenceRepository.findUnsubscribedUserIds(anyCollection(), eq("admin.custom")))
                .thenReturn(Set.of("u-unsub"));

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 2L);

        SendResponse resp = service(5000, 10).send(PRINCIPAL, req);

        assertEquals(1, resp.queued());
        assertEquals(1, resp.skippedUnsubscribed());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailOutboxRepository).saveAll(captor.capture());
        List<EmailOutbox> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals("u-sub", saved.get(0).getUserId());

        verify(adminEmailCampaignRepository)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                c ->
                                        c.getRecipientCount() == 2
                                                && c.getSkippedUnsubscribed() == 1
                                                && c.getQueuedCount() == 1));
    }

    // ------------------------------------------------------------------------------------------
    // Control #2 — hard recipient cap, refused not truncated
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("control #2: send() refuses (400 RECIPIENT_CAP_EXCEEDED) an audience over the configured cap, without touching the outbox")
    void sendRefusesOverCap() {
        stubAdmin();
        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(2L);

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 2L);

        ApiException ex = assertThrows(ApiException.class, () -> service(1, 10).send(PRINCIPAL, req));
        assertEquals("RECIPIENT_CAP_EXCEEDED", ex.getCode());
        assertEquals(400, ex.getStatus().value());

        verify(userRepository, never()).findForCustomEmailAudience(any(), anyBoolean(), any(), any());
        verify(emailOutboxRepository, never()).saveAll(any());
        verify(adminEmailCampaignRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------------------------
    // A2 (REVIEW-R3.md ship-blocker) — send() runs at READ_COMMITTED, not the MySQL default
    // ------------------------------------------------------------------------------------------

    /**
     * A2 (REVIEW-R3.md ship-blocker): {@code send()} must run at {@code READ_COMMITTED}, not
     * MySQL/InnoDB's default REPEATABLE READ — see {@code send()}'s and {@link
     * AdminEmailSendLock}'s javadoc for why REPEATABLE READ lets {@link
     * #enforceRateLimit}'s post-lock plain read still see a stale, pre-lock snapshot even though
     * the {@code SELECT ... FOR UPDATE} lock genuinely serialized the two transactions in time.
     *
     * <p><b>This is deliberately NOT a behavioral test, and does not pretend to be one</b> — per
     * REVIEW-R3.md's testing discipline ("where you cannot test real behaviour, say so plainly in
     * the test's javadoc rather than letting a string assertion imply behavioural coverage"). A
     * real proof needs two genuinely concurrent transactions against a real MySQL/InnoDB instance,
     * with one committing a row between the other's lock-acquire and its post-lock plain read — the
     * same shape as {@code AdminEmailSendLockRepositoryConcurrencyTest}, but asserting the SNAPSHOT
     * behavior of a later plain SELECT, not just that {@code FOR UPDATE} blocks. That test cannot
     * be built here: this module has no Testcontainers/real-MySQL harness (offline, no Docker —
     * same constraint documented in {@code BrandAiCreditRepositoryQueryTest}), and H2 is not a
     * substitute — H2's isolation-level implementation does not reproduce MySQL/InnoDB's "snapshot
     * pinned at the transaction's first plain SELECT" semantics, which is the EXACT mechanism this
     * fix depends on; a green H2 test here would prove nothing about the real bug and could hide a
     * regression exactly the way REVIEW-R3.md warned the existing H2 concurrency test already does
     * for B1. So this only pins that the annotation Spring's proxy actually reads is what it must
     * be — a reflection check, same technique as {@code EmailOutboxRepositoryQueryTest}'s JPQL pin.
     *
     * <p>Falsification: reverting {@code send()} to a bare {@code @Transactional} (no isolation
     * override, MySQL/InnoDB's default REPEATABLE READ) turns this red — {@code isolation()} comes
     * back {@code Isolation.DEFAULT}, not {@code Isolation.READ_COMMITTED}. Verified directly.
     */
    @Test
    @DisplayName(
            "A2 (annotation pin, NOT behavioral — see javadoc): send() is annotated"
                    + " @Transactional(isolation = READ_COMMITTED)")
    void sendRunsAtReadCommittedIsolation() throws NoSuchMethodException {
        java.lang.reflect.Method sendMethod =
                AdminCustomEmailService.class.getMethod(
                        "send",
                        com.influora.security.AuthPrincipal.class,
                        com.influora.web.dto.admin.AdminCustomEmailDtos.SendRequest.class);
        org.springframework.transaction.annotation.Transactional txn =
                sendMethod.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertTrue(txn != null, "send() must carry a @Transactional annotation");
        assertEquals(
                org.springframework.transaction.annotation.Isolation.READ_COMMITTED,
                txn.isolation(),
                "send() must override isolation to READ_COMMITTED -- MySQL/InnoDB's default"
                        + " REPEATABLE READ pins the transaction's snapshot before the send lock is"
                        + " even acquired (A2, REVIEW-R3.md ship-blocker)");
    }

    // ------------------------------------------------------------------------------------------
    // Control #1 — persisted, cross-instance rate limit + real serialization (B1)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("control #1: send() refuses (429 RATE_LIMITED) when the last confirmed send was inside min-interval-minutes")
    void sendRateLimited() {
        stubAdmin();
        AdminEmailCampaign recent =
                AdminEmailCampaign.builder()
                        .id("prev-campaign")
                        .adminUserId("some-other-admin")
                        .subject("Earlier send")
                        .bodyText("Body")
                        .audienceUserType("ALL")
                        .audienceOnlyVerified(false)
                        .recipientCount(10)
                        .skippedUnsubscribed(0)
                        .queuedCount(10)
                        .build();
        when(adminEmailCampaignRepository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.of(recent));

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 2L);

        ApiException ex = assertThrows(ApiException.class, () -> service(5000, 10).send(PRINCIPAL, req));
        assertEquals("RATE_LIMITED", ex.getCode());
        assertEquals(429, ex.getStatus().value());

        // Rate limit is checked BEFORE the audience is even recomputed — a throttled admin never
        // triggers the (relatively expensive) audience query at all.
        verify(userRepository, never()).countForCustomEmailAudience(any(), anyBoolean(), any());
        verify(emailOutboxRepository, never()).saveAll(any());
        // B1: the rate limit is only ever checked AFTER the send lock was taken.
        verify(adminEmailSendLockRepository).lockForUpdate(AdminEmailSendLock.SINGLETON_ID);
    }

    @Test
    @DisplayName(
            "B1 (REVIEW-R2.md ship-blocker): send() takes the singleton send lock BEFORE"
                    + " enforceRateLimit() runs, for a genuinely new (non-replay) send")
    void sendTakesLockBeforeRateLimitCheck() {
        stubAdmin();
        User asha = creator("u-asha", "asha@example.com", "Asha", null);
        when(adminEmailCampaignRepository.findById(anyString())).thenReturn(Optional.empty());
        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(1L);
        when(userRepository.findForCustomEmailAudience(eq(null), eq(false), eq(null), any(Limit.class)))
                .thenReturn(List.of(asha));
        when(emailPreferenceRepository.findUnsubscribedUserIds(anyCollection(), eq("admin.custom")))
                .thenReturn(Set.of());

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 1L);
        service(5000, 10).send(PRINCIPAL, req);

        verify(adminEmailSendLockRepository).lockForUpdate(AdminEmailSendLock.SINGLETON_ID);
        // Real serialization behavior (blocking a concurrent holder) is proven by
        // AdminEmailSendLockRepositoryConcurrencyTest, not here — a mock cannot demonstrate that.
    }

    @Test
    @DisplayName(
            "B1: send() surfaces a bounded lock-wait timeout as 429 SEND_IN_PROGRESS, not an"
                    + " unhandled exception")
    void sendSurfacesLockTimeoutAsSendInProgress() {
        stubAdmin();
        // Override the lenient stubAdmin() stub for this one test: simulate the lock genuinely
        // being held by another in-flight send when this one's bounded wait expires.
        when(adminEmailSendLockRepository.lockForUpdate(AdminEmailSendLock.SINGLETON_ID))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock wait timeout"));

        SendRequest req = new SendRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), 1L);

        ApiException ex = assertThrows(ApiException.class, () -> service(5000, 10).send(PRINCIPAL, req));
        assertEquals("SEND_IN_PROGRESS", ex.getCode());
        assertEquals(429, ex.getStatus().value());
        verify(emailOutboxRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------------------------------
    // Unknown-token rejection — both entry points, 400
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("send() 400s with UNKNOWN_TOKEN on an unrecognized {{token}}, before any DB write")
    void sendRejectsUnknownToken() {
        SendRequest req =
                new SendRequest(
                        "Hi {{first_name}}", "Use code {{discount_code}} today!", null, null, allAudience(), 1L);

        ApiException ex = assertThrows(ApiException.class, () -> service(5000, 10).send(PRINCIPAL, req));
        assertEquals("UNKNOWN_TOKEN", ex.getCode());
        assertEquals(400, ex.getStatus().value());

        verify(userRepository, never()).countForCustomEmailAudience(any(), anyBoolean(), any());
        verify(emailOutboxRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("preview() 400s with UNKNOWN_TOKEN on an unrecognized {{token}} in the subject")
    void previewRejectsUnknownToken() {
        PreviewRequest req =
                new PreviewRequest("Hi {{nickname}}", "Body text.", null, null, allAudience(), null);

        ApiException ex = assertThrows(ApiException.class, () -> service(5000, 10).preview(PRINCIPAL, req));
        assertEquals("UNKNOWN_TOKEN", ex.getCode());
        assertEquals(400, ex.getStatus().value());
    }

    /**
     * B4 (REVIEW-R2.md): {@code {{first-name}}}, {@code {{ first_name }}}, and {@code
     * {{first.name}}} all matched neither the old {@code \{\{(\w+)\}\}} validator nor the
     * substitutor, so they used to ship as literal text to every recipient. Falsification:
     * reverting {@code TOKEN_PATTERN} to {@code \{\{(\w+)\}\}} turns this red — {@code
     * assertThrows} fails because {@code send()} returns normally instead of throwing (the
     * malformed token is silently invisible to validation again). Verified directly.
     */
    @ParameterizedTest(name = "malformed token shape [{0}] is rejected, not shipped literally")
    @ValueSource(
            strings = {
                "Use {{first-name}} today!",
                "Use {{ first_name }} today!",
                "Use {{first.name}} today!"
            })
    @DisplayName("B4: send() rejects malformed {{token}} shapes that the narrow \\w+ pattern used to miss")
    void sendRejectsMalformedTokenShapes(String bodyWithMalformedToken) {
        SendRequest req =
                new SendRequest("Hi {{first_name}}", bodyWithMalformedToken, null, null, allAudience(), 1L);

        ApiException ex = assertThrows(ApiException.class, () -> service(5000, 10).send(PRINCIPAL, req));
        assertEquals("UNKNOWN_TOKEN", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(emailOutboxRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------------------------------------
    // Preview: never writes to the outbox/campaign tables, but IS audited (B2)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "preview() computes recipientCount/capped/sample, never touches the outbox or"
                    + " campaign tables, but DOES write an audit entry (B2)")
    void previewComputesCountAndSampleAndIsAudited() {
        stubAdmin();
        User sample = creator("u-sample", "sample@example.com", "Sam", null);

        when(userRepository.countForCustomEmailAudience(UserType.CREATOR, true, null)).thenReturn(9000L);
        when(userRepository.findForCustomEmailAudience(eq(UserType.CREATOR), eq(true), eq(null), any(Limit.class)))
                .thenReturn(List.of(sample));
        when(msg91EmailClient.renderPreview(eq("admin.custom"), any()))
                .thenReturn(new EmailPreviewResult("Hi Sam", "<html>rendered</html>", "plain"));

        PreviewRequest req =
                new PreviewRequest(
                        "Hi {{first_name}}",
                        "Body text.",
                        null,
                        null,
                        new AudienceDto("CREATOR", true, null),
                        null);

        PreviewResponse resp = service(5000, 10).preview(PRINCIPAL, req);

        assertEquals(9000L, resp.recipientCount());
        assertTrue(resp.capped());
        assertEquals(5000, resp.cap());
        assertEquals("sample@example.com", resp.sampleRecipientEmail());
        assertEquals("Hi Sam", resp.subject());
        assertEquals("<html>rendered</html>", resp.html());

        verify(emailOutboxRepository, never()).saveAll(any());
        verify(emailOutboxRepository, never()).save(any());
        verify(adminEmailCampaignRepository, never()).save(any());
        // B2 fix: round 2 made NO recordAdminAction call from preview() at all. This must now
        // fire, every time.
        verify(auditLogService)
                .recordAdminAction(
                        eq(ADMIN_ID), eq("ADMIN_CUSTOM_EMAIL_PREVIEWED"), eq(AuditLogService.OUTCOME_ALLOWED), any());
    }

    /**
     * B2(a) (REVIEW-R2.md ship-blocker): round 2 scoped the caller-supplied {@code sampleUserId}
     * lookup to {@code matchesAudience}, but {@code audience} is itself caller-chosen — {@code
     * {userType:"ALL",onlyVerified:false,registeredWithinDays:null}} reduces that predicate to
     * just {@code status == ACTIVE}, so ANY existing active userId still resolved and had its
     * REAL email returned in {@code sampleRecipientEmail}: an unaudited, platform-wide
     * email-address oracle. Fixed by never resolving the caller-supplied id at all.
     *
     * <p>Falsification: reverting {@code resolveSample} to look the caller's {@code sampleUserId}
     * up via {@code userRepository.findById} turns this red — {@code findById} gets invoked.
     * Verified directly.
     */
    @Test
    @DisplayName(
            "B2(a): preview() never resolves an arbitrary caller-supplied sampleUserId via"
                    + " findById — the sample is always server-selected from the audience query")
    void previewNeverResolvesArbitraryCallerSuppliedSampleUserId() {
        stubAdmin();
        User serverSample = creator("u-real-first", "real-first@example.com", "Real", null);

        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(50L);
        when(userRepository.findForCustomEmailAudience(eq(null), eq(false), eq(null), any(Limit.class)))
                .thenReturn(List.of(serverSample));
        when(msg91EmailClient.renderPreview(eq("admin.custom"), any()))
                .thenReturn(new EmailPreviewResult("Hi Real", "<html>rendered</html>", "plain"));

        // A completely different, "victim" user id the caller supplies — must never be looked up.
        PreviewRequest req =
                new PreviewRequest(
                        "Hi {{first_name}}", "Body text.", null, null, allAudience(), "u-arbitrary-victim");

        PreviewResponse resp = service(5000, 10).preview(PRINCIPAL, req);

        assertEquals(
                "real-first@example.com",
                resp.sampleRecipientEmail(),
                "the sample must be the server-side audience query's own first row, not the"
                        + " caller-supplied id");
        verify(userRepository, never()).findById(anyString());
    }

    // ------------------------------------------------------------------------------------------
    // A5 (REVIEW-R4.md) — preview() rate limit and audit descriptor completeness
    // ------------------------------------------------------------------------------------------

    /**
     * A5 (REVIEW-R4.md): {@code preview} previously had no rate limit at all — sweeping {@code
     * registeredWithinDays} one day at a time (crossed with {@code userType}/{@code onlyVerified})
     * hands back one real, unredacted address per call with nothing slowing a scripted sweep down.
     * Constructs the service directly with a real, generous {@code previewMinIntervalMillis}
     * (the {@code service(cap, minInterval)} helper used everywhere else in this class disables
     * this limit at 0ms, deliberately, so it cannot interfere with any other test) and calls {@code
     * preview()} twice back-to-back — real elapsed wall-clock between the two calls is
     * microseconds, so the second call must be rejected.
     *
     * <p>Falsification: removing the {@code enforcePreviewRateLimit()} call from {@code preview()}
     * turns this red — the second call returns normally instead of throwing. Verified directly.
     */
    @Test
    @DisplayName("A5: preview() 429s a second call that arrives before the rate-limit interval elapses")
    void previewRateLimitsRapidSecondCall() {
        stubAdmin();
        User sample = creator("u-sample", "sample@example.com", "Sam", null);
        when(userRepository.countForCustomEmailAudience(null, false, null)).thenReturn(10L);
        when(userRepository.findForCustomEmailAudience(eq(null), eq(false), eq(null), any(Limit.class)))
                .thenReturn(List.of(sample));
        when(msg91EmailClient.renderPreview(eq("admin.custom"), any()))
                .thenReturn(new EmailPreviewResult("Hi Sam", "<html>rendered</html>", "plain"));

        PreviewRequest req =
                new PreviewRequest("Hi {{first_name}}", "Body text.", null, null, allAudience(), null);
        // 60s window -- comfortably longer than this test can possibly take to run twice.
        AdminCustomEmailService throttled = service(5000, 10, 60_000L);

        throttled.preview(PRINCIPAL, req); // first call succeeds, claims the window

        ApiException ex = assertThrows(ApiException.class, () -> throttled.preview(PRINCIPAL, req));
        assertEquals("PREVIEW_RATE_LIMITED", ex.getCode());
        assertEquals(429, ex.getStatus().value());
    }

    /**
     * A5 (REVIEW-R4.md): round 2/3's preview audit entry recorded only {@code audienceUserType} /
     * {@code recipientCount} / {@code capped} / {@code hasSample} — {@code onlyVerified} and {@code
     * registeredWithinDays} (the two axes that actually determine WHICH address {@link
     * AdminCustomEmailService#preview} hands back) were missing, so the audit trail could not
     * reconstruct which audience a given preview call had actually swept.
     *
     * <p>Falsification: removing the two new {@code Map.of()} entries from {@code preview()} turns
     * this red — the captured detail map no longer contains {@code audienceOnlyVerified}/{@code
     * audienceRegisteredWithinDays}. Verified directly.
     */
    @Test
    @DisplayName(
            "A5: preview() audit entry records audienceOnlyVerified and audienceRegisteredWithinDays")
    void previewAuditRecordsFullAudienceDescriptor() {
        stubAdmin();
        User sample = creator("u-sample", "sample@example.com", "Sam", null);
        when(userRepository.countForCustomEmailAudience(eq(UserType.CREATOR), eq(true), any(Instant.class)))
                .thenReturn(3L);
        when(userRepository.findForCustomEmailAudience(
                        eq(UserType.CREATOR), eq(true), any(Instant.class), any(Limit.class)))
                .thenReturn(List.of(sample));
        when(msg91EmailClient.renderPreview(eq("admin.custom"), any()))
                .thenReturn(new EmailPreviewResult("Hi Sam", "<html>rendered</html>", "plain"));

        PreviewRequest req =
                new PreviewRequest(
                        "Hi {{first_name}}",
                        "Body text.",
                        null,
                        null,
                        new AudienceDto("CREATOR", true, 7),
                        null);

        service(5000, 10).preview(PRINCIPAL, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService)
                .recordAdminAction(
                        eq(ADMIN_ID), eq("ADMIN_CUSTOM_EMAIL_PREVIEWED"), eq(AuditLogService.OUTCOME_ALLOWED), captor.capture());
        Map<String, Object> detail = captor.getValue();
        assertEquals(true, detail.get("audienceOnlyVerified"));
        assertEquals("7", detail.get("audienceRegisteredWithinDays"));
    }

    // ------------------------------------------------------------------------------------------
    // B3 (REVIEW-R2.md ship-blocker) — cancel a queued send
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "B3: cancel() marks every PENDING admin.custom outbox row for this campaign terminal,"
                    + " leaves other rows alone, and is audited")
    void cancelMarksPendingRowsTerminalAndAudits() {
        stubAdmin();
        String campaignId = "cc-campaign-to-cancel";
        AdminEmailCampaign campaign =
                AdminEmailCampaign.builder()
                        .id(campaignId)
                        .adminUserId(ADMIN_ID)
                        .subject("Subject")
                        .bodyText("Body")
                        .audienceUserType("ALL")
                        .audienceOnlyVerified(false)
                        .recipientCount(3)
                        .skippedUnsubscribed(0)
                        .queuedCount(3)
                        .build();
        when(adminEmailCampaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));

        EmailOutbox pendingOne =
                EmailOutbox.builder()
                        .id("out-1")
                        .userId("u-1")
                        .toEmail("u1@example.com")
                        .templateKey("admin.custom")
                        .templateData("{}")
                        .idempotencyKey("admin.custom:" + campaignId + ":u-1")
                        .build();
        EmailOutbox pendingTwo =
                EmailOutbox.builder()
                        .id("out-2")
                        .userId("u-2")
                        .toEmail("u2@example.com")
                        .templateKey("admin.custom")
                        .templateData("{}")
                        .idempotencyKey("admin.custom:" + campaignId + ":u-2")
                        .build();
        when(emailOutboxRepository.findByTemplateKeyAndStatusAndIdempotencyKeyStartingWith(
                        "admin.custom", EmailOutboxStatus.PENDING, "admin.custom:" + campaignId + ":"))
                .thenReturn(List.of(pendingOne, pendingTwo));

        CancelResponse resp = service(5000, 10).cancel(PRINCIPAL, campaignId);

        assertEquals(campaignId, resp.campaignId());
        assertEquals(2, resp.cancelled());
        assertEquals(EmailOutboxStatus.FAILED, pendingOne.getStatus());
        assertEquals(EmailOutboxStatus.FAILED, pendingTwo.getStatus());
        assertFalse(pendingOne.canRetry(), "a cancelled row must never be picked up again by EmailWorker");
        assertFalse(pendingTwo.canRetry());

        verify(emailOutboxRepository).saveAll(List.of(pendingOne, pendingTwo));
        verify(auditLogService)
                .recordAdminAction(
                        eq(ADMIN_ID), eq("ADMIN_CUSTOM_EMAIL_CANCELLED"), eq(AuditLogService.OUTCOME_ALLOWED), any());
    }

    @Test
    @DisplayName("B3: cancel() 404s with CAMPAIGN_NOT_FOUND for an unknown campaignId")
    void cancelUnknownCampaignNotFound() {
        stubAdmin();
        when(adminEmailCampaignRepository.findById("does-not-exist")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service(5000, 10).cancel(PRINCIPAL, "does-not-exist"));
        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(emailOutboxRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("B3: cancel() is idempotent — nothing PENDING left returns cancelled: 0, not an error")
    void cancelWithNothingPendingReturnsZero() {
        stubAdmin();
        String campaignId = "cc-already-drained";
        AdminEmailCampaign campaign =
                AdminEmailCampaign.builder()
                        .id(campaignId)
                        .adminUserId(ADMIN_ID)
                        .subject("Subject")
                        .bodyText("Body")
                        .audienceUserType("ALL")
                        .audienceOnlyVerified(false)
                        .recipientCount(1)
                        .skippedUnsubscribed(0)
                        .queuedCount(1)
                        .build();
        when(adminEmailCampaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(emailOutboxRepository.findByTemplateKeyAndStatusAndIdempotencyKeyStartingWith(
                        "admin.custom", EmailOutboxStatus.PENDING, "admin.custom:" + campaignId + ":"))
                .thenReturn(List.of());

        CancelResponse resp = service(5000, 10).cancel(PRINCIPAL, campaignId);

        assertEquals(0, resp.cancelled());
    }
}
