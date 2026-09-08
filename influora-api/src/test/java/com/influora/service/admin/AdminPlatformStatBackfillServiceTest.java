package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.ExternalCreatorLinkService;
import com.influora.service.ExternalCreatorLinkService.AdoptOutcome;
import com.influora.web.dto.admin.AdminBackfillDtos.PlatformStatBackfillResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * F-0740 — the retroactive half of F-0701.
 *
 * <p>F-0701 made a creator discoverable at the moment they join through a verified invite. It does
 * nothing for the creators who joined before it shipped: their {@code platform_stats} row was never
 * written, so a brand filtering Discover to {@code platforms=INSTAGRAM} still cannot see them, even
 * though the handle and follower count are sitting on their {@code external_creators} row. This
 * class pins the behaviour of the one-time walk that closes that gap.
 *
 * <p>The assertions that matter are the ones about what the backfill does NOT do:
 *
 * <ul>
 *   <li><b>A dry run writes nothing.</b> This is the whole reason dry run exists, and it is the one
 *       property that cannot be checked by reading the response — a run that wrote rows and then
 *       reported {@code dryRun: true} would look identical.
 *   <li><b>It delegates rather than building rows itself.</b> Every write goes through the same
 *       {@code adoptExternalPlatformStat} the live join path calls. A backfill with its own copy of
 *       the row shape drifts from the live path, and the difference surfaces only as inconsistent
 *       data nobody can account for. Verified by asserting the delegate is called and that this
 *       service never touches a PlatformStat itself.
 *   <li><b>One bad row does not sink the run.</b> A FAILED row is counted and stepped over; the
 *       rows after it are still processed. A backfill that aborts on row 3 of 400 while reporting
 *       partial success would be worse than not running it.
 *   <li><b>It is idempotent.</b> Re-running reports ALREADY_PRESENT rather than writing again;
 *       {@code alreadyPresent == scanned} is the healthy end state.
 *   <li><b>It is admin-gated.</b> Role + MFA is checked BEFORE any row is read, not after.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminPlatformStatBackfillServiceTest {

    @Mock private AdminContextService adminContext;
    @Mock private ExternalCreatorRepository externalCreatorRepository;
    @Mock private ExternalCreatorLinkService externalCreatorLinkService;

    private AdminPlatformStatBackfillService service;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        service =
                new AdminPlatformStatBackfillService(
                        adminContext, externalCreatorRepository, externalCreatorLinkService);
        principal = new AuthPrincipal("01HADMINUSER000000001", "admin", null, null);
    }

    private static ExternalCreator linked(String id, String profileId, String handle) {
        ExternalCreator e =
                ExternalCreator.builder()
                        .id(id)
                        .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                        .igUsername(handle)
                        .followers(120_000L)
                        .build();
        e.markJoined(profileId);
        return e;
    }

    @Test
    @DisplayName("dry run writes NOTHING — it may only ask what would happen")
    void dryRun_neverWrites() {
        when(externalCreatorRepository.findByLinkedCreatorProfileIdIsNotNull())
                .thenReturn(List.of(linked("01HEXT1", "01HPROF1", "a.creator"), linked("01HEXT2", "01HPROF2", "b.creator")));
        when(externalCreatorLinkService.wouldAdopt(any(), anyString())).thenReturn(AdoptOutcome.WROTE);

        PlatformStatBackfillResult r = service.backfillFromLinkedExternalCreators(principal, true);

        assertTrue(r.dryRun());
        assertEquals(2, r.scanned());
        assertEquals(2, r.written(), "a dry run still reports what it WOULD write");
        // The one thing a dry run must never do, and the one thing the response cannot prove.
        verify(externalCreatorLinkService, never()).adoptExternalPlatformStat(any(), anyString());
    }

    @Test
    @DisplayName("a real run delegates every write to the live adoption path, never its own row shape")
    void realRun_delegatesToTheLivePath() {
        ExternalCreator a = linked("01HEXT1", "01HPROF1", "a.creator");
        when(externalCreatorRepository.findByLinkedCreatorProfileIdIsNotNull()).thenReturn(List.of(a));
        when(externalCreatorLinkService.adoptExternalPlatformStat(a, "01HPROF1")).thenReturn(AdoptOutcome.WROTE);

        PlatformStatBackfillResult r = service.backfillFromLinkedExternalCreators(principal, false);

        verify(externalCreatorLinkService).adoptExternalPlatformStat(a, "01HPROF1");
        // wouldAdopt is the dry-run predicate; a real run must not be quietly deciding twice.
        verify(externalCreatorLinkService, never()).wouldAdopt(any(), anyString());
        assertEquals(1, r.written());
        assertEquals(List.of("01HPROF1"), r.samples());
    }

    @Test
    @DisplayName("one failing row is counted and stepped over — the rest of the run still happens")
    void oneBadRowDoesNotSinkTheRun() {
        ExternalCreator a = linked("01HEXT1", "01HPROF1", "a.creator");
        ExternalCreator bad = linked("01HEXT2", "01HPROF2", "b.creator");
        ExternalCreator c = linked("01HEXT3", "01HPROF3", "c.creator");
        when(externalCreatorRepository.findByLinkedCreatorProfileIdIsNotNull()).thenReturn(List.of(a, bad, c));
        when(externalCreatorLinkService.adoptExternalPlatformStat(a, "01HPROF1")).thenReturn(AdoptOutcome.WROTE);
        when(externalCreatorLinkService.adoptExternalPlatformStat(bad, "01HPROF2")).thenReturn(AdoptOutcome.FAILED);
        when(externalCreatorLinkService.adoptExternalPlatformStat(c, "01HPROF3")).thenReturn(AdoptOutcome.WROTE);

        PlatformStatBackfillResult r = service.backfillFromLinkedExternalCreators(principal, false);

        assertEquals(3, r.scanned());
        assertEquals(2, r.written());
        assertEquals(1, r.failed());
        verify(externalCreatorLinkService).adoptExternalPlatformStat(c, "01HPROF3");
    }

    @Test
    @DisplayName("re-running is idempotent — already-backfilled creators are reported, not rewritten")
    void secondRunReportsAlreadyPresent() {
        ExternalCreator a = linked("01HEXT1", "01HPROF1", "a.creator");
        ExternalCreator b = linked("01HEXT2", "01HPROF2", "b.creator");
        when(externalCreatorRepository.findByLinkedCreatorProfileIdIsNotNull()).thenReturn(List.of(a, b));
        when(externalCreatorLinkService.adoptExternalPlatformStat(any(), anyString()))
                .thenReturn(AdoptOutcome.ALREADY_PRESENT);

        PlatformStatBackfillResult r = service.backfillFromLinkedExternalCreators(principal, false);

        assertEquals(2, r.scanned());
        assertEquals(0, r.written());
        assertEquals(2, r.alreadyPresent(), "alreadyPresent == scanned is the healthy end state");
        assertTrue(r.samples().isEmpty(), "nothing was written, so there is nothing to spot-check");
    }

    @Test
    @DisplayName("an un-enriched import counts as noHandle rather than being written as a blank row")
    void rowWithNoHandleIsCountedNotWritten() {
        ExternalCreator stub = linked("01HEXT1", "01HPROF1", "");
        when(externalCreatorRepository.findByLinkedCreatorProfileIdIsNotNull()).thenReturn(List.of(stub));
        when(externalCreatorLinkService.adoptExternalPlatformStat(stub, "01HPROF1"))
                .thenReturn(AdoptOutcome.NO_HANDLE);

        PlatformStatBackfillResult r = service.backfillFromLinkedExternalCreators(principal, false);

        assertEquals(1, r.noHandle());
        assertEquals(0, r.written());
    }

    @Test
    @DisplayName("role + MFA is checked BEFORE any row is read, not after the work is done")
    void refusesANonAdminBeforeReadingAnything() {
        doThrow(new ApiException("FORBIDDEN", "no", HttpStatus.FORBIDDEN))
                .when(adminContext)
                .requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        assertThrows(
                ApiException.class, () -> service.backfillFromLinkedExternalCreators(principal, false));

        verify(externalCreatorRepository, never()).findByLinkedCreatorProfileIdIsNotNull();
        verify(externalCreatorLinkService, never()).adoptExternalPlatformStat(any(), anyString());
    }
}
