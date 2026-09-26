package com.influora.repository;

import com.influora.domain.entity.SupportTicketMessage;
import com.influora.domain.entity.SupportTicketMessage.SenderType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketMessageRepository extends JpaRepository<SupportTicketMessage, String> {

    List<SupportTicketMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);

    /**
     * F-0525 (dead-metric repair, T-DEADMETRIC-REPAIR-0915) — every ADMIN-sent message, oldest
     * first, across all tickets. Feeds {@code AdminSupportService.getStats}'s real {@code
     * avgResponseTime}: the first row per {@code ticketId} in this ascending-by-createdAt order is
     * that ticket's first admin reply, with no separate {@code first_responded_at} column needed —
     * the message thread already carries this data (see that method's javadoc for why this reads
     * cheaper than adding a column).
     */
    List<SupportTicketMessage> findBySenderTypeOrderByCreatedAtAsc(SenderType senderType);
}
