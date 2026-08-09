package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.DealMessageStreamRegistry;
import com.influora.service.DealService;
import com.influora.service.DisputeService;
import com.influora.service.ShipmentService;
import com.influora.web.dto.dispute.DisputeDtos.DisputeResponse;
import com.influora.web.dto.dispute.DisputeDtos.OpenDisputeRequest;
import com.influora.web.dto.deal.DealDtos.CounterRequest;
import com.influora.web.dto.deal.DealDtos.CreateDealRequest;
import com.influora.web.dto.deal.DealDtos.DealMessageResponse;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import com.influora.web.dto.deal.DealDtos.OkResponse;
import com.influora.web.dto.deal.DealDtos.RejectRequest;
import com.influora.web.dto.deal.DealDtos.SendMessageRequest;
import com.influora.web.dto.deliverable.CreatorDeliverableDtos.DeliverableListItem;
import com.influora.web.dto.shipment.ShipmentDtos.ConfirmReceiptRequest;
import com.influora.web.dto.shipment.ShipmentDtos.MarkShippedRequest;
import com.influora.web.dto.shipment.ShipmentDtos.ShipmentResponse;
import com.influora.web.dto.shipment.ShipmentDtos.ShippingAddressRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Task #9 — unified deal room for brand + creator. Backed by {@link
 * com.influora.domain.entity.Collaboration} + {@link com.influora.domain.entity.DealMessage}
 * timeline. Identity always from JWT {@link AuthPrincipal} via {@code CreatorContextService} /
 * {@code BrandContextService} — never trust path-param user ids.
 */
@RestController
@RequestMapping("/deals")
public class DealController {

    private final DealService dealService;
    private final DisputeService disputeService;
    private final DealMessageStreamRegistry messageStreamRegistry;
    private final ShipmentService shipmentService;

    public DealController(
            DealService dealService,
            DisputeService disputeService,
            DealMessageStreamRegistry messageStreamRegistry,
            ShipmentService shipmentService) {
        this.dealService = dealService;
        this.disputeService = disputeService;
        this.messageStreamRegistry = messageStreamRegistry;
        this.shipmentService = shipmentService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DealResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false, defaultValue = "all") String status) {
        return ResponseEntity.ok(ApiResponse.ok(dealService.list(principal, status)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DealResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(dealService.get(principal, id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DealResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateDealRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(dealService.createProposal(principal, body)));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<DealResponse>> accept(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(ApiResponse.ok(dealService.accept(principal, id, idempotencyKey)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<OkResponse>> reject(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RejectRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(dealService.reject(principal, id, body, idempotencyKey)));
    }

    @PostMapping("/{id}/counter")
    public ResponseEntity<ApiResponse<DealResponse>> counter(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CounterRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(dealService.counter(principal, id, body, idempotencyKey)));
    }

    @GetMapping("/{dealId}/messages")
    public ResponseEntity<ApiResponse<List<DealMessageResponse>>> listMessages(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String dealId,
            @RequestParam(required = false) String before) {
        return ResponseEntity.ok(ApiResponse.ok(dealService.listMessages(principal, dealId, before)));
    }

    /**
     * Realtime deal-message stream (SSE, consistent with the existing Meera {@code /chat}
     * stream — Priya's architecture call, not WebSocket). Frontend contract: consumed via
     * fetch-based SSE with a standard {@code Authorization: Bearer <token>} header (like {@code
     * useMeeraStream}), NOT raw {@code EventSource} — so the token is never required in the query
     * string. Emits the SAME {@link DealMessageResponse} shape {@code GET /{dealId}/messages}
     * already returns, as named SSE events ({@code event: deal-message}). Authorization runs
     * FIRST and reuses {@link DealService#authorizeMessageStream}, itself a thin wrapper around
     * the exact ownership check {@link DealService#listMessages}/{@link DealService#sendMessage}
     * use — an unauthorized caller gets the normal 404/403 JSON error and no emitter is ever
     * created or registered.
     *
     * <p>CR-95: reconnects carry the standard {@code Last-Event-ID} header (browsers set this
     * automatically from the SSE {@code id:} field of the last event they processed); it is
     * passed straight through to {@link
     * DealMessageStreamRegistry#register(String, SseEmitter, String)} so any messages sent during
     * the {@link DealMessageStreamRegistry#EMITTER_TIMEOUT_MS} reconnect gap are replayed instead
     * of silently dropped.
     */
    @GetMapping("/{dealId}/messages/stream")
    public SseEmitter streamMessages(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String dealId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        dealService.authorizeMessageStream(principal, dealId);

        SseEmitter emitter = new SseEmitter(DealMessageStreamRegistry.EMITTER_TIMEOUT_MS);
        messageStreamRegistry.register(dealId, emitter, lastEventId);
        try {
            // Heartbeat comment (":...") so the connection opens cleanly — a bare comment line
            // is valid SSE and doesn't fire the client's onmessage handler.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    @PostMapping("/{dealId}/messages")
    public ResponseEntity<ApiResponse<DealMessageResponse>> sendMessage(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String dealId,
            @Valid @RequestBody SendMessageRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(dealService.sendMessage(principal, dealId, body)));
    }

    @PostMapping("/{dealId}/messages/read")
    public ResponseEntity<ApiResponse<OkResponse>> markRead(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String dealId) {
        return ResponseEntity.ok(ApiResponse.ok(dealService.markRead(principal, dealId)));
    }

    /**
     * Brand Deal Room persistence gap closure (B-1) — previously 404'd; {@code
     * src/lib/api.ts}'s {@code deliverables.list} already called this exact path expecting it.
     */
    @GetMapping("/{dealId}/deliverables")
    public ResponseEntity<ApiResponse<List<DeliverableListItem>>> listDeliverables(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String dealId) {
        return ResponseEntity.ok(ApiResponse.ok(dealService.listDeliverables(principal, dealId)));
    }

    /** Task #34 — either party may open a dispute; identity resolved in {@link DisputeService}. */
    @PostMapping("/{dealId}/disputes")
    public ResponseEntity<ApiResponse<DisputeResponse>> openDispute(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String dealId,
            @Valid @RequestBody OpenDisputeRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(disputeService.openDispute(principal, dealId, body)));
    }

    /**
     * Product-seeding shipment / confirm-receipt (wiki/decisions/shipment-backend-design-2026-07-24.md
     * §4). Creator submits/updates the shipping address; lazy-creates the {@code Shipment} row.
     */
    @PostMapping("/{id}/shipping-address")
    public ResponseEntity<ApiResponse<ShipmentResponse>> submitShippingAddress(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody ShippingAddressRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(shipmentService.submitAddress(principal, id, body)));
    }

    /** Brand marks the product shipped — publishes {@code ShipmentCreatedEvent} (design doc §4/§6). */
    @PostMapping("/{id}/shipment")
    public ResponseEntity<ApiResponse<ShipmentResponse>> markShipped(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody MarkShippedRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(shipmentService.markShipped(principal, id, body)));
    }

    /** Creator confirms receipt — publishes {@code ShipmentReceivedEvent} (design doc §4/§6). */
    @PostMapping("/{id}/shipment/confirm-receipt")
    public ResponseEntity<ApiResponse<ShipmentResponse>> confirmReceipt(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody ConfirmReceiptRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(shipmentService.confirmReceipt(principal, id, body)));
    }

    /** Dual-role read; returns a synthetic {@code AWAITING_ADDRESS} response if no row exists yet. */
    @GetMapping("/{id}/shipment")
    public ResponseEntity<ApiResponse<ShipmentResponse>> getShipment(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(shipmentService.getShipment(principal, id)));
    }
}
