package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.SupportTicket;
import com.influora.domain.entity.SupportTicketMessage;
import com.influora.domain.entity.SupportTicketMessage.SenderType;
import com.influora.domain.enums.TicketPriority;
import com.influora.domain.enums.TicketStatus;
import com.influora.repository.SupportTicketMessageRepository;
import com.influora.repository.SupportTicketRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.support.SupportDtos.AddMessageRequest;
import com.influora.web.dto.support.SupportDtos.CreateTicketRequest;
import com.influora.web.dto.support.SupportDtos.TicketDetailResponse;
import com.influora.web.dto.support.SupportDtos.TicketMessageResponse;
import com.influora.web.dto.support.SupportDtos.TicketSummaryResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [F-0535] Requester side of support. Before this class, {@code support_tickets} could only be
 * read and mutated by {@code AdminSupportService}: there was no route by which a brand or creator
 * could open a ticket, read a reply, or answer one. The tables, the {@code SenderType.USER} enum
 * value and the {@code WAITING_USER} status all existed and anticipated this surface; only the
 * code that reaches them was missing.
 *
 * <p><b>Ownership is the whole security model here.</b> Every read and write goes through {@link
 * #requireOwnTicket}, which resolves the ticket by id and then refuses it unless {@code userId}
 * matches the authenticated principal — resolve-then-scope, never a caller-supplied user id, the
 * same discipline the webhook controllers use for shop domains. A ticket belonging to someone else
 * returns the SAME 404 as one that does not exist, so this cannot be used to probe which ticket
 * ids are real.
 */
@Service
public class SupportService {

    private final SupportTicketRepository supportTicketRepository;
    private final SupportTicketMessageRepository supportTicketMessageRepository;

    public SupportService(
            SupportTicketRepository supportTicketRepository,
            SupportTicketMessageRepository supportTicketMessageRepository) {
        this.supportTicketRepository = supportTicketRepository;
        this.supportTicketMessageRepository = supportTicketMessageRepository;
    }

    @Transactional
    public TicketDetailResponse create(AuthPrincipal principal, CreateTicketRequest req) {
        SupportTicket ticket =
                SupportTicket.open(
                        Ulids.newUlid(),
                        principal.getUserId(),
                        principal.getUserType(),
                        req.category(),
                        req.subject(),
                        parsePriority(req.priority()));
        supportTicketRepository.save(ticket);

        // The opening message is a real thread entry, not a column on the ticket. That keeps one
        // rendering path for the whole conversation and means the admin thread view shows the
        // original request without a special case for "the first one".
        SupportTicketMessage first =
                SupportTicketMessage.create(
                        Ulids.newUlid(), ticket.getId(), principal.getUserId(), SenderType.USER, req.message());
        supportTicketMessageRepository.save(first);

        return toDetail(ticket, List.of(first));
    }

    @Transactional(readOnly = true)
    public List<TicketSummaryResponse> listMine(AuthPrincipal principal) {
        return supportTicketRepository.findByUserIdOrderByCreatedAtDesc(principal.getUserId()).stream()
                .map(SupportService::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public TicketDetailResponse getMine(AuthPrincipal principal, String ticketId) {
        SupportTicket ticket = requireOwnTicket(principal, ticketId);
        return toDetail(ticket, supportTicketMessageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId));
    }

    /**
     * The requester answers. Refused on a terminal ticket: replying to something RESOLVED or
     * CLOSED would otherwise append a message nobody is assigned to read, which is worse than a
     * clear error telling them to open a new ticket.
     */
    @Transactional
    public TicketDetailResponse addMessage(AuthPrincipal principal, String ticketId, AddMessageRequest req) {
        SupportTicket ticket = requireOwnTicket(principal, ticketId);
        if (ticket.getStatus() == TicketStatus.RESOLVED || ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ApiException(
                    "TICKET_NOT_OPEN",
                    "This ticket is closed. Please open a new one.",
                    HttpStatus.CONFLICT);
        }

        SupportTicketMessage message =
                SupportTicketMessage.create(
                        Ulids.newUlid(), ticket.getId(), principal.getUserId(), SenderType.USER, req.content());
        supportTicketMessageRepository.save(message);

        ticket.noteUserReplied();
        supportTicketRepository.save(ticket);

        return toDetail(ticket, supportTicketMessageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId));
    }

    /** Resolve-then-scope. Someone else's ticket is indistinguishable from a missing one. */
    private SupportTicket requireOwnTicket(AuthPrincipal principal, String ticketId) {
        SupportTicket ticket =
                supportTicketRepository
                        .findById(ticketId)
                        .orElseThrow(() -> notFound());
        if (!ticket.getUserId().equals(principal.getUserId())) {
            throw notFound();
        }
        return ticket;
    }

    private static ApiException notFound() {
        return new ApiException("TICKET_NOT_FOUND", "Ticket not found", HttpStatus.NOT_FOUND);
    }

    /** An unrecognised priority is a 400, not a silent downgrade to MEDIUM. */
    private static TicketPriority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TicketPriority.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_PRIORITY",
                    "priority must be one of LOW, MEDIUM, HIGH, URGENT",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static TicketSummaryResponse toSummary(SupportTicket t) {
        return new TicketSummaryResponse(
                t.getId(),
                t.getCategory(),
                t.getSubject(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getPriority() == null ? null : t.getPriority().name(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    private static TicketDetailResponse toDetail(SupportTicket t, List<SupportTicketMessage> messages) {
        return new TicketDetailResponse(
                t.getId(),
                t.getCategory(),
                t.getSubject(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getPriority() == null ? null : t.getPriority().name(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getResolvedAt(),
                messages.stream()
                        .map(
                                m ->
                                        new TicketMessageResponse(
                                                m.getId(),
                                                m.getSenderType() == SenderType.ADMIN,
                                                m.getContent(),
                                                m.getCreatedAt()))
                        .toList());
    }
}
