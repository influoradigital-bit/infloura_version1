package com.influora.repository;

import com.influora.domain.entity.SupportTicketMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketMessageRepository extends JpaRepository<SupportTicketMessage, String> {

    List<SupportTicketMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);
}
