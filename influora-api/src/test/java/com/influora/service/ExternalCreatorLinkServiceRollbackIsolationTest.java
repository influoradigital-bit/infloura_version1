package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.ExternalCreatorRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Q5.5 (T-CREATORCONNECT-0902, Medium) — real-transaction-manager proof that {@link
 * ExternalCreatorLinkService#onCreatorIdentified} cannot roll back a caller's own business write,
 * even when its own failure surfaces only at flush time (not at the {@code save()} call itself).
 * Same shape and same justification as {@code ApplicationHistoryServiceRollbackIsolationTest} —
 * see that class's javadoc for why a mocked collaborator cannot prove this class of bug (no real
 * {@code TransactionInterceptor}, no deferred {@code em.merge()} flush).
 *
 * <p><b>Why {@code externalCreatorRepository} is a hand-built proxy, not
 * {@code @EnableJpaRepositories}-generated.</b> {@link ExternalCreatorRepository} carries a
 * pre-existing {@code @Modifying @Query} method, {@code renameIgUsername}, whose JPQL ({@code SET
 * e.updatedAt = CURRENT_TIMESTAMP}) fails Hibernate 6's strict JPQL type-check specifically under
 * {@code H2Dialect} ({@code current_timestamp}'s declared type there does not match the entity's
 * {@code Instant} field — apparently fine under the real {@code MySQLDialect} this app actually
 * runs on, per {@code application.yml}, which is presumably why nothing has caught it before).
 * Spring Data validates every {@code @Query} method on a repository EAGERLY at proxy-creation
 * time — regardless of {@code bootstrap-mode} or whether a test ever calls that method — so simply
 * scanning this repository interface via {@code @EnableJpaRepositories} makes the WHOLE context
 * fail to start. {@code renameIgUsername} lives in a file outside this fix's scope (Q4.4 already
 * touches {@code ExternalCreatorRepository}'s neighbours, not this file), so instead of editing it,
 * this test wires a {@link java.lang.reflect.Proxy} implementing the real {@code
 * ExternalCreatorRepository} interface: base CRUD ({@code findById}/{@code save}/{@code
 * saveAndFlush}/...) forwards to a genuine {@link SimpleJpaRepository} backed by the SAME real
 * {@link EntityManager} (byte-for-byte what Spring Data's own generated proxy would have delegated
 * to), and the two custom finders used by {@code onCreatorIdentified} are re-implemented directly
 * against the same {@code EntityManager}. {@code renameIgUsername}/{@code findByIgUsername} are
 * never called by anything this test exercises and throw if they ever are. Nothing here changes
 * production behavior — it only avoids one unrelated pre-existing query bug this test would
 * otherwise trip over just by existing.
 *
 * <p><b>How the failure is forced.</b> {@code ig_account_id} is {@code @Column(length = 64)} with
 * no Bean Validation {@code @Size}, so passing an over-length value is not rejected in Java —
 * Hibernate generates a real {@code VARCHAR(64)} column ({@code ddl-auto=create-drop} honors
 * {@code @Column(length=...)}), and H2 enforces that width at flush, throwing a genuine {@code
 * DataIntegrityViolationException} the same way a live MySQL column would. The row fed the
 * over-length id is pre-seeded already {@code JOINED} to the SAME {@code creatorProfileId} being
 * passed — the exact shape {@code MetaTokenRefreshService}'s background token refresh produces
 * (Q5.2) — so it passes every guard up to the point where {@code applyIgAccountId} dirties the
 * entity and the subsequent {@code connectionRequestRepository.findByExternalCreatorIdAndStatusIn}
 * query auto-flushes it.
 *
 * <p><b>What this proves, and about which fix.</b> {@code onCreatorIdentified} is {@code
 * REQUIRES_NEW}, so this flush failure commits/rolls back on ITS OWN transaction boundary. But
 * REQUIRES_NEW alone does not stop the failure from re-surfacing as an exception out of the CALL
 * to {@code onCreatorIdentified} once its own transaction fails to commit — even though its
 * internal {@code catch (Exception e)} already ran. That is why {@code
 * MetaTokenStorage#storeCreatorToken} and {@code PortfolioService#upsertPlatformStat} (this fix's
 * two remaining call sites after Q5.4 removed the third) now ALSO wrap the call in their own
 * {@code try/catch (RuntimeException)} — mirroring this test's own call site below — rather than
 * relying on the hook alone. This test exercises exactly that combination and proves the actually
 * meaningful thing: whatever happens inside/around {@code onCreatorIdentified}, the OUTER
 * transaction's own already-good business write survives.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(
        basePackageClasses = {
            CreatorProfile.class,
            ExternalCreator.class,
            CreatorConnectionRequest.class,
            // F-0701 — finishLinking now adopts the external row's Instagram identity onto
            // platform_stats, so this entity must be mapped for the context to start.
            PlatformStat.class
        })
@EnableJpaRepositories(
        basePackageClasses = {
            CreatorProfileRepository.class,
            CreatorConnectionRequestRepository.class,
            // F-0701 — a real repository, not a proxy: PlatformStatRepository carries only
            // derived finders (no @Query), so it does not hit the eager-JPQL-validation
            // problem that forces ExternalCreatorRepository to be hand-proxied above.
            PlatformStatRepository.class
        },
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "com\\.influora\\.repository\\.(?!CreatorProfileRepository$|CreatorConnectionRequestRepository$|PlatformStatRepository$).*"))
@Import({ExternalCreatorLinkService.class, ExternalCreatorLinkServiceRollbackIsolationTest.TestRepoConfig.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:external_creator_link_rollback_isolation_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class ExternalCreatorLinkServiceRollbackIsolationTest {

    private static final String EXTERNAL_ID = "01HEXTROLLBACKISO00001";
    private static final String CREATOR_PROFILE_ID = "01HCREATORROLLBACK0001";
    private static final String USER_ID = "01HUSERROLLBACKISO0001";

    /** REAL Spring-proxied bean (via {@code @Import}) — a manually-{@code new}'d instance would
     * bypass AOP entirely and prove nothing about the {@code REQUIRES_NEW} annotation. */
    @Autowired private ExternalCreatorLinkService externalCreatorLinkService;

    @Autowired private ExternalCreatorRepository externalCreatorRepository;
    @Autowired private CreatorProfileRepository creatorProfileRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName(
            "onCreatorIdentified's own failure — surfacing only at flush time inside its REQUIRES_NEW"
                    + " transaction — must never roll back the caller's already-succeeded business write")
    void hookFailureAtFlushTimeNeverRollsBackCallerTransaction() {
        TransactionTemplate seed = new TransactionTemplate(transactionManager);
        seed.executeWithoutResult(
                status -> {
                    ExternalCreator row =
                            ExternalCreator.builder()
                                    .id(EXTERNAL_ID)
                                    .source(ExternalCreatorSource.ADMIN_IMPORT)
                                    .igUsername("targetuser")
                                    .build();
                    // Already JOINED to the SAME creatorProfileId — the Q5.2 background-token-
                    // refresh shape, which is a legitimate re-confirmation (not a first-link) and
                    // so is not blocked by the Q5.4 ig_account_id-required-for-first-link guard.
                    row.markJoined(CREATOR_PROFILE_ID);
                    externalCreatorRepository.saveAndFlush(row);
                });

        TransactionTemplate outerBusinessTransaction = new TransactionTemplate(transactionManager);
        outerBusinessTransaction.execute(
                status -> {
                    // The business write — stands in for MetaTokenStorage's own token-row save, or
                    // PortfolioService's platform_stats/creator_profiles save, immediately before
                    // either calls the JOINED hook.
                    CreatorProfile profile = CreatorProfile.newForUser(CREATOR_PROFILE_ID, USER_ID, "Target Creator");
                    creatorProfileRepository.save(profile);

                    // The hook call — deliberately forced to fail at flush time (see class javadoc)
                    // via an over-length ig_account_id. Wrapped in the EXACT try/catch shape
                    // MetaTokenStorage/PortfolioService now use at their own call sites.
                    try {
                        externalCreatorLinkService.onCreatorIdentified(
                                CREATOR_PROFILE_ID, "targetuser", "1".repeat(100));
                    } catch (RuntimeException e) {
                        // Best-effort — the business write above already succeeded and must survive.
                    }
                    return null;
                });

        assertTrue(
                creatorProfileRepository.findById(CREATOR_PROFILE_ID).isPresent(),
                "the caller's own business write must survive a JOINED-hook failure, even one that"
                        + " only surfaces at flush time inside onCreatorIdentified's own REQUIRES_NEW"
                        + " transaction — if this fails, either the hook is participating in the"
                        + " caller's transaction again, or the call site is missing its own"
                        + " try/catch");
    }

    /** See class javadoc: hand-built {@link ExternalCreatorRepository} proxy that sidesteps the
     * pre-existing broken {@code renameIgUsername} query validation. */
    @TestConfiguration
    static class TestRepoConfig {

        @PersistenceContext private EntityManager entityManager;

        @Bean
        ExternalCreatorRepository externalCreatorRepository() {
            SimpleJpaRepository<ExternalCreator, String> delegate =
                    new SimpleJpaRepository<>(ExternalCreator.class, entityManager);

            InvocationHandler handler =
                    (proxyInstance, method, args) -> {
                        switch (method.getName()) {
                            case "findByIgAccountId":
                                return findByIgAccountId((String) args[0]);
                            case "findByIgUsernameIgnoreCase":
                                return findByIgUsernameIgnoreCase((String) args[0]);
                            case "findByIgUsername":
                            case "renameIgUsername":
                                throw new UnsupportedOperationException(
                                        method.getName() + " is not exercised by this test and is"
                                                + " intentionally not implemented on this test proxy");
                            default:
                                return forwardToDelegate(delegate, method, args);
                        }
                    };

            return (ExternalCreatorRepository)
                    Proxy.newProxyInstance(
                            ExternalCreatorRepository.class.getClassLoader(),
                            new Class<?>[] {ExternalCreatorRepository.class},
                            handler);
        }

        private Optional<ExternalCreator> findByIgAccountId(String igAccountId) {
            if (igAccountId == null || igAccountId.isBlank()) {
                return Optional.empty();
            }
            List<ExternalCreator> results =
                    entityManager
                            .createQuery(
                                    "SELECT e FROM ExternalCreator e WHERE e.igAccountId IS NOT NULL AND"
                                            + " e.igAccountId = :igAccountId",
                                    ExternalCreator.class)
                            .setParameter("igAccountId", igAccountId)
                            .getResultList();
            return results.stream().findFirst();
        }

        private Optional<ExternalCreator> findByIgUsernameIgnoreCase(String igUsername) {
            List<ExternalCreator> results =
                    entityManager
                            .createQuery(
                                    "SELECT e FROM ExternalCreator e WHERE LOWER(e.igUsername) = LOWER(:igUsername)",
                                    ExternalCreator.class)
                            .setParameter("igUsername", igUsername)
                            .getResultList();
            return results.stream().findFirst();
        }

        /** Forwards any other call (base {@code JpaRepository}/{@code JpaSpecificationExecutor}
         * CRUD) to a real {@link SimpleJpaRepository}, matched by name and parameter count since
         * generic erasure means the delegate's reflected parameter types are not the interface's
         * declared ones. */
        private Object forwardToDelegate(Object delegate, Method method, Object[] args) throws Throwable {
            int paramCount = method.getParameterCount();
            for (Method candidate : delegate.getClass().getMethods()) {
                if (candidate.getName().equals(method.getName())
                        && candidate.getParameterCount() == paramCount) {
                    candidate.setAccessible(true);
                    try {
                        return candidate.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new NoSuchMethodException(
                    "No matching delegate method for " + method.getName() + " (" + paramCount + " args)");
        }
    }
}
