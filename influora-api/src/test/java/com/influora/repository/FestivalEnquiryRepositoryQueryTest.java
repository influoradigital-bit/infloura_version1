package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.FestivalEnquiry;
import com.influora.domain.enums.FestivalEnquiryStatus;
import com.influora.domain.enums.FestivalTier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * Executes {@link FestivalEnquiryRepository}'s queries against a real (H2) database
 * (T-FESTIVALBOX-0905).
 *
 * <p>WHY THIS EXISTS RATHER THAN A MOCKED REPOSITORY TEST: {@code FestivalEnquiryServiceTest} mocks
 * this repository, so it gives the JPQL and the derived query names ZERO execution coverage. Two
 * specific failures are invisible to it and both are boot- or runtime-only:
 *
 * <ul>
 *   <li>{@link FestivalEnquiryRepository#countGroupedByStatus()} is a hand-written {@code @Query}.
 *       Spring Data parses it when the repository bean is created, so a typo is not a failing test
 *       — it is a crash at application startup.
 *   <li>That same method returns {@code List<Object[]>}, and {@code AdminFestivalEnquiryService}
 *       casts element 0 to {@link FestivalEnquiryStatus}. Whether Hibernate hands back the mapped
 *       enum or the raw {@code String} it stored is a property of the provider, not of our code —
 *       a mock cannot answer it, and getting it wrong is a {@code ClassCastException} the first
 *       time an admin opens the inbox.
 * </ul>
 *
 * <p>Same {@code @DataJpaTest} + {@code @AutoConfigureTestDatabase} + narrowly-scoped {@code
 * @EnableJpaRepositories} pattern as {@code ContractRepositoryUnsignedByCreatorTest}: repository
 * scanning is restricted to the one repository under test, so H2 is never asked to validate other
 * repositories' MySQL-specific queries.
 *
 * <p>NOT CHECKED HERE: that the entity matches the Flyway migration. Hibernate builds this schema
 * from the entity itself ({@code ddl-auto=create-drop}, {@code flyway.enabled=false}), so a column
 * named differently in {@code V20260905130000__festival_enquiries.sql} would still pass every
 * assertion below and fail only at boot under {@code ddl-auto=validate}. That agreement is checked
 * by diffing the two files.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = FestivalEnquiry.class)
@EnableJpaRepositories(
        basePackageClasses = FestivalEnquiryRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!FestivalEnquiryRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:festival_enquiry_query_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class FestivalEnquiryRepositoryQueryTest {

    @Autowired private FestivalEnquiryRepository repository;

    // ------------------------------------------------------------------------------------------
    // countGroupedByStatus — the hand-written @Query
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("countGroupedByStatus: the JPQL parses and executes against a real database")
    void countGroupedByStatusExecutes() {
        // Reaching this line at all proves the repository bean was created, which is where Spring
        // Data parses the @Query — a JPQL typo fails the context load, not this assertion.
        assertTrue(repository.countGroupedByStatus().isEmpty(), "no rows yet");
    }

    @Test
    @DisplayName("countGroupedByStatus: element 0 is the mapped enum, which is what the admin service casts to")
    void countGroupedByStatusReturnsMappedEnum() {
        save(brand("01FESTIVALAAAAAAAAAAAAAAA1", FestivalEnquiryStatus.NEW));

        List<Object[]> rows = repository.countGroupedByStatus();
        assertEquals(1, rows.size());

        // This is the assertion AdminFestivalEnquiryService#statusCounts depends on. If Hibernate
        // returned the raw stored String instead, the cast there would throw ClassCastException the
        // first time an admin opened the inbox.
        assertInstanceOf(FestivalEnquiryStatus.class, rows.get(0)[0]);
        assertInstanceOf(Number.class, rows.get(0)[1]);
        assertEquals(FestivalEnquiryStatus.NEW, rows.get(0)[0]);
        assertEquals(1L, ((Number) rows.get(0)[1]).longValue());
    }

    @Test
    @DisplayName("countGroupedByStatus: groups correctly across several statuses")
    void countGroupedByStatusGroups() {
        save(brand("01FESTIVALAAAAAAAAAAAAAAA1", FestivalEnquiryStatus.NEW));
        save(brand("01FESTIVALAAAAAAAAAAAAAAA2", FestivalEnquiryStatus.NEW));
        save(brand("01FESTIVALAAAAAAAAAAAAAAA3", FestivalEnquiryStatus.CONTACTED));
        save(brand("01FESTIVALAAAAAAAAAAAAAAA4", FestivalEnquiryStatus.WON));

        Map<FestivalEnquiryStatus, Long> counts = new HashMap<>();
        for (Object[] row : repository.countGroupedByStatus()) {
            counts.put((FestivalEnquiryStatus) row[0], ((Number) row[1]).longValue());
        }

        assertEquals(2L, counts.get(FestivalEnquiryStatus.NEW));
        assertEquals(1L, counts.get(FestivalEnquiryStatus.CONTACTED));
        assertEquals(1L, counts.get(FestivalEnquiryStatus.WON));
        // A status with no rows is ABSENT, not zero — the service seeds the full enum with zeros
        // precisely because of this, so the inbox header never drops a column.
        assertTrue(!counts.containsKey(FestivalEnquiryStatus.LOST));
    }

    // ------------------------------------------------------------------------------------------
    // Throttle queries — derived, but their SEMANTICS are what the abuse control rests on
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("countBySourceIpHashAndCreatedAtAfter: counts only this origin, only inside the window")
    void perIpThrottleQueryIsScopedAndWindowed() {
        String mine = "a".repeat(64);
        String other = "b".repeat(64);

        save(withIpAndCreatedAt("01FESTIVALBBBBBBBBBBBBBBB1", mine, Instant.now()));
        save(withIpAndCreatedAt("01FESTIVALBBBBBBBBBBBBBBB2", mine, Instant.now()));
        // Same origin but older than the window — must NOT count, or the throttle would never
        // release and a brand that enquired last month could not enquire again.
        save(
                withIpAndCreatedAt(
                        "01FESTIVALBBBBBBBBBBBBBBB3", mine, Instant.now().minus(3, ChronoUnit.HOURS)));
        // Different origin, inside the window — must NOT count, or one visitor would throttle
        // everybody.
        save(withIpAndCreatedAt("01FESTIVALBBBBBBBBBBBBBBB4", other, Instant.now()));

        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        assertEquals(2L, repository.countBySourceIpHashAndCreatedAtAfter(mine, since));
        assertEquals(1L, repository.countBySourceIpHashAndCreatedAtAfter(other, since));
    }

    @Test
    @DisplayName("countByEmailAndCreatedAtAfter: counts this address inside the window, not others")
    void perEmailThrottleQueryIsScopedAndWindowed() {
        // [SEC: Kabir F-6] This was `countByEmailIgnoreCaseAndCreatedAtAfter` and asserted
        // case-insensitivity at the QUERY level. That spelling compiles to `WHERE upper(email) =
        // upper(?)`, which MySQL cannot serve from the plain b-tree index on `email` — so every
        // unauthenticated POST full-scanned a table an anonymous attacker can grow. Case is now
        // handled where it always actually was: FestivalEnquiryService lower-cases the address
        // before BOTH the insert and the lookup, so every stored value is lower-case.
        //
        // `emailIsLowerCased` in FestivalEnquiryServiceTest is the test that pins that invariant;
        // this one pins that the query counts the right rows once the invariant holds.
        save(withEmail("01FESTIVALCCCCCCCCCCCCCCC1", "ops@rangoli.in"));
        save(withEmail("01FESTIVALCCCCCCCCCCCCCCC2", "ops@rangoli.in"));
        save(withEmail("01FESTIVALCCCCCCCCCCCCCCC3", "someone@else.in"));

        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        assertEquals(2L, repository.countByEmailAndCreatedAtAfter("ops@rangoli.in", since));
        assertEquals(1L, repository.countByEmailAndCreatedAtAfter("someone@else.in", since));
        assertEquals(0L, repository.countByEmailAndCreatedAtAfter("nobody@nowhere.in", since));
    }

    @Test
    @DisplayName("a duplicate email is accepted — the same brand may enquire for two editions")
    void emailIsNotUnique() {
        save(withEmail("01FESTIVALDDDDDDDDDDDDDDD1", "ops@rangoli.in"));
        save(withEmail("01FESTIVALDDDDDDDDDDDDDDD2", "ops@rangoli.in"));

        assertEquals(2, repository.count());
    }

    // ------------------------------------------------------------------------------------------
    // Round-trip
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a creator row round-trips with the brand half null, and vice versa")
    void halvesRoundTripAsNull() {
        FestivalEnquiry creator =
                FestivalEnquiry.creator(
                        "01FESTIVALEEEEEEEEEEEEEEE1",
                        "MUMBAI_FESTIVE_2026",
                        "Priya S",
                        "priya@example.com",
                        null,
                        "priya.styles",
                        42_000L,
                        "Mumbai",
                        "Would love to be in the room.");
        repository.saveAndFlush(creator);

        FestivalEnquiry loaded = repository.findById("01FESTIVALEEEEEEEEEEEEEEE1").orElseThrow();
        assertEquals("priya.styles", loaded.getInstagramHandle());
        assertEquals(42_000L, loaded.getFollowers());
        assertEquals(FestivalEnquiryStatus.NEW, loaded.getStatus());
        // The brand half must survive as NULL, not as ""/0 — admin renders null as an em dash.
        assertEquals(null, loaded.getCompany());
        assertEquals(null, loaded.getTier());
        assertEquals(null, loaded.getWebsite());
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private FestivalEnquiry save(FestivalEnquiry e) {
        return repository.saveAndFlush(e);
    }

    private static FestivalEnquiry brand(String id, FestivalEnquiryStatus status) {
        FestivalEnquiry e =
                FestivalEnquiry.brand(
                        id,
                        "MUMBAI_FESTIVE_2026",
                        "Anita Desai",
                        "ops+" + id + "@rangoli.in",
                        null,
                        "Rangoli Threads",
                        "https://rangolithreads.in",
                        FestivalTier.FEATURED,
                        "Apparel",
                        null);
        if (status != FestivalEnquiryStatus.NEW) {
            e.applyStatus(status, "01ADMINAAAAAAAAAAAAAAAAAA1", null);
        }
        return e;
    }

    private static FestivalEnquiry withEmail(String id, String email) {
        return FestivalEnquiry.brand(
                id,
                "MUMBAI_FESTIVE_2026",
                "Anita Desai",
                email,
                null,
                "Rangoli Threads",
                null,
                FestivalTier.GIFTING,
                null,
                null);
    }

    /**
     * Builds a row with a chosen {@code sourceIpHash} and {@code createdAt}. {@code createdAt} is
     * set by the factory to "now", so backdating it needs reflection — the field is deliberately
     * not settable from outside the entity (it is {@code updatable = false} and must never be
     * rewritten by application code).
     */
    private static FestivalEnquiry withIpAndCreatedAt(String id, String ipHash, Instant createdAt) {
        FestivalEnquiry e = withEmail(id, "ops+" + id + "@rangoli.in");
        e.applyProvenance(null, null, null, ipHash, null);
        try {
            java.lang.reflect.Field f = FestivalEnquiry.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(e, createdAt);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("FestivalEnquiry.createdAt field renamed?", ex);
        }
        return e;
    }
}
