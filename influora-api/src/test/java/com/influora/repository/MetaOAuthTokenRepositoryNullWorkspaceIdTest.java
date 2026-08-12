package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * CR-111 (Kabir red-team, Medium): real Hibernate + H2 regression proof for {@link
 * MetaOAuthTokenRepository#findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse}'s null-workspaceId
 * fail-closed guarantee.
 *
 * <p>Before this fix, the method was a plain Spring Data derived-query
 * ({@code findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse}). Spring Data JPA's query-derivation
 * machinery rewrites a {@code SIMPLE_PROPERTY} equality part into an {@code IS NULL} check whenever
 * the bound parameter value is {@code null} at call time — so calling the brand-scoped method with
 * {@code workspaceId == null} generated {@code workspace_id IS NULL AND creator_profile_id = ? AND
 * revoked = false}, which matches a creator-owned row (creator rows always have
 * {@code workspace_id IS NULL} per {@link MetaOAuthToken}'s javadoc). That crossed the brand/creator
 * key-space the class-level javadoc claimed was disjoint, even though no shipped caller currently
 * passes {@code null} (confirmed by grep across {@code MetaTokenStorage} and {@code
 * MetricsAuthorizationService}, the method's only production callers).
 *
 * <p>The fix replaces the derived query with hand-written JPQL carrying an explicit
 * {@code t.workspaceId IS NOT NULL} predicate, so the null-to-{@code IS NULL} rewrite can no longer
 * happen (it only applies to method-name-derived {@code PartTree} queries, not {@code @Query} JPQL)
 * and the invariant is textually enforced. This test proves it against a genuine H2-backed
 * Hibernate session — not a Mockito stub, which cannot observe Spring Data's query-derivation
 * behavior at all — using the same {@code @DataJpaTest} + {@code @AutoConfigureTestDatabase} +
 * scoped {@code @EnableJpaRepositories} pattern established by {@code
 * IdempotencyServicePersistenceTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = MetaOAuthToken.class)
// Scope JPA repository scanning to exactly the repository under test -- same reasoning as
// IdempotencyServicePersistenceTest: scanning the whole com.influora.repository package would
// also try to validate other repositories' MySQL-specific queries against H2.
@EnableJpaRepositories(
        basePackageClasses = MetaOAuthTokenRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!MetaOAuthTokenRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:meta_oauth_token_null_workspace_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class MetaOAuthTokenRepositoryNullWorkspaceIdTest {

    private static final String CREATOR_PROFILE_ID = "01HWXYZCREATORPROFILE001";
    private static final String BRAND_WORKSPACE_ID = "01HWXYZBRANDWORKSPACE001";

    @Autowired private MetaOAuthTokenRepository repository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // A creator-owned row for CREATOR_PROFILE_ID: workspaceId == null, exactly the shape the
        // brand-scoped method must never match, regardless of what argument it's called with.
        entityManager.persist(
                MetaOAuthToken.builder()
                        .id("01CREATORTOKENROW0000001")
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        // .workspaceId(...) intentionally omitted -- stays null (creator-owned row).
                        .encryptedAccessToken("ciphertext-creator")
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .grantedScopesJson("[]")
                        .lastRefreshedAt(Instant.now())
                        .build());

        // A brand-owned row for the SAME creator, correctly scoped to a real workspace -- present
        // to prove the fix doesn't break the legitimate brand-scoped lookup.
        entityManager.persist(
                MetaOAuthToken.builder()
                        .id("01BRANDTOKENROW00000001")
                        .workspaceId(BRAND_WORKSPACE_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .encryptedAccessToken("ciphertext-brand")
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .grantedScopesJson("[]")
                        .lastRefreshedAt(Instant.now())
                        .build());

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName(
            "findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(null, creatorProfileId) never"
                    + " matches the creator-owned (workspace_id IS NULL) row")
    void nullWorkspaceIdNeverMatchesCreatorOwnedRow() {
        Optional<MetaOAuthToken> result =
                repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(null, CREATOR_PROFILE_ID);

        assertTrue(
                result.isEmpty(),
                "a null workspaceId must never resolve to a row through the brand-scoped method --"
                        + " got: " + result.map(MetaOAuthToken::getId).orElse("empty"));
    }

    @Test
    @DisplayName("findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse: legitimate brand lookup still works")
    void realWorkspaceIdStillMatchesTheBrandOwnedRow() {
        Optional<MetaOAuthToken> result =
                repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        BRAND_WORKSPACE_ID, CREATOR_PROFILE_ID);

        assertTrue(result.isPresent(), "the real brand-workspace lookup must still resolve its own row");
        assertEquals("01BRANDTOKENROW00000001", result.get().getId());
    }
}
