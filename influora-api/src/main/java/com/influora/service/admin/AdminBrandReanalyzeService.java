package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.AnalysisStatus;
import com.influora.repository.BrandProfileRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FIX 2, 2026-09-12 analyze-site prod incident] The re-drive path for brands whose site analysis
 * is stuck.
 *
 * <p><b>Why this had to exist.</b> {@code AnalysisStatus.FAILED} is terminal. Nothing retries it:
 * {@link AnalyzeSiteTriggerService#runAnalysis} has no backoff and no retry, nothing in {@code
 * com.influora.job} touches {@code BrandProfile}, and there was no admin mapping to re-run one.
 * The only re-drive in the whole codebase was a website-URL *change* -- and both call sites ({@code
 * OnboardingService.saveBrandCompany}, {@code WorkspaceService}) gate the trigger on {@code
 * hasChanged}, so a brand re-saving the SAME url sets nothing in motion. Net effect: fixing the
 * base-url misconfiguration (FIX 1) unblocks NEW signups only. Without this endpoint the 6 brands
 * that failed between 2026-08-30 and today keep a blank profile forever, with no operator action
 * available short of hand-editing rows in MySQL.
 *
 * <p><b>Deliberately a separate service, not a method on {@code AdminBrandService}.</b> That class
 * is built entirely around {@code Workspace}/wallet/campaign repositories and its constructor
 * already takes ten collaborators; bolting {@code BrandProfileRepository} +
 * {@code AnalyzeSiteTriggerService} onto it would churn three unrelated test files' construction
 * sites for no gain. Same package, same {@link AdminContextService} gate, same {@link
 * AdminAuditLogService} trail -- it just owns one verb.
 *
 * <p><b>No existence oracle.</b> {@link #reanalyze} resolves ONLY {@code
 * BrandProfileRepository#findByWorkspaceId}. "No such workspace" and "workspace exists but has no
 * brand_profiles row" are therefore not two branches that happen to return the same status -- they
 * are literally the same branch, so they cannot drift apart later and cannot be used to probe
 * which workspace ids exist. The code/message/status is the same {@code BRAND_NOT_FOUND} /
 * "Brand not found" / 404 triple {@code AdminBrandService#requireBrandWorkspace} already returns.
 *
 * <p><b>Status handling is delegated, never written here.</b> {@link
 * AnalyzeSiteTriggerService#trigger} already does exactly the right thing -- its {@code
 * markAnalyzing} sets {@code ANALYZING} and saves, then publishes the AFTER_COMMIT event that
 * schedules the real call. That is what moves the row off {@code FAILED} so the existing polling
 * UI picks the retry up. This class writing {@code analysisStatus} by hand would be a second,
 * divergeable definition of "a retry is in flight".
 *
 * <p><b>Idempotent-safe.</b> Calling it twice is harmless: there is one {@code brand_profiles} row
 * per workspace and {@code markAnalyzing} is a find-then-save on that row, so no duplicate is
 * possible; the second call simply re-marks {@code ANALYZING} and schedules a second analysis
 * whose result overwrites the first with the same content. It is NOT rejected when the row is
 * already {@code ANALYZING}, on purpose -- a row can sit in {@code ANALYZING} forever if the JVM
 * died mid-call, and refusing there would rebuild the exact "stuck with no operator recourse"
 * trap this class exists to remove.
 */
@Service
public class AdminBrandReanalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AdminBrandReanalyzeService.class);

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final BrandProfileRepository brandProfileRepository;
    private final AnalyzeSiteTriggerService analyzeSiteTrigger;

    public AdminBrandReanalyzeService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            BrandProfileRepository brandProfileRepository,
            AnalyzeSiteTriggerService analyzeSiteTrigger) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.brandProfileRepository = brandProfileRepository;
        this.analyzeSiteTrigger = analyzeSiteTrigger;
    }

    /**
     * Re-runs site analysis for one brand workspace using the website URL already stored on its
     * profile. Never takes a URL from the caller -- an admin endpoint that could point analysis at
     * an arbitrary host would be an SSRF-shaped surface, and the whole point here is to re-drive
     * the brand's own, already-accepted URL.
     *
     * <p>{@code @Transactional} is load-bearing and public-and-proxied on purpose: {@code
     * trigger()}'s {@code TransactionTemplate} joins this transaction (PROPAGATION_REQUIRED), so
     * the {@code ANALYZING} write and the {@code AnalyzeSiteRequestedEvent} publication share one
     * commit and the AFTER_COMMIT listener fires only once that row is durable -- the exact
     * ordering {@code AnalyzeSiteTriggerService}'s class javadoc requires of its callers.
     *
     * @return a small JSON-ready map (raw, unwrapped -- same contract as every other {@code
     *     Admin*Controller}, see {@code AdminBrandController}'s class javadoc)
     * @throws ApiException 404 {@code BRAND_NOT_FOUND} if no brand profile resolves for this
     *     workspace id (see class javadoc: no existence oracle); 400 {@code
     *     BRAND_HAS_NO_WEBSITE_URL} if the profile carries no website URL, since there is then
     *     nothing to analyze and triggering would silently no-op inside {@code trigger()}
     */
    @Transactional
    public Map<String, Object> reanalyze(
            AuthPrincipal principal, HttpServletRequest request, String workspaceId) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        BrandProfile profile =
                brandProfileRepository
                        .findByWorkspaceId(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "BRAND_NOT_FOUND",
                                                "Brand not found",
                                                HttpStatus.NOT_FOUND));

        String websiteUrl = profile.getWebsiteUrl();
        if (websiteUrl == null || websiteUrl.isBlank()) {
            // Refused rather than silently accepted: trigger() no-ops on a blank URL (by design --
            // "callers are not expected to pre-validate"), so returning success here would tell an
            // operator a retry is running when nothing was scheduled at all.
            throw new ApiException(
                    "BRAND_HAS_NO_WEBSITE_URL",
                    "This brand has no website URL on file, so there is nothing to analyze",
                    HttpStatus.BAD_REQUEST);
        }

        AnalysisStatus previousStatus = profile.getAnalysisStatus();

        analyzeSiteTrigger.trigger(workspaceId, websiteUrl);

        log.info(
                "AdminBrandReanalyzeService: re-triggered site analysis for workspace={} (was {})",
                workspaceId,
                previousStatus);

        adminAuditLogService.record(
                principal,
                request,
                "UPDATE",
                "BRAND",
                workspaceId,
                Map.of(
                        "id",
                        workspaceId,
                        "analysisStatus",
                        String.valueOf(previousStatus)),
                Map.of("id", workspaceId, "analysisStatus", AnalysisStatus.ANALYZING.name()),
                "admin re-ran site analysis");

        // LinkedHashMap, not Map.of: previousStatus can legitimately be null on a row written
        // before the status column was populated, and Map.of rejects null values.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("workspaceId", workspaceId);
        body.put("previousStatus", previousStatus == null ? null : previousStatus.name());
        body.put("analysisStatus", AnalysisStatus.ANALYZING.name());
        return body;
    }
}
