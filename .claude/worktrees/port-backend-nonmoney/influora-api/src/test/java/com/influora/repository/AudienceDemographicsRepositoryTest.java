package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AudienceDemographics;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wave B task B4: unit tests for {@link AudienceDemographics} entity mapping and {@link
 * AudienceDemographicsRepository}'s derived-query signature.
 *
 * <p>Mirrors {@code MediaMetricsRepositoryTest}'s convention — Spring Data JPA derived query, no
 * custom implementation to test in isolation without a real DB, so this confirms the entity
 * builder/getters round-trip correctly and the repository method is callable via Mockito.
 */
@ExtendWith(MockitoExtension.class)
class AudienceDemographicsRepositoryTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR000000001";

    @Mock private AudienceDemographicsRepository repository;

    @Test
    @DisplayName("entity builder: all fields round-trip through getters, defaults applied when omitted")
    void testEntityBuilderRoundTripsFieldsAndAppliesDefaults() {
        Instant time = Instant.parse("2026-07-01T00:00:00Z");
        String ageGenderJson = "{\"F.25-34\":400,\"M.18-24\":250}";
        String countryJson = "{\"US\":1200,\"GB\":300}";
        String cityJson = "{\"New York, NY\":210}";
        String localeJson = "{\"en_US\":900}";

        AudienceDemographics entity =
                AudienceDemographics.builder()
                        .id("01AD1234567890123456789")
                        .time(time)
                        .creatorProfileId(CREATOR_ID)
                        .ageGenderBreakdownJson(ageGenderJson)
                        .countryBreakdownJson(countryJson)
                        .cityBreakdownJson(cityJson)
                        .localeBreakdownJson(localeJson)
                        .build();

        assertEquals("01AD1234567890123456789", entity.getId());
        assertEquals(time, entity.getTime());
        assertEquals(CREATOR_ID, entity.getCreatorProfileId());
        assertEquals(ageGenderJson, entity.getAgeGenderBreakdownJson());
        assertEquals(countryJson, entity.getCountryBreakdownJson());
        assertEquals(cityJson, entity.getCityBreakdownJson());
        assertEquals(localeJson, entity.getLocaleBreakdownJson());
        // Defaults applied by the builder when not explicitly set.
        assertEquals("INSTAGRAM", entity.getPlatform());
        assertEquals("META_API", entity.getDataSource());
        assertTrue(entity.getFetchedAt() != null);
        assertTrue(entity.getCreatedAt() != null);
    }

    @Test
    @DisplayName("entity builder: null breakdown columns are preserved as null (no fabricated empty maps)")
    void testEntityBuilderPreservesNullBreakdowns() {
        AudienceDemographics entity =
                AudienceDemographics.builder()
                        .id("01AD1234567890123456790")
                        .creatorProfileId(CREATOR_ID)
                        .build();

        assertFalse(entity.getAgeGenderBreakdownJson() != null);
        assertFalse(entity.getCountryBreakdownJson() != null);
        assertFalse(entity.getCityBreakdownJson() != null);
        assertFalse(entity.getLocaleBreakdownJson() != null);
    }

    @Test
    @DisplayName("findFirstByCreatorProfileIdOrderByTimeDesc: derived query for latest snapshot")
    void testFindFirstByCreatorProfileIdOrderByTimeDesc() {
        AudienceDemographics entity =
                AudienceDemographics.builder().id("01AD1").creatorProfileId(CREATOR_ID).build();

        when(repository.findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID))
                .thenReturn(Optional.of(entity));

        Optional<AudienceDemographics> result =
                repository.findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID);

        assertTrue(result.isPresent());
        assertEquals(CREATOR_ID, result.get().getCreatorProfileId());
        verify(repository).findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID);
    }

    @Test
    @DisplayName("findFirstByCreatorProfileIdOrderByTimeDesc: no snapshot yet returns empty, not an exception")
    void testFindFirstByCreatorProfileIdOrderByTimeDescReturnsEmptyWhenNoneExist() {
        when(repository.findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID))
                .thenReturn(Optional.empty());

        Optional<AudienceDemographics> result =
                repository.findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID);

        assertTrue(result.isEmpty());
    }
}
