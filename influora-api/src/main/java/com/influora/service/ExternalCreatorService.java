package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.CreatorAlreadyOnInfluoraException;
import com.influora.common.JsonLists;
import com.influora.common.TextSanitizer;
import com.influora.common.Ulids;
import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.ConnectionRequestStatus;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.domain.enums.ExternalCreatorStatus;
import com.influora.integration.meta.client.CreatorMarketplaceClient;
import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.BusinessDiscoveryResponse;
import com.influora.integration.meta.dto.CreatorMarketplaceCreatorsResponse;
import com.influora.integration.meta.dto.FacebookAccountsListResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.repository.ExternalCreatorSpecs;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.event.CreatorConnectionRequestedEvent;
import com.influora.web.dto.creator.ExternalCreatorDtos.ConnectionRequestResponse;
import com.influora.web.dto.creator.ExternalCreatorDtos.ExternalCreatorResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brand-facing service backing {@code ExternalCreatorController} (T-CREATORCONNECT-0902,
 * .proof-os/tasks/T-CREATORCONNECT-0902/TASKS.md §Backend API contract). Never fakes creator
 * data: {@link #lookup} and {@link #list}'s Marketplace leg degrade to an honest 503 /
 * table-only read rather than inventing a card (wiki/decisions/2026-09-02-what-we-need.md).
 */
@Service
public class ExternalCreatorService {

    private static final Logger log = LoggerFactory.getLogger(ExternalCreatorService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._]{1,80}$");

    /** Q3.5 — rolling window a "new/reopened connect request" cap is measured over. */
    private static final Duration CONNECT_CAP_WINDOW = Duration.ofDays(1);

    /**
     * Q3.5 (T-CREATORCONNECT-0902) — config-driven per-workspace cap on new/reopened {@code
     * POST /creators/external/{id}/connect} requests (each one fires an admin email — see
     * {@link #connect}). Field-level {@code @Value} (not a constructor parameter) so unit tests
     * constructing this service directly via {@code new ExternalCreatorService(...)} are
     * unaffected and keep this Java-declared default. {@code <= 0} disables the cap.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${influora.creator-connect.daily-cap-per-workspace:50}")
    private int dailyConnectCapPerWorkspace = 50;

    /**
     * Q7.3 — process-local, short-TTL cache of the resolved Marketplace Page access token per
     * workspace. {@code tryEnrichFromMarketplace} used to re-resolve this via {@code GET
     * /me/accounts} on every {@code list()} call; the Page token does not rotate independently of
     * the underlying user token, so re-resolving it on every request call was one of the two
     * synchronous Graph round-trips this method made per list load. Not distributed (each app
     * instance holds its own copy) — acceptable for a cache whose only job is cutting down
     * redundant Graph calls, never a source of truth.
     */
    private final Map<String, CachedPageToken> pageTokenCache = new ConcurrentHashMap<>();

    private static final Duration PAGE_TOKEN_CACHE_TTL = Duration.ofMinutes(10);

    private record CachedPageToken(FacebookAccountsListResponse.PageWithInstagram page, Instant cachedAt) {}

    private final BrandContextService brandContextService;
    private final ExternalCreatorRepository externalCreatorRepository;
    private final CreatorConnectionRequestRepository connectionRequestRepository;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final MetaTokenStorage metaTokenStorage;
    private final MetaApiProperties metaApiProperties;
    private final InstagramInsightsClient instagramInsightsClient;
    private final FacebookPageClient facebookPageClient;
    private final CreatorMarketplaceClient creatorMarketplaceClient;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Q1.4/Q3.4 (T-CREATORCONNECT-0902) — runs the racy write inside {@link #upsertFromBusinessDiscovery}
     * and {@link #connect} in its OWN {@code REQUIRES_NEW} transaction via a hand-driven {@link
     * TransactionTemplate}, deliberately NOT {@code @Transactional(propagation = REQUIRES_NEW)} on a
     * private method — this class has no interface and {@code lookup()}/{@code connect()} call the
     * racy write via plain {@code this.method()}, which bypasses Spring AOP's proxy entirely and
     * would silently run the "REQUIRES_NEW" method in the SAME ambient transaction (self-invocation
     * defeats method-level {@code @Transactional}; see Spring's own docs on the proxy limitation).
     * {@code TransactionTemplate} demarcates the transaction programmatically instead, so no proxy
     * is needed and self-invocation is a non-issue.
     *
     * <p>Per the JPA spec (EntityManager javadoc), every {@code PersistenceException} other than
     * {@code NoResult}/{@code NonUniqueResult}/{@code LockTimeout}/{@code QueryTimeout} marks the
     * CURRENT transaction rollback-only the instant it is thrown (Hibernate's {@code
     * ExceptionConverterImpl#handlePersistenceException} calls {@code markForRollbackOnly}) — so a
     * {@code DataIntegrityViolationException} from {@code saveAndFlush()} caught and swallowed in
     * {@code lookup()}/{@code connect()}'s OWN transaction still dooms that transaction's eventual
     * commit to {@code RollbackException -> TransactionSystemException} (a 500 {@code
     * GlobalExceptionHandler} has no handler for), even though the catch appears to recover and
     * return a normal 200/409. Isolating the write in its own {@code REQUIRES_NEW} transaction means
     * a losing writer's constraint violation rolls back only THAT inner transaction; the caller's
     * outer transaction — and its subsequent re-read — is untouched and commits normally.
     */
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ExternalCreatorService(
            BrandContextService brandContextService,
            ExternalCreatorRepository externalCreatorRepository,
            CreatorConnectionRequestRepository connectionRequestRepository,
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            MetaTokenStorage metaTokenStorage,
            MetaApiProperties metaApiProperties,
            InstagramInsightsClient instagramInsightsClient,
            FacebookPageClient facebookPageClient,
            CreatorMarketplaceClient creatorMarketplaceClient,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.brandContextService = brandContextService;
        this.externalCreatorRepository = externalCreatorRepository;
        this.connectionRequestRepository = connectionRequestRepository;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.metaTokenStorage = metaTokenStorage;
        this.metaApiProperties = metaApiProperties;
        this.instagramInsightsClient = instagramInsightsClient;
        this.facebookPageClient = facebookPageClient;
        this.creatorMarketplaceClient = creatorMarketplaceClient;
        this.eventPublisher = eventPublisher;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("ExternalCreatorService.requiresNew");
    }

    /**
     * Runs {@code write} in the isolated REQUIRES_NEW transaction described on {@link
     * #requiresNewTransactionTemplate}. A {@code DataIntegrityViolationException} thrown by {@code
     * write} propagates out of this call (the inner transaction rolls back; the outer one does not)
     * for the caller to catch — see {@link #upsertFromBusinessDiscovery} and {@link #connect}.
     */
    private <T> T runInNewTransaction(TransactionCallback<T> write) {
        return requiresNewTransactionTemplate.execute(write);
    }

    public record PageResult<T>(List<T> items, long total, boolean hasMore) {}

    // ------------------------------------------------------------------------------------------
    // GET /creators/external
    // ------------------------------------------------------------------------------------------

    /**
     * Q7.3 — deliberately NOT {@code @Transactional}. {@code tryEnrichFromMarketplace} below makes
     * up to two synchronous Graph HTTP calls; wrapping this method would hold a pooled Hikari
     * connection open (Spring's transaction interceptor opens one before the method body runs)
     * for the full round-trip of BOTH calls, on every brand's every Discover page load, with the
     * default pool at 10 connections. Each JPA repository call below still runs inside its own
     * short-lived, Spring Data-managed transaction (`SimpleJpaRepository` is transactional per
     * call) — no atomicity is lost, only the artificial connection-holding span around the HTTP
     * calls.
     */
    public PageResult<ExternalCreatorResponse> list(
            AuthPrincipal principal, String q, Long minFollowers, Long maxFollowers, int page, int limit) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);

        if (metaApiProperties.getCreatorMarketplace().isEnabled()) {
            tryEnrichFromMarketplace(workspace, q, minFollowers, maxFollowers);
        }

        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        var spec = ExternalCreatorSpecs.withFilters(q, minFollowers, maxFollowers, null);
        Page<ExternalCreator> result =
                externalCreatorRepository.findAll(
                        spec,
                        PageRequest.of(safePage - 1, safeLimit, Sort.by(Sort.Direction.DESC, "updatedAt")));

        Map<String, CreatorConnectionRequest> requestsByExternalCreatorId =
                loadWorkspaceRequestsByExternalCreatorId(workspace.getId());

        List<ExternalCreatorResponse> items =
                result.getContent().stream()
                        .map(e -> toResponse(e, requestsByExternalCreatorId.get(e.getId())))
                        .toList();
        return new PageResult<>(items, result.getTotalElements(), result.hasNext());
    }

    /** Best-effort — never blocks or fails the read. Logs at INFO and falls back to the table. */
    private void tryEnrichFromMarketplace(Workspace workspace, String q, Long minFollowers, Long maxFollowers) {
        try {
            Optional<MetaOAuthToken> brandToken = firstUsableBrandToken(workspace.getId());
            if (brandToken.isEmpty()) {
                log.info(
                        "CreatorMarketplaceClient enabled but workspace={} has no usable page token —"
                                + " falling back to external_creators table",
                        workspace.getId());
                return;
            }
            String userAccessToken =
                    metaTokenStorage.getValidToken(workspace.getId(), brandToken.get().getCreatorProfileId())
                            .orElse(null);
            if (userAccessToken == null) {
                return;
            }
            FacebookAccountsListResponse.PageWithInstagram page =
                    resolveCachedPageToken(workspace.getId(), userAccessToken);
            if (page == null || page.instagramBusinessAccount() == null) {
                log.info(
                        "CreatorMarketplaceClient enabled but no Page access token resolved for"
                                + " workspace={} — falling back to external_creators table",
                        workspace.getId());
                return;
            }
            CreatorMarketplaceCreatorsResponse response =
                    creatorMarketplaceClient.search(
                            page.instagramBusinessAccount().id(),
                            page.accessToken(),
                            new CreatorMarketplaceClient.SearchParams(q, null, minFollowers, maxFollowers, null));
            if (response == null || response.data() == null) {
                return;
            }
            for (CreatorMarketplaceCreatorsResponse.Creator creator : response.data()) {
                upsertFromMarketplace(creator);
            }
        } catch (MetaApiException e) {
            log.info(
                    "CreatorMarketplaceClient call failed for workspace={} — falling back to"
                            + " external_creators table: {}",
                    workspace.getId(),
                    e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected CreatorMarketplaceClient enrichment failure (swallowed)", e);
        }
    }

    /**
     * Q7.3 — 10-minute process-local cache, keyed by workspace: the Page access token does not
     * rotate independently of the user token it was resolved from, so re-fetching {@code GET
     * /me/accounts} on every {@code list()} call was pure waste and the second of the two
     * synchronous Graph calls this enrichment made per request.
     */
    private FacebookAccountsListResponse.PageWithInstagram resolveCachedPageToken(
            String workspaceId, String userAccessToken) {
        CachedPageToken cached = pageTokenCache.get(workspaceId);
        if (cached != null && cached.cachedAt().plus(PAGE_TOKEN_CACHE_TTL).isAfter(Instant.now())) {
            return cached.page();
        }
        FacebookAccountsListResponse.PageWithInstagram page =
                facebookPageClient.resolvePageAccessToken(userAccessToken);
        if (page != null) {
            pageTokenCache.put(workspaceId, new CachedPageToken(page, Instant.now()));
        }
        return page;
    }

    /**
     * Q1.2 — Business Discovery and {@code /me/accounts} are FACEBOOK_LOGIN-only
     * (InstagramInsightsClient/MetaGraphApiClient), so a workspace-scoped token must be filtered
     * the same way the creator-fallback path already is. Unreachable today (nothing yet writes
     * {@code authPath} on a workspace-scoped row), but a future brand Instagram-Login flow would
     * otherwise select an unusable INSTAGRAM_LOGIN row here and 503 instead of falling through.
     */
    private Optional<MetaOAuthToken> firstUsableBrandToken(String workspaceId) {
        return metaOAuthTokenRepository.findByWorkspaceIdAndRevokedFalse(workspaceId).stream()
                .filter(t -> t.getAuthPath() == com.influora.domain.entity.MetaAuthPath.FACEBOOK_LOGIN)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .findFirst();
    }

    /**
     * Q7.2 — resolves by {@code ig_account_id} first (mirroring {@link #lookup}'s Q1.4 fix) so a
     * Marketplace row is matchable by its stable Meta id, not only by username, and calls {@link
     * ExternalCreator#applyIgAccountId} which the previous version never did — every
     * META_MARKETPLACE row was persisted with a permanently-null {@code ig_account_id}. Guards the
     * same {@code uk_external_creators_ig_account}/{@code uk_external_creators_username} race
     * Business Discovery guards, but per-creator: one bad/racing row in a Marketplace search
     * result must not abort enrichment of the rest of the page.
     */
    private void upsertFromMarketplace(CreatorMarketplaceCreatorsResponse.Creator creator) {
        if (creator.username() == null || creator.username().isBlank()) {
            return;
        }
        String username = normalizeUsername(creator.username());
        boolean hasAccountId = creator.id() != null && !creator.id().isBlank();
        ExternalCreator existingByAccount =
                hasAccountId ? externalCreatorRepository.findByIgAccountId(creator.id()).orElse(null) : null;
        ExternalCreator external =
                existingByAccount != null
                        ? existingByAccount
                        : externalCreatorRepository
                                .findByIgUsername(username)
                                .orElseGet(
                                        () ->
                                                ExternalCreator.builder()
                                                        .id(Ulids.newUlid())
                                                        .source(ExternalCreatorSource.META_MARKETPLACE)
                                                        .igUsername(username)
                                                        .build());
        external.applyIgAccountId(creator.id());
        external.applySync(
                null,
                creator.biography(),
                creator.profilePictureUrl(),
                null,
                null,
                null,
                creator.country());
        try {
            externalCreatorRepository.saveAndFlush(external);
        } catch (DataIntegrityViolationException e) {
            log.info(
                    "Marketplace upsert raced for ig_account_id={}, username={} — skipping this"
                            + " cycle, the next sync reconciles",
                    creator.id(),
                    username);
        }
    }

    // ------------------------------------------------------------------------------------------
    // GET /creators/external/lookup?username=
    // ------------------------------------------------------------------------------------------

    @Transactional
    public ExternalCreatorResponse lookup(AuthPrincipal principal, String username) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        String normalized = normalizeUsername(username == null ? "" : username);
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new ApiException("INVALID_USERNAME", "Invalid Instagram username", HttpStatus.BAD_REQUEST);
        }

        if (!metaApiProperties.isConfigured()) {
            throw new ApiException(
                    "INSTAGRAM_LOOKUP_UNAVAILABLE",
                    "Instagram lookup isn't connected yet",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        CallerToken caller = resolveBusinessDiscoveryCaller(workspace.getId());
        if (caller == null) {
            throw new ApiException(
                    "INSTAGRAM_LOOKUP_UNAVAILABLE",
                    "Instagram lookup isn't connected yet",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        BusinessDiscoveryResponse response;
        try {
            response =
                    instagramInsightsClient.businessDiscovery(caller.igUserId(), normalized, caller.accessToken());
        } catch (MetaApiException e) {
            log.warn("Business Discovery lookup failed for username={}: {}", normalized, e.getMessage());
            throw new ApiException(
                    "INSTAGRAM_LOOKUP_UNAVAILABLE",
                    "Instagram lookup isn't connected yet",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (response == null || response.businessDiscovery() == null) {
            throw new ApiException(
                    "CREATOR_NOT_FOUND", "No such Instagram professional account", HttpStatus.NOT_FOUND);
        }

        ExternalCreator external = upsertFromBusinessDiscovery(response.businessDiscovery(), normalized);

        CreatorConnectionRequest existing =
                connectionRequestRepository
                        .findByWorkspaceIdAndExternalCreatorId(workspace.getId(), external.getId())
                        .orElse(null);
        return toResponse(external, existing);
    }

    /**
     * Q1.4 — resolves by {@code ig_account_id} FIRST, before falling back to username: a creator
     * who renamed their handle on Instagram must not become permanently unlookupable because
     * their old row still holds the account id and a plain username lookup can never find it
     * again. When a rename IS detected, the new username is written via a bulk update ({@link
     * ExternalCreatorRepository#renameIgUsername}) rather than through the unique-constrained
     * entity {@code save()} that resolved the row — routing it through {@code save()} would
     * immediately re-trigger {@code uk_external_creators_username} against whichever OTHER row
     * (if any) currently holds that username. Separately guards the concurrent-insert race two
     * brands hit looking up the same brand-new handle at once: on a {@link
     * DataIntegrityViolationException} from {@code save()}, re-reads by account id then username
     * and returns whichever row actually won, instead of surfacing an unrecoverable 409. Q1.4
     * (2026-09-03 fix) — that recovery re-read runs via {@link #runInNewTransaction} in its own
     * {@code REQUIRES_NEW} transaction, not inside this method's caller's ambient {@code
     * @Transactional}: under MySQL's default REPEATABLE READ, the ambient transaction's
     * consistent-read snapshot was already established before the losing {@code save()} was even
     * attempted, so a re-read on that same connection/snapshot can never see a row the winner
     * committed afterward — it would rethrow the original exception every time. A fresh
     * REQUIRES_NEW transaction opens its own snapshot after the winner's commit, so it observes
     * the winning row.
     */
    private ExternalCreator upsertFromBusinessDiscovery(
            BusinessDiscoveryResponse.BusinessDiscovery bd, String normalizedUsername) {
        ExternalCreator byAccountId =
                bd.id() != null && !bd.id().isBlank()
                        ? externalCreatorRepository.findByIgAccountId(bd.id()).orElse(null)
                        : null;
        boolean rename = byAccountId != null && !normalizedUsername.equalsIgnoreCase(byAccountId.getIgUsername());
        ExternalCreator external =
                byAccountId != null
                        ? byAccountId
                        : externalCreatorRepository
                                .findByIgUsername(normalizedUsername)
                                .orElseGet(
                                        () ->
                                                ExternalCreator.builder()
                                                        .id(Ulids.newUlid())
                                                        .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                                                        .igUsername(normalizedUsername)
                                                        .build());
        external.applyIgAccountId(bd.id());
        external.applySync(
                bd.name(), bd.biography(), bd.profilePictureUrl(), bd.followersCount(), bd.mediaCount(), null, null);

        final ExternalCreator toSave = external;
        try {
            // Q1.4 — saveAndFlush runs in its OWN REQUIRES_NEW transaction (see
            // #requiresNewTransactionTemplate): a DataIntegrityViolationException here must not
            // mark lookup()'s own ambient transaction rollback-only, or the catch below's clean
            // re-read-and-return would still be doomed to a 500 TransactionSystemException at
            // lookup()'s eventual commit. Return value intentionally discarded (not reassigned to
            // `external`) — `toSave` already carries a client-assigned id, so it is the SAME
            // managed instance saveAndFlush would have returned; this also keeps the success path
            // independent of what a test double stubs saveAndFlush to return.
            runInNewTransaction(
                    status -> {
                        externalCreatorRepository.saveAndFlush(toSave);
                        return null;
                    });
        } catch (DataIntegrityViolationException e) {
            log.info(
                    "Business Discovery upsert raced for ig_account_id={}, username={} — re-reading"
                            + " the winning row instead of surfacing a 409",
                    bd.id(),
                    normalizedUsername);
            // Q1.4 (2026-09-03 fix) — this recovery re-read MUST run in its own REQUIRES_NEW
            // transaction, not on lookup()'s own ambient @Transactional connection. lookup() is
            // @Transactional and MySQL defaults to REPEATABLE READ, so that ambient transaction's
            // consistent-read snapshot was already taken (at requireBrandWorkspace, before this
            // method was ever called) — a re-read on that same snapshot is guaranteed blind to a
            // row the concurrent winner committed after the snapshot was established, and
            // orElseThrow(() -> e) would rethrow the original 409 every single time, not just
            // occasionally. runInNewTransaction opens a brand-new transaction/snapshot AFTER the
            // winner's commit, so it actually observes the winning row.
            external =
                    runInNewTransaction(
                            status ->
                                    externalCreatorRepository
                                            .findByIgAccountId(bd.id())
                                            .or(() -> externalCreatorRepository.findByIgUsername(normalizedUsername))
                                            .orElseThrow(() -> e));
            rename = false;
        }

        if (rename) {
            final String renameTargetId = external.getId();
            try {
                // Q1.4 (finding's third path) — renameIgUsername is an unguarded @Modifying bulk
                // UPDATE; if any OTHER row already holds `normalizedUsername` (e.g. an
                // ADMIN_IMPORT stub created for that handle with no ig_account_id yet), this
                // UPDATE violates uk_external_creators_username. Same REQUIRES_NEW isolation as
                // the save above, so a collision here cannot poison lookup()'s own transaction —
                // and unlike the save, there is no separate row to re-read as a "winner": the
                // creator's OLD username stays authoritative and the row remains lookupable by
                // ig_account_id (the branch this method resolved it through in the first place).
                runInNewTransaction(
                        status -> externalCreatorRepository.renameIgUsername(renameTargetId, normalizedUsername));
                // Q1.4 (Priya rejection, 2026-09-03) — `external` is a MANAGED entity in THIS
                // method's own OUTER persistence context (loaded via findByIgAccountId inside
                // lookup()'s @Transactional, several lines above). applySync() above already made
                // it dirty (it unconditionally writes lastSyncedAt/updatedAt — see
                // ExternalCreator#applySync), so it WILL be flushed again — with a full-column
                // UPDATE, since this entity has no @DynamicUpdate and the build does no bytecode
                // enhancement — when the OUTER transaction eventually commits. The rename above
                // just committed in a SEPARATE REQUIRES_NEW transaction/EntityManager, so
                // re-reading by id on THIS (ambient) transaction, as a naive fix might try, just
                // returns this SAME L1-cache-resident instance unchanged: Hibernate does not
                // refresh an already-managed entity's fields from a plain find(), and a locking
                // entityManager.refresh() would not reliably fare better either — under MySQL's
                // default REPEATABLE READ, this transaction's consistent-read snapshot was
                // established at its first read (findByIgAccountId above), so even a fresh SELECT
                // on the ambient connection can still be served from that snapshot. Q1.4
                // (2026-09-03 reconcile) — this is the SAME visibility problem the concurrent-race
                // catch above now solves with runInNewTransaction/REQUIRES_NEW, and a REQUIRES_NEW
                // re-read here WOULD see the just-committed rename too. It is deliberately not
                // used here anyway: unlike the race above (where a genuinely different row may
                // have won and there is nothing to reconcile onto), there is only one row here —
                // `external`, already managed by the OUTER persistence context — and a
                // REQUIRES_NEW read would hand back a second, detached instance whose username
                // would still need copying onto this one before returning it. ExternalCreator
                // intentionally exposes no setter for igUsername (every other field goes through
                // applySync/applyIgAccountId), so this reconciles the in-memory field directly via
                // reflection — the SAME field-based access Hibernate itself uses for this
                // @Access(FIELD) entity — so the entity's in-memory state now matches exactly what
                // the bulk update just durably committed. The response built below is correct, and
                // the outer transaction's eventual flush re-persists that SAME value instead of
                // reverting the rename.
                reconcileManagedIgUsername(external, normalizedUsername);
            } catch (DataIntegrityViolationException e) {
                log.warn(
                        "Business Discovery rename collided for externalCreatorId={} -> new"
                                + " username={} (another row already holds it) — keeping the"
                                + " existing username; still lookupable by ig_account_id",
                        renameTargetId,
                        normalizedUsername,
                        e);
            }
        }
        return external;
    }

    /**
     * Q1.4 — {@link ExternalCreator} deliberately has no public setter for {@code igUsername}
     * (every other field is mutated only through {@code applySync}/{@code applyIgAccountId}), so
     * a rename that has ALREADY committed via {@link ExternalCreatorRepository#renameIgUsername}
     * in its own transaction is reconciled onto the still-managed instance here, directly, so the
     * caller's own transaction commit does not flush a stale value and silently revert the
     * rename. See the call site in {@link #upsertFromBusinessDiscovery} for the full reasoning.
     */
    private static void reconcileManagedIgUsername(ExternalCreator entity, String igUsername) {
        try {
            java.lang.reflect.Field field = ExternalCreator.class.getDeclaredField("igUsername");
            field.setAccessible(true);
            field.set(entity, igUsername);
        } catch (ReflectiveOperationException e) {
            // Unreachable in practice — igUsername is a declared field on this exact class — but
            // if the reflective set ever fails, do not silently keep serving/persisting the OLD
            // username as though the rename never happened.
            log.error(
                    "Failed to reconcile ig_username on managed ExternalCreator id={} after a"
                            + " committed rename to {} — response/next flush may still show the"
                            + " old handle",
                    entity.getId(),
                    igUsername,
                    e);
        }
    }

    private record CallerToken(String igUserId, String accessToken) {}

    /**
     * Requesting brand's own workspace token if present, else Influora's own system caller (Q1.3),
     * else any valid creator FACEBOOK_LOGIN token, else {@code null} — per TASKS.md §Backend API
     * contract, extended to prefer an Influora-owned caller over borrowing a live creator's own
     * token. Without a system caller configured, this falls through to the creator-token path
     * exactly as before.
     */
    private CallerToken resolveBusinessDiscoveryCaller(String workspaceId) {
        Optional<MetaOAuthToken> brandToken = firstUsableBrandToken(workspaceId);
        if (brandToken.isPresent()) {
            MetaOAuthToken token = brandToken.get();
            if (token.getIgBusinessAccountId() != null && !token.getIgBusinessAccountId().isBlank()) {
                Optional<String> accessToken =
                        metaTokenStorage.getValidToken(workspaceId, token.getCreatorProfileId());
                if (accessToken.isPresent()) {
                    return new CallerToken(token.getIgBusinessAccountId(), accessToken.get());
                }
            }
        }

        CallerToken systemCaller = resolveSystemCaller();
        if (systemCaller != null) {
            return systemCaller;
        }

        // Last resort: an arbitrary connected creator's FACEBOOK_LOGIN token (Q1.3) — throttles
        // that creator's own MetricsPollingJob, since MetaGraphApiClient rate-limits on the same
        // igBusinessAccountId key. Cross-user token reuse needs a platform-terms ruling from
        // Swapnil before this path should be relied on in production; escalated, not resolved,
        // here — kept only so lookup still degrades to something rather than a hard 503 while no
        // system caller is configured.
        Optional<MetaOAuthToken> creatorToken =
                metaOAuthTokenRepository
                        .findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(Instant.now())
                        .stream()
                        .filter(t -> t.getAuthPath() == com.influora.domain.entity.MetaAuthPath.FACEBOOK_LOGIN)
                        .filter(t -> t.getIgBusinessAccountId() != null && !t.getIgBusinessAccountId().isBlank())
                        .findFirst();
        if (creatorToken.isEmpty()) {
            return null;
        }
        MetaOAuthToken token = creatorToken.get();
        return metaTokenStorage
                .getValidCreatorToken(token.getCreatorProfileId())
                .map(accessToken -> new CallerToken(token.getIgBusinessAccountId(), accessToken))
                .orElse(null);
    }

    /** Q1.3 — Influora-owned IG Business caller from config; {@code null} when either half of the
     * pair is unset (default), so callers fall through to the next option in the chain. */
    private CallerToken resolveSystemCaller() {
        String systemIgUserId = metaApiProperties.getSystemIgUserId();
        String systemAccessToken = metaApiProperties.getSystemIgAccessToken();
        if (systemIgUserId == null
                || systemIgUserId.isBlank()
                || systemAccessToken == null
                || systemAccessToken.isBlank()) {
            return null;
        }
        return new CallerToken(systemIgUserId, systemAccessToken);
    }

    // ------------------------------------------------------------------------------------------
    // POST /creators/external/{id}/connect
    // ------------------------------------------------------------------------------------------

    @Transactional
    public ConnectionRequestResponse connect(AuthPrincipal principal, String externalCreatorId, String rawMessage) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        ExternalCreator external =
                externalCreatorRepository
                        .findById(externalCreatorId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "EXTERNAL_CREATOR_NOT_FOUND",
                                                "Creator not found",
                                                HttpStatus.NOT_FOUND));

        if (external.getStatus() == ExternalCreatorStatus.JOINED
                && external.getLinkedCreatorProfileId() != null) {
            throw new CreatorAlreadyOnInfluoraException(
                    "This creator is already on Influora", external.getLinkedCreatorProfileId());
        }

        String message = TextSanitizer.sanitizePlainText(rawMessage);
        if (message != null && message.length() > 1000) {
            message = message.substring(0, 1000);
        }

        Optional<CreatorConnectionRequest> existingOpt =
                connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(
                        workspace.getId(), external.getId());

        CreatorConnectionRequest request;
        if (existingOpt.isPresent()) {
            CreatorConnectionRequest existing = existingOpt.get();
            if (existing.getStatus() != ConnectionRequestStatus.DECLINED) {
                // Idempotent: return the existing non-DECLINED request unchanged. No cap check —
                // this call created nothing new.
                return toConnectionResponse(existing, external);
            }
            enforceDailyConnectCap(workspace.getId());
            existing.reopen(message, principal.getUserId());
            request = existing;
        } else {
            enforceDailyConnectCap(workspace.getId());
            request =
                    CreatorConnectionRequest.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspace.getId())
                            .requestedByUserId(principal.getUserId())
                            .externalCreatorId(external.getId())
                            .message(message)
                            .build();
        }

        final CreatorConnectionRequest toSave = request;
        try {
            // saveAndFlush (not save): the uk_ccr_workspace_creator race this guards against
            // (Q3.4 — a double-click or two-tab POST for the same workspace+creator) must surface
            // its DataIntegrityViolationException HERE, synchronously, not at the transaction's
            // eventual commit where this catch could never see it. Runs in its OWN REQUIRES_NEW
            // transaction (see #requiresNewTransactionTemplate) so a losing writer's constraint
            // violation cannot mark THIS method's own ambient transaction rollback-only — without
            // that isolation, the clean re-read-and-return in the catch below would still be
            // doomed to a 500 TransactionSystemException at connect()'s eventual commit instead
            // of the idempotent 200 the contract specifies. Return value intentionally discarded
            // (not reassigned to `request`) — `toSave` already carries a client-assigned id, so it
            // is the SAME managed instance saveAndFlush would have returned; this also keeps the
            // success path independent of what a test double stubs saveAndFlush to return.
            runInNewTransaction(
                    status -> {
                        connectionRequestRepository.saveAndFlush(toSave);
                        return null;
                    });
            eventPublisher.publishEvent(
                    new CreatorConnectionRequestedEvent(
                            principal.getUserId(),
                            workspace.getId(),
                            request.getId(),
                            workspace.getName(),
                            external.getIgUsername(),
                            external.getFollowers(),
                            message));
        } catch (DataIntegrityViolationException e) {
            log.info(
                    "connect() raced on (workspace={}, externalCreator={}) — returning the winning"
                            + " request as the idempotent 200 the contract specifies, not a 409",
                    workspace.getId(),
                    external.getId());
            request =
                    connectionRequestRepository
                            .findByWorkspaceIdAndExternalCreatorId(workspace.getId(), external.getId())
                            .orElseThrow(() -> e);
        }
        return toConnectionResponse(request, external);
    }

    /**
     * Q3.5 — per-workspace cap on NEW/reopened connect requests only; the idempotent
     * already-exists-and-not-DECLINED return path above never reaches here. Counts this
     * workspace's own request history in-memory rather than adding a repository query method
     * (out of this fixer's file ownership) — bounded by how many external creators one workspace
     * can plausibly have requested, nowhere near a performance concern at this cap's scale.
     */
    private void enforceDailyConnectCap(String workspaceId) {
        if (dailyConnectCapPerWorkspace <= 0) {
            return;
        }
        Instant windowStart = Instant.now().minus(CONNECT_CAP_WINDOW);
        long recentCount =
                connectionRequestRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                        .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(windowStart))
                        .count();
        if (recentCount >= dailyConnectCapPerWorkspace) {
            throw new ApiException(
                    "CREATOR_CONNECT_RATE_LIMITED",
                    "You've reached today's limit for creator connection requests. Try again"
                            + " tomorrow.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    // ------------------------------------------------------------------------------------------
    // GET /creators/external/connection-requests
    // ------------------------------------------------------------------------------------------

    @Transactional
    public List<ConnectionRequestResponse> connectionRequests(AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        List<CreatorConnectionRequest> requests =
                connectionRequestRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<String, ExternalCreator> externalCreatorsById = new HashMap<>();
        for (ExternalCreator e :
                externalCreatorRepository.findAllById(
                        requests.stream().map(CreatorConnectionRequest::getExternalCreatorId).distinct().toList())) {
            externalCreatorsById.put(e.getId(), e);
        }
        return requests.stream()
                .map(r -> toConnectionResponse(r, externalCreatorsById.get(r.getExternalCreatorId())))
                .toList();
    }

    // ------------------------------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------------------------------

    private Map<String, CreatorConnectionRequest> loadWorkspaceRequestsByExternalCreatorId(String workspaceId) {
        Map<String, CreatorConnectionRequest> map = new HashMap<>();
        for (CreatorConnectionRequest r :
                connectionRequestRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)) {
            map.put(r.getExternalCreatorId(), r);
        }
        return map;
    }

    private ExternalCreatorResponse toResponse(ExternalCreator e, CreatorConnectionRequest request) {
        boolean verified = e.getStatus() == ExternalCreatorStatus.JOINED && e.getLinkedCreatorProfileId() != null;
        return new ExternalCreatorResponse(
                e.getId(),
                e.getSource().name(),
                e.getIgUsername(),
                e.getDisplayName(),
                e.getBio(),
                e.getProfilePictureUrl(),
                e.getFollowers(),
                e.getMediaCount(),
                e.getEngagementRate(),
                e.getCountry(),
                JsonLists.stringListFromJson(e.getCategoriesJson()).isEmpty()
                        ? null
                        : JsonLists.stringListFromJson(e.getCategoriesJson()),
                e.getStatus().name(),
                verified,
                e.getLinkedCreatorProfileId(),
                request != null ? request.getStatus().name() : null,
                request != null ? request.getId() : null,
                e.getLastSyncedAt(),
                e.getInvitedAt());
    }

    private ConnectionRequestResponse toConnectionResponse(CreatorConnectionRequest r, ExternalCreator e) {
        return new ConnectionRequestResponse(
                r.getId(),
                r.getExternalCreatorId(),
                e != null ? e.getIgUsername() : null,
                e != null ? e.getDisplayName() : null,
                e != null ? e.getProfilePictureUrl() : null,
                r.getMessage(),
                r.getStatus().name(),
                r.getCreatedAt(),
                r.getHandledAt(),
                r.getUpdatedAt(),
                e != null ? e.getLinkedCreatorProfileId() : null);
    }

    private static String normalizeUsername(String raw) {
        String trimmed = raw.trim().toLowerCase();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
