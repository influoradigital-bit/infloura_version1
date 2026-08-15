package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Payout;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
 * CR-77 — real Hibernate + H2 proof that {@link
 * PayoutRepository#findByCreatorUserIdOrderByCreatedAtDesc} actually filters by creator.
 *
 * <p>WHY A @DataJpaTest AND NOT A MOCKITO TEST. {@code WalletServiceTest} already asserts that the
 * service passes the authenticated creator's own id into this method — but that is a claim about
 * the CALL, not about the QUERY. With the repository mocked, replacing the derived method with one
 * that returns every creator's payouts leaves the entire backend suite green: nothing executes SQL,
 * so nothing can observe the missing WHERE clause. On a money surface whose whole tenancy guarantee
 * is "the method name IS the WHERE clause", that gap is worth closing with a real query.
 *
 * <p>Same {@code @DataJpaTest} + {@code @AutoConfigureTestDatabase} + narrowly-scoped {@code
 * @EnableJpaRepositories} pattern as {@code MetaOAuthTokenRepositoryNullWorkspaceIdTest} and {@code
 * IdempotencyServicePersistenceTest} — repository scanning is restricted to the one repository
 * under test so H2 is not asked to validate other repositories' MySQL-specific queries.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = Payout.class)
@EnableJpaRepositories(
        basePackageClasses = PayoutRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!PayoutRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:payout_creator_scoping_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class PayoutRepositoryCreatorScopingTest {

    private static final String CREATOR_A = "01HCREATORAAAAAAAAAAAAA1";
    private static final String CREATOR_B = "01HCREATORBBBBBBBBBBBBB2";

    @Autowired private PayoutRepository repository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void seedTwoCreatorsPayouts() {
        repository.deleteAll();
        // Two payouts for A, one for B. If the query ever stops filtering, A's page picks up B's
        // row and the assertions below fail on both count and content.
        repository.save(payout("01HPAYOUTA0000000000001", CREATOR_A, Instant.now().minusSeconds(120)));
        repository.save(payout("01HPAYOUTA0000000000002", CREATOR_A, Instant.now().minusSeconds(60)));
        repository.save(payout("01HPAYOUTB0000000000001", CREATOR_B, Instant.now()));
        entityManager.flush();
        entityManager.clear();
    }

    private static Payout payout(String id, String creatorUserId, Instant when) {
        return Payout.createQueued(
                id,
                null,
                creatorUserId,
                "pout_" + id,
                "fa_" + creatorUserId,
                new BigDecimal("1500.00"),
                "INR",
                "processed",
                "idem_" + id,
                when);
    }

    @Test
    @DisplayName(
            "CR-77: the query returns ONLY the requested creator's payouts — another creator's row"
                    + " is never visible")
    void findByCreatorUserId_returnsOnlyThatCreatorsRows() {
        List<Payout> forA =
                repository
                        .findByCreatorUserIdOrderByCreatedAtDesc(CREATOR_A, PageRequest.of(0, 20))
                        .getContent();

        assertEquals(2, forA.size(), "creator A has exactly two payouts");
        assertTrue(
                forA.stream().allMatch(p -> CREATOR_A.equals(p.getCreatorUserId())),
                "every returned row must belong to the requesting creator — a payout list is a money"
                        + " surface and a leak here exposes another creator's disbursements");
        assertTrue(
                forA.stream().noneMatch(p -> "01HPAYOUTB0000000000001".equals(p.getId())),
                "creator B's payout must not appear in creator A's page");
    }

    @Test
    @DisplayName("CR-77: a creator with no payouts gets an empty page, not everyone else's")
    void findByCreatorUserId_unknownCreatorGetsEmptyPage() {
        var page =
                repository.findByCreatorUserIdOrderByCreatedAtDesc(
                        "01HCREATORNOPAYOUTSXXXX9", PageRequest.of(0, 20));

        assertEquals(0, page.getContent().size());
        assertEquals(0L, page.getTotalElements());
    }

    @Test
    @DisplayName("CR-77: rows come back newest-first, and pagination is scoped to the creator")
    void findByCreatorUserId_ordersNewestFirstAndPagesWithinTheCreator() {
        var firstPage =
                repository.findByCreatorUserIdOrderByCreatedAtDesc(CREATOR_A, PageRequest.of(0, 1));

        assertEquals(1, firstPage.getContent().size(), "page size is honoured");
        assertEquals(
                2L,
                firstPage.getTotalElements(),
                "the total must count only this creator's rows, not the whole table");
        assertEquals(
                "01HPAYOUTA0000000000002",
                firstPage.getContent().get(0).getId(),
                "newest-first: the more recent of creator A's two payouts leads");
    }
}
