package com.influora.service.billing;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.CompanyTaxProperties;
import com.influora.domain.entity.Invoice;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.HsnSacAppliesTo;
import com.influora.domain.enums.InvoiceNumberSeriesType;
import com.influora.domain.enums.InvoiceStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.InvoiceRepository;
import com.influora.repository.PlanRepository;
import com.influora.repository.SubscriptionRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.GstSplitUtil;
import com.influora.service.HsnSacCodeService;
import com.influora.service.InvoiceNumberService;
import com.influora.service.InvoicePdfService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice reads + on-demand PDF rendering. Task 18 subscription-billing functional core, Phase 1.
 *
 * <p><b>PDF generation strategy (architectural call):</b> render on demand via {@link
 * InvoicePdfService#render} rather than requiring a pre-stored R2 object. No invoices exist yet
 * in Phase 1 (Razorpay subscription creation — the only thing that produces an {@link Invoice}
 * row — is Phase 2/4), so there is nothing to have pre-rendered; on-demand generation also closes
 * the "unwired renderer" gap immediately instead of leaving {@code InvoicePdfService} dead code
 * until Task 24. Once Task 24 adds R2 storage + {@code Invoice.pdfR2Key}, this method can prefer
 * the stored bytes and fall back to on-demand rendering, but that's out of scope here.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    /** Storage path prefix for generated invoice PDFs — mirrors {@code ContractService}'s {@code CONTRACT_PDF_KEY_PREFIX} convention. */
    private static final String INVOICE_PDF_KEY_PREFIX = "invoices/";

    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final WorkspaceRepository workspaceRepository;
    private final InvoicePdfService invoicePdfService;
    private final R2StorageService r2StorageService;
    private final InvoiceNumberService invoiceNumberService;
    private final HsnSacCodeService hsnSacCodeService;
    private final CompanyTaxProperties companyTaxProperties;

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            SubscriptionRepository subscriptionRepository,
            PlanRepository planRepository,
            WorkspaceRepository workspaceRepository,
            InvoicePdfService invoicePdfService,
            R2StorageService r2StorageService,
            InvoiceNumberService invoiceNumberService,
            HsnSacCodeService hsnSacCodeService,
            CompanyTaxProperties companyTaxProperties) {
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.workspaceRepository = workspaceRepository;
        this.invoicePdfService = invoicePdfService;
        this.r2StorageService = r2StorageService;
        this.invoiceNumberService = invoiceNumberService;
        this.hsnSacCodeService = hsnSacCodeService;
        this.companyTaxProperties = companyTaxProperties;
    }

    public List<Invoice> getInvoicesForWorkspace(String workspaceId) {
        return invoiceRepository.findByWorkspaceIdOrderByIssuedAtDesc(workspaceId);
    }

    /**
     * Ownership-checked PDF render: resolves the invoice by ID first, then verifies it belongs to
     * the caller's workspace (TECH-STACK.md rule #2 — never trust a path param alone).
     */
    public byte[] getInvoicePdf(String invoiceId, String workspaceId) {
        Invoice invoice =
                invoiceRepository
                        .findById(invoiceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVOICE_NOT_FOUND", "Invoice not found", HttpStatus.NOT_FOUND));
        if (!invoice.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException(
                    "FORBIDDEN", "This invoice does not belong to your workspace", HttpStatus.FORBIDDEN);
        }

        Subscription subscription =
                subscriptionRepository
                        .findById(invoice.getSubscriptionId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SUBSCRIPTION_NOT_FOUND",
                                                "Subscription for this invoice no longer exists",
                                                HttpStatus.NOT_FOUND));
        Plan plan =
                planRepository
                        .findById(subscription.getPlanId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "PLAN_NOT_FOUND", "Plan for this invoice no longer exists", HttpStatus.NOT_FOUND));
        Workspace workspace =
                workspaceRepository
                        .findById(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));

        return invoicePdfService.render(invoice, subscription, plan, workspace);
    }

    /**
     * Phase 4 (Task 24) — generates an {@link Invoice} row from a verified {@code
     * subscription.charged} webhook ({@code RazorpayWebhookController}), renders the PDF (mirrors
     * {@code ContractService#generateAndDeliverContractPdf}'s pattern exactly), and stores it to
     * R2. Wires the Phase 1 stub for real.
     *
     * <p><b>Amount is server-derived from the webhook payload, never recomputed/trusted from a
     * client (TECH-STACK.md rule #4).</b> {@code amountInPaise} is the verified webhook's own
     * {@code payload.payment.entity.amount} — the actual amount Razorpay charged — not {@code
     * Plan.priceInr}, since the two could in principle drift (proration, a manually-adjusted
     * Razorpay-side plan, etc.) and the payment processor's own callback is the more authoritative
     * source for "what was actually charged" per the same rule.
     *
     * <p><b>Idempotency:</b> the caller ({@code RazorpayWebhookController}) already wraps the
     * whole {@code subscription.charged} handling in {@code IdempotencyService.executeOnce} keyed
     * per exact webhook delivery, so a raw delivery replay never reaches this method twice. This
     * method adds a second, cheap defense-in-depth check keyed on {@code razorpayPaymentReference}
     * (defensive only — should never actually trigger given the layer above).
     *
     * @param razorpayPaymentReference the webhook's {@code payload.payment.entity.id} — stored in
     *     {@link Invoice#getRazorpayInvoiceId()} as the closest available Razorpay-side reference;
     *     Razorpay Subscriptions' {@code subscription.charged} payload carries a payment entity,
     *     not a separate Invoices-API invoice id, since checkout in this codebase uses the
     *     Subscriptions API ({@code RazorpayClient#createSubscription}), not the Invoices API.
     * @param periodStart/periodEnd the just-applied billing period from the webhook (falls back to
     *     the subscription row's own period if the webhook envelope carried none).
     * @param paidAt the webhook envelope's own {@code created_at}.
     */
    @Transactional
    public Invoice generateInvoiceFromWebhook(
            String workspaceId,
            String razorpayPaymentReference,
            long amountInPaise,
            Instant periodStart,
            Instant periodEnd,
            Instant paidAt) {
        if (razorpayPaymentReference != null) {
            Optional<Invoice> existing = invoiceRepository.findByRazorpayInvoiceId(razorpayPaymentReference);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Subscription subscription =
                subscriptionRepository
                        .findByWorkspaceId(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SUBSCRIPTION_NOT_FOUND",
                                                "No subscription found for workspace " + workspaceId
                                                        + " — cannot generate invoice",
                                                HttpStatus.NOT_FOUND));
        Plan plan =
                planRepository
                        .findById(subscription.getPlanId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "PLAN_NOT_FOUND", "Plan for this subscription no longer exists", HttpStatus.NOT_FOUND));
        Workspace workspace =
                workspaceRepository
                        .findById(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));

        Instant resolvedPaidAt = paidAt != null ? paidAt : Instant.now();

        Invoice invoice =
                Invoice.builder()
                        .id(Ulids.newUlid())
                        .subscriptionId(subscription.getId())
                        .workspaceId(workspaceId)
                        .razorpayInvoiceId(razorpayPaymentReference)
                        .amount((int) amountInPaise)
                        .status(InvoiceStatus.PAID)
                        .periodStart(periodStart != null ? periodStart : subscription.getCurrentPeriodStart())
                        .periodEnd(periodEnd != null ? periodEnd : subscription.getCurrentPeriodEnd())
                        .issuedAt(resolvedPaidAt)
                        .build();
        invoice.markPaid(resolvedPaidAt);
        applyD14GstBreakup(invoice, workspace);
        invoiceRepository.save(invoice);

        // Best-effort PDF render + R2 store, mirroring ContractService#generateAndDeliverContractPdf:
        // a rendering/storage hiccup must never roll back the already-persisted Invoice row (the
        // invoice is real and paid regardless of whether its PDF is ready yet) — logged, not thrown.
        try {
            byte[] pdfBytes = invoicePdfService.render(invoice, subscription, plan, workspace);
            if (r2StorageService.isAvailable()) {
                String objectKey = INVOICE_PDF_KEY_PREFIX + invoice.getId() + ".pdf";
                r2StorageService.putBytes(objectKey, pdfBytes, "application/pdf");
                invoice.setPdfR2Key(objectKey);
                invoiceRepository.save(invoice);
            } else {
                log.warn("R2 not configured — invoice PDF for {} generated but not stored", invoice.getId());
            }
        } catch (Exception e) {
            log.error(
                    "Invoice PDF generation/storage failed for invoice {} — invoice row was still recorded as PAID",
                    invoice.getId(),
                    e);
        }

        return invoice;
    }

    /**
     * D14 Wave 9 (Rohan build-flag #5) — statutory invoice number + CGST/SGST/IGST breakup + HSN/SAC,
     * computed FROM the already-set int-paise {@link Invoice#getAmount()} (rupees = amount/100,
     * GST-inclusive at the standard 18% rate). Best-effort, same discipline as the PDF-render block
     * below: a numbering/lookup failure must never block the webhook from recording the (already
     * genuinely paid) subscription charge — logged loudly, not thrown, leaving {@code
     * invoiceNumber} null on that one row rather than failing the whole webhook delivery.
     */
    private void applyD14GstBreakup(Invoice invoice, Workspace workspace) {
        try {
            String invoiceNumber =
                    invoiceNumberService.generateNext(InvoiceNumberSeriesType.SUBSCRIPTION, null);
            BigDecimal amountRupees =
                    BigDecimal.valueOf(invoice.getAmount()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
            BigDecimal baseAmount = amountRupees.divide(new BigDecimal("1.18"), 2, RoundingMode.HALF_UP);
            BigDecimal totalGst = amountRupees.subtract(baseAmount);
            GstSplitUtil.GstSplit split =
                    GstSplitUtil.compute(companyTaxProperties.getGstin(), workspace.getGstin(), totalGst);
            String hsnSacCode = hsnSacCodeService.resolveCode(HsnSacAppliesTo.SUBSCRIPTION);
            invoice.applyGstBreakup(invoiceNumber, baseAmount, split.cgst(), split.sgst(), split.igst(), hsnSacCode);
        } catch (RuntimeException e) {
            log.error(
                    "Failed to compute D14 GST breakup/statutory number for invoice {} — invoice row"
                            + " was still recorded as PAID (missing invoice_number/GST breakup requires"
                            + " manual follow-up before this can be relied on for a filed return)",
                    invoice.getId(),
                    e);
        }
    }

    /** Mints a fresh presigned download URL for an already-stored invoice PDF, or {@code null} if not yet stored. */
    public String getInvoiceDownloadUrl(Invoice invoice) {
        if (invoice.getPdfR2Key() == null || invoice.getPdfR2Key().isBlank() || !r2StorageService.isAvailable()) {
            return null;
        }
        return r2StorageService.presignGet(invoice.getPdfR2Key()).uploadUrl();
    }
}
