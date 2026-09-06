package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AdminAuditLog;
import com.influora.domain.entity.AdminUser;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.AdminAuditLogRepository;
import com.influora.repository.AdminUserRepository;
import com.influora.security.AuthPrincipal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Proves that the entity types this codebase's admin services actually pass to {@link
 * AdminAuditLogService#record} are present in its private {@code ALLOWED_ENTITY_TYPES} and {@code
 * FIELD_ALLOWLIST} — by exercising the REAL service and asserting a row is persisted.
 *
 * <h2>Why this test exists — the same bug shipped twice</h2>
 *
 * {@code record()} never throws (Rule 5): an unknown {@code entityType} raises {@code
 * IllegalArgumentException} deep inside, which the method's own catch swallows into a {@code
 * log.error}. So a service that passes an unregistered entity type writes NOTHING, forever, and
 * nothing fails.
 *
 * <p>Both {@code FESTIVAL_SPONSOR_PROVISIONING} (phase 2) and {@code CAMPAIGN_COUPON} (phase 11)
 * shipped with exactly that defect. In both cases the service had a unit test doing {@code
 * verify(adminAuditLogService).record(...)} against a MOCK — which passes, because {@code record()}
 * genuinely is called. <b>Mocking the collaborator you are trying to verify proves the call, never
 * the effect.</b> That is the entire reason this test uses a real {@link AdminAuditLogService} with
 * only the repository mocked.
 *
 * <p>Add a case here whenever a service starts passing a new {@code entityType}. A missing entry is
 * invisible in production except as an absent audit trail, which is exactly when you need it.
 */
@ExtendWith(MockitoExtension.class)
class AdminAuditLogEntityTypeAllowlistTest {

    @Mock private AdminAuditLogRepository adminAuditLogRepository;
    @Mock private AdminUserRepository adminUserRepository;
    @Mock private AdminContextService adminContext;

    private AdminAuditLogService service;
    private AuthPrincipal principal;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        // REAL service, mocked repository — the point is to exercise the allow-list checks that a
        // mocked AdminAuditLogService would skip entirely.
        service = new AdminAuditLogService(adminAuditLogRepository, adminUserRepository, adminContext);
        principal = new AuthPrincipal("01HADMIN00000000000001", "admin@influora.in", UserType.ADMIN, null);
        httpRequest = new MockHttpServletRequest();

        // record() resolves the acting admin and orElseThrow()s if absent — and Rule 5's catch
        // would swallow that into a log line, producing exactly the silent no-save this test exists
        // to detect. Stub it so a failure here can only mean an allow-list problem.
        when(adminUserRepository.findById("01HADMIN00000000000001"))
                .thenReturn(
                        Optional.of(
                                AdminUser.create(
                                        "01HADMIN00000000000001",
                                        "admin@influora.in",
                                        "hash",
                                        AdminRole.ADMIN)));
    }

    @Test
    @DisplayName(
            "CAMPAIGN_COUPON (phase 11) persists a row — remove it from ALLOWED_ENTITY_TYPES and"
                    + " every admin coupon issuance becomes silently unaudited")
    void campaignCouponIsAudited() {
        service.record(
                principal,
                httpRequest,
                "CREATE",
                "CAMPAIGN_COUPON",
                "01HCOUPON1234567890AB",
                null,
                Map.of(
                        "id", "01HCOUPON1234567890AB",
                        "campaignId", "01HCAMPAIGN123456789A",
                        "workspaceId", "01HWORKSPACE12345678A",
                        "code", "FEST10",
                        "brandLevel", true),
                null);

        ArgumentCaptor<AdminAuditLog> saved = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository).save(saved.capture());

        assertEquals("CAMPAIGN_COUPON", saved.getValue().getEntityType());
        assertEquals("01HCOUPON1234567890AB", saved.getValue().getEntityId());

        // FIELD_ALLOWLIST must also carry the entity type: without its entry, filterFields falls
        // back to Set.of() and strips every key, persisting a row whose snapshot cannot answer
        // "which code was issued" — the one question this audit exists for.
        String newValue = saved.getValue().getNewValueJson();
        assertTrue(
                newValue != null && newValue.contains("FEST10"),
                "the issued coupon code must survive FIELD_ALLOWLIST filtering, got: " + newValue);
    }

    @Test
    @DisplayName(
            "FESTIVAL_SPONSOR_PROVISIONING (phase 2/8) persists a row — regression pin for the same"
                    + " defect, which shipped on this entity type first")
    void festivalSponsorProvisioningIsAudited() {
        service.record(
                principal,
                httpRequest,
                "CREATE",
                "FESTIVAL_SPONSOR_PROVISIONING",
                "01HENQUIRY123456789AB",
                null,
                Map.of("id", "01HENQUIRY123456789AB"),
                null);

        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }
}
