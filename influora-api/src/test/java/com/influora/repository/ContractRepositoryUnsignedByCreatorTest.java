package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import java.math.BigDecimal;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * F-0623/F-0630 — real Hibernate + H2 proof that {@link
 * ContractRepository#findUnsignedByCreatorId} filters what it actually claims to.
 *
 * <p>WHY A @DataJpaTest AND NOT ONLY MOCKITO. {@code ContractServiceTest#testListUnsignedForCreator}
 * already covers the SERVICE's mapping of whatever the repository returns — but with the
 * repository mocked, the JPQL string itself has zero execution coverage: a wrong filter, or one
 * silently dropped in a future edit, leaves the entire backend suite green, because nothing
 * executes SQL against it. This is the query that decides what a creator sees under "awaiting
 * your signature" on their dashboard; the earlier version of it (before F-0623) shipped for some
 * time with exactly that kind of gap — filtering only {@code creatorSignedAt IS NULL}, which also
 * matched DRAFT and CANCELLED-collaboration contracts — and no test caught it because none ran
 * the real query. This is that test.
 *
 * <p>Same {@code @DataJpaTest} + {@code @AutoConfigureTestDatabase} + narrowly-scoped {@code
 * @EnableJpaRepositories} pattern as {@code PayoutRepositoryCreatorScopingTest} and {@code
 * MetaOAuthTokenRepositoryNullWorkspaceIdTest} — repository scanning is restricted to the one
 * repository under test so H2 is not asked to validate other repositories' MySQL-specific
 * queries. {@code Collaboration} needs no separate {@code @EnableJpaRepositories} entry since the
 * query only ever SELECTs from it inside a subquery — it is scanned as an entity via {@code
 * @EntityScan}, never loaded as a repository here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = Contract.class)
@EnableJpaRepositories(
        basePackageClasses = ContractRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!ContractRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:contract_unsigned_by_creator_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class ContractRepositoryUnsignedByCreatorTest {

    private static final String CREATOR = "01HCREATORAAAAAAAAAAAA1";
    private static final String OTHER_CREATOR = "01HCREATORBBBBBBBBBBBB2";
    private static final String WORKSPACE = "01HWORKSPACEAAAAAAAAAA1";

    @Autowired private ContractRepository contractRepository;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    private org.springframework.data.jpa.repository.support.SimpleJpaRepository<Collaboration, String>
            collaborationHelperRepo;

    @BeforeEach
    void setUp() {
        collaborationHelperRepo =
                new org.springframework.data.jpa.repository.support.SimpleJpaRepository<>(
                        Collaboration.class, entityManager);
    }

    private Collaboration collaboration(String id, String creatorId, CollaborationStatus status) {
        Collaboration c =
                Collaboration.propose(id, "camp_" + id, creatorId, new BigDecimal("30000"), "INR", "msg");
        if (status != CollaborationStatus.IN_NEGOTIATION) {
            c.transitionTo(status);
        }
        return collaborationHelperRepo.save(c);
    }

    private Contract contract(String id, String collaborationId, ContractStatus status, boolean creatorSigned) {
        Contract c =
                Contract.builder()
                        .id(id)
                        .collaborationId(collaborationId)
                        .workspaceId(WORKSPACE)
                        .status(status)
                        .totalAmount(new BigDecimal("30000"))
                        .build();
        if (creatorSigned) {
            c.recordCreatorSignature("Creator Name");
        }
        return contractRepository.save(c);
    }

    @Test
    @DisplayName(
            "returns a PENDING_SIGNATURES contract with creatorSignedAt null, on a live collaboration"
                    + " owned by the caller — the one genuinely-positive case")
    void returnsTheGenuinelyAwaitingContract() {
        collaboration("collab_awaiting", CREATOR, CollaborationStatus.TERMS_AGREED);
        contract("ctr_awaiting", "collab_awaiting", ContractStatus.PENDING_SIGNATURES, false);

        List<Contract> result = contractRepository.findUnsignedByCreatorId(CREATOR);

        assertEquals(1, result.size());
        assertEquals("ctr_awaiting", result.get(0).getId());
    }

    @Test
    @DisplayName("F-0623: excludes a DRAFT contract — never sent to either party")
    void excludesDraftContract() {
        collaboration("collab_draft", CREATOR, CollaborationStatus.TERMS_AGREED);
        contract("ctr_draft", "collab_draft", ContractStatus.DRAFT, false);

        assertTrue(contractRepository.findUnsignedByCreatorId(CREATOR).isEmpty());
    }

    @Test
    @DisplayName("excludes a contract the caller has ALREADY signed (creatorSignedAt is set)")
    void excludesAlreadySignedByCreator() {
        collaboration("collab_signed", CREATOR, CollaborationStatus.TERMS_AGREED);
        contract("ctr_signed", "collab_signed", ContractStatus.PENDING_SIGNATURES, true);

        assertTrue(contractRepository.findUnsignedByCreatorId(CREATOR).isEmpty());
    }

    @Test
    @DisplayName("F-0630: excludes a PENDING_SIGNATURES contract whose collaboration was CANCELLED")
    void excludesContractOnCancelledCollaboration() {
        collaboration("collab_cancelled", CREATOR, CollaborationStatus.CANCELLED);
        contract("ctr_orphaned", "collab_cancelled", ContractStatus.PENDING_SIGNATURES, false);

        assertTrue(
                contractRepository.findUnsignedByCreatorId(CREATOR).isEmpty(),
                "a contract orphaned by its collaboration's cancellation must not appear as actionable");
    }

    @Test
    @DisplayName("does not leak another creator's contract — the tenancy guarantee this query exists for")
    void doesNotLeakAnotherCreatorsContract() {
        collaboration("collab_mine", CREATOR, CollaborationStatus.TERMS_AGREED);
        contract("ctr_mine", "collab_mine", ContractStatus.PENDING_SIGNATURES, false);
        collaboration("collab_theirs", OTHER_CREATOR, CollaborationStatus.TERMS_AGREED);
        contract("ctr_theirs", "collab_theirs", ContractStatus.PENDING_SIGNATURES, false);

        List<Contract> result = contractRepository.findUnsignedByCreatorId(CREATOR);

        assertEquals(1, result.size());
        assertEquals("ctr_mine", result.get(0).getId());
    }
}
