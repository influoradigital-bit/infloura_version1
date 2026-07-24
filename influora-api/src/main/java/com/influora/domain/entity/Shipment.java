package com.influora.domain.entity;

import com.influora.domain.enums.ShipmentCondition;
import com.influora.domain.enums.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Product-seeding shipment — 1:1 with {@link Collaboration} ({@code UNIQUE(collaboration_id)}),
 * lazily created as {@code ADDRESS_PROVIDED} on the creator's first address submit (see {@link
 * com.influora.service.ShipmentService}). wiki/decisions/shipment-backend-design-2026-07-24.md
 * §1.
 *
 * <p>Re-ships do NOT create a new row — a {@code DAMAGED} shipment is re-shipped by overwriting
 * tracking via {@link #markShipped} and transitioning back to {@code SHIPPED} on this same row.
 *
 * <p>Address is flat columns (not embedded JSON) — a fixed, bounded value object with per-field
 * validation, matching the {@code CreatorBankAccount} / tax-identity flat-column precedent. Never
 * load this entity by its own {@code id} from a client-supplied path/body param — callers must
 * authorize the owning {@link Collaboration} first (via the existing {@code
 * findByIdAndCreatorId}/{@code findByIdAndWorkspaceId} finders), then look this row up by {@code
 * collaboration_id}. That ordering is the whole IDOR guard for this entity.
 */
@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "collaboration_id", nullable = false, length = 26, unique = true)
    private String collaborationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ShipmentStatus status;

    @Column(name = "recipient_name", nullable = false, length = 200)
    private String recipientName;

    @Column(name = "address_line1", nullable = false, length = 300)
    private String addressLine1;

    @Column(name = "address_line2", length = 300)
    private String addressLine2;

    @Column(name = "city", nullable = false, length = 120)
    private String city;

    @Column(name = "state", nullable = false, length = 120)
    private String state;

    @Column(name = "pincode", nullable = false, length = 12)
    private String pincode;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    /** Brand-supplied on mark-shipped; the sole source of the {@code productName} event field. */
    @Column(name = "product_name", length = 300)
    private String productName;

    @Column(name = "carrier", length = 120)
    private String carrier;

    @Column(name = "tracking_number", length = 120)
    private String trackingNumber;

    @Column(name = "tracking_url", length = 500)
    private String trackingUrl;

    /** Creator's note on receipt, especially when {@link #receivedCondition} is {@code DAMAGED}. */
    @Column(name = "condition_note", columnDefinition = "TEXT")
    private String conditionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "received_condition", length = 16)
    private ShipmentCondition receivedCondition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shipment() {}

    /** Lazily creates the row on the creator's first address submit — status starts {@code ADDRESS_PROVIDED}. */
    public static Shipment createWithAddress(
            String id,
            String collaborationId,
            String recipientName,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String pincode,
            String phone,
            Instant now) {
        Shipment shipment = new Shipment();
        shipment.id = id;
        shipment.collaborationId = collaborationId;
        shipment.status = ShipmentStatus.ADDRESS_PROVIDED;
        shipment.recipientName = recipientName;
        shipment.addressLine1 = addressLine1;
        shipment.addressLine2 = addressLine2;
        shipment.city = city;
        shipment.state = state;
        shipment.pincode = pincode;
        shipment.phone = phone;
        shipment.createdAt = now;
        shipment.updatedAt = now;
        return shipment;
    }

    /** Re-submitting the address (e.g. before SHIPPED) overwrites the address fields on the same row. */
    public void updateAddress(
            String recipientName,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String pincode,
            String phone,
            Instant now) {
        this.recipientName = recipientName;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.city = city;
        this.state = state;
        this.pincode = pincode;
        this.phone = phone;
        this.status = ShipmentStatus.ADDRESS_PROVIDED;
        this.updatedAt = now;
    }

    /** ADDRESS_PROVIDED or DAMAGED -> SHIPPED. Guard (can't ship before an address exists) lives in ShipmentService. */
    public void markShipped(
            String carrier, String trackingNumber, String trackingUrl, String productName, Instant now) {
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.trackingUrl = trackingUrl;
        this.productName = productName;
        this.status = ShipmentStatus.SHIPPED;
        this.updatedAt = now;
    }

    /** SHIPPED -> RECEIVED (GOOD) or DAMAGED. Guard (can't confirm before SHIPPED) lives in ShipmentService. */
    public void confirmReceipt(ShipmentCondition condition, String conditionNote, Instant now) {
        this.receivedCondition = condition;
        this.conditionNote = conditionNote;
        this.status = condition == ShipmentCondition.DAMAGED ? ShipmentStatus.DAMAGED : ShipmentStatus.RECEIVED;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPincode() {
        return pincode;
    }

    public String getPhone() {
        return phone;
    }

    public String getProductName() {
        return productName;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getTrackingUrl() {
        return trackingUrl;
    }

    public String getConditionNote() {
        return conditionNote;
    }

    public ShipmentCondition getReceivedCondition() {
        return receivedCondition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
