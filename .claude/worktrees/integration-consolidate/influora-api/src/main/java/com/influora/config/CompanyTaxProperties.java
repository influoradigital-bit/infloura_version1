package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Influora's own tax identity, used as the supplier-of-record on Doc#1 (subscription) and Doc#3
 * (platform commission) invoices — the documents where Influora itself is the supplier. Never
 * hardcoded in a PDF renderer; sourced here so ops can set the real registered values via env vars
 * without a code change.
 *
 * <p><b>INV-2 (2026-07-15, Priya):</b> {@code gstin} still defaults to the placeholder
 * {@code REPLACE_WITH_REAL_GSTIN} committed in {@code application.yml}, but it is no longer
 * silently trusted — {@link CompanyTaxStartupValidator} refuses to boot the {@code prod} profile
 * on a blank, still-placeholder, or malformed GSTIN (warns only outside {@code prod}). Before this
 * fix the placeholder both printed on customer-facing tax invoices and, worse, silently drove
 * {@link com.influora.service.GstSplitUtil#stateCode} to derive a bogus 2-char state prefix
 * ({@code "RE"}) that never matched a real customer GSTIN, so every intra-state invoice was
 * incorrectly taxed IGST instead of CGST+SGST.
 *
 * <p>There used to be a separate {@code stateCode} field/{@code state-code} config key here for
 * the CGST+SGST-vs-IGST split. It was deleted (INV-2): it had zero callers anywhere in the
 * codebase, so setting {@code INFLUORA_COMPANY_STATE_CODE} could never actually have fixed the
 * IGST-misclassification bug above — {@link com.influora.service.GstSplitUtil} always derives the
 * state purely from the first two digits of {@link #getGstin()}, which is the single correct
 * source of truth. Keeping a config knob that does nothing was a trap for whoever tried to
 * remediate this next.
 */
@ConfigurationProperties(prefix = "influora.company")
public class CompanyTaxProperties {

    private String legalName = "Influora Technologies Pvt. Ltd.";
    private String gstin = "";
    private String registeredAddress = "";

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getGstin() {
        return gstin;
    }

    public void setGstin(String gstin) {
        this.gstin = gstin;
    }

    public String getRegisteredAddress() {
        return registeredAddress;
    }

    public void setRegisteredAddress(String registeredAddress) {
        this.registeredAddress = registeredAddress;
    }
}
