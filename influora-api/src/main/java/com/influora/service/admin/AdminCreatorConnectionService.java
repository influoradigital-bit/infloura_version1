package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.TextSanitizer;
import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.ConnectionRequestStatus;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.domain.enums.ExternalCreatorStatus;
import com.influora.integration.meta.dto.BusinessDiscoveryResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.repository.ExternalCreatorSpecs;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.AdminConnectionDto;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.AdminExternalCreatorDto;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.ImportResult;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.PagedConnectionsDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin console backing service for {@code AdminCreatorConnectionController}
 * (T-CREATORCONNECT-0902). Admin auth is enforced HERE, in the service layer — copy {@code
 * AdminCreatorService}'s discipline exactly (no {@code @PreAuthorize} anywhere in this codebase).
 * Every mutation is audit-logged via {@link AdminAuditLogService} AFTER the row is saved.
 */
@Service
public class AdminCreatorConnectionService {

    private static final Logger log = LoggerFactory.getLogger(AdminCreatorConnectionService.class);
    private static final int MAX_IMPORT_USERNAMES = 50;

    /**
     * Q4.4 (T-CREATORCONNECT-0902, High) — mirrors {@code ExternalCreatorService.USERNAME_PATTERN}
     * exactly (same shape Instagram actually allows: letters/digits/dot/underscore, 1-80 chars,
     * which also enforces the {@code ig_username VARCHAR(80)} column limit for free). Kept as a
     * local copy rather than a shared reference because that constant is {@code private} on a
     * class outside this fix's file scope (promoting it to a shared validator is the correct
     * longer-term follow-up — flagged separately, not done here to avoid touching a file another
     * work package owns concurrently). Before this, {@code normalizeUsername} only trimmed/
     * lower-cased/stripped a leading '@', so garbage like {@code "foo bar"} or {@code "foo)"} was
     * interpolated raw into the Graph API path and, after Meta rejected it, still PERSISTED as a
     * brand-visible stub — and an over-80-char line failed only at flush, discarding the whole
     * batch.
     */
    private static final Pattern IMPORT_USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._]{1,80}$");

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final CreatorConnectionRequestRepository connectionRequestRepository;
    private final ExternalCreatorRepository externalCreatorRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final MetaTokenStorage metaTokenStorage;
    private final com.influora.integration.meta.client.InstagramInsightsClient instagramInsightsClient;
    private final com.influora.integration.msg91.Msg91EmailClient msg91EmailClient;
    private final com.influora.service.InviteTokenService inviteTokenService;
    private final String webBaseUrl;

    public AdminCreatorConnectionService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            CreatorConnectionRequestRepository connectionRequestRepository,
            ExternalCreatorRepository externalCreatorRepository,
            WorkspaceRepository workspaceRepository,
            UserRepository userRepository,
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            MetaTokenStorage metaTokenStorage,
            com.influora.integration.meta.client.InstagramInsightsClient instagramInsightsClient,
            com.influora.integration.msg91.Msg91EmailClient msg91EmailClient,
            com.influora.service.InviteTokenService inviteTokenService,
            @Value("${influora.web-base-url}") String webBaseUrl) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.connectionRequestRepository = connectionRequestRepository;
        this.externalCreatorRepository = externalCreatorRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.metaTokenStorage = metaTokenStorage;
        this.instagramInsightsClient = instagramInsightsClient;
        this.msg91EmailClient = msg91EmailClient;
        this.inviteTokenService = inviteTokenService;
        this.webBaseUrl = webBaseUrl;
    }

    // ------------------------------------------------------------------------------------------
    // GET /admin/creator-connections
    // ------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedConnectionsDto<AdminConnectionDto> list(
            AuthPrincipal principal, String status, String search, int page, int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        ConnectionRequestStatus statusEnum = parseStatus(status);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);

        Specification<CreatorConnectionRequest> spec = buildSpec(statusEnum, search);
        Page<CreatorConnectionRequest> result =
                connectionRequestRepository.findAll(
                        spec,
                        PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<AdminConnectionDto> items = toDtos(result.getContent());
        return new PagedConnectionsDto<>(items, safePage, safePageSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminConnectionDto getById(AuthPrincipal principal, String id) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);
        CreatorConnectionRequest request = requireRequest(id);
        return toDtos(List.of(request)).get(0);
    }

    // ------------------------------------------------------------------------------------------
    // POST /admin/creator-connections/{id}/contacted
    // ------------------------------------------------------------------------------------------

    @Transactional
    public AdminConnectionDto markContacted(
            AuthPrincipal principal, HttpServletRequest httpRequest, String id, String rawNotes) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        CreatorConnectionRequest request = requireRequest(id);
        Map<String, Object> before = Map.of("id", request.getId(), "status", request.getStatus().name());

        String notes = sanitizeNotes(rawNotes);
        request.markContacted(adminContext.requireAdminId(principal), notes);
        connectionRequestRepository.save(request);

        adminAuditLogService.record(
                principal,
                httpRequest,
                "UPDATE",
                "CREATOR_CONNECTION_REQUEST",
                request.getId(),
                before,
                Map.of("id", request.getId(), "status", request.getStatus().name(), "adminNotes", nullToEmpty(notes)),
                notes);
        return toDtos(List.of(request)).get(0);
    }

    // ------------------------------------------------------------------------------------------
    // POST /admin/creator-connections/{id}/decline
    // ------------------------------------------------------------------------------------------

    @Transactional
    public AdminConnectionDto decline(
            AuthPrincipal principal, HttpServletRequest httpRequest, String id, String rawNotes) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        CreatorConnectionRequest request = requireRequest(id);
        Map<String, Object> before = Map.of("id", request.getId(), "status", request.getStatus().name());

        String notes = sanitizeNotes(rawNotes);
        request.markDeclined(adminContext.requireAdminId(principal), notes);
        connectionRequestRepository.save(request);

        adminAuditLogService.record(
                principal,
                httpRequest,
                "REJECT",
                "CREATOR_CONNECTION_REQUEST",
                request.getId(),
                before,
                Map.of("id", request.getId(), "status", request.getStatus().name(), "adminNotes", nullToEmpty(notes)),
                notes);
        return toDtos(List.of(request)).get(0);
    }

    // ------------------------------------------------------------------------------------------
    // POST /admin/creator-connections/{id}/invite
    // ------------------------------------------------------------------------------------------

    @Transactional
    public AdminConnectionDto invite(
            AuthPrincipal principal, HttpServletRequest httpRequest, String id, String email, String rawNotes) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        CreatorConnectionRequest request = requireRequest(id);
        ExternalCreator external =
                externalCreatorRepository
                        .findById(request.getExternalCreatorId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "EXTERNAL_CREATOR_NOT_FOUND",
                                                "External creator not found",
                                                HttpStatus.NOT_FOUND));

        Map<String, Object> beforeCreator =
                Map.of("id", external.getId(), "status", external.getStatus().name(), "email", nullToEmpty(external.getEmail()));
        Map<String, Object> beforeRequest = Map.of("id", request.getId(), "status", request.getStatus().name());

        String notes = sanitizeNotes(rawNotes);
        String normalizedEmail = email.trim();

        external.markInvited(normalizedEmail);
        externalCreatorRepository.save(external);

        request.markInvited(adminContext.requireAdminId(principal), notes);
        connectionRequestRepository.save(request);

        adminAuditLogService.record(
                principal,
                httpRequest,
                "UPDATE",
                "EXTERNAL_CREATOR",
                external.getId(),
                beforeCreator,
                Map.of("id", external.getId(), "status", external.getStatus().name(), "email", normalizedEmail),
                notes);
        adminAuditLogService.record(
                principal,
                httpRequest,
                "UPDATE",
                "CREATOR_CONNECTION_REQUEST",
                request.getId(),
                beforeRequest,
                Map.of("id", request.getId(), "status", request.getStatus().name(), "adminNotes", nullToEmpty(notes)),
                notes);

        sendJoinInvitationEmail(external, request);
        return toDtos(List.of(request)).get(0);
    }

    /**
     * Q4.2 (T-CREATORCONNECT-0902, Medium) — the {@code creator.join_invitation} subject templates
     * {@code {{brand_name}}}; this used to always pass the literal {@code "A brand"}, so every
     * invite read "A brand wants to work with you on Influora" regardless of who actually asked.
     * The request row carries {@code workspaceId}, so the real workspace name is one lookup away —
     * falls back to "A brand" only if that workspace row is somehow gone.
     */
    private void sendJoinInvitationEmail(ExternalCreator external, CreatorConnectionRequest request) {
        String brandName =
                workspaceRepository
                        .findById(request.getWorkspaceId())
                        .map(Workspace::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .orElse("A brand");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("brand_name", brandName);
        data.put("ig_username", external.getIgUsername());
        // Q5.5 — a signed, single-use token bound to this exact external_creators row, never a
        // bare handle (a raw ?handle= param is not a claim — see InviteTokenService's javadoc for
        // why, and Q5.4 for the identical spoof class on a different path). ig_username stays on
        // the URL only as a human-readable hint for the email/link preview, never trusted as data.
        data.put(
                "signup_url",
                webBaseUrl
                        + "/creator/register?ref=influora-invite&handle="
                        + external.getIgUsername()
                        + "&invite_token="
                        + inviteTokenService.issue(external.getId(), external.getInvitedAt()));
        // T-CREATORCONNECT-0902 — pre-account recipient (the external creator has no Influora
        // User row yet), so this bypasses the NotificationEvent/NotificationService#notify
        // pipeline entirely and calls Msg91EmailClient directly — same "sendInviteEmailDirect"
        // precedent as WorkspaceMemberService.
        boolean sent =
                msg91EmailClient.sendTemplateEmail(
                        external.getEmail(), "creator.join_invitation", JsonLists.toJsonObject(data));
        if (!sent) {
            log.error(
                    "creator.join_invitation email delivery failed for externalCreatorId={}",
                    external.getId());
        }
    }

    // ------------------------------------------------------------------------------------------
    // GET /admin/external-creators
    // ------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedConnectionsDto<AdminExternalCreatorDto> listExternalCreators(
            AuthPrincipal principal, String status, String q, int page, int pageSize) {
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        ExternalCreatorStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = ExternalCreatorStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApiException("INVALID_STATUS", "Invalid status: " + status, HttpStatus.BAD_REQUEST);
            }
        }

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        var spec = ExternalCreatorSpecs.withFilters(q, null, null, statusEnum);
        Page<ExternalCreator> result =
                externalCreatorRepository.findAll(
                        spec,
                        PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "updatedAt")));

        List<AdminExternalCreatorDto> items = result.getContent().stream().map(this::toExternalCreatorDto).toList();
        return new PagedConnectionsDto<>(items, safePage, safePageSize, result.getTotalElements());
    }

    // ------------------------------------------------------------------------------------------
    // DELETE /admin/external-creators/{id}
    // ------------------------------------------------------------------------------------------

    /**
     * Q4.4 (T-CREATORCONNECT-0902, High) — before this, a garbage ADMIN_IMPORT stub (or any bad
     * row) was permanent: {@code AdminExternalCreatorController} exposed only GET and POST
     * {@code /import}. Guarded — refuses to delete a row any {@code creator_connection_requests}
     * row still references ({@code fk_ccr_external_creator}), matching this file's existing
     * "check via a Specification on the JpaSpecificationExecutor already on the repository"
     * pattern rather than adding a new derived-query method to a repository file outside this
     * fix's scope. Audit-logged like every other mutation in this service.
     */
    @Transactional
    public void deleteExternalCreator(AuthPrincipal principal, HttpServletRequest httpRequest, String id) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        ExternalCreator external =
                externalCreatorRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "EXTERNAL_CREATOR_NOT_FOUND",
                                                "External creator not found",
                                                HttpStatus.NOT_FOUND));

        boolean referenced =
                connectionRequestRepository.exists(
                        (root, query, cb) -> cb.equal(root.get("externalCreatorId"), id));
        if (referenced) {
            throw new ApiException(
                    "EXTERNAL_CREATOR_REFERENCED",
                    "This creator has one or more connection requests and cannot be deleted",
                    HttpStatus.CONFLICT);
        }

        Map<String, Object> before =
                Map.of(
                        "id", external.getId(),
                        "igUsername", external.getIgUsername(),
                        "status", external.getStatus().name());
        externalCreatorRepository.deleteById(id);

        adminAuditLogService.record(
                principal,
                httpRequest,
                "DELETE",
                "EXTERNAL_CREATOR",
                id,
                before,
                Map.of(),
                "Deleted external creator @" + external.getIgUsername());
    }

    // ------------------------------------------------------------------------------------------
    // POST /admin/external-creators/import
    // ------------------------------------------------------------------------------------------

    /**
     * Q4.3 (T-CREATORCONNECT-0902, Medium) — deliberately NOT {@code @Transactional} at the method
     * level any more. This loop makes up to {@link #MAX_IMPORT_USERNAMES} synchronous, blocking
     * Business Discovery Graph calls; wrapping the whole method in one transaction used to hold a
     * single pooled DB connection open for the entire sequence. Each {@code
     * externalCreatorRepository.save(...)} below now runs with NO ambient transaction, so Spring
     * Data's own per-call implicit transaction commits it immediately — "persist the stub in a
     * short transaction" — rather than the connection being held across every remaining Graph
     * round-trip. A full out-of-band enrichment job (picking up rows via {@code lastSyncedAt IS
     * NULL}) is the further follow-up TASKS.md envisions; not built here (a new {@code @Scheduled}
     * job class is a new production file outside this fix's file scope) — see the rate-limit
     * handling below for what IS fixed now: the batch stops hammering Meta the moment it is
     * throttled, and un-enriched stubs are distinguishable (via {@code lastSyncedAt IS NULL}) for
     * that future job, or a manual re-import, to pick up.
     *
     * <p>Rate-limited stubs are also no longer folded into the plain {@code imported} count returned
     * to the admin console — they are bucketed into {@code skipped} with a reason string instead, so
     * "Imported N, enriched M, skipped K" stops looking like ordinary success on a throttled batch.
     * {@link com.influora.web.dto.admin.AdminCreatorConnectionDtos.ImportResult} has no dedicated
     * rate-limited field of its own; adding one is a DTO change outside this fix's file scope.
     */
    public ImportResult importHandles(AuthPrincipal principal, HttpServletRequest httpRequest, List<String> rawUsernames) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        if (rawUsernames == null || rawUsernames.isEmpty()) {
            throw new ApiException("USERNAMES_REQUIRED", "At least one username is required", HttpStatus.BAD_REQUEST);
        }
        if (rawUsernames.size() > MAX_IMPORT_USERNAMES) {
            throw new ApiException(
                    "TOO_MANY_USERNAMES",
                    "At most " + MAX_IMPORT_USERNAMES + " usernames per import",
                    HttpStatus.BAD_REQUEST);
        }

        Optional<CallerToken> caller = resolveAnyBusinessDiscoveryCaller();

        int imported = 0;
        int enriched = 0;
        List<String> skipped = new ArrayList<>();
        List<String> rateLimitedHandles = new ArrayList<>();
        // Q4.3 — once Meta's own 429 (or our pre-flight throttle) trips once, every remaining call
        // in this batch will hit the same wall; stop attempting Graph calls for the rest of the
        // batch instead of re-triggering (and re-catching) the same rate limit per handle.
        boolean rateLimited = false;

        for (String raw : rawUsernames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String username = normalizeUsername(raw);
            if (username.isBlank()) {
                skipped.add(raw);
                continue;
            }
            // Q4.4 (High) — reject anything that isn't a plausible Instagram handle BEFORE it is
            // ever interpolated into the Graph API path or persisted. Previously garbage like
            // "foo bar" / "foo)" was saved anyway as a brand-visible ADMIN_IMPORT stub once Meta
            // rejected it, and an over-80-char line failed only at flush (discarding the batch —
            // now moot too, since @Transactional no longer wraps the whole loop).
            if (!IMPORT_USERNAME_PATTERN.matcher(username).matches()) {
                skipped.add(raw);
                continue;
            }

            Optional<ExternalCreator> existingOpt = externalCreatorRepository.findByIgUsernameIgnoreCase(username);
            ExternalCreator external =
                    existingOpt.orElseGet(
                            () ->
                                    ExternalCreator.builder()
                                            .id(Ulids.newUlid())
                                            .source(ExternalCreatorSource.ADMIN_IMPORT)
                                            .igUsername(username)
                                            .build());
            boolean wasNew = existingOpt.isEmpty();

            boolean thisEnriched = false;
            if (caller.isPresent() && !rateLimited) {
                try {
                    BusinessDiscoveryResponse response =
                            instagramInsightsClient.businessDiscovery(
                                    caller.get().igUserId(), username, caller.get().accessToken());
                    if (response != null && response.businessDiscovery() != null) {
                        BusinessDiscoveryResponse.BusinessDiscovery bd = response.businessDiscovery();
                        external.applyIgAccountId(bd.id());
                        external.applySync(
                                bd.name(),
                                bd.biography(),
                                bd.profilePictureUrl(),
                                bd.followersCount(),
                                bd.mediaCount(),
                                null,
                                null);
                        thisEnriched = true;
                    }
                } catch (MetaRateLimitException e) {
                    // Q4.3 — caught SEPARATELY from the generic MetaApiException below: this is
                    // NOT "this one handle failed to enrich", it is "stop calling Meta for the
                    // rest of this batch". The stub is still saved (unenriched, lastSyncedAt
                    // stays null) rather than lost — a future re-import or enrichment job can
                    // pick it up — but it is reported distinctly, not silently folded into an
                    // ordinary "imported" count.
                    rateLimited = true;
                    log.warn(
                            "Admin import: Meta rate limit hit at @{} — stopping enrichment for the"
                                    + " rest of this batch, remaining handles saved un-enriched: {}",
                            username,
                            e.getMessage());
                } catch (MetaApiException e) {
                    log.info("Admin import: Business Discovery enrichment failed for @{}: {}", username, e.getMessage());
                }
            }
            boolean thisRateLimitedStub = rateLimited && caller.isPresent();
            if (thisRateLimitedStub) {
                rateLimitedHandles.add(username);
            }

            externalCreatorRepository.save(external);
            if (wasNew) {
                if (thisRateLimitedStub) {
                    // Q4.3 — the row IS still persisted (javadoc above: a future enrichment job or
                    // manual re-import can pick it up via lastSyncedAt IS NULL), but it must NOT be
                    // counted as an ordinary successful import. ImportResult (web/dto/admin/
                    // AdminCreatorConnectionDtos.java) has no dedicated rate-limited/unenriched
                    // field — adding one is a DTO change outside this fix's file scope — so folding
                    // this into `imported` would make "Imported 50, enriched 1" from a batch that
                    // got throttled after handle 2 indistinguishable from a batch that genuinely
                    // succeeded. Routing it into the existing `skipped` list instead means the count
                    // the admin already reads ("Imported N, enriched M, skipped K") stops lying: a
                    // skip count on a batch of otherwise-valid handles is the signal to retry. The
                    // full un-enriched handle list is also in the audit-log entry below for detail.
                    skipped.add(raw + " (Meta rate-limited — saved un-enriched, retry later)");
                } else {
                    imported++;
                }
            }
            if (thisEnriched) {
                enriched++;
            }
        }

        adminAuditLogService.record(
                principal,
                httpRequest,
                "CREATE",
                "EXTERNAL_CREATOR",
                "bulk-import",
                Map.of(),
                Map.of(
                        "id",
                        "bulk-import",
                        "status",
                        "imported=" + imported + " enriched=" + enriched + " rateLimited=" + rateLimitedHandles.size()),
                "Bulk import of "
                        + rawUsernames.size()
                        + " handles"
                        + (rateLimitedHandles.isEmpty()
                                ? ""
                                : " (rate-limited, un-enriched: " + rateLimitedHandles + ")"));

        return new ImportResult(imported, enriched, skipped);
    }

    private record CallerToken(String igUserId, String accessToken) {}

    /** Any valid platform token (brand or creator, FACEBOOK_LOGIN) — admin import is system-wide,
     * not scoped to one workspace, so it is not limited to "the requesting brand's" token. */
    private Optional<CallerToken> resolveAnyBusinessDiscoveryCaller() {
        return metaOAuthTokenRepository.findByRevokedFalseAndExpiresAtAfter(Instant.now()).stream()
                .filter(t -> t.getAuthPath() == com.influora.domain.entity.MetaAuthPath.FACEBOOK_LOGIN)
                .filter(t -> t.getIgBusinessAccountId() != null && !t.getIgBusinessAccountId().isBlank())
                .map(
                        t -> {
                            Optional<String> accessToken =
                                    t.getWorkspaceId() != null
                                            ? metaTokenStorage.getValidToken(t.getWorkspaceId(), t.getCreatorProfileId())
                                            : metaTokenStorage.getValidCreatorToken(t.getCreatorProfileId());
                            return accessToken.map(token -> new CallerToken(t.getIgBusinessAccountId(), token));
                        })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    // ------------------------------------------------------------------------------------------
    // Mapping / helpers
    // ------------------------------------------------------------------------------------------

    private Specification<CreatorConnectionRequest> buildSpec(ConnectionRequestStatus status, String search) {
        Specification<CreatorConnectionRequest> spec = Specification.where(null);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (search != null && !search.isBlank()) {
            Set<String> workspaceIds = findMatchingWorkspaceIds(search);
            Set<String> externalCreatorIds = findMatchingExternalCreatorIds(search);
            if (workspaceIds.isEmpty() && externalCreatorIds.isEmpty()) {
                spec = spec.and((root, query, cb) -> cb.disjunction());
            } else {
                spec =
                        spec.and(
                                (root, query, cb) -> {
                                    var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
                                    if (!workspaceIds.isEmpty()) {
                                        predicates.add(root.get("workspaceId").in(workspaceIds));
                                    }
                                    if (!externalCreatorIds.isEmpty()) {
                                        predicates.add(root.get("externalCreatorId").in(externalCreatorIds));
                                    }
                                    return cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
                                });
            }
        }
        return spec;
    }

    private Set<String> findMatchingWorkspaceIds(String search) {
        Specification<Workspace> nameSpec =
                (root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%");
        return workspaceRepository.findAll(nameSpec).stream().map(Workspace::getId).collect(java.util.stream.Collectors.toSet());
    }

    private Set<String> findMatchingExternalCreatorIds(String search) {
        var spec = ExternalCreatorSpecs.withFilters(search, null, null, null);
        return externalCreatorRepository.findAll(spec).stream()
                .map(ExternalCreator::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private CreatorConnectionRequest requireRequest(String id) {
        return connectionRequestRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CONNECTION_REQUEST_NOT_FOUND",
                                        "Connection request not found",
                                        HttpStatus.NOT_FOUND));
    }

    private ConnectionRequestStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ConnectionRequestStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException("INVALID_STATUS", "Invalid status: " + status, HttpStatus.BAD_REQUEST);
        }
    }

    private List<AdminConnectionDto> toDtos(List<CreatorConnectionRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<String, ExternalCreator> externalCreatorsById = new HashMap<>();
        for (ExternalCreator e :
                externalCreatorRepository.findAllById(
                        requests.stream().map(CreatorConnectionRequest::getExternalCreatorId).distinct().toList())) {
            externalCreatorsById.put(e.getId(), e);
        }
        Map<String, Workspace> workspacesById = new HashMap<>();
        for (Workspace w :
                workspaceRepository.findAllById(
                        requests.stream().map(CreatorConnectionRequest::getWorkspaceId).distinct().toList())) {
            workspacesById.put(w.getId(), w);
        }
        Map<String, User> usersById = new HashMap<>();
        for (User u :
                userRepository.findAllById(
                        requests.stream().map(CreatorConnectionRequest::getRequestedByUserId).distinct().toList())) {
            usersById.put(u.getId(), u);
        }

        return requests.stream()
                .map(
                        r -> {
                            ExternalCreator e = externalCreatorsById.get(r.getExternalCreatorId());
                            Workspace w = workspacesById.get(r.getWorkspaceId());
                            User u = usersById.get(r.getRequestedByUserId());
                            return new AdminConnectionDto(
                                    r.getId(),
                                    r.getStatus().name(),
                                    r.getMessage(),
                                    r.getAdminNotes(),
                                    r.getWorkspaceId(),
                                    w != null ? w.getName() : null,
                                    r.getRequestedByUserId(),
                                    u != null ? u.getEmail() : null,
                                    r.getExternalCreatorId(),
                                    e != null ? e.getIgUsername() : null,
                                    e != null ? e.getDisplayName() : null,
                                    e != null ? e.getProfilePictureUrl() : null,
                                    e != null ? e.getFollowers() : null,
                                    e != null ? e.getEmail() : null,
                                    e != null ? e.getStatus().name() : null,
                                    e != null ? e.getLinkedCreatorProfileId() : null,
                                    r.getCreatedAt(),
                                    r.getHandledAt(),
                                    r.getJoinedNotifiedAt());
                        })
                .toList();
    }

    private AdminExternalCreatorDto toExternalCreatorDto(ExternalCreator e) {
        return new AdminExternalCreatorDto(
                e.getId(),
                e.getSource().name(),
                e.getIgUsername(),
                e.getDisplayName(),
                e.getProfilePictureUrl(),
                e.getFollowers(),
                e.getEngagementRate(),
                e.getCountry(),
                e.getEmail(),
                e.getStatus().name(),
                e.getLinkedCreatorProfileId(),
                e.getInvitedAt(),
                e.getJoinedAt(),
                e.getLastSyncedAt(),
                e.getCreatedAt());
    }

    private static String sanitizeNotes(String rawNotes) {
        String notes = TextSanitizer.sanitizePlainText(rawNotes);
        if (notes != null && notes.length() > 1000) {
            notes = notes.substring(0, 1000);
        }
        return notes;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String normalizeUsername(String raw) {
        String trimmed = raw.trim().toLowerCase();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
