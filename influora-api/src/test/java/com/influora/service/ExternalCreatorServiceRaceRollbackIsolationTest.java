package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.integration.meta.client.CreatorMarketplaceClient;
import com.influora.integration.meta.client.FacebookPageClient;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.BusinessDiscoveryResponse;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creator.ExternalCreatorDtos.ConnectionRequestResponse;
import com.influora.web.dto.creator.ExternalCreatorDtos.ExternalCreatorResponse;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-CREATORCONNECT-0902 Q1.4/Q3.4 (High) — sign-off review defect. Real-transaction-manager proof
 * that a genuine flush-time {@code DataIntegrityViolationException} inside {@link
 * ExternalCreatorService#connect} / {@link ExternalCreatorService#lookup} no longer surfaces as a
 * {@code TransactionSystemException} out of the method's OWN {@code @Transactional} boundary.
 *
 * <p><b>The defect this pins.</b> Per the JPA spec (EntityManager javadoc), every {@code
 * PersistenceException} other than {@code NoResult}/{@code NonUniqueResult}/{@code
 * LockTimeout}/{@code QueryTimeout} marks the CURRENT transaction rollback-only the instant it is
 * thrown. Before this fix, {@code connect()}/{@code lookup()} caught {@code
 * DataIntegrityViolationException} from {@code saveAndFlush()} in their OWN ambient transaction and
 * tried to recover by re-reading and returning normally — but that transaction was already
 * rollback-only, so {@code JpaTransactionManager.doCommit} would hit {@code RollbackException},
 * Spring would wrap it as {@code TransactionSystemException}, and {@code GlobalExceptionHandler}
 * (which has no handler for that type) would turn the caller's intended 200/409 into a generic 500.
 * The fix moves the racy write into its OWN {@code REQUIRES_NEW} transaction (see {@code
 * ExternalCreatorService#requiresNewTransactionTemplate}), so only that inner transaction rolls
 * back and the outer one commits cleanly. A plain Mockito test (see {@code
 * ExternalCreatorServiceTest}) cannot observe any of this — it has no real {@code
 * EntityManager}/{@code TransactionInterceptor}, so a stubbed exception never actually marks a
 * transaction rollback-only in the first place.
 *
 * <p><b>How the failure is forced.</b> Neither {@code ExternalCreator} nor {@code
 * CreatorConnectionRequest} carries its production unique constraint ({@code
 * uk_external_creators_ig_account}/{@code uk_ccr_workspace_creator}) at the JPA/entity level — those
 * live only in the Flyway migration, which {@code spring.flyway.enabled=false} + {@code
 * ddl-auto=create-drop} does not run here — so a genuinely duplicate value would NOT be rejected by
 * H2 in this schema. Exactly like {@code ExternalCreatorLinkServiceRollbackIsolationTest}, this
 * instead forces a real {@code DataIntegrityViolationException} at flush via an over-length column
 * value ({@code @Column(length = ...)} IS honored by {@code ddl-auto=create-drop}, and H2 enforces
 * {@code VARCHAR} width strictly) — {@code requestedByUserId} (length 26) for {@code connect()},
 * {@code igAccountId} (length 64) for {@code lookup()}. This does not reproduce a literal
 * concurrent duplicate-key race, but it reproduces the exact mechanism the finding is about: SOME
 * genuine flush-time {@code PersistenceException}, caught inside the method's own transaction.
 *
 * <p><b>Why the third Q1.4 path (unguarded {@code renameIgUsername} bulk-update collision) is not
 * pinned here.</b> {@code renameIgUsername} only ever writes {@code igUsername} (length 80) and
 * {@code updatedAt}; forcing that specific write to overflow would require a value longer than the
 * column, but {@code lookup()}'s own {@code USERNAME_PATTERN} rejects any username over 80
 * characters before the rename is ever reached — the application-level guard and the column length
 * are numerically identical, so this test technique cannot reach that one write. It runs through the
 * exact same {@link ExternalCreatorService#runInNewTransaction} helper being pinned here, so this
 * test still covers its transactional-isolation mechanics; its own catch-and-continue control flow
 * (log + keep the old username on a caught {@code DataIntegrityViolationException}) is instead
 * pinned at the Mockito level in {@code ExternalCreatorServiceTest#lookup_renameCollision_*}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = {ExternalCreator.class, CreatorConnectionRequest.class})
@EnableJpaRepositories(
        basePackageClasses = {CreatorConnectionRequestRepository.class},
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CreatorConnectionRequestRepository$).*"))
@Import({ExternalCreatorService.class, ExternalCreatorServiceRaceRollbackIsolationTest.TestRepoConfig.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:external_creator_service_race_rollback_isolation_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class ExternalCreatorServiceRaceRollbackIsolationTest {

    private static final String WORKSPACE_ID = "01HWORKSPACERACEISO001";
    private static final String EXTERNAL_ID = "01HEXTRACEISO000000001";

    /** REAL Spring-proxied bean (via {@code @Import}) — {@code connect()}/{@code lookup()}'s own
     * {@code @Transactional} boundary must be genuine for this test to mean anything. */
    @Autowired private ExternalCreatorService externalCreatorService;

    @Autowired private ExternalCreatorRepository externalCreatorRepository;
    @Autowired private CreatorConnectionRequestRepository connectionRequestRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private BrandContextService brandContextService;
    @MockBean private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @MockBean private MetaTokenStorage metaTokenStorage;
    @MockBean private MetaApiProperties metaApiProperties;
    @MockBean private InstagramInsightsClient instagramInsightsClient;
    @MockBean private FacebookPageClient facebookPageClient;
    @MockBean private CreatorMarketplaceClient creatorMarketplaceClient;

    private Workspace workspace() {
        return Workspace.newBrand(WORKSPACE_ID, "Race Isolation Brand", "race-isolation-brand", "Beauty", "10-50");
    }

    // @DataJpaTest wraps every test method in its OWN ambient transaction, auto-rolled-back only
    // at teardown — with that active, connect()/lookup()'s own @Transactional (REQUIRED) would
    // just PARTICIPATE in that already-open transaction instead of being the genuine top-level
    // transaction whose commit this test needs to actually run (same confound documented on
    // BrandDeliverableServiceApprovalRollbackIsolationTest). NOT_SUPPORTED suspends the ambient
    // wrapping so each call below drives a real, independent top-level transaction.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "connect(): a genuine flush-time DataIntegrityViolationException surfaces as itself —"
                    + " never wrapped as TransactionSystemException — and does not poison later calls"
                    + " (Q3.4)")
    void connect_flushFailureSurfacesCleanlyAndDoesNotPoisonLaterCalls() {
        // The hand-built externalCreatorRepository proxy (see TestRepoConfig) has no AOP
        // transaction advice of its own — a genuine transaction must be provided by the caller for
        // a WRITE, exactly as connect()/lookup() themselves provide one via
        // requiresNewTransactionTemplate. NOT_SUPPORTED above means no ambient transaction exists
        // here to piggyback on, so this seed write needs its own, same as
        // ExternalCreatorLinkServiceRollbackIsolationTest's seed.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                externalCreatorRepository.saveAndFlush(
                                        ExternalCreator.builder()
                                                .id(EXTERNAL_ID)
                                                .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                                                .igUsername("racecondition.creator")
                                                .build()));
        when(brandContextService.requireBrandWorkspace(any())).thenReturn(workspace());

        // Over-length (> length=26) requestedByUserId forces a genuine VARCHAR overflow at
        // saveAndFlush — see class javadoc on why this stands in for the real unique-constraint
        // race in this create-drop schema.
        AuthPrincipal overLengthPrincipal =
                new AuthPrincipal("u".repeat(100), "brand@example.com", com.influora.domain.enums.UserType.BRAND, WORKSPACE_ID);

        Exception thrown =
                assertThrows(
                        Exception.class,
                        () -> externalCreatorService.connect(overLengthPrincipal, EXTERNAL_ID, "hi"));
        assertTrue(
                thrown instanceof DataIntegrityViolationException,
                "connect() must surface the genuine DataIntegrityViolationException — not a"
                        + " TransactionSystemException/UnexpectedRollbackException from a poisoned"
                        + " ambient transaction (Q3.4); actually got "
                        + thrown.getClass().getName());
        assertTrue(
                connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(WORKSPACE_ID, EXTERNAL_ID).isEmpty(),
                "the failed insert must not have left a partial/committed row behind");

        // The real, meaningful assertion: the transaction manager and this row are still in a
        // healthy, usable state after that failure — a SUBSEQUENT, valid connect() call for the
        // exact same (workspace, externalCreator) must succeed normally. Before the fix, the first
        // call's TransactionSystemException was the caller-visible symptom; the fix's actual job is
        // that nothing downstream is left broken by it.
        AuthPrincipal validPrincipal =
                new AuthPrincipal("01HVALIDUSER0000000001", "brand@example.com", com.influora.domain.enums.UserType.BRAND, WORKSPACE_ID);
        ConnectionRequestResponse response = externalCreatorService.connect(validPrincipal, EXTERNAL_ID, "hello");
        assertEquals("PENDING", response.status());
        assertTrue(
                connectionRequestRepository.findByWorkspaceIdAndExternalCreatorId(WORKSPACE_ID, EXTERNAL_ID).isPresent(),
                "the recovered call must have actually persisted its request");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "lookup(): a genuine flush-time DataIntegrityViolationException surfaces as itself —"
                    + " never wrapped as TransactionSystemException — and does not poison a later"
                    + " lookup for the same handle (Q1.4)")
    void lookup_flushFailureSurfacesCleanlyAndDoesNotPoisonLaterCalls() {
        when(brandContextService.requireBrandWorkspace(any())).thenReturn(workspace());
        when(metaApiProperties.isConfigured()).thenReturn(true);
        when(metaApiProperties.getSystemIgUserId()).thenReturn("17841400000099999");
        when(metaApiProperties.getSystemIgAccessToken()).thenReturn("system-caller-token");

        // Over-length (> length=64) ig_account_id forces a genuine VARCHAR overflow at
        // saveAndFlush inside upsertFromBusinessDiscovery — see class javadoc.
        String overLengthAccountId = "1".repeat(100);
        BusinessDiscoveryResponse.BusinessDiscovery overLengthBd =
                new BusinessDiscoveryResponse.BusinessDiscovery(
                        overLengthAccountId, "racecondition.handle", "Race Condition", null, null, 500L, 10L);
        when(instagramInsightsClient.businessDiscovery(
                        "17841400000099999", "racecondition.handle", "system-caller-token"))
                .thenReturn(new BusinessDiscoveryResponse("17841400000099999", overLengthBd));

        AuthPrincipal principal =
                new AuthPrincipal("01HBRANDUSER00000001", "brand@example.com", com.influora.domain.enums.UserType.BRAND, WORKSPACE_ID);

        Exception thrown =
                assertThrows(
                        Exception.class, () -> externalCreatorService.lookup(principal, "racecondition.handle"));
        assertTrue(
                thrown instanceof DataIntegrityViolationException,
                "lookup() must surface the genuine DataIntegrityViolationException — not a"
                        + " TransactionSystemException/UnexpectedRollbackException from a poisoned"
                        + " ambient transaction (Q1.4); actually got "
                        + thrown.getClass().getName());
        assertTrue(
                externalCreatorRepository.findByIgUsername("racecondition.handle").isEmpty(),
                "the failed insert must not have left a partial/committed row behind");

        // Same real, meaningful assertion as the connect() test: a SUBSEQUENT, valid Business
        // Discovery response for the same handle must still resolve normally afterward.
        BusinessDiscoveryResponse.BusinessDiscovery validBd =
                new BusinessDiscoveryResponse.BusinessDiscovery(
                        "17841400000000777", "racecondition.handle", "Race Condition", null, null, 500L, 10L);
        when(instagramInsightsClient.businessDiscovery(
                        "17841400000099999", "racecondition.handle", "system-caller-token"))
                .thenReturn(new BusinessDiscoveryResponse("17841400000099999", validBd));

        ExternalCreatorResponse response = externalCreatorService.lookup(principal, "racecondition.handle");
        assertEquals("racecondition.handle", response.igUsername());
        assertFalse(externalCreatorRepository.findByIgUsername("racecondition.handle").isEmpty());
    }

    /** Hand-built {@link ExternalCreatorRepository} proxy — see
     * {@code ExternalCreatorLinkServiceRollbackIsolationTest} for why {@code
     * @EnableJpaRepositories} cannot scan this interface directly: Spring Data validates every
     * {@code @Query} method EAGERLY at proxy-creation time, and {@code renameIgUsername}'s {@code
     * CURRENT_TIMESTAMP} fails Hibernate 6's strict JPQL type-check under {@code H2Dialect} (fine
     * under the real {@code MySQLDialect} this app runs on). Unlike that test, THIS test's {@code
     * lookup()} coverage genuinely needs {@code findByIgUsername}/{@code renameIgUsername} to work,
     * so both are implemented for real here against the same {@link EntityManager}, using a bound
     * {@code Instant} parameter instead of {@code CURRENT_TIMESTAMP} to sidestep the exact same H2
     * type-check issue without changing the production query. */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestRepoConfig {

        @PersistenceContext private EntityManager entityManager;

        /**
         * Real Spring Data JPA repository beans get {@code PersistenceExceptionTranslation} applied
         * by the repository infrastructure automatically, which is how production code sees {@code
         * DataIntegrityViolationException} rather than a raw {@code org.hibernate.exception.*}. This
         * hand-built proxy bypasses that infrastructure (see class javadoc), so it must apply the
         * SAME translation itself — otherwise a genuine flush failure here would surface as, e.g.,
         * {@code org.hibernate.exception.DataException}, which is not the class production code
         * actually throws and would make this test assert on the wrong exception type entirely.
         */
        private static final HibernateJpaDialect EXCEPTION_TRANSLATOR = new HibernateJpaDialect();

        @org.springframework.context.annotation.Bean
        ExternalCreatorRepository externalCreatorRepository() {
            SimpleJpaRepository<ExternalCreator, String> delegate =
                    new SimpleJpaRepository<>(ExternalCreator.class, entityManager);

            InvocationHandler handler =
                    (proxyInstance, method, args) -> {
                        try {
                            switch (method.getName()) {
                                case "findByIgAccountId":
                                    return findByIgAccountId((String) args[0]);
                                case "findByIgUsernameIgnoreCase":
                                    return findByIgUsernameIgnoreCase((String) args[0]);
                                case "findByIgUsername":
                                    return findByIgUsername((String) args[0]);
                                case "renameIgUsername":
                                    return renameIgUsername((String) args[0], (String) args[1]);
                                default:
                                    return forwardToDelegate(delegate, method, args);
                            }
                        } catch (RuntimeException e) {
                            DataAccessException translated = EXCEPTION_TRANSLATOR.translateExceptionIfPossible(e);
                            throw translated != null ? translated : e;
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

        private Optional<ExternalCreator> findByIgUsername(String igUsername) {
            List<ExternalCreator> results =
                    entityManager
                            .createQuery(
                                    "SELECT e FROM ExternalCreator e WHERE e.igUsername = :igUsername",
                                    ExternalCreator.class)
                            .setParameter("igUsername", igUsername)
                            .getResultList();
            return results.stream().findFirst();
        }

        /** Same observable effect as the production {@code @Query} (rename + bump updatedAt +
         * evict the persistence context) via a bound {@code Instant} instead of {@code
         * CURRENT_TIMESTAMP}, purely to sidestep the H2 JPQL type-check — see class javadoc. */
        private int renameIgUsername(String id, String igUsername) {
            int updated =
                    entityManager
                            .createQuery(
                                    "UPDATE ExternalCreator e SET e.igUsername = :igUsername, e.updatedAt = :now"
                                            + " WHERE e.id = :id")
                            .setParameter("igUsername", igUsername)
                            .setParameter("now", java.time.Instant.now())
                            .setParameter("id", id)
                            .executeUpdate();
            entityManager.clear();
            return updated;
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
