package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.InvoiceNumberSequence;
import com.influora.domain.enums.InvoiceNumberSeriesType;
import com.influora.repository.InvoiceNumberSequenceRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D14-C (2026-07-15, Rohan) statutory invoice numbering. Generates a continuous,
 * per-financial-year, per-document-type sequential number — never a ULID, never a global counter
 * for the per-creator series.
 *
 * <p><b>Atomicity contract:</b> callers MUST invoke {@link #generateNext} inside the SAME {@code
 * @Transactional} boundary as the ledger posting + invoice row it numbers (this method itself
 * opens a nested/participating transaction that row-locks the sequence, but the caller's own
 * gating on {@code LedgerPostingResult} — see the D14 build ticket's Rohan build-flag #2 — is what
 * prevents a retry from double-issuing a statutory number: this service is never called except
 * from inside that already-idempotency-checked path).
 *
 * <p>Format (D14-C, decided 2026-07-15):
 *
 * <ul>
 *   <li>SUBSCRIPTION: {@code INF/SUB/<FY>/<seq:06d>}
 *   <li>COMMISSION_BRAND: {@code INF/CMB/<FY>/<seq:06d>}
 *   <li>COMMISSION_CREATOR: {@code INF/CMC/<FY>/<seq:06d>}
 *   <li>CAMPAIGN_SERVICE: {@code <creatorInvoiceCode>/<FY>/<seq:06d>}
 * </ul>
 *
 * FY is the Indian financial year (Apr 1 – Mar 31), computed from IST wall-clock date, formatted
 * {@code "2026-27"}.
 */
@Service
public class InvoiceNumberService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final InvoiceNumberSequenceRepository sequenceRepository;

    public InvoiceNumberService(InvoiceNumberSequenceRepository sequenceRepository) {
        this.sequenceRepository = sequenceRepository;
    }

    /**
     * @param seriesType which statutory series to draw from
     * @param creatorInvoiceCode required (non-blank) for {@link InvoiceNumberSeriesType#CAMPAIGN_SERVICE};
     *     must be {@code null} for every other series type (those are platform-wide, not per-creator)
     */
    @Transactional
    public String generateNext(InvoiceNumberSeriesType seriesType, String creatorInvoiceCode) {
        boolean perCreator = seriesType == InvoiceNumberSeriesType.CAMPAIGN_SERVICE;
        if (perCreator && (creatorInvoiceCode == null || creatorInvoiceCode.isBlank())) {
            throw new ApiException(
                    "CREATOR_INVOICE_CODE_REQUIRED",
                    "A creatorInvoiceCode is required to number a CAMPAIGN_SERVICE invoice — the"
                            + " creator has not been assigned a statutory series code yet",
                    HttpStatus.CONFLICT);
        }
        if (!perCreator && creatorInvoiceCode != null) {
            throw new ApiException(
                    "INVALID_INVOICE_NUMBER_REQUEST",
                    "creatorInvoiceCode must be null for platform-wide series " + seriesType,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String fiscalYear = currentFiscalYear();
        InvoiceNumberSequence sequence =
                perCreator
                        ? requireOrCreatePerCreator(seriesType, fiscalYear, creatorInvoiceCode)
                        : requireOrCreateGlobal(seriesType, fiscalYear);

        int seq = sequence.consumeNext();
        sequenceRepository.save(sequence);

        String prefix = perCreator ? creatorInvoiceCode : platformPrefix(seriesType);
        return prefix + "/" + fiscalYear + "/" + String.format("%06d", seq);
    }

    /** Indian FY (Apr 1 cutoff), formatted {@code "2026-27"}. */
    public static String currentFiscalYear() {
        LocalDate today = LocalDate.now(IST);
        int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        int endYearShort = (startYear + 1) % 100;
        return startYear + "-" + String.format("%02d", endYearShort);
    }

    private static String platformPrefix(InvoiceNumberSeriesType type) {
        return switch (type) {
            case SUBSCRIPTION -> "INF/SUB";
            case COMMISSION_BRAND -> "INF/CMB";
            case COMMISSION_CREATOR -> "INF/CMC";
            case CAMPAIGN_SERVICE ->
                    throw new IllegalStateException("CAMPAIGN_SERVICE is a per-creator series, not platform-wide");
        };
    }

    private InvoiceNumberSequence requireOrCreateGlobal(
            InvoiceNumberSeriesType seriesType, String fiscalYear) {
        return sequenceRepository
                .findForUpdateGlobal(seriesType, fiscalYear)
                .orElseGet(() -> createSeries(seriesType, fiscalYear, null));
    }

    private InvoiceNumberSequence requireOrCreatePerCreator(
            InvoiceNumberSeriesType seriesType, String fiscalYear, String creatorInvoiceCode) {
        return sequenceRepository
                .findForUpdatePerCreator(seriesType, fiscalYear, creatorInvoiceCode)
                .orElseGet(() -> createSeries(seriesType, fiscalYear, creatorInvoiceCode));
    }

    /**
     * First-use-per-(series,FY[,creator]) row creation. A concurrent first-issuance race is caught
     * by the table's composite unique constraint (see migration) and re-fetched-and-locked rather
     * than allowed to insert a duplicate series row.
     */
    private InvoiceNumberSequence createSeries(
            InvoiceNumberSeriesType seriesType, String fiscalYear, String creatorInvoiceCode) {
        InvoiceNumberSequence created =
                InvoiceNumberSequence.newSeries(Ulids.newUlid(), seriesType, fiscalYear, creatorInvoiceCode);
        try {
            return sequenceRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException raced) {
            Optional<InvoiceNumberSequence> existing =
                    creatorInvoiceCode == null
                            ? sequenceRepository.findForUpdateGlobal(seriesType, fiscalYear)
                            : sequenceRepository.findForUpdatePerCreator(seriesType, fiscalYear, creatorInvoiceCode);
            return existing.orElseThrow(
                    () ->
                            new ApiException(
                                    "INVOICE_NUMBER_SEQUENCE_CONFLICT",
                                    "Failed to initialize invoice number sequence",
                                    HttpStatus.CONFLICT));
        }
    }
}
