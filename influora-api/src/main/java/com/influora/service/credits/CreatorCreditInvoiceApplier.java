package com.influora.service.credits;

import com.influora.config.CompanyTaxProperties;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.enums.HsnSacAppliesTo;
import com.influora.domain.enums.InvoiceNumberSeriesType;
import com.influora.repository.CreatorCreditOrderRepository;
import com.influora.service.GstSplitUtil;
import com.influora.service.HsnSacCodeService;
import com.influora.service.InvoiceNumberService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.4/§7.5 B21) — computes and writes the statutory invoice number
 * + GST breakup for an already-CREDITED order, strictly best-effort (F-14/F-22).
 *
 * <p>This used to run synchronously inside {@code CreatorCreditOrderService#confirmPaid}'s own
 * transaction, wrapped in a try/catch for {@code RuntimeException}. That was not enough: {@link
 * InvoiceNumberService#generateNext} is itself {@code @Transactional} (REQUIRED), so it joins
 * {@code confirmPaid}'s transaction rather than starting a new one. Any {@code RuntimeException}
 * it throws (a {@code createSeries} first-issuance race, a lock-wait timeout) propagates through
 * that joined transactional boundary first and marks the WHOLE transaction rollback-only —
 * catching the exception one frame up does not undo that. {@code confirmPaid}'s own commit then
 * failed with {@code UnexpectedRollbackException}, which rolled back the just-minted PAID credit
 * grant. Every retry (webhook redelivery, {@code /verify}, the reconciliation job) repeated the
 * same risk, and because {@code grantWelcome}'s claims are permanent (REQUIRES_NEW, already
 * committed), a creator's welcome 40 could be lost for good even though her money was taken.
 *
 * <p>This bean is instead invoked ONLY via {@link com.influora.common.AfterCommit#run} — after
 * {@code confirmPaid}'s transaction has already committed and the credit is durably in the
 * database. It reloads the order fresh (in its OWN transaction) and every exception here is
 * caught internally: nothing it does can ever roll back a purchase or a welcome grant.
 */
@Service
public class CreatorCreditInvoiceApplier {

    private static final Logger log = LoggerFactory.getLogger(CreatorCreditInvoiceApplier.class);

    private final CreatorCreditOrderRepository orderRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final HsnSacCodeService hsnSacCodeService;
    private final CompanyTaxProperties companyTaxProperties;

    public CreatorCreditInvoiceApplier(
            CreatorCreditOrderRepository orderRepository,
            InvoiceNumberService invoiceNumberService,
            HsnSacCodeService hsnSacCodeService,
            CompanyTaxProperties companyTaxProperties) {
        this.orderRepository = orderRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.hsnSacCodeService = hsnSacCodeService;
        this.companyTaxProperties = companyTaxProperties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyBestEffort(String orderId) {
        CreatorCreditOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.error(
                    "CreatorCreditInvoiceApplier: order {} no longer exists — cannot apply invoice"
                            + " numbering",
                    orderId);
            return;
        }
        if (order.getInvoiceNumber() != null) {
            return; // already applied (a redelivered event, or a manual backfill) — idempotent no-op
        }
        try {
            String invoiceNumber = invoiceNumberService.generateNext(InvoiceNumberSeriesType.CREATOR_CREDITS, null);
            BigDecimal amountRupees =
                    BigDecimal.valueOf(order.getAmountPaise()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxable = amountRupees.divide(new BigDecimal("1.18"), 2, RoundingMode.HALF_UP);
            BigDecimal totalGst = amountRupees.subtract(taxable);
            // Creators carry no GSTIN on file today — an unknown customer state conservatively
            // defaults to IGST (GstSplitUtil's documented behaviour for a null customerGstin).
            GstSplitUtil.GstSplit split = GstSplitUtil.compute(companyTaxProperties.getGstin(), null, totalGst);
            String hsnSacCode = hsnSacCodeService.resolveCode(HsnSacAppliesTo.CREATOR_CREDITS);
            order.applyInvoice(
                    invoiceNumber,
                    toPaise(taxable),
                    toPaise(split.cgst()),
                    toPaise(split.sgst()),
                    toPaise(split.igst()),
                    hsnSacCode,
                    // Q6 residual (SPEC.md): B2C place-of-supply is a CA question this repo does
                    // not have an answer to yet — left null rather than guessed.
                    null);
            orderRepository.save(order);
        } catch (RuntimeException e) {
            log.error(
                    "CreatorCreditInvoiceApplier: failed to compute the statutory invoice"
                            + " number/GST breakup for order {} — the order stays CREDITED (missing"
                            + " invoice_number/GST breakup requires manual follow-up before this can be"
                            + " relied on for a filed return)",
                    orderId,
                    e);
        }
    }

    private static int toPaise(BigDecimal rupees) {
        return rupees.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
