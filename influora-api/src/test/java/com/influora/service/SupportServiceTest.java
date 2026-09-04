package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.SupportTicket;
import com.influora.domain.entity.SupportTicketMessage;
import com.influora.domain.entity.SupportTicketMessage.SenderType;
import com.influora.domain.enums.TicketPriority;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.SupportTicketMessageRepository;
import com.influora.repository.SupportTicketRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.support.SupportDtos.AddMessageRequest;
import com.influora.web.dto.support.SupportDtos.CreateTicketRequest;
import com.influora.web.dto.support.SupportDtos.TicketDetailResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * F-0535 — the requester side of support, which did not exist: no route anywhere let a brand or
 * creator open a ticket, read a reply, or answer one.
 *
 * <p>The security-critical assertions here are the ownership ones. Every read and write is scoped
 * by {@code userId} at the repository, and another user's ticket must be indistinguishable from a
 * missing one — a 404 either way, so ticket ids cannot be probed for existence.
 */
@ExtendWith(MockitoExtension.class)
class SupportServiceTest {

    private static final String USER_ID = "01HUSER0000000000001";
    private static final String OTHER_USER_ID = "01HUSER0000000000002";
    private static final String TICKET_ID = "01HTICKET00000000001";

    @Mock private SupportTicketRepository supportTicketRepository;
    @Mock private SupportTicketMessageRepository supportTicketMessageRepository;

    private SupportService service;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        service = new SupportService(supportTicketRepository, supportTicketMessageRepository);
        principal = new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, "01HWORKSPACE12345678A");
    }

    private static SupportTicket ticketOwnedBy(String ownerId, TicketStatus status) {
        SupportTicket t =
                SupportTicket.open(
                        TICKET_ID, ownerId, UserType.BRAND, "billing", "Cannot withdraw", TicketPriority.HIGH);
        if (status != TicketStatus.OPEN) {
            t.updateStatus(status);
        }
        return t;
    }

    // ------------------------------------------------------------------
    // create — the defect itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("F-0535: a brand can open a ticket, and the opening message is persisted as a USER thread entry")
    void create_persistsTicketAndOpeningMessage() {
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(i -> i.getArgument(0));
        when(supportTicketMessageRepository.save(any(SupportTicketMessage.class))).thenAnswer(i -> i.getArgument(0));

        TicketDetailResponse res =
                service.create(
                        principal, new CreateTicketRequest("billing", "Cannot withdraw", "It fails at the last step", "HIGH"));

        ArgumentCaptor<SupportTicket> saved = ArgumentCaptor.forClass(SupportTicket.class);
        verify(supportTicketRepository).save(saved.capture());
        assertEquals(USER_ID, saved.getValue().getUserId(), "the ticket must belong to the caller");
        assertEquals(UserType.BRAND, saved.getValue().getUserType());
        assertEquals(TicketStatus.OPEN, saved.getValue().getStatus(), "a new ticket starts OPEN");
        assertEquals(TicketPriority.HIGH, saved.getValue().getPriority(), "the requester's stated urgency is kept");

        ArgumentCaptor<SupportTicketMessage> msg = ArgumentCaptor.forClass(SupportTicketMessage.class);
        verify(supportTicketMessageRepository).save(msg.capture());
        assertEquals(SenderType.USER, msg.getValue().getSenderType());
        assertEquals("It fails at the last step", msg.getValue().getContent());

        assertEquals(1, res.messages().size());
        assertFalse(res.messages().get(0).fromSupport(), "the opening message is from the requester");
    }

    @Test
    @DisplayName("F-0535: an omitted priority falls back to the entity default rather than failing")
    void create_withoutPriority_usesDefault() {
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(i -> i.getArgument(0));
        when(supportTicketMessageRepository.save(any(SupportTicketMessage.class))).thenAnswer(i -> i.getArgument(0));

        service.create(principal, new CreateTicketRequest("billing", "Subject", "Body", null));

        ArgumentCaptor<SupportTicket> saved = ArgumentCaptor.forClass(SupportTicket.class);
        verify(supportTicketRepository).save(saved.capture());
        assertEquals(TicketPriority.MEDIUM, saved.getValue().getPriority());
    }

    @Test
    @DisplayName("F-0535: an unrecognised priority is a 400, never a silent downgrade")
    void create_withGarbagePriority_isRejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(principal, new CreateTicketRequest("billing", "S", "B", "SUPER_URGENT")));
        assertEquals("INVALID_PRIORITY", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(supportTicketRepository, never()).save(any(SupportTicket.class));
    }

    // ------------------------------------------------------------------
    // ownership — the security boundary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("F-0535: another user's ticket returns the SAME 404 as a missing one, so ids cannot be probed")
    void getMine_otherUsersTicket_is404_indistinguishableFromMissing() {
        when(supportTicketRepository.findById(TICKET_ID))
                .thenReturn(Optional.of(ticketOwnedBy(OTHER_USER_ID, TicketStatus.OPEN)));
        ApiException foreign = assertThrows(ApiException.class, () -> service.getMine(principal, TICKET_ID));

        when(supportTicketRepository.findById("01HTICKETMISSING0001")).thenReturn(Optional.empty());
        ApiException missing =
                assertThrows(ApiException.class, () -> service.getMine(principal, "01HTICKETMISSING0001"));

        assertEquals(missing.getCode(), foreign.getCode(), "a foreign ticket must not be distinguishable");
        assertEquals(missing.getStatus(), foreign.getStatus());
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatus());
    }

    @Test
    @DisplayName("F-0535: a reply to another user's ticket is refused and writes nothing")
    void addMessage_otherUsersTicket_isRefused() {
        when(supportTicketRepository.findById(TICKET_ID))
                .thenReturn(Optional.of(ticketOwnedBy(OTHER_USER_ID, TicketStatus.OPEN)));

        assertThrows(
                ApiException.class, () -> service.addMessage(principal, TICKET_ID, new AddMessageRequest("hello")));
        verify(supportTicketMessageRepository, never()).save(any(SupportTicketMessage.class));
    }

    @Test
    @DisplayName("F-0535: listing is scoped by userId at the query, not filtered afterwards")
    void listMine_queriesByUserId() {
        when(supportTicketRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(ticketOwnedBy(USER_ID, TicketStatus.OPEN)));

        assertEquals(1, service.listMine(principal).size());
        verify(supportTicketRepository).findByUserIdOrderByCreatedAtDesc(USER_ID);
    }

    // ------------------------------------------------------------------
    // WAITING_USER — the state a requester could not previously leave
    // ------------------------------------------------------------------

    @Test
    @DisplayName("F-0535: replying to a WAITING_USER ticket moves it back to OPEN")
    void addMessage_clearsWaitingUser() {
        SupportTicket ticket = ticketOwnedBy(USER_ID, TicketStatus.WAITING_USER);
        when(supportTicketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(i -> i.getArgument(0));
        when(supportTicketMessageRepository.save(any(SupportTicketMessage.class))).thenAnswer(i -> i.getArgument(0));
        when(supportTicketMessageRepository.findByTicketIdOrderByCreatedAtAsc(TICKET_ID)).thenReturn(List.of());

        service.addMessage(principal, TICKET_ID, new AddMessageRequest("here is the detail you asked for"));

        assertEquals(TicketStatus.OPEN, ticket.getStatus(), "the requester answering must clear WAITING_USER");
    }

    @Test
    @DisplayName("F-0535: replying to an IN_PROGRESS ticket does NOT reorder it")
    void addMessage_doesNotDisturbInProgress() {
        SupportTicket ticket = ticketOwnedBy(USER_ID, TicketStatus.IN_PROGRESS);
        when(supportTicketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(i -> i.getArgument(0));
        when(supportTicketMessageRepository.save(any(SupportTicketMessage.class))).thenAnswer(i -> i.getArgument(0));
        when(supportTicketMessageRepository.findByTicketIdOrderByCreatedAtAsc(TICKET_ID)).thenReturn(List.of());

        service.addMessage(principal, TICKET_ID, new AddMessageRequest("any update?"));

        assertEquals(TicketStatus.IN_PROGRESS, ticket.getStatus());
    }

    @Test
    @DisplayName("F-0535: a RESOLVED ticket refuses new messages rather than appending unread ones")
    void addMessage_onResolvedTicket_isRefused() {
        when(supportTicketRepository.findById(TICKET_ID))
                .thenReturn(Optional.of(ticketOwnedBy(USER_ID, TicketStatus.RESOLVED)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.addMessage(principal, TICKET_ID, new AddMessageRequest("one more thing")));
        assertEquals("TICKET_NOT_OPEN", ex.getCode());
        verify(supportTicketMessageRepository, never()).save(any(SupportTicketMessage.class));
    }

    // ------------------------------------------------------------------
    // the other half of the defect: an admin reply the requester can now read
    // ------------------------------------------------------------------

    @Test
    @DisplayName("F-0535: an admin reply is readable by the requester and marked as from support")
    void getMine_exposesAdminReply_withoutLeakingTheAdminId() {
        SupportTicket ticket = ticketOwnedBy(USER_ID, TicketStatus.IN_PROGRESS);
        when(supportTicketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(supportTicketMessageRepository.findByTicketIdOrderByCreatedAtAsc(TICKET_ID))
                .thenReturn(
                        List.of(
                                SupportTicketMessage.create("m1", TICKET_ID, USER_ID, SenderType.USER, "my problem"),
                                SupportTicketMessage.create(
                                        "m2", TICKET_ID, "01HADMIN000000000001", SenderType.ADMIN, "we are on it")));

        TicketDetailResponse res = service.getMine(principal, TICKET_ID);

        assertEquals(2, res.messages().size());
        assertFalse(res.messages().get(0).fromSupport());
        assertTrue(res.messages().get(1).fromSupport(), "the admin reply must be visible and attributed to support");
        assertEquals("we are on it", res.messages().get(1).content());
        // The response carries no sender id at all, so the admin_users.id cannot leak to a customer.
        assertFalse(res.toString().contains("01HADMIN000000000001"), "internal admin id must not reach the requester");
    }
}
