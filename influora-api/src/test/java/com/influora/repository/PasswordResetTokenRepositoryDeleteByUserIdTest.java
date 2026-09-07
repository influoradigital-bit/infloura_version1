package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.PasswordResetToken;
import java.time.Instant;
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
 * F-0702 — real Hibernate + H2 proof that {@link PasswordResetTokenRepository#deleteByUserId}
 * executes and deletes the right rows.
 *
 * <p>WHY THIS EXISTS ON TOP OF THE MOCKITO TESTS. {@code AuthServiceTest#testPurgePasswordResetTokens}
 * and {@code AccountControllerTest} both mock the repository, so between them they prove the method
 * is CALLED and nothing more. {@code deleteByUserId} is a Spring Data DERIVED query: its correctness
 * lives entirely in the method name being parseable against the entity's properties, and that is
 * resolved when the Spring context starts — not at compile time. A mocked repository never
 * exercises it, so a name that does not parse would leave the whole backend suite green and fail
 * only at application boot. The Testcontainers classes that would otherwise catch that are skipped
 * locally without Docker (the repo's standing {@code Skipped: 15}), which is exactly why this uses
 * the same {@code @DataJpaTest} + H2 pattern as {@code ContractRepositoryUnsignedByCreatorTest} and
 * {@code PayoutRepositoryCreatorScopingTest} — those exist for the same reason, on the same
 * reasoning.
 *
 * <p>Repository scanning is narrowed to the one repository under test so H2 is never asked to
 * validate other repositories' MySQL-specific queries.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = PasswordResetToken.class)
@EnableJpaRepositories(
        basePackageClasses = PasswordResetTokenRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!PasswordResetTokenRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:password_reset_token_delete_by_user_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class PasswordResetTokenRepositoryDeleteByUserIdTest {

    private static final String DELETED_USER = "01HDELETEDUSERAAAAAAAA1";
    private static final String OTHER_USER = "01HOTHERUSERBBBBBBBBBB2";

    @Autowired private PasswordResetTokenRepository repository;

    private PasswordResetToken token(String id, String userId, boolean used) {
        PasswordResetToken t =
                PasswordResetToken.create(id, userId, "hash-" + id, Instant.now().plusSeconds(3600));
        if (used) {
            t.markUsed();
        }
        return t;
    }

    @Test
    @DisplayName(
            "deleteByUserId F-0702: removes every token for that user — used and unused alike —"
                    + " and touches nobody else's")
    void testDeleteByUserIdRemovesOnlyThatUsersTokens() {
        repository.save(token("01HTOKENAAAAAAAAAAAAAA1", DELETED_USER, false));
        // A USED row is deleted too. It cannot be replayed, but it is still a record tying the
        // deleted account to a reset it performed, and deletion means deletion.
        repository.save(token("01HTOKENAAAAAAAAAAAAAA2", DELETED_USER, true));
        repository.save(token("01HTOKENBBBBBBBBBBBBBB1", OTHER_USER, false));
        assertEquals(3, repository.count());

        long removed = repository.deleteByUserId(DELETED_USER);

        assertEquals(2, removed);
        assertEquals(1, repository.count());
        assertTrue(repository.findById("01HTOKENBBBBBBBBBBBBBB1").isPresent());
        assertFalse(repository.findById("01HTOKENAAAAAAAAAAAAAA1").isPresent());
        assertFalse(repository.findById("01HTOKENAAAAAAAAAAAAAA2").isPresent());
    }

    @Test
    @DisplayName("deleteByUserId F-0702: a user with no tokens is a no-op returning 0, not an error")
    void testDeleteByUserIdWithNoTokens() {
        repository.save(token("01HTOKENBBBBBBBBBBBBBB2", OTHER_USER, false));

        assertEquals(0, repository.deleteByUserId(DELETED_USER));
        assertEquals(1, repository.count());
    }

    /**
     * The one the mocked tests structurally cannot reach: after the purge, the lookup
     * {@code resetPassword} actually performs must miss. Asserting on {@code findById} alone would
     * not prove that, because it is not the query the reset path uses.
     */
    @Test
    @DisplayName(
            "deleteByUserId F-0702: after the purge, findByTokenHashAndUsedFalse no longer resolves"
                    + " the deleted user's token")
    void testPurgedTokenNoLongerResolvesByHash() {
        repository.save(token("01HTOKENAAAAAAAAAAAAAA3", DELETED_USER, false));
        assertTrue(repository.findByTokenHashAndUsedFalse("hash-01HTOKENAAAAAAAAAAAAAA3").isPresent());

        repository.deleteByUserId(DELETED_USER);

        assertFalse(repository.findByTokenHashAndUsedFalse("hash-01HTOKENAAAAAAAAAAAAAA3").isPresent());
    }
}
