package com.influora.web;

import com.influora.service.admin.AdminFestivalEnquiryService;
import com.influora.service.admin.FestivalSponsorProvisioningService;
import com.influora.web.dto.admin.AdminFestivalEnquiryDtos.AdminFestivalEnquiryDto;
import com.influora.web.dto.admin.AdminFestivalEnquiryDtos.PagedFestivalEnquiriesDto;
import com.influora.web.dto.admin.AdminFestivalEnquiryDtos.UpdateEnquiryStatusRequest;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.ExistingSponsorAccountResponse;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.LinkExistingSponsorRequest;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.LinkExistingSponsorResponse;
import com.influora.web.dto.admin.FestivalSponsorProvisioningDtos.ProvisionFestivalSponsorResponse;
import com.influora.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Festival Box enquiry inbox for the admin console (T-FESTIVALBOX-0905). Mounted at
 * {@code /admin/festival-enquiries} (full path {@code /api/v1/admin/festival-enquiries/...} given
 * {@code server.servlet.context-path=/api/v1}) — same convention as every other
 * {@code Admin*Controller}.
 *
 * <p>Returns raw DTOs (unwrapped, no {@code ApiResponse} envelope), matching what
 * {@code apiRequest()} in {@code src/admin/services/api-contracts.ts} expects — the admin client
 * reads {@code response.json()} directly, unlike the brand/creator client which unwraps the
 * envelope.
 *
 * <p>The role gate (SUPER_ADMIN + ADMIN, MFA satisfied) and the audit-log write both live in
 * {@link AdminFestivalEnquiryService}, not here: this codebase uses no {@code @PreAuthorize}, so a
 * controller carries no authorization of its own.
 */
@RestController
@RequestMapping("/admin/festival-enquiries")
public class AdminFestivalEnquiryController {

    private final AdminFestivalEnquiryService adminFestivalEnquiryService;
    private final FestivalSponsorProvisioningService festivalSponsorProvisioningService;

    public AdminFestivalEnquiryController(
            AdminFestivalEnquiryService adminFestivalEnquiryService,
            FestivalSponsorProvisioningService festivalSponsorProvisioningService) {
        this.adminFestivalEnquiryService = adminFestivalEnquiryService;
        this.festivalSponsorProvisioningService = festivalSponsorProvisioningService;
    }

    /** GET /admin/festival-enquiries?type=&status=&edition=&q=&page=&pageSize= */
    @GetMapping
    public PagedFestivalEnquiriesDto list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String edition,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminFestivalEnquiryService.list(principal, type, status, edition, q, page, pageSize);
    }

    /**
     * PATCH /admin/festival-enquiries/{id} — move one enquiry along the pipeline.
     *
     * <p>PATCH rather than a verb-per-status POST (the shape {@code AdminCreatorConnectionController}
     * uses) because status here is a five-value pipeline an account manager moves in both
     * directions, so a POST per transition would be five near-identical endpoints that all do the
     * same thing.
     */
    @PatchMapping("/{id}")
    public AdminFestivalEnquiryDto updateStatus(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest httpRequest,
            @PathVariable String id,
            @Valid @RequestBody UpdateEnquiryStatusRequest body) {
        return adminFestivalEnquiryService.updateStatus(
                principal, httpRequest, id, body.status(), body.notes());
    }

    /**
     * POST /admin/festival-enquiries/{id}/provision — turn a {@code WON}/{@code BRAND} enquiry
     * into a real sponsor: a User, a Workspace, a Wallet, and a {@code DRAFT} Campaign
     * (T-FESTIVALBOX-0905 phase 2). See {@link FestivalSponsorProvisioningService} for the full
     * design — idempotency, the existing-account refusal, and the emailed password-set link.
     */
    @PostMapping("/{id}/provision")
    public ProvisionFestivalSponsorResponse provision(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest httpRequest,
            @PathVariable String id) {
        return festivalSponsorProvisioningService.provision(principal, httpRequest, id);
    }

    /**
     * POST /admin/festival-enquiries/{id}/link-existing — the returning-sponsor path
     * (T-FESTIVALBOX-0905 phase 8). For a {@code WON}/{@code BRAND} enquiry whose email already
     * belongs to an established account (a brand sponsoring a later edition), links this enquiry
     * to the admin-chosen, already-existing workspace and creates only a new {@code DRAFT}
     * Campaign under it — no new User/Workspace/Wallet, no password-set email. Does not loosen
     * {@link #provision}'s {@code EMAIL_ALREADY_REGISTERED} refusal in any way; see {@link
     * FestivalSponsorProvisioningService} class javadoc "Phase 8" section for the full design.
     */
    @PostMapping("/{id}/link-existing")
    public LinkExistingSponsorResponse linkExisting(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest httpRequest,
            @PathVariable String id,
            @Valid @RequestBody LinkExistingSponsorRequest body) {
        return festivalSponsorProvisioningService.linkExisting(
                principal, httpRequest, id, body.workspaceId());
    }

    /**
     * GET /admin/festival-enquiries/{id}/existing-account — makes {@link #provision}'s {@code 409
     * EMAIL_ALREADY_REGISTERED} refusal actionable. Resolves the enquiry's email to whichever
     * existing user/workspace already holds it, so the admin console can offer "link to the
     * existing workspace instead" and pass the returned {@code workspaceId} into {@link
     * #linkExisting}. Admin-gated the same as every other endpoint on this controller — never
     * exposed publicly.
     */
    @GetMapping("/{id}/existing-account")
    public ExistingSponsorAccountResponse existingAccount(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return festivalSponsorProvisioningService.findExistingAccountForEmail(principal, id);
    }
}
