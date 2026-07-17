package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Influora's own tax identity, used as the supplier-of-record on Doc#1 (subscription) and Doc#3
 * (platform commission) invoices — the documents where Influora itself is the supplier. Never
 * hardcoded in a PDF renderer; sourced here so ops can set the real registered values via env vars
 * without a code change. {@code gstin}/{@code stateCode} are placeholder defaults — CA/Rohan to
 * confirm before any of these documents are relied on for a real filed return (D14 spec, Rohan
 * build-flag #4/#5).
 */
@ConfigurationProperties(prefix = "influora.company")
public class CompanyTaxProperties {

    private String legalName = "Influora Technologies Pvt. Ltd.";
    private String gstin = "";
    /** GSTIN state code (first 2 digits) — used for CGST+SGST (intra-state) vs IGST (inter-state) split. */
    private String stateCode = "";
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

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public String getRegisteredAddress() {
        return registeredAddress;
    }

    public void setRegisteredAddress(String registeredAddress) {
        this.registeredAddress = registeredAddress;
    }
}
