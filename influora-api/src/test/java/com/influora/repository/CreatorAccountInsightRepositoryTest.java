package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.CreatorAccountInsight;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * Account insights snapshots (2026-09-24) through a real JPA round trip: the newest fetch is the
 * current one, and a number Meta did not return comes back null, not 0. Real MySQL
 * (ddl-auto=validate against V20260924100000) is covered by CI's Testcontainers boot tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = CreatorAccountInsight.class)
@EnableJpaRepositories(
        basePackageClasses = CreatorAccountInsightRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CreatorAccountInsightRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:creator_account_insight_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class CreatorAccountInsightRepositoryTest {

    private static final String CREATOR = "01HCREATORACCOUNTINS01";

    @Autowired private CreatorAccountInsightRepository repository;

    private static CreatorAccountInsight row(String id, Long reach, Long views, String fetchedAt) {
        return new CreatorAccountInsight(
                id, CREATOR, "INSTAGRAM", LocalDate.of(2026, 8, 27), LocalDate.of(2026, 9, 23),
                reach, views, 10L, 5L, null, "META_API", Instant.parse(fetchedAt));
    }

    @Test
    @DisplayName("the newest fetch is the current snapshot; a missing number stays null")
    void newestSnapshotWins() {
        repository.save(row("01HACCOUNTINSIGHTOLD01", 900L, 1000L, "2026-09-22T23:30:00Z"));
        repository.save(row("01HACCOUNTINSIGHTNEW01", 1200L, null, "2026-09-23T23:30:00Z"));

        CreatorAccountInsight current =
                repository.findFirstByCreatorProfileIdOrderByFetchedAtDesc(CREATOR).orElseThrow();

        assertEquals(1200L, current.getReach());
        assertNull(current.getViews());
        assertNull(current.getProfileLinksTaps());
        assertEquals(LocalDate.of(2026, 8, 27), current.getPeriodStart());
        assertTrue(current.getCreatedAt() != null, "created_at is filled on insert");
    }
}
