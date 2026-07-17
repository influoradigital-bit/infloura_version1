package com.influora.config;

import jakarta.annotation.PostConstruct;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * INV-2 (2026-07-15, Priya-approved scope): {@link CompanyTaxProperties#getGstin()} previously had
 * no boot-time validation anywhere, so a real deploy could silently boot with the committed
 * placeholder {@code REPLACE_WITH_REAL_GSTIN}. Two failure modes that placeholder caused: (a) it
 * printed verbatim as the supplier GSTIN on customer-facing tax invoices ({@code
 * CommissionInvoicePdfService}); (b) far worse and silent, {@code GstSplitUtil.stateCode()} took
 * {@code gstin.substring(0, 2)} -> {@code "RE"}, which never equals any real customer state code,
 * so {@code GstSplitUtil.compute} always fell through to its "unknown state" default and taxed
 * EVERY invoice as inter-state IGST — including genuinely intra-state ones, which should have been
 * split CGST+SGST. The total tax collected was correct (so nothing looked wrong downstream) but the
 * tax head was wrong on every line, which breaks the brand's ability to claim input tax credit.
 *
 * <p>Gated on {@link InfluoraEnvironment#isDev()} (Spring active-profile based, matching the W0-3
 * fix — the current, non-{@code influora.env}-based house convention for "is this a real deploy")
 * rather than reinventing a third gating mechanism: fails closed (throws, aborts startup) outside
 * the {@code dev} profile; {@code dev} only logs a WARN and continues so a fresh dev checkout still
 * boots with zero config.
 */
@Configuration
public class CompanyTaxStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(CompanyTaxStartupValidator.class);

    /** Must match the literal {@code influora.company.gstin} placeholder default in application.yml. */
    static final String KNOWN_PLACEHOLDER_GSTIN = "REPLACE_WITH_REAL_GSTIN";

    /**
     * Standard 15-character GSTIN shape: 2-digit state code, 10-char PAN (5 letters + 4 digits + 1
     * letter), 1-digit entity/registration number, the fixed literal {@code Z}, and a 1-char
     * alphanumeric checksum. Case-sensitive upper-case, matching how GSTINs are always issued/printed.
     */
    static final Pattern GSTIN_PATTERN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    private final CompanyTaxProperties companyTaxProperties;
    private final InfluoraEnvironment environment;

    public CompanyTaxStartupValidator(
            CompanyTaxProperties companyTaxProperties, InfluoraEnvironment environment) {
        this.companyTaxProperties = companyTaxProperties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        String problem = checkGstin(companyTaxProperties.getGstin());
        if (problem == null) {
            return;
        }

        String message =
                "Company tax config validation failed: influora.company.gstin " + problem;

        if (environment.isDev()) {
            log.warn(message);
        } else {
            throw new IllegalStateException(message);
        }
    }

    /** @return a human-readable problem description, or {@code null} if the GSTIN is valid. */
    private static String checkGstin(String gstin) {
        if (gstin == null || gstin.isBlank()) {
            return "is missing";
        }
        String trimmed = gstin.trim();
        if (KNOWN_PLACEHOLDER_GSTIN.equals(trimmed)) {
            return "is still the committed placeholder (REPLACE_WITH_REAL_GSTIN)";
        }
        if (!GSTIN_PATTERN.matcher(trimmed).matches()) {
            return "is not a valid 15-character GSTIN";
        }
        return null;
    }
}
