package com.influora.domain.enums;

/**
 * Where an {@code external_creators} row came from (T-CREATORCONNECT-0902,
 * V20260902120000__external_creators_connection_requests.sql).
 */
public enum ExternalCreatorSource {
    /** Flagged, dark client — {@code influora.meta.creator-marketplace.enabled}. */
    META_MARKETPLACE,
    /** Live today — a Business Discovery lookup by handle. */
    BUSINESS_DISCOVERY,
    /** Admin bulk import ({@code POST /admin/external-creators/import}). */
    ADMIN_IMPORT
}
