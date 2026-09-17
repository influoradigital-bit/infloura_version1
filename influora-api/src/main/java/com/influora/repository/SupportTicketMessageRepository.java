package com.influora.repository;

import com.influora.domain.entity.SupportTicketMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SupportTicketMessageRepository extends JpaRepository<SupportTicketMessage, String> {

    List<SupportTicketMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);

    /**
     * F-0525 (dead-metric repair, T-DEADMETRIC-REPAIR-0915, fix round) — feeds {@code
     * AdminSupportService.getStats}'s real {@code avgResponseTime} (minutes) with a single bounded
     * SQL aggregate, computed entirely in MySQL:
     *
     * <ol>
     *   <li>the inner derived table groups {@code support_ticket_messages} by {@code ticket_id}
     *       (ADMIN-sent only) and takes {@code MIN(created_at)} — that ticket's FIRST admin reply,
     *       never a later one;
     *   <li>joined back to {@code support_tickets} to get each ticket's own {@code created_at};
     *   <li>{@code TIMESTAMPDIFF(MINUTE, ...)} gives whole-minute response time per ticket, and
     *       {@code AVG(...)} means it across every ticket that has at least one admin reply.
     * </ol>
     *
     * <p>Replaces a prior implementation that pulled every ADMIN message ever written into Java
     * ({@code findBySenderTypeOrderByCreatedAtAsc}, since removed — it had no other caller) and
     * then called {@code SupportTicketRepository#findAllById} with every replied-to ticket id, on
     * every single stats request — both grow unbounded with the support inbox. This method instead
     * returns exactly one row, and MySQL never materializes a message or ticket entity for it.
     *
     * <p>Returns {@code null} (MySQL's {@code AVG} over zero grouped rows) when no ticket has ever
     * received an admin reply — {@code AdminSupportService.computeAvgResponseTimeMinutes} maps that
     * to {@code 0.0}, the same "absence is not zero" semantics the prior implementation had.
     */
    @Query(
            value =
                    "SELECT AVG(TIMESTAMPDIFF(MINUTE, t.created_at, fr.first_admin_reply_at)) "
                            + "FROM support_tickets t "
                            + "JOIN ("
                            + "  SELECT ticket_id, MIN(created_at) AS first_admin_reply_at "
                            + "  FROM support_ticket_messages "
                            + "  WHERE sender_type = 'ADMIN' "
                            + "  GROUP BY ticket_id"
                            + ") fr ON fr.ticket_id = t.id",
            nativeQuery = true)
    Double avgFirstAdminReplyMinutes();
}
