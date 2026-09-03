package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.User;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * T-ADMINMAIL-0903 round 4, A1 (REVIEW-R3.md ship-blocker): real Hibernate + H2 proof that the two
 * admin-custom-email audience queries actually EXCLUDE a soft-deleted user, not merely that the
 * string {@code AND u.deletedAt IS NULL} appears in the {@code @Query} annotation.
 *
 * <p><b>Why this test exists at all.</b> The A1 fix has two halves — the query predicate here, and
 * a defensive null-email skip in {@code AdminCustomEmailService}. Only the second half was covered:
 * deleting {@code AND u.deletedAt IS NULL} from BOTH audience queries left the entire suite green,
 * because every service test stubs {@code UserRepository} and so can never exercise a predicate.
 * That is the same false-green shape REVIEW-R2.md flagged for {@code EmailWorkerTest}'s C2 case, and
 * it was hiding the primary fix for the defect that would have taken every production send down.
 *
 * <p><b>Why the bug is invisible without a database.</b> {@link User#softDelete()} blanks {@code
 * email} but deliberately leaves {@code status} untouched (V61 keeps FKs resolving), so a deleted
 * account is still {@code status = ACTIVE}. It therefore satisfies every other predicate in the
 * audience queries and is returned with a {@code null} email — which then hits {@code
 * EmailOutbox.to_email NOT NULL} at flush and rolls back the whole send.
 *
 * <p><b>Falsification:</b> removing {@code AND u.deletedAt IS NULL} from either query turns this
 * test red — the count becomes 2 and the fetched list contains {@code u-deleted}. Verified both
 * ways before this file was committed.
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
                    + "jdbc:h2:mem:user_audience_soft_delete_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class UserRepositoryAudienceSoftDeleteTest {

    @Autowired private UserRepository userRepository;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();

        User live = User.newCreator("u-live", "live@example.com", "hash", "Live", "User", "Live User");
        // setEmailVerified(true) is the only transition to ACTIVE on this entity (User.java:278-284).
        live.setEmailVerified(true);

        User deleted =
                User.newCreator("u-deleted", "deleted@example.com", "hash", "Gone", "User", "Gone User");
        deleted.setEmailVerified(true);
        // The entity's own transition, not a hand-built fixture — this is the exact state a real
        // deleted account is left in, including status staying ACTIVE.
        deleted.softDelete();

        userRepository.saveAll(List.of(live, deleted));
        userRepository.flush();
    }

    @Test
    @DisplayName("a soft-deleted user stays ACTIVE with a null email — the fixture must be real")
    void softDeletedUserIsStillActiveWithNoEmail() {
        User deleted = userRepository.findById("u-deleted").orElseThrow();
        assertEquals(
                com.influora.domain.enums.UserStatus.ACTIVE,
                deleted.getStatus(),
                "softDelete() must leave status ACTIVE — if this ever changes, the audience"
                        + " queries' deletedAt predicate stops being load-bearing and this whole"
                        + " test is measuring nothing");
        assertTrue(deleted.getEmail() == null || deleted.getEmail().isBlank());
    }

    @Test
    @DisplayName("countForCustomEmailAudience excludes the soft-deleted user")
    void countExcludesSoftDeleted() {
        assertEquals(
                1L,
                userRepository.countForCustomEmailAudience(null, false, null),
                "a soft-deleted account must not be counted — it inflates recipientCount, the cap"
                        + " check and the audit row");
    }

    @Test
    @DisplayName("findForCustomEmailAudience never returns a recipient with no email")
    void fetchExcludesSoftDeleted() {
        List<User> rows = userRepository.findForCustomEmailAudience(null, false, null, Limit.of(100));

        assertEquals(1, rows.size(), "expected only the live user, got: " + ids(rows));
        assertEquals("u-live", rows.get(0).getId());
        assertTrue(
                rows.stream().noneMatch(u -> u.getEmail() == null || u.getEmail().isBlank()),
                "a null-email row reaching the outbox fails EmailOutbox.to_email NOT NULL at flush,"
                        + " rolling back the ENTIRE send: " + ids(rows));
    }

    @Test
    @DisplayName("count and fetch stay predicate-identical, or the 409 mismatch check drifts")
    void countAndFetchAgree() {
        long counted = userRepository.countForCustomEmailAudience(null, false, null);
        List<User> fetched = userRepository.findForCustomEmailAudience(null, false, null, Limit.of(100));

        // send() gates the 409 on the count but enqueues the fetched rows. If the two predicates
        // ever diverge, the mismatch check fires for reasons unrelated to the audience changing.
        assertEquals(counted, fetched.size(), "count and fetch disagree: " + ids(fetched));
    }

    private static String ids(List<User> users) {
        return users.stream().map(User::getId).toList().toString();
    }
}
