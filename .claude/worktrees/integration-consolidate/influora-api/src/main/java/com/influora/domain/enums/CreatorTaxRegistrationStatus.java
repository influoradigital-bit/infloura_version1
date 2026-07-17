package com.influora.domain.enums;

/**
 * D14-B (2026-07-15, Swapnil): creator GST onboarding is schema-captured but not hard-gated at
 * launch — enforcement is a runtime toggle pending Legal, not a schema constraint. This status is
 * therefore nullable-friendly at the entity layer (defaults to {@link #UNREGISTERED}).
 */
public enum CreatorTaxRegistrationStatus {
    UNREGISTERED,
    GST_REGISTERED,
    EXEMPT
}
