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
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.WorkspaceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

    private final CampaignServiceInvoiceRepository invoiceRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final HsnSacCodeService hsnSacCodeService;
    private final CampaignServiceInvoicePdfService pdfService;
    private final R2StorageService r2StorageService;
    private final CreatorInvoiceCodeService creatorInvoiceCodeService;

    public CampaignServiceInvoiceService(
            CampaignServiceInvoiceRepository invoiceRepository,
            CreatorProfileRepository creatorProfileRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            InvoiceNumberService invoiceNumberService,
            HsnSacCodeService hsnSacCodeService,
            CampaignServiceInvoicePdfService pdfService,
            R2StorageService r2StorageService,
            CreatorInvoiceCodeService creatorInvoiceCodeService) {
        this.invoiceRepository = invoiceRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.hsnSacCodeService = hsnSacCodeService;
        this.pdfService = pdfService;
        this.r2StorageService = r2StorageService;
        this.creatorInvoiceCodeService = creatorInvoiceCodeService;
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
        }

        return invoice;
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
