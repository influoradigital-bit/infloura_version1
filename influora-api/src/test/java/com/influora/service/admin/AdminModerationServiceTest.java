package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.entity.ContentFlag;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.ContentFlagType;
import com.influora.repository.ContentFlagRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminModerationDtos.ActionFlagRequest;
import com.influora.web.dto.admin.AdminModerationDtos.FlagDto;
import com.influora.web.dto.admin.AdminModerationDtos.PagedFlagsDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@code AdminModerationService#actionFlag} and {@code #listFlags}, focused
 * on the ESCALATE branch (P2 — was a 501 stub, then briefly {@code markActioned}-based, now
 * non-terminal per Priya's ruling 2026-07-15: an escalated flag must not vanish from the review
 * queue before a human resolves it). Plain Mockito, no {@code @SpringBootTest} — mirrors {@code
 * AdminSupportServiceTest}'s and {@code AdminBillingServiceTest}'s shape in this package: assert
 * the {@link AdminAuditLogService#record} wiring actually fires with the right
 * action/entityType/reason (not just that the DTO shape looks right), and that role gating +
 * mandatory-reason validation genuinely short-circuit before any mutation or audit write.
 */
@ExtendWith(MockitoExtension.class)
class AdminModerationServiceTest {

    private static final String FLAG_ID = "01HWXYZFLAG00000000001";
    private static final String ADMIN_ID = "01HWXYZADMIN000000000001";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private ContentFlagRepository contentFlagRepository;
    @Mock private AuthPrincipal principal;
    @Mock private HttpServletRequest request;

    private AdminModerationService adminModerationService;
    private AdminUser supportAdmin;

    @BeforeEach
    void setUp() {
        adminModerationService =
                new AdminModerationService(adminContext, adminAuditLogService, contentFlagRepository);
        supportAdmin = AdminUser.create(ADMIN_ID, "support@influora.ai", "hash", AdminRole.SUPPORT);
    }

    @Test
    @DisplayName("ESCALATE marks the flag ESCALATED (non-terminal) with actionTaken=ESCALATE and writes an audit record with the supplied reason")
    void testEscalateActionsFlagAndWritesAudit() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        ContentFlag flag = pendingFlag();
        when(contentFlagRepository.findById(FLAG_ID)).thenReturn(Optional.of(flag));
        when(contentFlagRepository.save(flag)).thenReturn(flag);

        FlagDto result =
                adminModerationService.actionFlag(
                        principal,
                        request,
                        FLAG_ID,
                        new ActionFlagRequest(null, null, "ESCALATE", "Repeat offender, needs SUPER_ADMIN review"));

        assertEquals(ContentFlagStatus.ESCALATED, result.status());
        assertEquals("ESCALATE", result.actionTaken());
        assertEquals(ADMIN_ID, result.reviewedBy());
        assertEquals(ContentFlagStatus.ESCALATED, flag.getStatus());

        verify(contentFlagRepository).save(flag);
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("ESCALATE"),
                        eq("CONTENT_FLAG"),
                        eq(FLAG_ID),
                        any(),
                        any(),
                        eq("Repeat offender, needs SUPER_ADMIN review"));
    }

    @Test
    @DisplayName("ESCALATE with a blank/null reason is rejected with REASON_REQUIRED / 400, no mutation or audit write")
    void testEscalateBlankReasonRejected() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        ContentFlag flag = pendingFlag();
        when(contentFlagRepository.findById(FLAG_ID)).thenReturn(Optional.of(flag));

        ApiException blank =
                assertThrows(
                        ApiException.class,
                        () ->
                                adminModerationService.actionFlag(
                                        principal,
                                        request,
                                        FLAG_ID,
                                        new ActionFlagRequest(null, null, "ESCALATE", "   ")));
        assertEquals("REASON_REQUIRED", blank.getCode());

        ApiException nullReason =
                assertThrows(
                        ApiException.class,
                        () ->
                                adminModerationService.actionFlag(
                                        principal,
                                        request,
                                        FLAG_ID,
                                        new ActionFlagRequest(null, null, "ESCALATE", null)));
        assertEquals("REASON_REQUIRED", nullReason.getCode());

        assertEquals(ContentFlagStatus.PENDING, flag.getStatus());
        verify(contentFlagRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a principal without SUPER_ADMIN/ADMIN/SUPPORT (or MFA unsatisfied) is rejected before any flag lookup")
    void testEscalateInsufficientRoleRejectedBeforeAnyLookup() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenThrow(
                        new ApiException(
                                "INSUFFICIENT_ROLE", "nope", org.springframework.http.HttpStatus.FORBIDDEN));

        assertThrows(
                ApiException.class,
                () ->
                        adminModerationService.actionFlag(
                                principal,
                                request,
                                FLAG_ID,
                                new ActionFlagRequest(null, null, "ESCALATE", "reason")));

        verify(contentFlagRepository, never()).findById(anyString());
        verify(contentFlagRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("ESCALATE on an already-actioned flag is rejected with FLAG_ALREADY_ACTIONED, no mutation or audit write")
    void testEscalateAlreadyActionedFlagRejected() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        ContentFlag flag = pendingFlag();
        flag.markActioned("REMOVE", ADMIN_ID);
        when(contentFlagRepository.findById(FLAG_ID)).thenReturn(Optional.of(flag));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                adminModerationService.actionFlag(
                                        principal,
                                        request,
                                        FLAG_ID,
                                        new ActionFlagRequest(null, null, "ESCALATE", "reason")));
        assertEquals("FLAG_ALREADY_ACTIONED", ex.getCode());

        verify(contentFlagRepository, never()).save(any());
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName(
            "listFlags surfaces ESCALATED flags alongside PENDING ones — an escalated flag does not"
                    + " vanish from the review queue (Priya's ruling, 2026-07-15)")
    void testListFlagsSurfacesEscalatedFlags() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);

        ContentFlag pending = pendingFlag();
        ContentFlag escalated =
                ContentFlag.userFlag(
                        "01HWXYZFLAG00000000002",
                        ContentFlagType.PROFILE,
                        "01HWXYZCONTENT000000002",
                        "preview text 2",
                        "Repeat offender",
                        "01HWXYZUSER0000000002");
        escalated.markEscalated("01HWXYZADMIN000000000099");

        when(contentFlagRepository.findPendingReviewQueue(any(
                        org.springframework.data.domain.Pageable.class)))
                .thenReturn(
                        new org.springframework.data.domain.PageImpl<>(
                                java.util.List.of(escalated, pending)));

        PagedFlagsDto result = adminModerationService.listFlags(principal, 1, 20);

        assertEquals(2, result.items().size());
        assertEquals(
                1,
                result.items().stream()
                        .filter(dto -> dto.status() == ContentFlagStatus.ESCALATED)
                        .count(),
                "escalated flag must still be present in the queue read");
        assertEquals(
                1,
                result.items().stream()
                        .filter(dto -> dto.status() == ContentFlagStatus.PENDING)
                        .count());

        // Widened queue read, not the PENDING-only method. As of Priya's 2026-07-15 ruling,
        // findByStatusOrderByCreatedAtAsc has no production caller left at all (ApprovalWorkflowService
        // switched to findPendingReviewQueue too, for the same reason this service uses it) — this
        // verify is now a pure regression guard against ever reintroducing a PENDING-only read here.
        verify(contentFlagRepository)
                .findPendingReviewQueue(any(org.springframework.data.domain.Pageable.class));
        verify(contentFlagRepository, never())
                .findByStatusOrderByCreatedAtAsc(any(), any());
    }

    @Test
    @DisplayName(
            "an already-ESCALATED flag can still be resolved via REMOVE — escalation is not a dead"
                    + " end, a subsequent admin can action it")
    void testEscalatedFlagCanStillBeActionedAfterward() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT))
                .thenReturn(supportAdmin);
        ContentFlag flag = pendingFlag();
        flag.markEscalated("01HWXYZADMIN000000000099"); // escalated by a first admin
        when(contentFlagRepository.findById(FLAG_ID)).thenReturn(Optional.of(flag));
        when(contentFlagRepository.save(flag)).thenReturn(flag);

        FlagDto result =
                adminModerationService.actionFlag(
                        principal,
                        request,
                        FLAG_ID,
                        new ActionFlagRequest(null, null, "REMOVE", "Confirmed violation on review"));

        assertEquals(ContentFlagStatus.ACTIONED, result.status());
        assertEquals("REMOVE", result.actionTaken());
        assertEquals(ADMIN_ID, result.reviewedBy()); // resolving admin's id wins over the escalator's
        assertEquals(ContentFlagStatus.ACTIONED, flag.getStatus());

        verify(contentFlagRepository).save(flag);
        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("ACTION"),
                        eq("CONTENT_FLAG"),
                        eq(FLAG_ID),
                        any(),
                        any(),
                        eq("Confirmed violation on review"));
    }

    private static ContentFlag pendingFlag() {
        return ContentFlag.userFlag(
                FLAG_ID,
                ContentFlagType.DELIVERABLE,
                "01HWXYZCONTENT000000001",
                "preview text",
                "Inappropriate content",
                "01HWXYZUSER0000000001");
    }
}
