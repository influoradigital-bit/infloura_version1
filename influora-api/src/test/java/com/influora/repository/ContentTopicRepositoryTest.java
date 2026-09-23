package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.ContentTopic;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * T-CONTENT-TOPICS -- real Hibernate + H2 proof of {@link ContentTopicRepository#findServable}'s
 * WHERE clause: {@code status = 'APPROVED'}, {@code live_from <= :today <= live_until}
 * (inclusive both ends), newest ({@code id}) first.
 *
 * <p>WHY A @DataJpaTest AND NOT A MOCKITO TEST. {@code ContentTopicServiceTest} mocks {@link
 * ContentTopicRepository#findServable} entirely -- it can be told to return whatever rows the
 * test wants, which proves nothing about whether the JPQL actually excludes a DRAFT row, a
 * REJECTED row, or a row outside {@code [live_from, live_until]}. Only a real query against a real
 * database can show that the WHERE clause does what its javadoc claims, which is exactly the gap
 * this class exists to close (see {@code CreatorMetricsRepositorySourceQueryTest} for the same
 * precedent in this module).
 *
 * <p>{@link #newTopic} constructs rows via reflection rather than a public factory on {@link
 * ContentTopic}: that entity is deliberately read-only in production (no setters, no builder --
 * see its class javadoc), because this ticket's only write path for {@code content_topics} is a
 * human typing SQL by hand. Adding a public construction API to the entity just to seed this test
 * would be the first crack in that "no application write path" guarantee, so the test reaches
 * around it with reflection instead of the entity offering one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = ContentTopic.class)
@EnableJpaRepositories(
        basePackageClasses = ContentTopicRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!ContentTopicRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:content_topics_query_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class ContentTopicRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    @Autowired private ContentTopicRepository repository;

    private static ContentTopic newTopic(
            String category, String title, LocalDate liveFrom, LocalDate liveUntil, String status)
            throws ReflectiveOperationException {
        Constructor<ContentTopic> ctor = ContentTopic.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ContentTopic topic = ctor.newInstance();
        setField(topic, "category", category);
        setField(topic, "title", title);
        setField(topic, "angles", "An angle.");
        setField(topic, "liveFrom", liveFrom);
        setField(topic, "liveUntil", liveUntil);
        setField(topic, "region", "India");
        setField(topic, "status", status);
        Instant now = Instant.now();
        setField(topic, "createdAt", now);
        setField(topic, "updatedAt", now);
        return topic;
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = ContentTopic.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("findServable: DRAFT and REJECTED rows are excluded even when in-window")
    void draftAndRejectedAreExcluded() throws ReflectiveOperationException {
        repository.save(
                newTopic("ALL", "Draft topic", TODAY.minusDays(1), TODAY.plusDays(1), ContentTopic.STATUS_DRAFT));
        repository.save(
                newTopic(
                        "ALL",
                        "Rejected topic",
                        TODAY.minusDays(1),
                        TODAY.plusDays(1),
                        ContentTopic.STATUS_REJECTED));
        ContentTopic approved =
                repository.save(
                        newTopic(
                                "ALL",
                                "Approved topic",
                                TODAY.minusDays(1),
                                TODAY.plusDays(1),
                                ContentTopic.STATUS_APPROVED));

        List<ContentTopic> servable = repository.findServable(TODAY);

        assertEquals(List.of(approved.getId()), servable.stream().map(ContentTopic::getId).toList());
    }

    @Test
    @DisplayName("findServable: a live_from in the future excludes the row")
    void futureLiveFromIsExcluded() throws ReflectiveOperationException {
        repository.save(
                newTopic(
                        "ALL",
                        "Not live yet",
                        TODAY.plusDays(1),
                        TODAY.plusDays(10),
                        ContentTopic.STATUS_APPROVED));

        assertTrue(repository.findServable(TODAY).isEmpty());
    }

    @Test
    @DisplayName("findServable: a live_until in the past excludes the row")
    void pastLiveUntilIsExcluded() throws ReflectiveOperationException {
        repository.save(
                newTopic(
                        "ALL",
                        "No longer live",
                        TODAY.minusDays(10),
                        TODAY.minusDays(1),
                        ContentTopic.STATUS_APPROVED));

        assertTrue(repository.findServable(TODAY).isEmpty());
    }

    @Test
    @DisplayName("findServable: live_from == today and live_until == today are BOTH included (inclusive bounds)")
    void boundaryDatesAreIncluded() throws ReflectiveOperationException {
        ContentTopic startsToday =
                repository.save(
                        newTopic(
                                "ALL", "Starts today", TODAY, TODAY.plusDays(5), ContentTopic.STATUS_APPROVED));
        ContentTopic endsToday =
                repository.save(
                        newTopic(
                                "ALL", "Ends today", TODAY.minusDays(5), TODAY, ContentTopic.STATUS_APPROVED));

        List<Long> ids = repository.findServable(TODAY).stream().map(ContentTopic::getId).toList();

        assertTrue(ids.contains(startsToday.getId()), "live_from == today must be included");
        assertTrue(ids.contains(endsToday.getId()), "live_until == today must be included");
    }

    @Test
    @DisplayName("findServable: newest (highest id) first")
    void newestFirst() throws ReflectiveOperationException {
        ContentTopic first =
                repository.save(
                        newTopic(
                                "ALL", "First", TODAY.minusDays(1), TODAY.plusDays(1), ContentTopic.STATUS_APPROVED));
        ContentTopic second =
                repository.save(
                        newTopic(
                                "ALL", "Second", TODAY.minusDays(1), TODAY.plusDays(1), ContentTopic.STATUS_APPROVED));
        ContentTopic third =
                repository.save(
                        newTopic(
                                "ALL", "Third", TODAY.minusDays(1), TODAY.plusDays(1), ContentTopic.STATUS_APPROVED));

        List<Long> ids = repository.findServable(TODAY).stream().map(ContentTopic::getId).toList();

        assertEquals(List.of(third.getId(), second.getId(), first.getId()), ids);
    }
}
