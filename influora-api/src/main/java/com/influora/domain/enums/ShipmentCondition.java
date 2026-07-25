package com.influora.domain.enums;

/**
 * Creator-reported condition on receipt (wiki/decisions/shipment-backend-design-2026-07-24.md
 * §1) — separate from {@link ShipmentStatus} so a {@code RECEIVED} row still records whether it
 * arrived in good shape; {@code DAMAGED} here drives {@code ShipmentStatus.DAMAGED} on the same
 * row.
 */
public enum ShipmentCondition {
    GOOD,
    DAMAGED
}
