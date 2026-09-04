package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.SupportService;
import com.influora.web.dto.support.SupportDtos.AddMessageRequest;
import com.influora.web.dto.support.SupportDtos.CreateTicketRequest;
import com.influora.web.dto.support.SupportDtos.TicketDetailResponse;
import com.influora.web.dto.support.SupportDtos.TicketSummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [F-0535] The requester-facing half of support, which did not exist. {@code
 * AdminSupportController} shipped list, detail, reply, status, assign and escalate — every verb
 * except the one that starts a ticket — so support was a system an operator could work but nobody
 * could enter. An admin reply landed in {@code support_ticket_messages} where the person who
 * needed it had no route to read it, and {@code WAITING_USER} named a state only an admin could
 * leave.
 *
 * <p>Four routes, all scoped to the authenticated requester by {@link SupportService}: open a
 * ticket, list mine, read one with its thread, reply to one. There is deliberately no
 * requester-side status route — reopening or resolving is a support decision, and {@code
 * noteUserReplied} already moves a ticket out of {@code WAITING_USER} as a side effect of the
 * thing a requester actually does, which is answer.
 */
@RestController
@RequestMapping("/support/tickets")
public class SupportController {

    private final SupportService supportService;

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    /** Opens a ticket for the authenticated brand or creator. 201 with the thread. */
    @PostMapping
    public ResponseEntity<ApiResponse<TicketDetailResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody CreateTicketRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(supportService.create(principal, body)));
    }

    /** The caller's own tickets, newest first. Never anyone else's. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TicketSummaryResponse>>> listMine(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(supportService.listMine(principal)));
    }

    /** One of the caller's tickets with its full thread. Someone else's returns the same 404 as a missing one. */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TicketDetailResponse>> getOne(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(supportService.getMine(principal, id)));
    }

    /** The requester answers. Clears WAITING_USER; refused on a RESOLVED or CLOSED ticket. */
    @PostMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<TicketDetailResponse>> addMessage(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody AddMessageRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(supportService.addMessage(principal, id, body)));
    }
}
