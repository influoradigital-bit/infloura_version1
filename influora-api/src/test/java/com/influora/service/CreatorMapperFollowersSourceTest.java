package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.web.dto.creator.CreatorDtos.CreatorResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0965 — discovery (/creators/search -> CreatorMapper.toResponse) must actually carry the
 * profile's follower provenance; the frontend labels an IMPORTED total "imported, not verified".
 */
class CreatorMapperFollowersSourceTest {

    @Test
    @DisplayName("toResponse carries followersSource from the profile (IMPORTED and VERIFIED)")
    void carriesFollowersSource() {
        CreatorProfile imported = CreatorProfile.newForUser("01HCPF0965MAPPER0001", "01HUSERF0965MAPPER01", "Ira");
        imported.applyFollowerTotals(
                FollowerTotals.from(
                        List.of(
                                PlatformStat.builder()
                                        .id("01HSTATF0965MAPPER01")
                                        .creatorProfileId("01HCPF0965MAPPER0001")
                                        .platform("INSTAGRAM")
                                        .followers(184_000L)
                                        .verified(false)
                                        .source(PlatformStat.SOURCE_IMPORTED)
                                        .build())));
        CreatorProfile verified = CreatorProfile.newForUser("01HCPF0965MAPPER0002", "01HUSERF0965MAPPER02", "Vikas");
        verified.applyFollowerTotals(new FollowerTotals(12_000L, null, FollowerTotals.VERIFIED));

        CreatorResponse importedResponse = CreatorMapper.toResponse(imported, List.of(), false, null);
        CreatorResponse verifiedResponse = CreatorMapper.toResponse(verified, List.of(), false, null);

        assertEquals("IMPORTED", importedResponse.followersSource());
        assertEquals(184_000L, importedResponse.totalFollowers());
        assertEquals("VERIFIED", verifiedResponse.followersSource());
    }
}
