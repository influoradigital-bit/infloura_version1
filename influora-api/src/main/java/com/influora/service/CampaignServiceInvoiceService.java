package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignServiceInvoice;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.HsnSacAppliesTo;
import com.influora.domain.enums.InvoiceNumberSeriesType;
import com.influora.domain.enums.MarketplaceInvoiceStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignServiceInvoiceRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D14 Doc#2 (creator service invoice, Creator -> Brand). Wired into all three {@code
 * EscrowService} release call sites ({@code release}, {@code adminReleaseForDispute}, {@code
 * adminSplitForDispute}), each called AFTER that call site's own escrow-release ledger posting
 * has already succeeded.
 *
 * <p><b>Idempotency (Rohan build-flag #2):</b> {@link #createAtRelease} is only ever called AFTER
 * the caller's ledger posting has already succeeded (gated on the returned {@code
 * LedgerPostingResult}, never on "the release endpoint was called"), and additionally checks
 * {@link CampaignServiceInvoiceRepository#findByEscrowHoldId} first — a retried release, or the
 * (should-never-happen) case of more than one caller reaching this for the same hold, can never
 * double-issue a statutory invoice number.
 *
 * <p><b>[B8 fix] Transaction isolation:</b> {@link #createAtRelease} previously ran plain {@code
 * @Transactional} (REQUIRED), so it JOINED the caller's ({@code EscrowService#release}, etc.)
 * transaction — an exception here (e.g. {@code CREATOR_PROFILE_NOT_FOUND} when a released
 * milestone's payee has no creator profile row) marked that shared transaction rollback-only and
 * REVERSED an already-completed escrow release + ledger posting. Same failure shape as the
 * {@code creator_invoice_code} collision {@link CreatorInvoiceCodeService} already fixed this way
 * — see that class's javadoc for the full rationale. This method now runs in its OWN transaction
 * ({@link Propagation#REQUIRES_NEW}, via this dedicated Spring bean so the transactional proxy
 * actually applies — self-invocation would silently no-op the propagation); a failure here can
 * only roll back whatever THIS method itself wrote, never the caller's release. Every {@code
 * EscrowService} call site additionally wraps its call to this method in a try/catch so an
 * exception thrown out of this REQUIRES_NEW boundary is logged loudly and swallowed there too —
 * REQUIRES_NEW alone only isolates the COMMIT boundary, an uncaught exception still propagates
 * through and would otherwise mark the caller's own transaction rollback-only.
 */
@Service
public class CampaignServiceInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(CampaignServiceInvoiceService.class);

    private static final String PDF_KEY_PREFIX = "campaign-service-invoices/";

    /**
     * [F-0390 D1] Retry ceiling for {@code escrow_invoice_failures} rows, matching {@code
     * EmailOutbox.MAX_RETRIES}'s "5, then give up and require a human" discipline.
     */
    private static final int MAX_FAILURE_RETRIES = 5;

    private static final int FAILURE_RETRY_BATCH_SIZE = 20;

    private final CampaignServiceInvoiceRepository invoiceRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final HsnSacCodeService hsnSacCodeService;
    private final CampaignServiceInvoicePdfService pdfService;
    private final R2StorageService r2StorageService;
    private final CreatorInvoiceCodeService creatorInvoiceCodeService;

    /** [F-0390 D1] Needed only to re-fetch the hold/collaboration when retrying an INVOICE_CREATE
     * failure — see {@link #retryInvoiceCreate}. */
    private final EscrowHoldRepository escrowHoldRepository;

    private final CollaborationRepository collaborationRepository;

    /**
     * [F-0390 D1] Self-injected proxy, {@code @Lazy} to break the circular-bean-creation cycle this
     * would otherwise cause. Needed ONLY so {@link #retryInvoiceCreate} can call {@link
     * #createAtRelease} THROUGH the Spring AOP proxy — a plain {@code this.createAtRelease(...)}
     * self-invocation from another method in this same class would silently bypass the proxy and
     * drop {@code createAtRelease}'s {@code @Transactional(REQUIRES_NEW)} (same self-invocation trap
     * {@code EmailWorker}'s class javadoc warns about, and the same reason {@code
     * CreatorInvoiceCodeService} is its own bean rather than a private method here). The idiomatic
     * fix given that existing convention would be a dedicated {@code @Component} retry worker
     * (mirroring {@code EmailWorker} exactly) — that was not done here because it requires a new
     * Java file outside this task's file-domain restriction (F-0390 Track D); flagged for a
     * follow-up to promote this into a proper worker class once that restriction lifts.
     */
    private final CampaignServiceInvoiceService self;

    @PersistenceContext private EntityManager entityManager;

    public CampaignServiceInvoiceService(
            CampaignServiceInvoiceRepository invoiceRepository,
            CreatorProfileRepository creatorProfileRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            InvoiceNumberService invoiceNumberService,
            HsnSacCodeService hsnSacCodeService,
            CampaignServiceInvoicePdfService pdfService,
            R2StorageService r2StorageService,
            CreatorInvoiceCodeService creatorInvoiceCodeService,
            EscrowHoldRepository escrowHoldRepository,
            CollaborationRepository collaborationRepository,
            @Lazy CampaignServiceInvoiceService self) {
        this.invoiceRepository = invoiceRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.hsnSacCodeService = hsnSacCodeService;
        this.pdfService = pdfService;
        this.r2StorageService = r2StorageService;
        this.creatorInvoiceCodeService = creatorInvoiceCodeService;
        this.escrowHoldRepository = escrowHoldRepository;
        this.collaborationRepository = collaborationRepository;
        this.self = self;
    }

    /**
     * @param hold the just-released (or being-released, same transaction) escrow hold — {@code
     *     hold.getAmount()} is the gross service value invoiced (pre platform-fee, matches the
     *     data-model comment on {@link CampaignServiceInvoice#getGrossAmount()})
     * @param collaboration the collaboration the release belongs to (payee == {@code
     *     collaboration.getCreatorId()})
     * @param ledgerCreditLegId the creator-side credit leg id of the release posting — traceability only
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CampaignServiceInvoice createAtRelease(
            EscrowHold hold, Collaboration collaboration, String ledgerCreditLegId) {
        var existing = invoiceRepository.findByEscrowHoldId(hold.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        BigDecimal grossAmount = hold.getAmount();
        if (grossAmount == null || grossAmount.signum() <= 0) {
            // A zero/negative-amount release (e.g. a fully brand-refunded dispute split) has no
            // creator service to invoice — legal no-op, matches EscrowService.adminSplitForDispute's
            // own "only call if creatorAmount > 0" guard at the call site, but re-checked here too.
            log.info(
                    "Skipping Doc#2 creator service invoice for escrow hold {} — non-positive gross amount",
                    hold.getId());
            return null;
        }

        String creatorUserId = collaboration.getCreatorId();
        CreatorProfile creator =
                creatorProfileRepository
                        .findByUserId(creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "Creator profile not found for user " + creatorUserId,
                                                HttpStatus.CONFLICT));

        // [Meera, D14 fix] Delegated to a dedicated bean running in its OWN transaction
        // (REQUIRES_NEW) — see CreatorInvoiceCodeService's class doc for why a collision here must
        // never poison this method's escrow-release transaction.
        String creatorInvoiceCode = creatorInvoiceCodeService.resolveOrAssign(creator.getId());

        Campaign campaign =
                campaignRepository
                        .findById(hold.getCampaignId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND",
                                                "Campaign not found for escrow hold " + hold.getId(),
                                                HttpStatus.CONFLICT));
        Workspace brandWorkspace =
                workspaceRepository
                        .findById(hold.getWorkspaceId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND",
                                                "Brand workspace not found for escrow hold " + hold.getId(),
                                                HttpStatus.CONFLICT));

        String invoiceNumber =
                invoiceNumberService.generateNext(InvoiceNumberSeriesType.CAMPAIGN_SERVICE, creatorInvoiceCode);

        // D14-D: TCS is REPORT-ONLY v1 — compute + record the 1% ECO liability, disburse the full
        // net to the creator, true up at GSTR-8. Does NOT change the release payout math above.
        BigDecimal tcsAmount = grossAmount.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);

        CampaignServiceInvoice invoice =
                CampaignServiceInvoice.builder()
                        .id(Ulids.newUlid())
                        .invoiceNumber(invoiceNumber)
                        .collaborationId(collaboration.getId())
                        .campaignId(campaign.getId())
                        .escrowHoldId(hold.getId())
                        .creatorUserId(creatorUserId)
                        .brandWorkspaceId(brandWorkspace.getId())
                        .grossAmount(grossAmount)
                        .currency(hold.getCurrency())
                        .creatorGstin(creator.getGstin())
                        .tcsAmount(tcsAmount)
                        .hsnSacCode(hsnSacCodeService.resolveCode(HsnSacAppliesTo.CREATOR_SERVICE))
                        .status(MarketplaceInvoiceStatus.PAID)
                        .issuedAt(Instant.now())
                        .build();
        invoiceRepository.save(invoice);

        // [F-0390 D1] The invoice row itself now exists (numbered, persisted) — clear any stale
        // INVOICE_CREATE failure marker a prior attempt against this same hold may have left behind
        // (e.g. this call IS a retry via retryInvoiceCreate). A PDF-stage failure below re-records
        // its own marker if it happens.
        markFailureResolved(hold.getId());

        log.info(
                "Issued Doc#2 creator service invoice {} for escrow hold {} (ledger credit leg {})",
                invoiceNumber,
                hold.getId(),
                ledgerCreditLegId);

        // Best-effort PDF render + R2 store, same discipline as InvoiceService — a rendering hiccup
        // must never roll back the already-persisted, already-numbered statutory invoice row.
        try {
            byte[] pdfBytes = pdfService.render(invoice, campaign, brandWorkspace, creator);
            if (r2StorageService.isAvailable()) {
                String objectKey = PDF_KEY_PREFIX + invoice.getId() + ".pdf";
                r2StorageService.putBytes(objectKey, pdfBytes, "application/pdf");
                invoice.setPdfR2Key(objectKey);
                invoiceRepository.save(invoice);
            } else {
                log.warn("R2 not configured — Doc#2 invoice PDF for {} generated but not stored", invoice.getId());
            }
        } catch (Exception e) {
            log.error(
                    "Doc#2 invoice PDF generation/storage failed for invoice {} — invoice row was still recorded",
                    invoice.getId(),
                    e);
            // [F-0390 D1] Row exists but has no PDF — durable, retryable marker (see
            // retryFailedInvoiceFailures/retryPdfRender) instead of only this log line.
            recordFailure(hold.getId(), collaboration.getId(), ledgerCreditLegId, "PDF_RENDER", e.getMessage());
        }

        return invoice;
    }

    /**
     * [F-0390 D1] Called from {@code EscrowService#safelyCreateServiceInvoice}'s catch block when
     * {@link #createAtRelease} itself throws — i.e. NO {@link CampaignServiceInvoice} row could be
     * created at all (creator-profile/campaign/workspace lookup, or invoice-number mint, failed).
     * Persists a durable, queryable failure marker (V20260828120000__escrow_invoice_failures.sql)
     * instead of the log-only handling this used to get, and {@link #retryFailedInvoiceFailures}
     * picks it back up on a schedule. Runs on the CALLER's ambient transaction (EscrowService's
     * release/adminReleaseForDispute/adminSplitForDispute, all {@code @Transactional}) so the
     * marker commits atomically with the release it documents — deliberately NOT its own {@code
     * REQUIRES_NEW}, unlike {@link #createAtRelease}, because there is no risk here of THIS write
     * needing isolation from a rollback the caller doesn't have (the release already succeeded by
     * the time this is called). Never throws — {@link #recordFailure} swallows its own failures.
     */
    public void recordInvoiceCreationFailure(
            String escrowHoldId, String collaborationId, String ledgerCreditLegId, String errorMessage) {
        recordFailure(escrowHoldId, collaborationId, ledgerCreditLegId, "INVOICE_CREATE", errorMessage);
    }

    /**
     * [F-0390 D1] Upserts a PENDING {@code escrow_invoice_failures} row for {@code escrowHoldId}
     * (one row per hold — {@code UNIQUE KEY uq_escrow_invoice_failures_hold}), via {@link
     * #entityManager} native SQL rather than a dedicated {@code @Entity}/{@code @Repository} pair —
     * see {@link #self}'s javadoc for why (file-domain restriction, not a design preference).
     * Never throws: a failure to record a failure marker must not itself become a second, worse
     * failure on top of the one already being handled (both call sites are inside a caller's
     * already-succeeded-money-movement transaction, or this service's own REQUIRES_NEW invoice
     * transaction — neither may be aborted by this).
     */
    private void recordFailure(
            String escrowHoldId,
            String collaborationId,
            String ledgerCreditLegId,
            String failureStage,
            String errorMessage) {
        try {
            String truncatedError =
                    errorMessage == null ? null : errorMessage.substring(0, Math.min(errorMessage.length(), 512));
            Timestamp firstRetryAt = Timestamp.from(Instant.now().plus(Duration.ofMinutes(5)));
            entityManager
                    .createNativeQuery(
                            "INSERT INTO escrow_invoice_failures "
                                    + "(id, escrow_hold_id, collaboration_id, ledger_credit_leg_id, failure_stage, "
                                    + " status, retry_count, next_retry_at, error_message, created_at, updated_at) "
                                    + "VALUES (:id, :escrowHoldId, :collaborationId, :ledgerCreditLegId, :stage, "
                                    + " 'PENDING', 0, :nextRetryAt, :errorMessage, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)) "
                                    + "ON DUPLICATE KEY UPDATE "
                                    + "  collaboration_id = VALUES(collaboration_id), "
                                    + "  ledger_credit_leg_id = VALUES(ledger_credit_leg_id), "
                                    + "  failure_stage = VALUES(failure_stage), "
                                    + "  status = 'PENDING', "
                                    + "  next_retry_at = VALUES(next_retry_at), "
                                    + "  error_message = VALUES(error_message), "
                                    + "  resolved_at = NULL, "
                                    + "  updated_at = CURRENT_TIMESTAMP(3)")
                    .setParameter("id", Ulids.newUlid())
                    .setParameter("escrowHoldId", escrowHoldId)
                    .setParameter("collaborationId", collaborationId)
                    .setParameter("ledgerCreditLegId", ledgerCreditLegId)
                    .setParameter("stage", failureStage)
                    .setParameter("nextRetryAt", firstRetryAt)
                    .setParameter("errorMessage", truncatedError)
                    .executeUpdate();
            log.error(
                    "Doc#2 creator service invoice failure recorded for escrow hold {} (stage {}, collaboration"
                            + " {}) — queryable in escrow_invoice_failures, retried by"
                            + " retryFailedInvoiceFailures",
                    escrowHoldId,
                    failureStage,
                    collaborationId);
        } catch (RuntimeException recordEx) {
            log.error(
                    "Failed to persist escrow_invoice_failures marker for escrow hold {} (stage {}) — this"
                            + " invoice failure now exists ONLY in logs, same gap as before F-0390 D1",
                    escrowHoldId,
                    failureStage,
                    recordEx);
        }
    }

    /** [F-0390 D1] Clears a PENDING/EXHAUSTED failure marker once its hold's invoice is whole again. */
    private void markFailureResolved(String escrowHoldId) {
        try {
            entityManager
                    .createNativeQuery(
                            "UPDATE escrow_invoice_failures SET status = 'RESOLVED', "
                                    + "resolved_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) "
                                    + "WHERE escrow_hold_id = :escrowHoldId AND status <> 'RESOLVED'")
                    .setParameter("escrowHoldId", escrowHoldId)
                    .executeUpdate();
        } catch (RuntimeException e) {
            log.error("Failed to resolve escrow_invoice_failures marker for escrow hold {}", escrowHoldId, e);
        }
    }

    /**
     * [F-0390 D1] Scheduled retry — the "(b)" half of the brief, following {@code EmailWorker}'s
     * {@code @Scheduled}/{@code @SchedulerLock} shape (same ShedLock table, V68__shedlock.sql; a
     * new distinct lock name needs no migration of its own). Polls every 5 minutes rather than
     * {@code EmailWorker}'s 30 seconds — these are low-volume, high-value operational failures (a
     * missing creator profile, a persistent R2 outage) that usually need a human to actually fix
     * the underlying cause, not a transient blip that clears itself in seconds.
     *
     * <p>Kept as a single {@code @Transactional} method rather than {@code EmailWorker}'s
     * claim/send/mark three-phase split — batch size and volume here are both far smaller (tens,
     * not up to 50 per 30s), so holding one DB connection for the batch's duration is an accepted,
     * documented simplification, not an oversight.
     */
    @Scheduled(fixedDelay = 300000) // 5 minutes
    @SchedulerLock(
            name = "CampaignServiceInvoiceFailureRetry_process",
            lockAtMostFor = "PT4M",
            lockAtLeastFor = "PT10S")
    @Transactional
    public void retryFailedInvoiceFailures() {
        List<Object[]> pending = fetchPendingFailures();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Retrying {} pending Doc#2 invoice failures", pending.size());
        for (Object[] row : pending) {
            String failureRowId = (String) row[0];
            String escrowHoldId = (String) row[1];
            String collaborationId = (String) row[2];
            String ledgerCreditLegId = (String) row[3];
            String failureStage = (String) row[4];
            int retryCountBeforeAttempt = ((Number) row[5]).intValue();
            try {
                if ("PDF_RENDER".equals(failureStage)) {
                    retryPdfRender(escrowHoldId);
                } else {
                    retryInvoiceCreate(escrowHoldId, collaborationId, ledgerCreditLegId);
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Doc#2 invoice failure retry attempt failed again: escrowHoldId={}, stage={}, attempt={}",
                        escrowHoldId,
                        failureStage,
                        retryCountBeforeAttempt + 1,
                        e);
                applyBackoffOrExhaust(failureRowId, retryCountBeforeAttempt, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> fetchPendingFailures() {
        return entityManager
                .createNativeQuery(
                        "SELECT id, escrow_hold_id, collaboration_id, ledger_credit_leg_id, failure_stage,"
                                + " retry_count "
                                + "FROM escrow_invoice_failures "
                                + "WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <="
                                + " CURRENT_TIMESTAMP(3)) "
                                + "ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED")
                .setParameter("limit", FAILURE_RETRY_BATCH_SIZE)
                .getResultList();
    }

    /** Re-attempts the PDF/R2 half only — the invoice row already exists (see its caller). */
    private void retryPdfRender(String escrowHoldId) {
        CampaignServiceInvoice invoice = invoiceRepository.findByEscrowHoldId(escrowHoldId).orElse(null);
        if (invoice == null) {
            // Row vanished (should never happen) — nothing left to retry against.
            markFailureResolved(escrowHoldId);
            return;
        }
        if (invoice.getPdfR2Key() != null) {
            // Already resolved by some other path (e.g. a manual backfill) — clear the marker.
            markFailureResolved(escrowHoldId);
            return;
        }
        Campaign campaign =
                campaignRepository
                        .findById(invoice.getCampaignId())
                        .orElseThrow(() -> new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.CONFLICT));
        Workspace brandWorkspace =
                workspaceRepository
                        .findById(invoice.getBrandWorkspaceId())
                        .orElseThrow(
                                () -> new ApiException("WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.CONFLICT));
        CreatorProfile creator =
                creatorProfileRepository
                        .findByUserId(invoice.getCreatorUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.CONFLICT));

        byte[] pdfBytes = pdfService.render(invoice, campaign, brandWorkspace, creator);
        if (!r2StorageService.isAvailable()) {
            throw new IllegalStateException("R2 not configured");
        }
        String objectKey = PDF_KEY_PREFIX + invoice.getId() + ".pdf";
        r2StorageService.putBytes(objectKey, pdfBytes, "application/pdf");
        invoice.setPdfR2Key(objectKey);
        invoiceRepository.save(invoice);
        markFailureResolved(escrowHoldId);
    }

    /**
     * Re-attempts full invoice creation. Calls {@link #self} (never {@code this}) — see {@link
     * #self}'s javadoc for why the proxy indirection is required for {@link #createAtRelease}'s
     * {@code REQUIRES_NEW} to actually apply.
     */
    private void retryInvoiceCreate(String escrowHoldId, String collaborationId, String ledgerCreditLegId) {
        EscrowHold hold =
                escrowHoldRepository
                        .findById(escrowHoldId)
                        .orElseThrow(() -> new IllegalStateException("Escrow hold vanished: " + escrowHoldId));
        Collaboration collaboration =
                collaborationRepository
                        .findById(collaborationId)
                        .orElseThrow(() -> new IllegalStateException("Collaboration vanished: " + collaborationId));

        self.createAtRelease(hold, collaboration, ledgerCreditLegId);

        // createAtRelease never re-throws a PDF-stage failure (it catches and records its own
        // PDF_RENDER marker internally) — only resolve THIS marker if the row now exists AND is
        // fully whole, so a same-call INVOICE_CREATE -> PDF_RENDER transition isn't clobbered.
        invoiceRepository
                .findByEscrowHoldId(escrowHoldId)
                .filter(inv -> inv.getPdfR2Key() != null)
                .ifPresent(inv -> markFailureResolved(escrowHoldId));
    }

    private void applyBackoffOrExhaust(String failureRowId, int retryCountBeforeAttempt, String errorMessage) {
        int newRetryCount = retryCountBeforeAttempt + 1;
        boolean exhausted = newRetryCount >= MAX_FAILURE_RETRIES;
        String truncatedError =
                errorMessage == null ? null : errorMessage.substring(0, Math.min(errorMessage.length(), 512));
        try {
            entityManager
                    .createNativeQuery(
                            "UPDATE escrow_invoice_failures SET retry_count = :retryCount, status = :status, "
                                    + "next_retry_at = :nextRetryAt, error_message = :errorMessage, "
                                    + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = :id")
                    .setParameter("retryCount", newRetryCount)
                    .setParameter("status", exhausted ? "EXHAUSTED" : "PENDING")
                    .setParameter(
                            "nextRetryAt",
                            exhausted ? null : Timestamp.from(Instant.now().plus(backoffFor(newRetryCount))))
                    .setParameter("errorMessage", truncatedError)
                    .setParameter("id", failureRowId)
                    .executeUpdate();
        } catch (RuntimeException e) {
            log.error("Failed to update escrow_invoice_failures retry state for row {}", failureRowId, e);
        }
        if (exhausted) {
            log.error(
                    "Doc#2 invoice failure permanently exhausted retries (row {}, {} attempts) — requires"
                            + " manual backfill",
                    failureRowId,
                    newRetryCount);
        }
    }

    /** 5m, 15m, 45m, 135m — same exponential shape as {@code EmailOutbox.markFailed}, scaled to minutes. */
    private static Duration backoffFor(int retryCount) {
        long minutes = (long) (5 * Math.pow(3, retryCount - 1));
        return Duration.ofMinutes(minutes);
    }

    /** Ownership-checked PDF render — creator side (own invoices only). */
    @Transactional(readOnly = true)
    public byte[] getInvoicePdfForCreator(String invoiceId, String creatorUserId) {
        CampaignServiceInvoice invoice = requireForCreator(invoiceId, creatorUserId);
        return renderForInvoice(invoice);
    }

    /** Ownership-checked PDF render — brand side (own workspace's billed invoices only). */
    @Transactional(readOnly = true)
    public byte[] getInvoicePdfForBrand(String invoiceId, String brandWorkspaceId) {
        CampaignServiceInvoice invoice = requireForBrand(invoiceId, brandWorkspaceId);
        return renderForInvoice(invoice);
    }

    private byte[] renderForInvoice(CampaignServiceInvoice invoice) {
        Campaign campaign =
                campaignRepository
                        .findById(invoice.getCampaignId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));
        Workspace brandWorkspace =
                workspaceRepository
                        .findById(invoice.getBrandWorkspaceId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));
        CreatorProfile creator =
                creatorProfileRepository
                        .findByUserId(invoice.getCreatorUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "Creator profile not found",
                                                HttpStatus.NOT_FOUND));
        return pdfService.render(invoice, campaign, brandWorkspace, creator);
    }

    public java.util.List<CampaignServiceInvoice> getInvoicesForCreator(String creatorUserId) {
        return invoiceRepository.findByCreatorUserIdOrderByIssuedAtDesc(creatorUserId);
    }

    public java.util.List<CampaignServiceInvoice> getInvoicesForBrand(String brandWorkspaceId) {
        return invoiceRepository.findByBrandWorkspaceIdOrderByIssuedAtDesc(brandWorkspaceId);
    }

    public String getInvoiceDownloadUrl(CampaignServiceInvoice invoice) {
        if (invoice.getPdfR2Key() == null || invoice.getPdfR2Key().isBlank() || !r2StorageService.isAvailable()) {
            return null;
        }
        return r2StorageService.presignGet(invoice.getPdfR2Key()).uploadUrl();
    }

    private CampaignServiceInvoice requireForCreator(String invoiceId, String creatorUserId) {
        return invoiceRepository
                .findByIdAndCreatorUserId(invoiceId, creatorUserId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "INVOICE_NOT_FOUND", "Invoice not found", HttpStatus.NOT_FOUND));
    }

    private CampaignServiceInvoice requireForBrand(String invoiceId, String brandWorkspaceId) {
        return invoiceRepository
                .findByIdAndBrandWorkspaceId(invoiceId, brandWorkspaceId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "INVOICE_NOT_FOUND", "Invoice not found", HttpStatus.NOT_FOUND));
    }
}
