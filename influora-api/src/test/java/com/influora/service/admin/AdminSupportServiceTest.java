package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.SupportTicket;
import com.influora.domain.entity.SupportTicketMessage;
import com.influora.domain.entity.SupportTicketMessage.SenderType;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.TicketPriority;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.AdminUserRepository;
import com.influora.repository.SupportTicketMessageRepository;
import com.influora.repository.SupportTicketRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminSupportDtos.SupportStatsDto;
import com.influora.web.dto.admin.AdminSupportDtos.TicketDetailDto;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@code AdminSupportService#escalate} (the {@code POST
 * /admin/support/tickets/{id}/escalate} endpoint added to close the phantom-path gap flagged
 * against {@code src/admin/components/support/TicketList.tsx}'s live escalate mutation). Plain
 * Mockito, no {@code @SpringBootTest} — Testcontainers/Docker discovery does not work in this
 * environment, same constraint every other {@code Admin*ServiceTest} in this package works around.
 *
 * <p>Mirrors {@code AdminBillingServiceTest}'s MP-adjacent wiring-test shape: assert the {@code
 * AdminAuditLogService#record} call actually fires with the right action/entityType/reason, and
 * that role gating and input validation genuinely short-circuit before any mutation, not just that
 * the DTO shapes look right in isolation.
 */
@ExtendWith(MockitoExtension.class)
class AdminSupportServiceTest {

    private static final String TICKET_ID = "01HWXYZTICKET0000000001";
    private static final String ADMIN_ID = "01HWXYZADMIN000000000001";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private SupportTicketRepository supportTicketRepository;
    @Mock private SupportTicketMessageRepository supportTicketMessageRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdminUserRepository adminUserRepository;
    @Mock private AuthPrincipal principal;
    @Mock private HttpServletRequest request;

    private AdminSupportService adminSupportService;
    private AdminUser supportAdmin;

    @BeforeEach
    void setUp() {
        adminSupportService =
                new AdminSupportService(
                        adminContext,
                        adminAuditLogService,
                        supportTicketRepository,
                        supportTicketMessageRepository,
                        userRepository,
                        adminUserRepository);

        supportAdmin = AdminUser.create(ADMIN_ID, "support@influora.ai", "hash", AdminRole.SUPPORT);
    }

    @Test
    @DisplayName("escalate raises priority to URGENT and writes an audit record with the supplied reason")
    void testEscalateRaisesPriorityAndWritesAudit() throws Exception {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        SupportTicket ticket = ticketWithPriority(TicketPriority.MEDIUM);
        when(supportTicketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(supportTicketMessageRepository.findByTicketIdOrderByCreatedAtAsc(TICKET_ID))
                .thenReturn(List.of());

        TicketDetailDto result =
                adminSupportService.escalate(principal, request, TICKET_ID, "Customer threatening chargeback");

        assertEquals("URGENT", result.priority());
        assertEquals(TicketPriority.URGENT, ticket.getPriority());

        verify(supportTicketRepository).save(ticket);
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("UPDATE"),
                        eq("SUPPORT_TICKET"),
                        eq(TICKET_ID),
                        any(),
                        any(),
                        eq("Customer threatening chargeback"));
    }

    @Test
    @DisplayName("blank reason is rejected with REASON_REQUIRED / 400, no mutation or audit write")
    void testBlankReasonRejected() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        SupportTicket ticket = ticketWithPriority(TicketPriority.MEDIUM);
        when(supportTicketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        ApiException blank =
                assertThrows(
                        ApiException.class,
                        () -> adminSupportService.escalate(principal, request, TICKET_ID, "   "));
        assertEquals("REASON_REQUIRED", blank.getCode());

        ApiException nullReason =
                assertThrows(
                        ApiException.class,
                        () -> adminSupportService.escalate(principal, request, TICKET_ID, null));
        assertEquals("REASON_REQUIRED", nullReason.getCode());

        verify(supportTicketRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a principal without SUPER_ADMIN/ADMIN/SUPPORT (or MFA unsatisfied) is rejected before any ticket lookup")
    void testInsufficientRoleRejectedBeforeAnyLookup() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenThrow(new ApiException("INSUFFICIENT_ROLE", "nope", org.springframework.http.HttpStatus.FORBIDDEN));

        assertThrows(
                ApiException.class,
                () -> adminSupportService.escalate(principal, request, TICKET_ID, "reason"));

        verify(supportTicketRepository, never()).findById(anyString());
        verify(supportTicketRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("unknown ticket id is rejected with TICKET_NOT_FOUND, no mutation or audit write")
    void testUnknownTicketRejected() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        when(supportTicketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> adminSupportService.escalate(principal, request, TICKET_ID, "reason"));
        assertEquals("TICKET_NOT_FOUND", ex.getCode());

        verify(supportTicketRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    // ---- F-0525 (dead-metric repair, T-DEADMETRIC-REPAIR-0915): getStats().avgResponseTime ----

    @Test
    @DisplayName(
            "getStats: avgResponseTime is the real mean time-to-first-admin-reply, derived from the"
                    + " message thread (F-0525 regression)")
    void testGetStatsComputesRealAvgResponseTime() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        when(supportTicketRepository.countByStatusIn(anyList())).thenReturn(0L);
        when(supportTicketRepository.findByResolvedAtIsNotNull()).thenReturn(List.of());

        String ticketA = "01HWXYZTICKETA000000001";
        String ticketB = "01HWXYZTICKETB000000001";
        Instant ticketACreatedAt = Instant.parse("2026-09-01T00:00:00Z");
        Instant ticketBCreatedAt = Instant.parse("2026-09-02T00:00:00Z");
        // Ticket A: first (and only counted) admin reply 2h after creation.
        // Ticket B: first admin reply 4h after creation — a LATER second admin reply (6h) must be
        // ignored, proving this reads "first reply", not "last reply" or "any reply".
        SupportTicketMessage ticketAFirstReply =
                messageAt("m1", ticketA, SenderType.ADMIN, ticketACreatedAt.plusSeconds(2 * 3600));
        SupportTicketMessage ticketBFirstReply =
                messageAt("m2", ticketB, SenderType.ADMIN, ticketBCreatedAt.plusSeconds(4 * 3600));
        SupportTicketMessage ticketBSecondReply =
                messageAt("m3", ticketB, SenderType.ADMIN, ticketBCreatedAt.plusSeconds(6 * 3600));
        when(supportTicketMessageRepository.findBySenderTypeOrderByCreatedAtAsc(SenderType.ADMIN))
                .thenReturn(List.of(ticketAFirstReply, ticketBFirstReply, ticketBSecondReply));

        SupportTicket ticketARow = ticketWithIdAndCreatedAt(ticketA, ticketACreatedAt);
        SupportTicket ticketBRow = ticketWithIdAndCreatedAt(ticketB, ticketBCreatedAt);
        when(supportTicketRepository.findAllById(anyIterable()))
                .thenReturn(List.of(ticketARow, ticketBRow));

        SupportStatsDto stats = adminSupportService.getStats(principal);

        // (2h + 4h) / 2 = 3.0h — NOT (2+4+6)/3, which would prove the second reply wasn't excluded.
        assertEquals(3.0, stats.avgResponseTime());
    }

    @Test
    @DisplayName("getStats: avgResponseTime is 0 when no ticket has ever received an admin reply")
    void testGetStatsAvgResponseTimeZeroWhenNoAdminReplies() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        when(supportTicketRepository.countByStatusIn(anyList())).thenReturn(0L);
        when(supportTicketRepository.findByResolvedAtIsNotNull()).thenReturn(List.of());
        when(supportTicketMessageRepository.findBySenderTypeOrderByCreatedAtAsc(SenderType.ADMIN))
                .thenReturn(List.of());

        SupportStatsDto stats = adminSupportService.getStats(principal);

        assertEquals(0.0, stats.avgResponseTime());
        verify(supportTicketRepository, never()).findAllById(any());
    }

    private SupportTicketMessage messageAt(
            String id, String ticketId, SenderType senderType, Instant createdAt) {
        SupportTicketMessage message =
                SupportTicketMessage.create(id, ticketId, ADMIN_ID, senderType, "reply body");
        try {
            Field createdAtField = SupportTicketMessage.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(message, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return message;
    }

    private SupportTicket ticketWithIdAndCreatedAt(String id, Instant createdAt) {
        try {
            java.lang.reflect.Constructor<SupportTicket> ctor = SupportTicket.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            SupportTicket ticket = ctor.newInstance();
            setField(ticket, "id", id);
            setField(ticket, "userId", "01HWXYZUSER00000000001");
            setField(ticket, "userType", UserType.BRAND);
            setField(ticket, "category", "billing");
            setField(ticket, "subject", "Refund not received");
            setField(ticket, "status", TicketStatus.OPEN);
            setField(ticket, "priority", TicketPriority.MEDIUM);
            setField(ticket, "assignedTo", null);
            setField(ticket, "createdAt", createdAt);
            setField(ticket, "updatedAt", createdAt);
            setField(ticket, "resolvedAt", null);
            return ticket;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@link SupportTicket} has no public constructor/builder for tests (mirrors the read/write
     * mapping's protected no-arg ctor) — reflection is used here the same way {@code
     * AdminBillingServiceTest} documents relying on entity mutators being the only supported
     * mutation path; since no {@code SupportTicket} factory/mutator sets every field, this helper
     * reaches into the private fields directly to build a fixture row.
     */
    private SupportTicket ticketWithPriority(TicketPriority priority) {
        try {
            java.lang.reflect.Constructor<SupportTicket> ctor = SupportTicket.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            SupportTicket ticket = ctor.newInstance();
            setField(ticket, "id", TICKET_ID);
            setField(ticket, "userId", "01HWXYZUSER00000000001");
            setField(ticket, "userType", UserType.BRAND);
            setField(ticket, "category", "billing");
            setField(ticket, "subject", "Refund not received");
            setField(ticket, "status", TicketStatus.OPEN);
            setField(ticket, "priority", priority);
            setField(ticket, "assignedTo", null);
            setField(ticket, "createdAt", Instant.now());
            setField(ticket, "updatedAt", Instant.now());
            setField(ticket, "resolvedAt", null);
            return ticket;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = SupportTicket.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
