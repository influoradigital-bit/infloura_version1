package com.influora.domain.enums;

/**
 * Admin-side pipeline state of a Festival Box enquiry (T-FESTIVALBOX-0905).
 *
 * <p>Deliberately a flat set with no enforced transition graph: this is a sales pipeline an
 * account manager moves by hand, and a brand can go straight from {@code NEW} to {@code WON}
 * after one call. The one rule the service does enforce is that {@code NEW} is only ever the
 * value assigned at insert — an admin cannot move a row back to it, because "never touched"
 * would then be indistinguishable from "touched and reset".
 */
public enum FestivalEnquiryStatus {
    /** Just submitted from the public page. The only status the public endpoint can write. */
    NEW,
    /** An account manager has reached out. */
    CONTACTED,
    /** Reached out, and the enquiry is a real fit for an edition slot. */
    QUALIFIED,
    /** Converted — the slot was booked (brand) or the creator joined the roster. */
    WON,
    /** Not proceeding. */
    LOST
}
