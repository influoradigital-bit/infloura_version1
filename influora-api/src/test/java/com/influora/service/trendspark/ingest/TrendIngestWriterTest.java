package com.influora.service.trendspark.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Trend;
import com.influora.domain.enums.TrendCampaignType;
import com.influora.domain.enums.TrendThemeSource;
import com.influora.repository.TrendRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — real Hibernate + H2 proof that {@link TrendIngestWriter}
 * persists a row with its {@code themes} JSON and soft {@code expires_at} intact (done_when: "a
 * passing headline is stored with only known-taxonomy themes and an expiry"). A Mockito-mocked
 * {@code TrendRepository} would prove nothing about whether the {@code json} column type or the
 * {@code @Transactional} write actually round-trips through real Hibernate/H2 — same rationale as
 * {@code ApplicationHistoryEventOrderingTest} in this codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = Trend.class)
@EnableJpaRepositories(
        basePackageClasses = TrendRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!TrendRepository$).*"))
@Import(TrendIngestWriter.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:trend_ingest_writer_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class TrendIngestWriterTest {

    @Autowired private TrendRepository trendRepository;
    @Autowired private TrendIngestWriter writer;

    @Test
    @DisplayName("a screened, tagged trend is stored with its themes JSON and a future expiry")
    void storesRowWithThemesAndExpiry() {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(30, ChronoUnit.DAYS);
        Trend trend =
                Trend.create(
                        "01HTRENDWRITETEST00001",
                        "Diwali fashion haul trending",
                        "[\"news\"]",
                        "IN",
                        LocalDate.now(),
                        30,
                        expiresAt,
                        "[\"festive\",\"fashion\"]",
                        TrendCampaignType.EDUCATIONAL,
                        TrendThemeSource.KEYWORD,
                        now,
                        now);

        int written = writer.writeAll(List.of(trend));

        assertEquals(1, written);
        Optional<Trend> reloaded = trendRepository.findById("01HTRENDWRITETEST00001");
        assertTrue(reloaded.isPresent());
        assertEquals("[\"festive\",\"fashion\"]", reloaded.get().getThemesJson());
        assertTrue(
                reloaded.get().getExpiresAt().isAfter(now),
                "expiry must be in the future for a freshly-ingested trend (F-0778 soft expiry)");
        assertEquals(TrendThemeSource.KEYWORD, reloaded.get().getThemeSource());
    }

    @Test
    @DisplayName("an empty list writes nothing and does not error")
    void emptyListIsANoOp() {
        assertEquals(0, writer.writeAll(List.of()));
    }
}
