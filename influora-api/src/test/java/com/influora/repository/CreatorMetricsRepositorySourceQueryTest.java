package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.influora.domain.entity.CreatorMetric;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * F-0961: the analytics headline reads {@link CreatorMetricsRepository
 * #findByCreatorProfileIdAndDataSourceOrderByTimeDesc}. Filtering by source in the QUERY is what
 * stops a burst of newer creator-declared rows from pushing every Meta-synced row out of the
 * LIMIT-20 page (the old read filtered after the limit). The unit tests mock this finder, so this
 * runs the real derived query.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = CreatorMetric.class)
@EnableJpaRepositories(
        basePackageClasses = CreatorMetricsRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CreatorMetricsRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:creator_metrics_source_query_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class CreatorMetricsRepositorySourceQueryTest {

    private static final String CREATOR = "01HCREATORSRCQUERY0001";

    @Autowired private CreatorMetricsRepository repository;

    private CreatorMetric row(String id, String source, long followers, String at) {
        return CreatorMetric.builder()
                .id(id)
                .creatorProfileId(CREATOR)
                .platform("INSTAGRAM")
                .dataSource(source)
                .followers(followers)
                .time(Instant.parse(at))
                .fetchedAt(Instant.parse(at))
                .build();
    }

    @Test
    @DisplayName("25 newer creator-declared rows cannot push the Meta rows out of a 20-row page")
    void declaredBurstCannotStarveTheMetaRows() {
        repository.save(row("01HSRCQUERYMETA000001", CreatorMetric.DATA_SOURCE_META_API, 9000, "2026-09-01T00:00:00Z"));
        repository.save(row("01HSRCQUERYMETA000002", CreatorMetric.DATA_SOURCE_META_API, 9100, "2026-09-02T00:00:00Z"));
        for (int i = 0; i < 25; i++) {
            repository.save(
                    row(String.format("01HSRCQUERYDECL%06d", i), CreatorMetric.DATA_SOURCE_CREATOR_REPORTED, 50000 + i,
                            String.format("2026-09-10T00:%02d:00Z", i)));
        }

        List<CreatorMetric> page =
                repository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        CREATOR, CreatorMetric.DATA_SOURCE_META_API, PageRequest.of(0, 20));

        assertEquals(List.of(9100L, 9000L), page.stream().map(CreatorMetric::getFollowers).toList());
    }
}
