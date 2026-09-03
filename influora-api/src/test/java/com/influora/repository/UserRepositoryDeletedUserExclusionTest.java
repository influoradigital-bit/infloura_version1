package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.User;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
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
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * T-ADMINMAIL-0903 round 4, A1 (REVIEW-R3.md ship-blocker) — real Hibernate + H2 proof that a
 * soft-deleted user is excluded from every audience-shaped {@link UserRepository} query.
 *
 * <p>WHY A @DataJpaTest AND NOT A MOCKITO TEST. {@code AdminCustomEmailServiceTest} mocks {@code
 * UserRepository} entirely — it can assert the SERVICE calls the repository correctly, but a mock
 * cannot fail if the JPQL/native SQL itself forgets a predicate, which is exactly the bug this
 * ticket found: {@link User#softDelete()} nulls {@code email} but deliberately leaves {@code
 * status} untouched (V61, to keep FKs resolving), so a soft-deleted account is still {@code status
 * = ACTIVE} and was, before this fix, indistinguishable from a real recipient to {@code
 * countForCustomEmailAudience}/{@code findForCustomEmailAudience}/{@code
 * findCreatorsWithoutConnectedAccount}. That let one deleted account anywhere in the audience blow
 * up {@code EmailOutbox.to_email VARCHAR(255) NOT NULL} at flush and roll back the entire batch —
 * see {@code AdminCustomEmailService}'s enqueue loop for the belt-and-braces defensive skip that
 * now also guards against this class of gap. Same {@code @DataJpaTest} +
 * {@code @AutoConfigureTestDatabase} + narrowly-scoped {@code @EnableJpaRepositories} pattern as
 * {@code PayoutRepositoryCreatorScopingTest}/{@code MetaOAuthTokenRepositoryNullWorkspaceIdTest}.
 *
 * <p>Falsification (per REVIEW-R3.md's testing note): reverting either {@code AND u.deletedAt IS
 * NULL} JPQL clause, or the native query's {@code AND u.deleted_at IS NULL}, turns the matching
 * test in this class red — the soft-deleted user reappears in the count/rows. Verified directly
 * below (each test's javadoc states exactly what a revert makes it assert instead).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = User.class)
@EnableJpaRepositories(
        basePackageClasses = UserRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!UserRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url="
                    + "jdbc:h2:mem:user_repository_deleted_exclusion_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class UserRepositoryDeletedUserExclusionTest {

    @Autowired private UserRepository repository;
    @Autowired private EntityManager entityManager;

    private User liveUser;
    private User softDeletedUser;

    @BeforeEach
    void seedOneLiveAndOneSoftDeletedActiveUser() {
        liveUser = User.newBrand("01HLIVEUSER00000000000001", "live@example.com", "hash", "Live", "One", "Live One");
        liveUser.setEmailVerified(true); // PENDING_VERIFICATION -> ACTIVE

        softDeletedUser =
                User.newBrand(
                        "01HDELETEDUSER0000000001", "deleted@example.com", "hash", "Del", "Two", "Del Two");
        softDeletedUser.setEmailVerified(true); // ACTIVE, same as liveUser
        softDeletedUser.softDelete(); // blanks email/PII, stamps deletedAt -- status stays ACTIVE

        entityManager.persist(liveUser);
        entityManager.persist(softDeletedUser);
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Sanity check on the fixture itself: {@link User#softDelete()} really does leave {@code
     * status = ACTIVE} while nulling {@code email} — this is the exact production shape
     * REVIEW-R3.md verified against {@code User.softDelete()}/{@code UserRepository} before A1 was
     * written. If this ever stops being true (a future change to {@code softDelete()} that also
     * flips {@code status}), the other tests in this class would no longer exercise the bug they
     * are meant to guard against, so it is worth failing loudly here rather than silently.
     */
    @Test
    @DisplayName("fixture sanity: softDelete() nulls email but leaves status ACTIVE")
    void softDeletedUserFixtureMatchesProductionShape() {
        User reloaded = entityManager.find(User.class, softDeletedUser.getId());
        assertEquals(com.influora.domain.enums.UserStatus.ACTIVE, reloaded.getStatus());
        assertEquals(null, reloaded.getEmail());
        assertTrue(reloaded.getDeletedAt() != null);
    }

    /**
     * Falsification: dropping {@code AND u.deletedAt IS NULL} from {@code
     * countForCustomEmailAudience}'s JPQL turns this red — {@code count} comes back {@code 2},
     * not {@code 1} ("expected: <1> but was: <2>").
     */
    @Test
    @DisplayName("A1: countForCustomEmailAudience excludes a soft-deleted ACTIVE user")
    void countForCustomEmailAudience_excludesSoftDeletedUser() {
        long count = repository.countForCustomEmailAudience(null, false, null);
        assertEquals(1, count, "only the live user should be counted, not the soft-deleted one");
    }

    /**
     * Falsification: dropping {@code AND u.deletedAt IS NULL} from {@code
     * findForCustomEmailAudience}'s JPQL turns this red — the returned list contains the
     * soft-deleted user's id (whose {@code email} is {@code null}), which is exactly the row that
     * used to blow up {@code EmailOutbox.to_email NOT NULL} at flush and roll back the whole send.
     */
    @Test
    @DisplayName("A1: findForCustomEmailAudience excludes a soft-deleted ACTIVE user (would-be null email)")
    void findForCustomEmailAudience_excludesSoftDeletedUser() {
        List<User> rows = repository.findForCustomEmailAudience(null, false, null, Limit.of(10));

        assertEquals(1, rows.size());
        assertEquals(liveUser.getId(), rows.get(0).getId());
        assertFalse(
                rows.stream().anyMatch(u -> u.getId().equals(softDeletedUser.getId())),
                "the soft-deleted user (null email) must never reach the recipient list: " + rows);
        assertTrue(
                rows.stream().allMatch(u -> u.getEmail() != null && !u.getEmail().isBlank()),
                "every returned row must have a real, sendable email address");
    }

    /**
     * {@code findCreatorsWithoutConnectedAccount} cannot be exercised end-to-end in THIS harness:
     * its native SQL also carries {@code t.revoked = 0} (a pre-existing literal, unrelated to this
     * fix), and H2's stricter BOOLEAN typing rejects comparing a real {@code BOOLEAN} column
     * against an {@code INTEGER} literal at statement-preparation time ({@code "Values of types
     * "BOOLEAN" and "INTEGER" are not comparable""}) — the statement fails to prepare before a
     * single row is ever read, regardless of what data is seeded or what the {@code deleted_at}
     * predicate says. This is the same class of H2/MySQL native-query gap {@code
     * EmailOutboxRepositoryQueryTest}'s javadoc describes for a different query in this codebase;
     * confirmed directly against this method (see the round-4 PR history for the H2 stack trace)
     * rather than assumed. Rewriting the unrelated {@code = 0} literal to fix H2 compatibility is
     * out of scope for A1 and was not done.
     *
     * <p>So — per REVIEW-R3.md's testing discipline ("where you cannot test real behaviour, say so
     * plainly in the test's javadoc rather than letting a string assertion imply behavioural
     * coverage") — this is honestly a WEAKER test than the two above: it pins the literal JPQL/SQL
     * text via reflection, same technique as {@code EmailOutboxRepositoryQueryTest}, and proves
     * only that the {@code deleted_at IS NULL} predicate text is present in the exact position that
     * matters (after the table alias is bound, before the anti-join), not that the query actually
     * excludes a soft-deleted row when run. Falsification: removing {@code AND u.deleted_at IS
     * NULL} from the native query in {@code UserRepository} turns this red.
     */
    @Test
    @DisplayName(
            "A1 (string-pin only, NOT behavioral — see javadoc): findCreatorsWithoutConnectedAccount's"
                    + " native SQL carries AND u.deleted_at IS NULL")
    void findCreatorsWithoutConnectedAccount_sqlTextExcludesSoftDeletedRows() throws NoSuchMethodException {
        Method method =
                UserRepository.class.getMethod(
                        "findCreatorsWithoutConnectedAccount",
                        java.time.Instant.class,
                        java.time.Instant.class,
                        Limit.class);
        Query query = method.getAnnotation(Query.class);
        assertTrue(query != null, "findCreatorsWithoutConnectedAccount must carry a @Query annotation");

        String sql = query.value();
        assertTrue(
                sql.contains("AND u.deleted_at IS NULL"),
                "the native query must exclude soft-deleted users the same way the JPQL audience"
                        + " queries do: "
                        + sql);
        assertTrue(
                sql.indexOf("AND u.deleted_at IS NULL") > sql.indexOf("u.status = 'ACTIVE'"),
                "deleted_at IS NULL must be a real, standalone AND clause alongside status = 'ACTIVE'"
                        + ", not accidentally folded into some other predicate: "
                        + sql);
    }
}
