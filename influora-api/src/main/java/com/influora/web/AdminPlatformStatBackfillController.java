package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminPlatformStatBackfillService;
import com.influora.web.dto.admin.AdminBackfillDtos.PlatformStatBackfillResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F-0740 — one-time backfill surface. Same raw-DTO, service-layer-auth discipline as every other
 * {@code Admin*Controller}; the role + MFA check lives in {@link AdminPlatformStatBackfillService}.
 *
 * <p><b>Why an admin endpoint rather than a Flyway migration or a startup runner.</b> A migration
 * cannot mint ULIDs or reuse the live adoption path, so it would have to reimplement the row shape
 * in SQL — the exact drift this backfill is written to avoid. A startup runner would re-run on
 * every boot and needs a guard that is itself untested. An endpoint is explicit, dry-runnable,
 * idempotent, re-runnable, and leaves an admin audit trail of who triggered it.
 */
@RestController
@RequestMapping("/admin/platform-stats")
public class AdminPlatformStatBackfillController {

    private final AdminPlatformStatBackfillService backfillService;

    public AdminPlatformStatBackfillController(AdminPlatformStatBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    /**
     * {@code dryRun} defaults to TRUE. A bulk write across every linked creator is not something an
     * operator should be able to trigger by forgetting a query parameter — they have to ask for it.
     */
    @PostMapping("/backfill")
    public PlatformStatBackfillResult backfill(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        return backfillService.backfillFromLinkedExternalCreators(principal, dryRun);
    }
}
