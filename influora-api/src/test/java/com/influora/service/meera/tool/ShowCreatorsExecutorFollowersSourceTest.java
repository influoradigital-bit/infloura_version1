package com.influora.service.meera.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.AuditLogService;
import com.influora.service.FollowerTotals;
import com.influora.web.dto.meera.MeeraToolDtos.CreatorSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * EV-008 - Meera's brand-facing show_creators tool must say what each creator's follower total is
 * made of. Its only provenance-looking field was {@code verified} (CreatorProfile.verified, an
 * identity flag), which the canvas rendered as "Instagram-verified stats" over totals that can be
 * Marketplace/admin-imported.
 */
@ExtendWith(MockitoExtension.class)
class ShowCreatorsExecutorFollowersSourceTest {

    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private AuditLogService auditLogService;

    private static CreatorProfile profile(String id, FollowerTotals totals) {
        CreatorProfile p = CreatorProfile.newForUser(id, "user-" + id, "Creator " + id);
        p.applyFollowerTotals(totals);
        return p;
    }

    @Test
    @DisplayName("EV-008 - each CreatorSummary carries followersSource straight from the profile")
    @SuppressWarnings("unchecked")
    void summariesCarryFollowersSource() {
        CreatorProfile imported =
                profile(
                        "01EV008IMPORTED00000000000",
                        FollowerTotals.from(
                                List.of(
                                        PlatformStat.builder()
                                                .id("01EV008STAT000000000000000")
                                                .creatorProfileId("01EV008IMPORTED00000000000")
                                                .platform("INSTAGRAM")
                                                .followers(80_000L)
                                                .verified(false)
                                                .source(PlatformStat.SOURCE_IMPORTED)
                                                .build())));
        CreatorProfile verified = profile("01EV008VERIFIED00000000000", new FollowerTotals(12_000L, null, FollowerTotals.VERIFIED));
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(imported, verified)));

        ShowCreatorsExecutor executor = new ShowCreatorsExecutor(creatorProfileRepository, auditLogService);
        List<CreatorSummary> creators = executor.execute("ws1", Map.of("niche", "fitness", "count", 5)).creators();

        assertEquals(FollowerTotals.IMPORTED, creators.get(0).followersSource());
        assertEquals(80_000L, creators.get(0).totalFollowers());
        // The identity flag is NOT a follower-provenance claim and stays false for an import.
        assertFalse(creators.get(0).verified());
        assertEquals(FollowerTotals.VERIFIED, creators.get(1).followersSource());
    }
}
