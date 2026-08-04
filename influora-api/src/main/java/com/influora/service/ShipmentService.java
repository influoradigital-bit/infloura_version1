package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.TextSanitizer;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Shipment;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.ShipmentCondition;
import com.influora.domain.enums.ShipmentStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.ShipmentRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.event.ShipmentCreatedEvent;
import com.influora.service.notification.event.ShipmentReceivedEvent;
import com.influora.web.dto.shipment.ShipmentDtos.ConfirmReceiptRequest;
import com.influora.web.dto.shipment.ShipmentDtos.MarkShippedRequest;
import com.influora.web.dto.shipment.ShipmentDtos.ShipmentResponse;
import com.influora.web.dto.shipment.ShipmentDtos.ShippingAddressRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product-seeding shipment / confirm-receipt (wiki/decisions/shipment-backend-design-2026-07-24.md
 * — Priya, 2026-07-24). Deliberately a sibling service to {@link DealService}, not folded into
 * it (design doc §4) — same trust-boundary finders, own state machine.
 *
 * <p><b>AuthZ (design doc §5, CRITICAL):</b> every entrypoint here resolves identity through
 * {@link CreatorContextService}/{@link BrandContextService} and authorizes the {@link
 * Collaboration} via the exact existing {@code CollaborationRepository.findByIdAndCreatorId} /
 * {@code findByIdAndWorkspaceId} finders — the same ones {@code DealService.requireOwnedCollaboration}
 * uses. The {@link Shipment} row itself is NEVER looked up by its own id from a client-supplied
 * param; it is always resolved via {@code shipmentRepository.findByCollaborationId} AFTER the
 * collaboration ownership check has already passed. That ordering is the whole IDOR guard.
 */
@Service
public class ShipmentService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    private final CollaborationRepository collaborationRepository;
    private final CreatorContextService creatorContext;
    private final BrandContextService brandContext;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final ShipmentRepository shipmentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final DealMessageRepository dealMessageRepository;

    public ShipmentService(
            CollaborationRepository collaborationRepository,
            CreatorContextService creatorContext,
            BrandContextService brandContext,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            CreatorProfileRepository creatorProfileRepository,
            ShipmentRepository shipmentRepository,
            ApplicationEventPublisher eventPublisher,
            DealMessageRepository dealMessageRepository) {
        this.collaborationRepository = collaborationRepository;
        this.creatorContext = creatorContext;
        this.brandContext = brandContext;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.shipmentRepository = shipmentRepository;
        this.eventPublisher = eventPublisher;
        this.dealMessageRepository = dealMessageRepository;
    }

    /** POST /deals/{id}/shipping-address — creator. Lazy-creates the row; rejected once SHIPPED. */
    @Transactional
    public ShipmentResponse submitAddress(AuthPrincipal principal, String dealId, ShippingAddressRequest body) {
        Collaboration collaboration = requireCreatorCollaboration(principal, dealId);
        Shipment shipment = shipmentRepository.findByCollaborationId(collaboration.getId()).orElse(null);

        String recipientName = TextSanitizer.sanitizePlainText(body.recipientName());
        String addressLine1 = TextSanitizer.sanitizePlainText(body.addressLine1());
        String addressLine2 = TextSanitizer.sanitizePlainText(body.addressLine2());
        String city = TextSanitizer.sanitizePlainText(body.city());
        String state = TextSanitizer.sanitizePlainText(body.state());
        String pincode = body.pincode().trim();
        String phone = body.phone().trim();
        Instant now = Instant.now();

        if (shipment == null) {
            // UNIQUE(collaboration_id) race guard (design doc risk #5) -- two concurrent first
            // submits can both pass this null-check before either commits; the DB unique
            // constraint is the real guard, this save/catch just turns the loser's race into a
            // clean retry against the row the winner just created instead of a 500.
            shipment =
                    Shipment.createWithAddress(
                            Ulids.newUlid(),
                            collaboration.getId(),
                            recipientName,
                            addressLine1,
                            addressLine2,
                            city,
                            state,
                            pincode,
                            phone,
                            now);
            try {
                shipmentRepository.save(shipment);
            } catch (DataIntegrityViolationException race) {
                shipment =
                        shipmentRepository
                                .findByCollaborationId(collaboration.getId())
                                .orElseThrow(
                                        () ->
                                                new ApiException(
                                                        "SHIPMENT_RACE_UNRESOLVED",
                                                        "Could not save shipping address, please retry",
                                                        HttpStatus.CONFLICT));
                requireNotShipped(shipment);
                shipment.updateAddress(
                        recipientName, addressLine1, addressLine2, city, state, pincode, phone, now);
                shipmentRepository.save(shipment);
            }
        } else {
            requireNotShipped(shipment);
            shipment.updateAddress(
                    recipientName, addressLine1, addressLine2, city, state, pincode, phone, now);
            shipmentRepository.save(shipment);
        }

        appendShipmentMessage(collaboration.getId(), "Creator provided a shipping address");
        return toResponse(collaboration.getId(), shipment);
    }

    /** POST /deals/{id}/shipment — brand marks shipped. ADDRESS_PROVIDED or DAMAGED -> SHIPPED. */
    @Transactional
    public ShipmentResponse markShipped(AuthPrincipal principal, String dealId, MarkShippedRequest body) {
        Collaboration collaboration = requireBrandCollaboration(principal, dealId);
        Shipment shipment =
                shipmentRepository
                        .findByCollaborationId(collaboration.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SHIPMENT_NO_ADDRESS",
                                                "No shipping address on file for this deal yet",
                                                HttpStatus.CONFLICT));
        if (shipment.getStatus() != ShipmentStatus.ADDRESS_PROVIDED
                && shipment.getStatus() != ShipmentStatus.DAMAGED) {
            if (shipment.getStatus() == ShipmentStatus.AWAITING_ADDRESS) {
                throw new ApiException(
                        "SHIPMENT_NO_ADDRESS",
                        "No shipping address on file for this deal yet",
                        HttpStatus.CONFLICT);
            }
            throw new ApiException(
                    "SHIPMENT_ALREADY_SHIPPED",
                    "This shipment is already " + shipment.getStatus().name().toLowerCase() + " and cannot be re-shipped from here",
                    HttpStatus.CONFLICT);
        }

        String carrier = TextSanitizer.sanitizePlainText(body.carrier());
        String trackingNumber = TextSanitizer.sanitizePlainText(body.trackingNumber());
        String trackingUrl = TextSanitizer.sanitizePlainText(body.trackingUrl());
        // SECURITY (Kabir CRITICAL, 2026-07-24): trackingUrl is served as an href to the *creator*
        // (shipment-card "Track Live") and fanned into notification bodies — sanitizePlainText only
        // strips tags, it does NOT gate the scheme, so a brand could store `javascript:`/`data:` and
        // land a cross-tenant stored XSS. Same class as the Batch A CampaignLinkService fix; gate the
        // scheme server-side before persistence. Optional field → blank/null is allowed through.
        validateTrackingUrl(trackingUrl);
        String productName = TextSanitizer.sanitizePlainText(body.productName());

        shipment.markShipped(
                carrier, trackingNumber, trackingUrl, productName, body.estimatedDelivery(), Instant.now());
        shipmentRepository.save(shipment);
        appendShipmentMessage(collaboration.getId(), "Brand shipped the product via " + carrier);

        try {
            notifyShipmentCreated(collaboration, shipment);
        } catch (RuntimeException e) {
            log.error(
                    "ShipmentCreatedEvent publish failed for collaboration {} — shipment itself already"
                            + " marked SHIPPED",
                    collaboration.getId(),
                    e);
        }

        return toResponse(collaboration.getId(), shipment);
    }

    /** POST /deals/{id}/shipment/confirm-receipt — creator. SHIPPED -> RECEIVED(GOOD) or DAMAGED. */
    @Transactional
    public ShipmentResponse confirmReceipt(AuthPrincipal principal, String dealId, ConfirmReceiptRequest body) {
        Collaboration collaboration = requireCreatorCollaboration(principal, dealId);
        Shipment shipment =
                shipmentRepository
                        .findByCollaborationId(collaboration.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SHIPMENT_NOT_SHIPPED",
                                                "This deal has no shipment to confirm receipt for",
                                                HttpStatus.CONFLICT));
        if (shipment.getStatus() != ShipmentStatus.SHIPPED) {
            throw new ApiException(
                    "SHIPMENT_NOT_SHIPPED",
                    "This shipment has not been marked shipped yet",
                    HttpStatus.CONFLICT);
        }

        ShipmentCondition condition = body.condition();
        String note = TextSanitizer.sanitizePlainText(body.note());
        shipment.confirmReceipt(condition, note, Instant.now());
        shipmentRepository.save(shipment);
        appendShipmentMessage(
                collaboration.getId(),
                condition == ShipmentCondition.DAMAGED
                        ? "Creator confirmed receipt — reported DAMAGED"
                        : "Creator confirmed receipt in good condition");

        try {
            notifyShipmentReceived(collaboration, shipment);
        } catch (RuntimeException e) {
            log.error(
                    "ShipmentReceivedEvent publish failed for collaboration {} — receipt confirmation"
                            + " itself already succeeded",
                    collaboration.getId(),
                    e);
        }

        return toResponse(collaboration.getId(), shipment);
    }

    /** GET /deals/{id}/shipment — both roles. Returns synthetic AWAITING_ADDRESS if no row exists. */
    @Transactional(readOnly = true)
    public ShipmentResponse getShipment(AuthPrincipal principal, String dealId) {
        Collaboration collaboration = requireOwnedCollaboration(principal, dealId);
        Shipment shipment = shipmentRepository.findByCollaborationId(collaboration.getId()).orElse(null);
        if (shipment == null) {
            return ShipmentResponse.awaitingAddress(collaboration.getId());
        }
        return toResponse(collaboration.getId(), shipment);
    }

    // ---- transition guards -------------------------------------------------------------------

    /**
     * Scheme allowlist for the brand-supplied {@code trackingUrl}, mirroring {@code
     * CampaignLinkService.validateBaseUrl}. The value is later rendered as a creator-facing href and
     * embedded in notification payloads, so anything but {@code http}/{@code https} is a stored-XSS /
     * open-redirect vector. The field is optional — blank/null passes; only a present-but-unsafe
     * value is rejected with {@code 400 INVALID_TRACKING_URL}.
     */
    private static void validateTrackingUrl(String trackingUrl) {
        if (trackingUrl == null || trackingUrl.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = new URI(trackingUrl);
        } catch (URISyntaxException e) {
            throw new ApiException(
                    "INVALID_TRACKING_URL",
                    "trackingUrl must be a valid http or https URL",
                    HttpStatus.BAD_REQUEST);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ApiException(
                    "INVALID_TRACKING_URL",
                    "trackingUrl must use the http or https scheme",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void requireNotShipped(Shipment shipment) {
        // QA C1 (Kavya, 2026-07-24): the address is editable ONLY before the parcel leaves — i.e.
        // AWAITING_ADDRESS / ADDRESS_PROVIDED. Every later state (SHIPPED, RECEIVED, and crucially
        // DAMAGED — which is post-SHIPPED and re-ships to the SAME address) must lock it. Allowlist
        // the two mutable states rather than denylisting, so a future status can't silently reopen
        // the address window.
        if (shipment.getStatus() != ShipmentStatus.AWAITING_ADDRESS
                && shipment.getStatus() != ShipmentStatus.ADDRESS_PROVIDED) {
            throw new ApiException(
                    "SHIPMENT_ALREADY_SHIPPED",
                    "This shipment has already been shipped and its address can no longer be changed",
                    HttpStatus.CONFLICT);
        }
    }

    // ---- event wiring (design doc §6 -- exact field derivation, mirrors DealService.notifyFirstMessage) ----

    private void notifyShipmentCreated(Collaboration collaboration, Shipment shipment) {
        Campaign campaign = campaignRepository.findById(collaboration.getCampaignId()).orElse(null);
        if (campaign == null) {
            return;
        }
        String workspaceId = campaign.getWorkspaceId();
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        String brandName = workspace != null ? workspace.getName() : "The brand";
        eventPublisher.publishEvent(
                new ShipmentCreatedEvent(
                        collaboration.getCreatorId(),
                        workspaceId,
                        collaboration.getId(),
                        brandName,
                        shipment.getProductName(),
                        shipment.getTrackingUrl()));
    }

    private void notifyShipmentReceived(Collaboration collaboration, Shipment shipment) {
        Campaign campaign = campaignRepository.findById(collaboration.getCampaignId()).orElse(null);
        if (campaign == null) {
            return;
        }
        String workspaceId = campaign.getWorkspaceId();
        var recipient = brandContext.resolveBillingRecipient(workspaceId);
        if (recipient == null) {
            // Null-check -> skip publish, never NPE (design doc §6 / risk #3), mirrors
            // DealService.notifyFirstMessage's own recipient == null guard.
            return;
        }
        CreatorProfile creator =
                creatorProfileRepository.findByUserId(collaboration.getCreatorId()).orElse(null);
        String creatorName = creator != null ? creator.getDisplayName() : "The creator";
        eventPublisher.publishEvent(
                new ShipmentReceivedEvent(
                        recipient.userId(),
                        workspaceId,
                        collaboration.getId(),
                        creatorName,
                        shipment.getProductName()));
    }

    // ---- authz (design doc §5, CRITICAL -- reuse the exact existing trust-boundary finders) ---

    private Collaboration requireOwnedCollaboration(AuthPrincipal principal, String dealId) {
        UserType role = requireRole(principal);
        if (role == UserType.CREATOR) {
            return requireCreatorCollaboration(principal, dealId);
        }
        return requireBrandCollaboration(principal, dealId);
    }

    private Collaboration requireCreatorCollaboration(AuthPrincipal principal, String dealId) {
        creatorContext.requireCreator(principal);
        return collaborationRepository
                .findByIdAndCreatorId(dealId, principal.getUserId())
                .orElseThrow(
                        () -> new ApiException("DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }

    private Collaboration requireBrandCollaboration(AuthPrincipal principal, String dealId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return collaborationRepository
                .findByIdAndWorkspaceId(dealId, workspace.getId())
                .orElseThrow(
                        () -> new ApiException("DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));
    }

    private UserType requireRole(AuthPrincipal principal) {
        if (principal == null
                || (principal.getUserType() != UserType.CREATOR
                        && principal.getUserType() != UserType.BRAND)) {
            throw new ApiException(
                    "WRONG_USER_TYPE",
                    "This endpoint is for brand or creator accounts only",
                    HttpStatus.FORBIDDEN);
        }
        return principal.getUserType();
    }

    // ---- misc ---------------------------------------------------------------------------------

    /** Deal-room timeline entry for each shipment transition (design doc §4 -- DealMessageKind.shipment already exists). */
    private void appendShipmentMessage(String collaborationId, String content) {
        dealMessageRepository.save(
                DealMessage.create(
                        Ulids.newUlid(),
                        collaborationId,
                        DealMessageKind.shipment,
                        "system",
                        DealSenderType.system,
                        content,
                        null));
    }

    private static ShipmentResponse toResponse(String dealId, Shipment shipment) {
        return new ShipmentResponse(
                dealId,
                shipment.getStatus(),
                shipment.getRecipientName(),
                shipment.getAddressLine1(),
                shipment.getAddressLine2(),
                shipment.getCity(),
                shipment.getState(),
                shipment.getPincode(),
                shipment.getPhone(),
                shipment.getProductName(),
                shipment.getCarrier(),
                shipment.getTrackingNumber(),
                shipment.getTrackingUrl(),
                shipment.getConditionNote(),
                shipment.getReceivedCondition(),
                shipment.getUpdatedAt(),
                shipment.getEstimatedDelivery());
    }
}
