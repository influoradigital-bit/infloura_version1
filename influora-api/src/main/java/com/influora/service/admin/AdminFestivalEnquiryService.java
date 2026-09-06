package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.common.TextSanitizer;
import com.influora.domain.entity.FestivalEnquiry;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.FestivalEnquiryStatus;
import com.influora.domain.enums.FestivalEnquiryType;
import com.influora.repository.FestivalEnquiryRepository;
import com.influora.repository.FestivalEnquirySpecs;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminFestivalEnquiryDtos.AdminFestivalEnquiryDto;
import com.influora.web.dto.admin.AdminFestivalEnquiryDtos.PagedFestivalEnquiriesDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin console backing service for {@code AdminFestivalEnquiryController} (T-FESTIVALBOX-0905).
 *
 * <p>Admin auth is enforced HERE, in the service layer, copying {@code
 * AdminCreatorConnectionService}'s discipline exactly — there is no {@code @PreAuthorize} anywhere
 * in this codebase, so a controller with no service-layer gate is an open route. Every mutation is
 * audit-logged via {@link AdminAuditLogService} AFTER the row is saved.
 */
@Service
public class AdminFestivalEnquiryService {

    private static final Logger log = LoggerFactory.getLogger(AdminFestivalEnquiryService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final FestivalEnquiryRepository festivalEnquiryRepository;

    public AdminFestivalEnquiryService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            FestivalEnquiryRepository festivalEnquiryRepository) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.festivalEnquiryRepository = festivalEnquiryRepository;
    }

    /**
     * One page of enquiries, newest first.
     *
     * <p>MFA-satisfied SUPER_ADMIN/ADMIN, matching every other admin read that returns personal
     * data — these rows hold a submitter's email and phone.
     */
    @Transactional(readOnly = true)
    public PagedFestivalEnquiriesDto list(
            AuthPrincipal principal,
            String rawType,
            String rawStatus,
            String edition,
            String q,
            int page,
            int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        FestivalEnquiryType type = parseTypeFilter(rawType);
        FestivalEnquiryStatus status = parseStatusFilter(rawStatus);

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        Specification<FestivalEnquiry> spec = FestivalEnquirySpecs.withFilters(type, status, edition, q);
        Page<FestivalEnquiry> result =
                festivalEnquiryRepository.findAll(
                        spec,
                        PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        return new PagedFestivalEnquiriesDto(
                result.getContent().stream().map(AdminFestivalEnquiryService::toDto).toList(),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.hasNext(),
                statusCounts());
    }

    /**
     * Move one enquiry to a new status, optionally recording a note.
     *
     * <p>{@code NEW} is refused: it is the value the public insert assigns and nothing else, so
     * allowing an admin to set it would make "never been touched" indistinguishable from "worked
     * and reset", which is the one thing the inbox's unread count depends on.
     */
    @Transactional
    public AdminFestivalEnquiryDto updateStatus(
            AuthPrincipal principal,
            HttpServletRequest httpRequest,
            String id,
            String rawStatus,
            String rawNotes) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        FestivalEnquiry enquiry = requireEnquiry(id);
        FestivalEnquiryStatus next = parseTargetStatus(rawStatus);

        Map<String, Object> before =
                Map.of("id", enquiry.getId(), "status", enquiry.getStatus().name());

        // null notes => leave the existing note alone (see UpdateEnquiryStatusRequest). An empty
        // string is a deliberate clear and must survive sanitization as "", not collapse to null.
        String notes = rawNotes == null ? null : truncate(TextSanitizer.sanitizePlainText(rawNotes), 2000);

        enquiry.applyStatus(next, adminContext.requireAdminId(principal), notes);
        festivalEnquiryRepository.save(enquiry);

        adminAuditLogService.record(
                principal,
                httpRequest,
                "UPDATE",
                "FESTIVAL_ENQUIRY",
                enquiry.getId(),
                before,
                Map.of("id", enquiry.getId(), "status", next.name()),
                notes);

        log.info("Festival enquiry {} moved to {}", enquiry.getId(), next);
        return toDto(enquiry);
    }

    // ----------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------

    /**
     * Totals per status across the WHOLE table, unaffected by the current filter or page — the
     * header's "12 new" must not change as the admin pages forward or searches.
     *
     * <p>Seeded with every enum constant at zero so a status nobody has used yet renders as "0"
     * rather than vanishing from the header, which would read as a broken filter.
     */
    private Map<String, Long> statusCounts() {
        Map<String, Long> out = new HashMap<>();
        for (FestivalEnquiryStatus s : FestivalEnquiryStatus.values()) {
            out.put(s.name(), 0L);
        }
        for (Object[] row : festivalEnquiryRepository.countGroupedByStatus()) {
            out.put(((FestivalEnquiryStatus) row[0]).name(), ((Number) row[1]).longValue());
        }
        return out;
    }

    private FestivalEnquiry requireEnquiry(String id) {
        return festivalEnquiryRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "ENQUIRY_NOT_FOUND",
                                        "Enquiry not found",
                                        HttpStatus.NOT_FOUND));
    }

    /** A blank filter means "all types"; an unrecognised one is a client bug, so it is rejected. */
    private static FestivalEnquiryType parseTypeFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return FestivalEnquiryType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_TYPE", "type must be BRAND or CREATOR", HttpStatus.BAD_REQUEST);
        }
    }

    private static FestivalEnquiryStatus parseStatusFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return FestivalEnquiryStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_STATUS", "Unknown enquiry status", HttpStatus.BAD_REQUEST);
        }
    }

    private static FestivalEnquiryStatus parseTargetStatus(String raw) {
        FestivalEnquiryStatus next = parseStatusFilter(raw);
        if (next == null) {
            throw new ApiException("INVALID_STATUS", "status is required", HttpStatus.BAD_REQUEST);
        }
        if (next == FestivalEnquiryStatus.NEW) {
            throw new ApiException(
                    "INVALID_STATUS",
                    "An enquiry cannot be moved back to NEW",
                    HttpStatus.BAD_REQUEST);
        }
        return next;
    }

    private static AdminFestivalEnquiryDto toDto(FestivalEnquiry e) {
        return new AdminFestivalEnquiryDto(
                e.getId(),
                e.getType().name(),
                e.getStatus().name(),
                e.getEdition(),
                e.getName(),
                e.getEmail(),
                e.getPhone(),
                e.getCompany(),
                e.getWebsite(),
                e.getTier() == null ? null : e.getTier().name(),
                e.getProductCategory(),
                e.getInstagramHandle(),
                e.getFollowers(),
                e.getCity(),
                e.getMessage(),
                e.getAdminNotes(),
                e.getHandledBy(),
                toIso(e.getHandledAt()),
                e.getUtmSource(),
                e.getUtmMedium(),
                e.getUtmCampaign(),
                toIso(e.getCreatedAt()),
                // The provisioning link-back. Null on every enquiry that has not been provisioned,
                // which is most of them — the admin console renders null as an em dash, so "never
                // provisioned" stays visibly distinct from "provisioned but the id is missing".
                e.getProvisionedUserId(),
                e.getProvisionedWorkspaceId(),
                e.getProvisionedCampaignId(),
                toIso(e.getProvisionedAt()));
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
