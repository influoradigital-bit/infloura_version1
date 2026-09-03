package com.influora.service.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.AdminEmailCampaign;
import com.influora.domain.entity.AdminEmailSendLock;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.entity.User;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.EmailOutboxStatus;
import com.influora.domain.enums.UserStatus;
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
import com.influora.web.dto.admin.AdminCustomEmailDtos.CancelResponse;
import com.influora.web.dto.admin.AdminCustomEmailDtos.PreviewRequest;
import com.influora.web.dto.admin.AdminCustomEmailDtos.PreviewResponse;
import com.influora.web.dto.admin.AdminCustomEmailDtos.SendRequest;
import com.influora.web.dto.admin.AdminCustomEmailDtos.SendResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin custom email send (T-ADMINMAIL-0903) — the real implementation behind {@code
 * AdminEmailController.sendBulk}'s 501, shipped instead as new endpoints per the settled contract
 * (.proof-os/tasks/T-ADMINMAIL-0903/SPEC.md). {@code sendBulk} itself is untouched.
 *
 * <p>Implements all five abuse controls the SPEC names as non-negotiable:
 *
 * <ol>
 *   <li><b>Rate limit</b> — {@link #enforceRateLimit()}, persisted via {@link
 *       AdminEmailCampaignRepository#findTopByOrderByCreatedAtDesc()} (survives a restart; applies
 *       across every admin, not per-admin) and, as of round 3 (B1 below), actually SERIALIZED
 *       rather than a bare read-then-decide.
 *   <li><b>Recipient cap</b> — {@link #recipientCap}; over the cap the send is refused (400
 *       {@code RECIPIENT_CAP_EXCEEDED}), never silently truncated.
 *   <li><b>Preview + confirm</b> — {@link #preview} never enqueues anything; {@link #send}
 *       recomputes the audience fresh and 409s ({@code RECIPIENT_COUNT_CHANGED}) unless it exactly
 *       matches the caller's {@code confirmRecipientCount} from that preview.
 *   <li><b>Audit trail</b> — one {@link AdminEmailCampaign} row per confirmed send (who, subject,
 *       audience, recipient count, when — survives a restart) plus an {@link
 *       AuditLogService#recordAdminAction} write for every send, preview, and cancel.
 *   <li><b>Unsubscribe enforcement</b> — {@link EmailPreferenceRepository#findUnsubscribedUserIds}
 *       filters the audience before any {@link EmailOutbox} row is created; {@code admin.custom}
 *       is deliberately NOT in {@code EmailTemplateRegistry.NO_UNSUBSCRIBE_FOOTER} (marketing mail
 *       must carry the footer).
 * </ol>
 *
 * <p>{@code preview}, {@code send}, and {@link #cancel} all gate {@code SUPER_ADMIN} + MFA via
 * {@link AdminContextService#requireRoleWithMfaSatisfied} — same tier as {@code
 * AdminEmailService.requireBulkSendAuthority}, appropriate given this can reach every user's inbox.
 *
 * <p>Personalization tokens ({@code {{first_name}}}/{@code {{name}}}/{@code {{email}}}) are
 * substituted per recipient HERE, at enqueue time — not later inside {@code EmailWorker}/{@code
 * Msg91EmailClient} — so each {@link EmailOutbox} row's {@code templateData} already carries that
 * recipient's own resolved subject/body. {@code EmailTemplateRegistry.renderCustom} (called at
 * actual dispatch) never sees a token, only the substituted text — see that method's javadoc.
 *
 * <p><b>Idempotency (round 2, REVIEW-R1.md C1; reordered round 3, REVIEW-R2.md doc defect #1):</b>
 * {@link #send} derives {@code campaignId} via {@link #deterministicCampaignId} from exactly what
 * makes two requests "the same logical send" (admin + subject + body + cta + audience +
 * confirmRecipientCount), so a retry — sequential or genuinely concurrent — maps onto the SAME
 * key. The {@code findById(campaignId)} short-circuit now runs FIRST, immediately after input
 * validation and BEFORE {@link #enforceRateLimit()} or the singleton lock below — so a real
 * double-click/retry of an ALREADY-COMMITTED send is genuinely caught cheaply, without ever
 * touching the rate limit or the audience recompute (round 2's javadoc claimed this ordering but
 * the code did not have it: {@code enforceRateLimit()} used to run first, so a double-click
 * inside the throttle window actually got a 429, not the documented replay). A second check runs
 * immediately after the lock is acquired, for the case where a truly concurrent identical retry
 * commits WHILE this request was waiting for the lock — see {@link #acquireSendLock()}'s javadoc
 * for why that check is then guaranteed to see it. Anything that still slips past both checks (two
 * requests that are BOTH inside the lock-free window before either acquires the lock — impossible
 * under the current single-lock design, since the lock is acquired before any DB write, but kept
 * as defense in depth) is still caught by the outbox table's {@code UNIQUE(idempotency_key)} at
 * flush time — {@code GlobalExceptionHandler.handleDataIntegrityViolation} turns that into a clean
 * 409, and the transaction rolls back atomically.
 *
 * <p><b>Serialization (round 3, B1 — REVIEW-R2.md ship-blocker):</b> the persisted rate limit used
 * to be a lock-free {@code findTopByOrderByCreatedAtDesc()} read with nothing serializing two
 * concurrent {@code send()} calls — two admins, or two app instances behind the LB, could both
 * read the same stale "last campaign" and both pass, since the row that would close the window
 * was not committed until the end of the method. {@link #acquireSendLock()} now takes a {@code
 * SELECT ... FOR UPDATE} on the {@link AdminEmailSendLock} singleton row before {@link
 * #enforceRateLimit()} runs, so at most one {@code send()} is inside the
 * rate-limit-check-through-commit critical section at a time, across every admin and every app
 * instance — see that method's and {@link AdminEmailSendLock}'s javadoc for the MySQL locking-read
 * guarantee this relies on.
 */
@Service
public class AdminCustomEmailService {

    private static final Logger log = LoggerFactory.getLogger(AdminCustomEmailService.class);

    /** Mirrors {@code EmailTemplateRegistry.ADMIN_CUSTOM_TEMPLATE_KEY} — that field is
     * package-private (com.influora.integration.msg91), this is the same literal used from
     * com.influora.service.admin. Kept in one place (here) rather than re-declared at each call
     * site below. */
    static final String TEMPLATE_KEY = "admin.custom";

    /**
     * B4 fix (REVIEW-R2.md): widened from the round-2 {@code \{\{(\w+)\}\}} to {@code
     * \{\{[^}]*\}\}} for VALIDATION/rejection ({@link #rejectUnknownTokens}/{@link
     * #rejectAnyToken}) only. The old, narrower pattern simply never matched a malformed token —
     * {@code {{first-name}}}, {@code {{ first_name }}}, {@code {{first.name}}} — so it slipped
     * past {@link #validateTokens} entirely and shipped as literal {@code {{...}}} text to every
     * recipient, exactly the shape a human typo takes. This pattern is used ONLY to detect that
     * SOMETHING token-shaped is present so it can be checked against {@link #ALLOWED_TOKENS} (an
     * exact-string match — {@code " first_name "} or {@code "first-name"} still fails that check
     * and is correctly rejected as {@code UNKNOWN_TOKEN}). {@link #substituteTokens} is
     * deliberately NOT driven by this pattern — it substitutes the three known token strings via
     * plain literal {@code String.replace}, so it is unaffected by this widening.
     */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([^}]*)\\}\\}");
    private static final Set<String> ALLOWED_TOKENS = Set.of("first_name", "name", "email");
    private static final String FALLBACK_NAME = "there";

    private final AdminContextService adminContext;
    private final UserRepository userRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final EmailPreferenceRepository emailPreferenceRepository;
    private final AdminEmailCampaignRepository adminEmailCampaignRepository;
    private final AdminEmailSendLockRepository adminEmailSendLockRepository;
    private final Msg91EmailClient msg91EmailClient;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final int recipientCap;
    private final Duration minInterval;

    /** A5 fix (round 5, REVIEW-R4.md): minimum gap enforced between {@link #preview} calls — see
     * {@link #enforcePreviewRateLimit()}'s javadoc. */
    private final Duration previewMinInterval;

    /** A5 fix: in-memory, not persisted like {@link #minInterval}'s check — see {@link
     * #enforcePreviewRateLimit()}'s javadoc for why that tradeoff is fine here. {@link
     * AtomicReference} + compare-and-set (not a plain field) so two genuinely concurrent preview
     * calls can't both read "no previous call yet" and both pass. */
    private final AtomicReference<Instant> lastPreviewAt = new AtomicReference<>();

    public AdminCustomEmailService(
            AdminContextService adminContext,
            UserRepository userRepository,
            EmailOutboxRepository emailOutboxRepository,
            EmailPreferenceRepository emailPreferenceRepository,
            AdminEmailCampaignRepository adminEmailCampaignRepository,
            AdminEmailSendLockRepository adminEmailSendLockRepository,
            Msg91EmailClient msg91EmailClient,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Value("${influora.admin-custom-email.recipient-cap:5000}") int recipientCap,
            @Value("${influora.admin-custom-email.min-interval-minutes:10}") int minIntervalMinutes,
            @Value("${influora.admin-custom-email.preview-min-interval-millis:2000}")
                    long previewMinIntervalMillis) {
        this.adminContext = adminContext;
        this.userRepository = userRepository;
        this.emailOutboxRepository = emailOutboxRepository;
        this.emailPreferenceRepository = emailPreferenceRepository;
        this.adminEmailCampaignRepository = adminEmailCampaignRepository;
        this.adminEmailSendLockRepository = adminEmailSendLockRepository;
        this.msg91EmailClient = msg91EmailClient;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.recipientCap = recipientCap;
        this.minInterval = Duration.ofMinutes(minIntervalMinutes);
        this.previewMinInterval = Duration.ofMillis(previewMinIntervalMillis);
    }

    /**
     * Dry-run only — never touches {@code email_outbox} or {@code admin_email_campaigns}. Renders
     * the exact HTML a real send would produce, sampled from the first user the audience query
     * itself returns (see {@link #resolveSample}'s javadoc for why the caller-supplied {@code
     * sampleUserId} is no longer trusted — B2, REVIEW-R2.md round 3 ship-blocker), and — also per
     * B2 — is now audited: every preview call writes an {@link AuditLogService#recordAdminAction}
     * entry (round 2 made none at all, leaving no trace of who previewed what).
     *
     * <p><b>A5 fix (round 5, REVIEW-R4.md):</b> {@link #enforcePreviewRateLimit()} now throttles
     * this endpoint — see that method's javadoc for why a real-address oracle needed one at all.
     */
    @Transactional(readOnly = true)
    public PreviewResponse preview(AuthPrincipal principal, PreviewRequest req) {
        AdminUser admin = adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);
        enforcePreviewRateLimit();

        validateTokens(req.subject(), req.bodyText());
        validateCta(req.ctaLabel(), req.ctaUrl());
        UserType userType = resolveUserType(req.audience().userType());
        Instant registeredAfter = resolveRegisteredAfter(req.audience().registeredWithinDays());

        long recipientCount =
                userRepository.countForCustomEmailAudience(
                        userType, req.audience().onlyVerified(), registeredAfter);

        User sample = resolveSample(userType, req.audience().onlyVerified(), registeredAfter);

        String personalizedSubject = sample != null ? substituteTokens(req.subject(), sample) : substituteTokens(req.subject(), null);
        String personalizedBody = sample != null ? substituteTokens(req.bodyText(), sample) : substituteTokens(req.bodyText(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subject", personalizedSubject);
        data.put("bodyText", personalizedBody);
        data.put("ctaLabel", blankToNull(req.ctaLabel()));
        data.put("ctaUrl", blankToNull(req.ctaUrl()));

        // B2 fix: no sampleUserId passed through — renderPreview never mints a real unsubscribe
        // token (see its javadoc), so there is nothing per-user left to pass here.
        EmailPreviewResult rendered = msg91EmailClient.renderPreview(TEMPLATE_KEY, data);

        boolean capped = recipientCount > recipientCap;

        // B2 fix: audit trail for preview (round 2 made no recordAdminAction call at all). Shapes/
        // counts only, per AuditLogService's redaction discipline — never the admin-authored
        // subject/body text, never the resolved sample's email address.
        //
        // A5 fix (round 5, REVIEW-R4.md): "audienceOnlyVerified"/"audienceRegisteredWithinDays"
        // added — round 2/3's entry recorded only audienceUserType/recipientCount/capped/hasSample,
        // so a preview sweep varying onlyVerified/registeredWithinDays (the two axes that actually
        // change WHICH address resolveSample returns — see that method's javadoc) left a trail that
        // could not reconstruct which address had leaked from which call. Same redaction discipline
        // as before: shapes/counts only, still never the resolved sample's email address.
        auditLogService.recordAdminAction(
                admin.getId(),
                "ADMIN_CUSTOM_EMAIL_PREVIEWED",
                AuditLogService.OUTCOME_ALLOWED,
                Map.of(
                        "audienceUserType", req.audience().userType(),
                        "audienceOnlyVerified", req.audience().onlyVerified(),
                        "audienceRegisteredWithinDays",
                        String.valueOf(req.audience().registeredWithinDays()),
                        "recipientCount", recipientCount,
                        "capped", capped,
                        "hasSample", sample != null));

        return new PreviewResponse(
                rendered.subject(),
                rendered.html(),
                recipientCount,
                capped,
                recipientCap,
                sample != null ? sample.getEmail() : null);
    }

    /**
     * Confirmed send. Enqueues one {@link EmailOutbox} row per non-unsubscribed recipient (never
     * sends inline — {@code EmailWorker} drains the outbox as usual), persists the {@link
     * AdminEmailCampaign} audit row, and writes {@link AuditLogService#recordAdminAction}. See the
     * class javadoc "Idempotency" and "Serialization" sections for the round-3 ordering fix (B1)
     * and the {@code findById} short-circuit's new position (doc defect #1).
     *
     * <p><b>[SEC: Vikram, A2 fix, REVIEW-R3.md ship-blocker]</b> {@code isolation =
     * READ_COMMITTED} — without this, MySQL/InnoDB's default REPEATABLE READ pins this
     * transaction's read view at its FIRST plain SELECT, which is {@code
     * requireRoleWithMfaSatisfied}'s read of {@code admin_users}, before {@link
     * #acquireSendLock()} even runs. {@code SELECT ... FOR UPDATE} (the lock acquisition) only
     * guarantees the LATEST COMMITTED data for the ROW(S) IT LOCKS — it does not refresh this
     * transaction's snapshot for later PLAIN selects on other tables. {@link #enforceRateLimit()}
     * is a plain {@code findTopByOrderByCreatedAtDesc()} on {@code admin_email_campaigns}, so under
     * REPEATABLE READ it could still read the stale, pre-lock snapshot and see no recent campaign
     * even though the previous lock holder just committed one — two full blasts inside the
     * throttle window. READ_COMMITTED gives every plain SELECT in this method a fresh snapshot as
     * of its own start, so {@code enforceRateLimit()}'s read — which only ever runs AFTER {@link
     * #acquireSendLock()} returns, i.e. after every prior holder has committed — is guaranteed to
     * see that prior holder's row. See {@link AdminEmailSendLock}'s javadoc for the full mechanism.
     *
     * <p>Nothing else in this method depends on a REPEATABLE READ snapshot for correctness:
     * {@link #replayIfAlreadyCommitted} and {@code findById} generally are single-row keyed lookups
     * (isolation level does not change what a keyed lookup returns, only whether a snapshot is
     * reused across statements); {@link #acquireSendLock()} is itself a locking read, unaffected by
     * the isolation level per the paragraph above; and the audience {@code countForCustomEmailAudience}
     * / {@code findForCustomEmailAudience} pair, run back-to-back immediately after the lock, only
     * need to agree with EACH OTHER — which they do, being predicate-identical (see {@code
     * UserRepository}'s javadoc) — not with some earlier snapshot. Under READ_COMMITTED a write to
     * the {@code users} table by a completely unrelated transaction landing in the narrow gap
     * between those two statements could in principle change the answer, but that gap already
     * existed under REPEATABLE READ for the FIRST of the two statements relative to whatever ran
     * before this transaction started, and is no wider here than it always was between two
     * sequential statements in the same transaction — it is not a regression this fix introduces.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SendResponse send(AuthPrincipal principal, SendRequest req) {
        AdminUser admin = adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);

        validateTokens(req.subject(), req.bodyText());
        validateCta(req.ctaLabel(), req.ctaUrl());

        String campaignId = deterministicCampaignId(admin.getId(), req);

        // Fast path: a sequential retry/double-click of an ALREADY-COMMITTED send never touches
        // the lock or the rate limit below — this is the short-circuit the class javadoc
        // describes, moved here (round 3, doc defect #1) so that claim is actually true.
        Optional<SendResponse> replay = replayIfAlreadyCommitted(campaignId);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Control #1, real serialization (B1 fix, REVIEW-R2.md ship-blocker): take the singleton
        // row lock FOR UPDATE so at most one admin.custom send is inside the critical section
        // below, across every admin and every app instance, at a time — see acquireSendLock()'s
        // javadoc.
        acquireSendLock();

        // A truly concurrent identical retry may have committed WHILE this request was waiting
        // for the lock above — re-check now that we are guaranteed to see it (see
        // acquireSendLock()'s javadoc for the MySQL locking-read guarantee this relies on).
        replay = replayIfAlreadyCommitted(campaignId);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Control #1 — checked only after the lock is held, so the read below is never racing
        // another in-flight send() for the answer.
        enforceRateLimit();

        UserType userType = resolveUserType(req.audience().userType());
        Instant registeredAfter = resolveRegisteredAfter(req.audience().registeredWithinDays());

        // Control #3 — recompute the audience fresh, right now, under this send. This is what
        // makes "you cannot send a set that shifted under you" true: the count below is never
        // trusted from the client beyond the equality check against what THEY are confirming.
        long recipientCount =
                userRepository.countForCustomEmailAudience(
                        userType, req.audience().onlyVerified(), registeredAfter);
        if (req.confirmRecipientCount() == null || recipientCount != req.confirmRecipientCount()) {
            // [SEC: Vikram, A6 fix, REVIEW-R3.md] Deliberately does NOT interpolate either count
            // into the message. Control #3's whole point is that a caller cannot send to an
            // audience it hasn't freshly previewed — but the OLD message ("confirmed 0, now 4312")
            // handed the live count back in the error body itself, so two requests defeated the
            // control entirely: send with confirmRecipientCount=0 (guaranteed to mismatch), read
            // the real count out of this 409, then resend with that number. The mismatch is still
            // reported (so a legitimate caller knows to re-preview), just not the number that made
            // it exploitable.
            throw new ApiException(
                    "RECIPIENT_COUNT_CHANGED",
                    "Recipient count changed since preview — preview again before sending",
                    HttpStatus.CONFLICT);
        }

        // Control #2 — hard cap. Refused, not truncated: an admin who confirms an over-cap count
        // gets told to shrink the audience, never a silent partial send.
        if (recipientCount > recipientCap) {
            throw new ApiException(
                    "RECIPIENT_CAP_EXCEEDED",
                    "Audience of " + recipientCount + " exceeds the " + recipientCap + " recipient cap",
                    HttpStatus.BAD_REQUEST);
        }

        List<User> recipients =
                userRepository.findForCustomEmailAudience(
                        userType, req.audience().onlyVerified(), registeredAfter, Limit.of(recipientCap));

        // Control #5 — unsubscribe enforcement, resolved for the whole batch in one query rather
        // than per-recipient (see EmailPreferenceRepository.findUnsubscribedUserIds javadoc).
        Set<String> unsubscribedUserIds =
                recipients.isEmpty()
                        ? Set.of()
                        : emailPreferenceRepository.findUnsubscribedUserIds(
                                recipients.stream().map(User::getId).toList(), TEMPLATE_KEY);

        String ctaLabel = blankToNull(req.ctaLabel());
        String ctaUrl = blankToNull(req.ctaUrl());

        List<EmailOutbox> toEnqueue = new ArrayList<>(recipients.size());
        int skippedUnsubscribed = 0;
        int skippedInvalidEmail = 0;
        for (User recipient : recipients) {
            if (unsubscribedUserIds.contains(recipient.getId())) {
                skippedUnsubscribed++;
                continue;
            }
            // [SEC: Vikram, A1 fix, REVIEW-R3.md ship-blocker] Belt and braces: the audience query
            // itself now excludes soft-deleted (deletedAt IS NOT NULL, email blanked) users — see
            // UserRepository#findForCustomEmailAudience's javadoc — but this loop no longer TRUSTS
            // that alone. EmailOutbox.to_email is NOT NULL; before the query fix, one null address
            // anywhere in the batch made the whole saveAll(...).flush() below throw and roll back
            // EVERY row in this send, not just the bad one. Skipping here means a future gap in the
            // audience query (a new soft-delete-like state nobody remembers to exclude) can only
            // ever cost this one recipient, never the batch.
            if (recipient.getEmail() == null || recipient.getEmail().isBlank()) {
                skippedInvalidEmail++;
                log.warn(
                        "admin.custom send skipped recipient with null/blank email at enqueue —"
                                + " userId={} (likely a soft-deleted account the audience query"
                                + " should already have excluded; this recipient is dropped instead"
                                + " of failing the whole batch)",
                        recipient.getId());
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("subject", substituteTokens(req.subject(), recipient));
            data.put("bodyText", substituteTokens(req.bodyText(), recipient));
            data.put("ctaLabel", ctaLabel);
            data.put("ctaUrl", ctaUrl);

            toEnqueue.add(
                    EmailOutbox.builder()
                            .id(Ulids.newUlid())
                            .userId(recipient.getId())
                            .toEmail(recipient.getEmail())
                            .templateKey(TEMPLATE_KEY)
                            .templateData(serializeTemplateData(data))
                            // Format fixed by the SPEC: admin.custom:<campaignId>:<userId>, now
                            // with campaignId made DETERMINISTIC (see class javadoc "Idempotency",
                            // C1 fix) rather than a fresh Ulids.newUlid() per request -- that's
                            // what makes the table's UNIQUE(idempotency_key) actually collapse a
                            // double-submitted send into a no-op instead of a second copy.
                            .idempotencyKey(TEMPLATE_KEY + ":" + campaignId + ":" + recipient.getId())
                            .build());
        }
        emailOutboxRepository.saveAll(toEnqueue);

        AdminEmailCampaign campaign =
                AdminEmailCampaign.builder()
                        .id(campaignId)
                        .adminUserId(admin.getId())
                        .subject(req.subject())
                        .bodyText(req.bodyText())
                        .ctaLabel(ctaLabel)
                        .ctaUrl(ctaUrl)
                        .audienceUserType(req.audience().userType().trim().toUpperCase())
                        .audienceOnlyVerified(req.audience().onlyVerified())
                        .audienceRegisteredWithinDays(req.audience().registeredWithinDays())
                        .recipientCount((int) recipientCount)
                        .skippedUnsubscribed(skippedUnsubscribed)
                        // B5 fix (REVIEW-R2.md): store the true queued figure rather than making
                        // replaySendResponse reconstruct it from recipientCount -
                        // skippedUnsubscribed later.
                        .queuedCount(toEnqueue.size())
                        .build();
        adminEmailCampaignRepository.save(campaign);

        // C1 fix, second half: force the outbox rows + campaign row to actually hit the DB HERE,
        // inside this method's own transaction, instead of letting Hibernate defer those INSERTs
        // to commit time (the default, since every id here is assigned up front rather than
        // @GeneratedValue). auditLogService.recordAdminAction below runs in its OWN REQUIRES_NEW
        // transaction and commits independently, immediately, when called -- if it ran BEFORE the
        // writes above were durably flushed, a losing idempotency race could still let the audit
        // log record "ADMIN_CUSTOM_EMAIL_SENT" for a request whose own outbox/campaign rows then
        // fail their constraint check and roll back -- an audit entry that lied. Flushing first
        // makes that failure surface HERE, before any REQUIRES_NEW write happens.
        emailOutboxRepository.flush();

        auditLogService.recordAdminAction(
                admin.getId(),
                "ADMIN_CUSTOM_EMAIL_SENT",
                AuditLogService.OUTCOME_ALLOWED,
                Map.of(
                        "campaignId", campaignId,
                        "audienceUserType", req.audience().userType(),
                        "recipientCount", recipientCount,
                        "queued", toEnqueue.size(),
                        "skippedUnsubscribed", skippedUnsubscribed,
                        // Defense-in-depth counter (A1 fix, REVIEW-R3.md) — expected to be 0 now
                        // that the audience query itself excludes soft-deleted users; a non-zero
                        // value here means that exclusion has a gap and is worth investigating, not
                        // that the send failed (it did not — this recipient was just dropped).
                        "skippedInvalidEmail", skippedInvalidEmail));

        return new SendResponse(campaignId, toEnqueue.size(), skippedUnsubscribed, false);
    }

    /**
     * B3 fix (REVIEW-R2.md ship-blocker): marks every still-PENDING {@code admin.custom} outbox
     * row for {@code campaignId} terminal ({@link EmailOutbox#markCancelled()}) — the only stop
     * for a queued send that {@code send()} used to offer was a manual production {@code UPDATE}.
     * Same authority tier as {@link #send}: {@code SUPER_ADMIN} + MFA, and audited. Idempotent —
     * calling this twice (or after the campaign has already fully drained) simply finds zero
     * PENDING rows the second time and returns {@code cancelled: 0}, not an error.
     *
     * @throws ApiException 404 {@code CAMPAIGN_NOT_FOUND} if {@code campaignId} does not match a
     *     confirmed send.
     */
    @Transactional
    public CancelResponse cancel(AuthPrincipal principal, String campaignId) {
        AdminUser admin = adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);

        adminEmailCampaignRepository
                .findById(campaignId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CAMPAIGN_NOT_FOUND",
                                        "No admin custom email campaign with that id",
                                        HttpStatus.NOT_FOUND));

        String idempotencyKeyPrefix = TEMPLATE_KEY + ":" + campaignId + ":";
        List<EmailOutbox> pending =
                emailOutboxRepository.findByTemplateKeyAndStatusAndIdempotencyKeyStartingWith(
                        TEMPLATE_KEY, EmailOutboxStatus.PENDING, idempotencyKeyPrefix);
        for (EmailOutbox row : pending) {
            row.markCancelled();
        }
        emailOutboxRepository.saveAll(pending);

        auditLogService.recordAdminAction(
                admin.getId(),
                "ADMIN_CUSTOM_EMAIL_CANCELLED",
                AuditLogService.OUTCOME_ALLOWED,
                Map.of("campaignId", campaignId, "cancelled", pending.size()));

        return new CancelResponse(campaignId, pending.size());
    }

    /**
     * Looks up an already-committed campaign by its deterministic id and, if found, logs and
     * returns the replay response instead of doing any new work. Called twice from {@link #send}
     * — see that method's inline comments and the class javadoc "Idempotency" section for why.
     */
    private Optional<SendResponse> replayIfAlreadyCommitted(String campaignId) {
        return adminEmailCampaignRepository
                .findById(campaignId)
                .map(
                        existing -> {
                            log.info(
                                    "admin.custom send is a retry of an already-committed"
                                            + " campaignId={} — returning the original result"
                                            + " instead of enqueueing a second copy",
                                    campaignId);
                            return replaySendResponse(existing);
                        });
    }

    /**
     * Takes the {@link AdminEmailSendLock} singleton row FOR UPDATE, blocking (bounded to 10s —
     * see {@code AdminEmailSendLockRepository#lockForUpdate}) until any prior holder's transaction
     * commits or rolls back. MySQL/InnoDB's locking-read semantics mean this method only returns
     * successfully AFTER every send() that was serialized ahead of this one has already committed
     * — so the very next thing this method's caller does (re-check idempotency, then {@link
     * #enforceRateLimit()}) is guaranteed to see that prior send's row, closing the B1 race
     * (REVIEW-R2.md: "two admins... both read the same stale 'last campaign' and both pass").
     *
     * @throws ApiException 429 {@code SEND_IN_PROGRESS} if the lock could not be acquired within
     *     the bounded timeout (another send is genuinely in flight right now).
     */
    private void acquireSendLock() {
        try {
            adminEmailSendLockRepository
                    .lockForUpdate(AdminEmailSendLock.SINGLETON_ID)
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "admin_email_send_lock singleton row missing —"
                                                    + " migration V20260903130000 did not seed"
                                                    + " it"));
        } catch (PessimisticLockingFailureException e) {
            throw new ApiException(
                    "SEND_IN_PROGRESS",
                    "Another admin custom email send is being processed right now — please retry"
                            + " shortly",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private void enforceRateLimit() {
        adminEmailCampaignRepository
                .findTopByOrderByCreatedAtDesc()
                .ifPresent(
                        last -> {
                            Duration elapsed = Duration.between(last.getCreatedAt(), Instant.now());
                            if (elapsed.compareTo(minInterval) < 0) {
                                Duration wait = minInterval.minus(elapsed);
                                throw new ApiException(
                                        "RATE_LIMITED",
                                        "Another admin custom email was sent "
                                                + elapsed.toMinutes()
                                                + " minute(s) ago — wait "
                                                + Math.max(1, wait.toMinutes())
                                                + " more minute(s) before sending again",
                                        HttpStatus.TOO_MANY_REQUESTS);
                            }
                        });
    }

    /**
     * A5 fix (round 5, REVIEW-R4.md): {@code preview} took no lock and had no rate limit at all —
     * {@code findForCustomEmailAudience} only has a LOWER bound on registration and orders
     * ascending, and {@link #resolveSample} takes the first row, so sweeping {@code
     * registeredWithinDays} one day at a time (crossed with {@code userType}/{@code onlyVerified})
     * walks the user table and hands back one real, unredacted email address per call — a scriptable
     * address oracle with no throttle standing in its way. This is a simple in-memory, process-local
     * sliding-window-of-one throttle (deliberately NOT the persisted {@link AdminEmailSendLock} +
     * {@link #enforceRateLimit()} machinery {@link #send} uses): that machinery exists because
     * control #1 is a SPEC-mandated, restart-durable guarantee against a real blast being sent
     * twice — correctness-critical. This is defense-in-depth against a sweep being fast, not a
     * correctness guarantee against a sweep happening at all (a determined caller behind multiple
     * app instances still gets one preview per instance per interval); slowing a table-walk from
     * "as fast as the network allows" to one row per {@link #previewMinInterval} is what closes the
     * practical exposure the review flagged, without adding a migration or a cross-instance lock to
     * a read-only, non-money endpoint. {@link AtomicReference#compareAndSet} makes the check-and-
     * claim atomic against two truly concurrent preview calls on this instance.
     *
     * @throws ApiException 429 {@code PREVIEW_RATE_LIMITED} if called again before {@link
     *     #previewMinInterval} has elapsed since the last call (to any admin — global, not
     *     per-admin, since the oracle risk does not care which admin account is walking the table).
     */
    private void enforcePreviewRateLimit() {
        Instant now = Instant.now();
        Instant previous;
        do {
            previous = lastPreviewAt.get();
            if (previous != null && Duration.between(previous, now).compareTo(previewMinInterval) < 0) {
                throw new ApiException(
                        "PREVIEW_RATE_LIMITED",
                        "Preview was called too soon after the previous one — wait a moment and"
                                + " retry",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        } while (!lastPreviewAt.compareAndSet(previous, now));
    }

    /** {@code null}/blank userType means "ALL" — no {@code user_type} filter (see {@code
     * UserRepository.countForCustomEmailAudience} javadoc). */
    private UserType resolveUserType(String raw) {
        String upper = raw == null ? "" : raw.trim().toUpperCase();
        if (upper.equals("ALL")) {
            return null;
        }
        try {
            return UserType.valueOf(upper);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_AUDIENCE",
                    "audience.userType must be CREATOR, BRAND, or ALL",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private Instant resolveRegisteredAfter(Integer registeredWithinDays) {
        if (registeredWithinDays == null) {
            return null;
        }
        if (registeredWithinDays < 0) {
            throw new ApiException(
                    "INVALID_AUDIENCE",
                    "audience.registeredWithinDays must not be negative",
                    HttpStatus.BAD_REQUEST);
        }
        return Instant.now().minus(Duration.ofDays(registeredWithinDays));
    }

    /**
     * B2 fix (REVIEW-R2.md round 3 ship-blocker): a caller-supplied {@code sampleUserId} is no
     * longer resolved at all — round 2's fix scoped the lookup to {@code matchesAudience}, but
     * {@code audience} itself is caller-supplied, so {@code
     * {userType:"ALL",onlyVerified:false,registeredWithinDays:null}} narrows that predicate down
     * to just {@code status == ACTIVE}: any existing active userId still resolved, and {@code
     * preview} handed back that user's REAL email address in {@code sampleRecipientEmail} — an
     * unaudited, platform-wide email-address oracle. The sample is now always the first row the
     * SAME server-side audience query {@code send()} itself uses ({@code
     * findForCustomEmailAudience}) returns, so a caller can never see any address beyond the one
     * a real, audience-scoped send would already have picked as its own first recipient.
     */
    private User resolveSample(UserType userType, boolean onlyVerified, Instant registeredAfter) {
        List<User> first =
                userRepository.findForCustomEmailAudience(userType, onlyVerified, registeredAfter, Limit.of(1));
        return first.isEmpty() ? null : first.get(0);
    }

    /**
     * Every unrecognized {@code {{token}}} is a 400 at BOTH preview and send (SPEC.md) — this is
     * called from both entry points before anything else happens, so an unknown token never
     * reaches a recipient as a literal {@code {{foo}}}. B4 fix (REVIEW-R2.md): {@link
     * #TOKEN_PATTERN} now recognizes malformed shapes like {@code {{first-name}}}/{@code
     * {{ first_name }}}/{@code {{first.name}}} as tokens too (previously these matched neither
     * this validator nor {@link #substituteTokens}, so they shipped as literal text) — they still
     * fail the exact-string {@link #ALLOWED_TOKENS} check below and are correctly rejected.
     */
    private void validateTokens(String subject, String bodyText) {
        rejectUnknownTokens(subject);
        rejectUnknownTokens(bodyText);
    }

    private void rejectUnknownTokens(String text) {
        Matcher m = TOKEN_PATTERN.matcher(text);
        while (m.find()) {
            String token = m.group(1);
            if (!ALLOWED_TOKENS.contains(token)) {
                throw new ApiException(
                        "UNKNOWN_TOKEN",
                        "Unknown personalization token: {{" + token + "}}",
                        HttpStatus.BAD_REQUEST);
            }
        }
    }

    /**
     * REVIEW-R1.md item 6 fix. Neither {@code ctaLabel} nor {@code ctaUrl} ever goes through
     * {@link #substituteTokens} — SPEC.md's token table lists only {@code subject}/{@code
     * bodyText} — so unlike {@link #validateTokens}, an ALLOWED_TOKENS name here is not actually
     * "known" for this field: it would ship as a literal {@code {{first_name}}} to every
     * recipient, same failure mode as a truly-unknown token, so ANY {{...}} shape in either field
     * is rejected (see {@link #rejectAnyToken}, deliberately not {@link #rejectUnknownTokens}).
     * {@code ctaUrl} is also scheme-checked — {@code EmailTemplateRegistry.wrapHtml} only
     * HTML-escapes it before emitting {@code <a href="...">}; escaping does not neutralize a
     * {@code javascript:}/{@code data:} URI as a clickable link.
     */
    private void validateCta(String ctaLabel, String ctaUrl) {
        if (ctaLabel != null && !ctaLabel.isBlank()) {
            rejectAnyToken(ctaLabel, "ctaLabel");
        }
        if (ctaUrl != null && !ctaUrl.isBlank()) {
            rejectAnyToken(ctaUrl, "ctaUrl");
            String normalized = ctaUrl.trim().toLowerCase(Locale.ROOT);
            if (!(normalized.startsWith("https://") || normalized.startsWith("http://"))) {
                throw new ApiException(
                        "INVALID_CTA_URL",
                        "ctaUrl must be an http:// or https:// link",
                        HttpStatus.BAD_REQUEST);
            }
        }
    }

    private void rejectAnyToken(String text, String fieldName) {
        Matcher m = TOKEN_PATTERN.matcher(text);
        if (m.find()) {
            throw new ApiException(
                    "UNKNOWN_TOKEN",
                    "Unknown personalization token: {{"
                            + m.group(1)
                            + "}} ("
                            + fieldName
                            + " does not support personalization tokens)",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Substitutes {@code {{first_name}}}/{@code {{name}}}/{@code {{email}}} for one recipient
     * (SPEC.md's token table). {@code user == null} renders the fallback-only preview shown when
     * an audience has no matching recipient yet — {@code {{email}}} has no fallback per the SPEC,
     * so it resolves to an empty string rather than a fabricated address. Deliberately uses plain
     * literal {@code String.replace} on the three known token strings, NOT {@link #TOKEN_PATTERN}
     * — see that field's javadoc.
     */
    private String substituteTokens(String template, User user) {
        String firstName = user != null ? firstNameOrFallback(user) : FALLBACK_NAME;
        String name = user != null ? nameOrFallback(user) : FALLBACK_NAME;
        String email = user != null && user.getEmail() != null ? user.getEmail() : "";
        return template
                .replace("{{first_name}}", firstName)
                .replace("{{name}}", name)
                .replace("{{email}}", email);
    }

    private String firstNameOrFallback(User user) {
        return user.getFirstName() != null && !user.getFirstName().isBlank()
                ? user.getFirstName().trim()
                : FALLBACK_NAME;
    }

    private String nameOrFallback(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName().trim();
        }
        return firstNameOrFallback(user);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * REVIEW-R1.md item 7 fix: this used to catch, log, and return {@code "{}"} — and the row was
     * STILL enqueued by the caller, so {@code EmailTemplateRegistry.renderCustom} would render an
     * empty subject and empty body to a real inbox. Now this throws, which (being unchecked, and
     * this method only ever being called from inside {@link #send}'s {@code @Transactional} loop)
     * propagates out of {@link #send} entirely — the whole transaction, including every row
     * already added to {@code toEnqueue} in earlier loop iterations and the not-yet-flushed
     * campaign row, rolls back. Nothing partial reaches the outbox.
     */
    private String serializeTemplateData(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize admin.custom template data — aborting send", e);
            throw new ApiException(
                    "TEMPLATE_SERIALIZATION_FAILED",
                    "Failed to serialize personalized email content — send aborted",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * C1 fix — see class javadoc "Idempotency". Derived from exactly what the review named as
     * defining "the same logical send": admin + subject + body + cta + audience +
     * confirmRecipientCount. Two byte-identical requests (a retry, a race) always hash to the same
     * 26-char id; content that differs in any of those fields — including the confirmed count,
     * which almost always shifts given more than a few minutes between two otherwise-identical
     * sends — does not collide. {@code admin_email_campaigns.id} is {@code VARCHAR(26)} (sized for
     * a ULID, but not format-constrained at the DB level), so a truncated hex digest fits without
     * a migration.
     */
    private static String deterministicCampaignId(String adminId, SendRequest req) {
        // A U+0001 control character (never valid in admin-typed subject/body/CTA text) as the
        // join delimiter -- WITHOUT one, "ab"+"c" and "a"+"bc" concatenate to the identical "abc"
        // and two DIFFERENT sends could hash to the same campaignId.
        String delimiter = String.valueOf((char) 1);
        String raw =
                String.join(
                        delimiter,
                        adminId,
                        req.subject(),
                        req.bodyText(),
                        String.valueOf(blankToNull(req.ctaLabel())),
                        String.valueOf(blankToNull(req.ctaUrl())),
                        req.audience().userType().trim().toUpperCase(),
                        String.valueOf(req.audience().onlyVerified()),
                        String.valueOf(req.audience().registeredWithinDays()),
                        String.valueOf(req.confirmRecipientCount()));
        return "cc" + sha256Hex(raw).substring(0, 24);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * C1 fix — the response for a detected retry (class javadoc "Idempotency"): the original
     * {@code campaignId} and the original queued/skipped counts, reconstructed from the already-
     * committed {@link AdminEmailCampaign} row rather than doing any new enqueue work.
     *
     * <p>B5 fix (REVIEW-R2.md): {@code queued} now reads {@link AdminEmailCampaign#getQueuedCount()}
     * directly instead of reconstructing it as {@code recipientCount - skippedUnsubscribed} — that
     * reconstruction silently disagreed with what the audit log recorded as {@code queued} for the
     * original send if the two numbers were ever computed differently.
     *
     * <p>Doc defect #2 fix (REVIEW-R2.md): {@code replay} is {@code true} here — see {@link
     * SendResponse}'s javadoc for why the caller needs to be able to tell this apart from a fresh
     * send that happened to enqueue the exact same counts.
     */
    private static SendResponse replaySendResponse(AdminEmailCampaign existing) {
        return new SendResponse(
                existing.getId(), existing.getQueuedCount(), existing.getSkippedUnsubscribed(), true);
    }
}
