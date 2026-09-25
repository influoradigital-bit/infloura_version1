package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.CreatorRecommendationStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * Meera intelligence v1, slice 2 -- {@link CreatorRecommendationRepository} against real
 * Hibernate + H2: the JPQL queries parse and filter as intended, both unique keys hold (replay and
 * one-post-one-recommendation are database guarantees), and the DPDP delete removes only the
 * given conversation's rows of the given creator. MySQL type fidelity is the Testcontainers boot
 * test's job ({@code MeeraPhaseB0BootValidationTest}); H2 cannot prove it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = CreatorRecommendation.class)
@EnableJpaRepositories(
        basePackageClasses = CreatorRecommendationRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CreatorRecommendationRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:creator_recommendation_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class CreatorRecommendationRepositoryTest {

    private static final String PROFILE = "01HPROFILE000000000000000A";
    private static final String OTHER_PROFILE = "01HPROFILE000000000000000B";
    private static final Instant T0 = Instant.parse("2026-09-20T04:00:00Z");

    @Autowired private CreatorRecommendationRepository repository;
    @Autowired private EntityManager entityManager;

    private static CreatorRecommendation row(
            String id, String profile, CreatorRecommendationSource source, String ref, String conversationId, Instant createdAt) {
        return CreatorRecommendation.open(
                id, "01HUSER", profile, source, ref, conversationId, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 22),
                ChallengeDayType.REEL, "weekday evening", null, null, null, null, "topic", null, "pv", "kv", createdAt);
    }

    @Test
    @DisplayName("uk_creator_rec_source: the same (profile, source, source_ref) twice is refused -- replay is a DB no-op")
    void sourceRefIsUnique() {
        repository.saveAndFlush(row("r1", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:0", "c1", T0));
        // Same ref under another source or another creator is fine.
        repository.saveAndFlush(row("r2", PROFILE, CreatorRecommendationSource.SCRIPT_CARD, "m1:0", "c1", T0));
        repository.saveAndFlush(row("r3", OTHER_PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:0", "c1", T0));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(row("r4", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:0", "c1", T0)));
    }

    @Test
    @DisplayName("uk_creator_rec_media: one post fills at most one row per creator; unmatched (NULL) rows never collide")
    void matchedMediaIsUnique() {
        CreatorRecommendation a = row("r1", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:0", "c1", T0);
        CreatorRecommendation b = row("r2", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:1", "c1", T0);
        repository.saveAndFlush(row("r0", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:9", "c1", T0));
        repository.saveAndFlush(row("r9", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:8", "c1", T0));
        a.matchTo("post1", true, null, "acct");
        repository.saveAndFlush(a);
        b.matchTo("post1", true, null, "acct");
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(b));
    }

    @Test
    @DisplayName(
            "queries: existing refs, live rows in created_at/id order, claimed media ids, rows since an"
                    + " instant, and the DPDP delete of one conversation of one creator only")
    void queriesAndDpdpDelete() {
        CreatorRecommendation settled = row("r3", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:2", "c1", T0);
        settled.matchTo("postS", true, null, "acct");
        settled.settle(100L, 10L, null, 3, null, T0);
        repository.saveAll(
                List.of(
                        row("r2", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:1", "c1", T0),
                        row("r1", PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, "m1:0", "c1", T0),
                        settled,
                        row("r4", PROFILE, CreatorRecommendationSource.CHALLENGE, "ch:0", null, T0.minusSeconds(60)),
                        row("r5", PROFILE, CreatorRecommendationSource.SCRIPT_CARD, "m2:0", "c2", T0.plusSeconds(60)),
                        row("r6", OTHER_PROFILE, CreatorRecommendationSource.SCRIPT_CARD, "m3:0", "c1", T0)));
        repository.flush();
        entityManager.clear();

        assertEquals(
                new TreeSet<>(Set.of("m1:0", "m1:2")),
                new TreeSet<>(
                        repository.findExistingSourceRefs(
                                PROFILE, CreatorRecommendationSource.PLAN_MY_WEEK, List.of("m1:0", "m1:2", "m1:7"))));
        assertEquals(
                List.of("r4", "r1", "r2", "r5"),
                repository
                        .findByCreatorProfileIdAndStatusInOrderByCreatedAtAscIdAsc(
                                PROFILE, List.of(CreatorRecommendationStatus.OPEN, CreatorRecommendationStatus.MATCHED))
                        .stream()
                        .map(CreatorRecommendation::getId)
                        .toList());
        assertEquals(List.of("postS"), repository.findClaimedMediaIds(PROFILE));
        assertEquals(
                List.of("r1", "r2", "r3", "r5"),
                repository.findByCreatorProfileIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(PROFILE, T0).stream()
                        .map(CreatorRecommendation::getId)
                        .toList());

        int deleted = repository.deleteByCreatorProfileIdAndConversationId(PROFILE, "c1");
        entityManager.clear();

        assertEquals(3, deleted, "r1, r2, r3 -- the conversation's plan rows of THIS creator");
        assertEquals(
                new TreeSet<>(Set.of("r4", "r5", "r6")),
                new TreeSet<>(repository.findAll().stream().map(CreatorRecommendation::getId).toList()),
                "challenge rows, other conversations and other creators are untouched");
    }
}
