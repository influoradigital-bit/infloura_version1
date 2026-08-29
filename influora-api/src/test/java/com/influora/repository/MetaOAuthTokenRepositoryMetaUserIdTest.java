package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.MetaAuthPath;
import com.influora.domain.entity.MetaOAuthToken;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
 * C5 (Kabir Track E, Meta deauthorize/data-deletion callback): real Hibernate + H2 regression
 * proof for {@link MetaOAuthTokenRepository#findByMetaUserIdAndAuthPathAndRevokedFalse} and
 * {@link MetaOAuthTokenRepository#findByIgBusinessAccountIdAndAuthPathAndRevokedFalse}'s
 * NULL-column safety.
 *
 * <p>Same reasoning as {@link MetaOAuthTokenRepositoryNullWorkspaceIdTest} (CR-111): both new
 * methods are hand-written {@code @Query} JPQL carrying an explicit {@code IS NOT NULL} predicate
 * on the matched column specifically so a {@code null}/blank argument — e.g. a malformed Meta
 * webhook payload — can never fall into Spring Data's derived-query null-to-{@code IS NULL}
 * rewrite and mass-match every pre-existing row with a NULL {@code meta_user_id} or {@code
 * ig_business_account_id} column (every row connected before this migration shipped). A Mockito
 * stub cannot observe real query-derivation/JPQL behavior at all, so this proves it against a
 * genuine H2-backed Hibernate session instead, same {@code @DataJpaTest} pattern already
 * established by that sibling test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = MetaOAuthToken.class)
@EnableJpaRepositories(
        basePackageClasses = MetaOAuthTokenRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!MetaOAuthTokenRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:meta_oauth_token_meta_user_id_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class MetaOAuthTokenRepositoryMetaUserIdTest {

    private static final String CREATOR_PROFILE_ID = "01HWXYZCREATORPROFILE002";
    private static final String NULL_COLUMN_ROW_CREATOR_ID = "01HWXYZCREATORPROFILE003";
    private static final String REAL_META_USER_ID = "10000000000000042";
    private static final String REAL_IG_USER_ID = "17841400000000042";

    @Autowired private MetaOAuthTokenRepository repository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // A pre-C5 FACEBOOK_LOGIN row: meta_user_id is NULL (connected before this migration
        // shipped) — exactly the shape a null/blank-argument query must never match.
        entityManager.persist(
                MetaOAuthToken.builder()
                        .id("01NULLMETAUSERIDROW00001")
                        .creatorProfileId(NULL_COLUMN_ROW_CREATOR_ID)
                        .authPath(MetaAuthPath.FACEBOOK_LOGIN)
                        // .metaUserId(...) intentionally omitted -- stays null.
                        .encryptedAccessToken("ciphertext-null-meta-user-id")
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .grantedScopesJson("[]")
                        .lastRefreshedAt(Instant.now())
                        .build());

        // A real FACEBOOK_LOGIN row with a resolved meta_user_id -- proves the legitimate lookup
        // still works, not just that the NULL case is safe.
        entityManager.persist(
                MetaOAuthToken.builder()
                        .id("01REALMETAUSERIDROW00001")
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .authPath(MetaAuthPath.FACEBOOK_LOGIN)
                        .metaUserId(REAL_META_USER_ID)
                        .encryptedAccessToken("ciphertext-real-meta-user-id")
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .grantedScopesJson("[]")
                        .lastRefreshedAt(Instant.now())
                        .build());

        // A real INSTAGRAM_LOGIN row with a resolved ig_business_account_id -- same sanity check
        // for the second lookup method.
        entityManager.persist(
                MetaOAuthToken.builder()
                        .id("01REALIGUSERIDROW000001")
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .authPath(MetaAuthPath.INSTAGRAM_LOGIN)
                        .igBusinessAccountId(REAL_IG_USER_ID)
                        .encryptedAccessToken("ciphertext-real-ig-user-id")
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .grantedScopesJson("[]")
                        .lastRefreshedAt(Instant.now())
                        .build());

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName(
            "findByMetaUserIdAndAuthPathAndRevokedFalse(null, FACEBOOK_LOGIN) never matches a"
                    + " NULL meta_user_id row")
    void nullMetaUserIdNeverMatchesNullColumnRow() {
        Optional<MetaOAuthToken> result =
                repository.findByMetaUserIdAndAuthPathAndRevokedFalse(null, MetaAuthPath.FACEBOOK_LOGIN);

        assertTrue(
                result.isEmpty(),
                "a null metaUserId argument must never resolve to a row -- got: "
                        + result.map(MetaOAuthToken::getId).orElse("empty"));
    }

    @Test
    @DisplayName(
            "findByIgBusinessAccountIdAndAuthPathAndRevokedFalse(null, INSTAGRAM_LOGIN) never"
                    + " matches a NULL ig_business_account_id row")
    void nullIgBusinessAccountIdNeverMatchesNullColumnRow() {
        Optional<MetaOAuthToken> result =
                repository.findByIgBusinessAccountIdAndAuthPathAndRevokedFalse(
                        null, MetaAuthPath.INSTAGRAM_LOGIN);

        assertTrue(
                result.isEmpty(),
                "a null igBusinessAccountId argument must never resolve to a row -- got: "
                        + result.map(MetaOAuthToken::getId).orElse("empty"));
    }

    @Test
    @DisplayName("findByMetaUserIdAndAuthPathAndRevokedFalse: legitimate FACEBOOK_LOGIN lookup still works")
    void realMetaUserIdStillMatchesItsRow() {
        Optional<MetaOAuthToken> result =
                repository.findByMetaUserIdAndAuthPathAndRevokedFalse(
                        REAL_META_USER_ID, MetaAuthPath.FACEBOOK_LOGIN);

        assertTrue(result.isPresent(), "the real meta_user_id lookup must still resolve its own row");
        assertEquals("01REALMETAUSERIDROW00001", result.get().getId());
    }

    @Test
    @DisplayName(
            "findByIgBusinessAccountIdAndAuthPathAndRevokedFalse: legitimate INSTAGRAM_LOGIN lookup"
                    + " still works")
    void realIgBusinessAccountIdStillMatchesItsRow() {
        Optional<MetaOAuthToken> result =
                repository.findByIgBusinessAccountIdAndAuthPathAndRevokedFalse(
                        REAL_IG_USER_ID, MetaAuthPath.INSTAGRAM_LOGIN);

        assertTrue(result.isPresent(), "the real ig_business_account_id lookup must still resolve its own row");
        assertEquals("01REALIGUSERIDROW000001", result.get().getId());
    }

    @Test
    @DisplayName(
            "findByMetaUserIdAndAuthPathAndRevokedFalse: authPath must match too -- a real"
                    + " meta_user_id on an INSTAGRAM_LOGIN row (should never happen by construction)"
                    + " does not leak across the FACEBOOK_LOGIN query")
    void metaUserIdLookupIsScopedToTheRequestedAuthPath() {
        Optional<MetaOAuthToken> result =
                repository.findByMetaUserIdAndAuthPathAndRevokedFalse(
                        REAL_META_USER_ID, MetaAuthPath.INSTAGRAM_LOGIN);

        assertTrue(
                result.isEmpty(),
                "a FACEBOOK_LOGIN row's meta_user_id must not match when queried against"
                        + " INSTAGRAM_LOGIN");
    }
}
