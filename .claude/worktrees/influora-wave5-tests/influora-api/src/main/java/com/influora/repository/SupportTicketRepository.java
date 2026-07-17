package com.influora.repository;

import com.influora.domain.entity.SupportTicket;
import com.influora.domain.enums.TicketStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * {@code JpaSpecificationExecutor} added cycle 6 (AdminSupportController) for the filtered/
 * paginated ticket list — same {@code Specification}-based filter pattern as {@code
 * CampaignRepository}/{@code CampaignSpecs} (see {@code SupportTicketSpecs}).
 */
public interface SupportTicketRepository
        extends JpaRepository<SupportTicket, String>, JpaSpecificationExecutor<SupportTicket> {

    long countByStatusIn(List<TicketStatus> statuses);

    /** Oldest still-open tickets past a cutoff — feeds the CEO Pulse {@code SUPPORT_AGING} red flag. */
    List<SupportTicket> findTop5ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            TicketStatus status, Instant cutoff);
}
