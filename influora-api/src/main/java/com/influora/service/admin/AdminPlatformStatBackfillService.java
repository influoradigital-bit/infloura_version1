package com.influora.service.admin;

import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.ExternalCreatorLinkService;
import com.influora.service.ExternalCreatorLinkService.AdoptOutcome;
import com.influora.web.dto.admin.AdminBackfillDtos.PlatformStatBackfillResult;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * F-0740 — the retroactive half of F-0701.
 *
 * <p>F-0701 taught {@code finishLinking} to write a {@code platform_stats} row when a creator joins
 * through a verified invite, which is what makes them findable by a brand filtering Discover to
 * {@code platforms=INSTAGRAM}. That fix only fires at the moment of joining, so every creator who
 * joined BEFORE it shipped still has no row and is still invisible to the one search a brand
 * actually runs. The Instagram identity for those creators is not lost — it is sitting on their
 * {@code external_creators} row, fetched from Business Discovery, exactly where the live path now
 * reads it from. This walks those rows once and carries it across.
 *
 * <p><b>Why this delegates instead of writing rows itself.</b> A backfill that builds its own row
 * is a second implementation of "what an adopted platform row looks like", and the two drift: the
 * live path grows a guard, the backfill does not, and the difference only ever shows up as
 * inconsistent data nobody can explain. So every write here goes through {@link
 * ExternalCreatorLinkService#adoptExternalPlatformStat}, the same method the live join path calls,
 * and the dry run goes through {@link ExternalCreatorLinkService#wouldAdopt}, the same predicates
 * without the write. This service decides WHICH creators to offer up; it does not decide what
 * happens to them. In particular it inherits, rather than restates, the three properties that make
 * an adopted row honest: it is never verified, it never overwrites a real Meta-synced row, and a
 * per-row failure never takes the run down.
 *
 * <p><b>Idempotent by construction.</b> Re-running is safe and cheap: a creator who already has an
 * INSTAGRAM row comes back {@code ALREADY_PRESENT} and is not touched. The healthy end state is
 * {@code alreadyPresent == scanned}, which is also how an operator can tell the run worked without
 * reading a log.
 */
@Service
public class AdminPlatformStatBackfillService {

    private static final Logger log = LoggerFactory.getLogger(AdminPlatformStatBackfillService.class);

    /** Enough to spot-check a run in Discover; not so many that a large run returns a wall of ids. */
    private static final int MAX_SAMPLES = 10;

    private final AdminContextService adminContext;
    private final ExternalCreatorRepository externalCreatorRepository;
    private final ExternalCreatorLinkService externalCreatorLinkService;

    public AdminPlatformStatBackfillService(
            AdminContextService adminContext,
            ExternalCreatorRepository externalCreatorRepository,
            ExternalCreatorLinkService externalCreatorLinkService) {
        this.adminContext = adminContext;
        this.externalCreatorRepository = externalCreatorRepository;
        this.externalCreatorLinkService = externalCreatorLinkService;
    }

    /**
     * Deliberately NOT {@code @Transactional}. Each adopted row is its own unit of work — the
     * per-row try/catch inside {@code adoptExternalPlatformStat} means one bad row is counted and
     * stepped over, and wrapping the whole walk in a transaction would convert that into "the last
     * row failed, so none of the earlier ones happened". A backfill that is all-or-nothing across
     * hundreds of unrelated creators is strictly worse than one that reports 3 failures out of 400.
     *
     * @param dryRun report what a real run would do, writing nothing. Run this first.
     */
    public PlatformStatBackfillResult backfillFromLinkedExternalCreators(
            AuthPrincipal principal, boolean dryRun) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        List<ExternalCreator> linked = externalCreatorRepository.findByLinkedCreatorProfileIdIsNotNull();

        int written = 0;
        int alreadyPresent = 0;
        int noHandle = 0;
        int failed = 0;
        List<String> samples = new ArrayList<>();

        for (ExternalCreator external : linked) {
            String creatorProfileId = external.getLinkedCreatorProfileId();
            if (creatorProfileId == null || creatorProfileId.isBlank()) {
                // The finder asks the database for IS NOT NULL, so this is unreachable in practice;
                // it is here so a future change to that query cannot turn a null into an NPE
                // halfway through a production backfill.
                continue;
            }

            AdoptOutcome outcome =
                    dryRun
                            ? externalCreatorLinkService.wouldAdopt(external, creatorProfileId)
                            : externalCreatorLinkService.adoptExternalPlatformStat(external, creatorProfileId);

            switch (outcome) {
                case WROTE -> {
                    written++;
                    if (samples.size() < MAX_SAMPLES) {
                        samples.add(creatorProfileId);
                    }
                }
                case ALREADY_PRESENT -> alreadyPresent++;
                case NO_HANDLE -> noHandle++;
                case FAILED -> failed++;
            }
        }

        log.info(
                "F-0740 platform_stats backfill {}: scanned={} written={} alreadyPresent={}"
                        + " noHandle={} failed={}",
                dryRun ? "DRY RUN (nothing written)" : "APPLIED",
                linked.size(),
                written,
                alreadyPresent,
                noHandle,
                failed);

        return new PlatformStatBackfillResult(
                dryRun, linked.size(), written, alreadyPresent, noHandle, failed, List.copyOf(samples));
    }
}
