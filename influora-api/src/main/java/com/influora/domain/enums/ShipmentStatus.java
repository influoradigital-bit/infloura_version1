package com.influora.domain.enums;

/**
 * Product-seeding shipment lifecycle (wiki/decisions/shipment-backend-design-2026-07-24.md §2).
 * {@code AWAITING_ADDRESS} is a synthetic state — it is never persisted; {@link
 * com.influora.service.ShipmentService#getShipment} returns it when no {@code Shipment} row
 * exists yet for a collaboration. The row itself is lazily created as {@code ADDRESS_PROVIDED} on
 * first address submit.
 *
 * <pre>
 * AWAITING_ADDRESS -&gt; ADDRESS_PROVIDED -&gt; SHIPPED -&gt; RECEIVED
 *                                              \-&gt; DAMAGED -&gt; (re-ship) SHIPPED
 * </pre>
 */
public enum ShipmentStatus {
    AWAITING_ADDRESS,
    ADDRESS_PROVIDED,
    SHIPPED,
    RECEIVED,
    DAMAGED
}
