package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.domain.enums.ReleaseCondition;
import java.math.BigDecimal;
import java.util.Set;
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
 * F-0656 — real Hibernate + H2 proof that {@link EscrowHoldRepository#hasEscrowForContract}
 * filters by contract version the way its javadoc claims.
 *
 * <p>WHY THIS EXISTS ALONGSIDE THE MOCKITO TEST. {@code DealServiceEscrowContractScopeTest} proves
 * {@code DealService} CALLS the contract-scoped method with the resolved current contract id. With
 * the repository mocked, the JPQL itself has zero execution coverage — a missing {@code
 * m.contractId} predicate would leave that test, and the whole backend suite, green while the
 * money-facing flag stayed exactly as wrong as before. This project has already been bitten by
 * that shape: a Mockito-only test let a wrong column name through to a boot-time failure. So the
 * predicate is executed here against a real database.
 *
 * <p>Scenario is the finding's own: v1 signed and FUNDED, then amended. v2's fresh milestone is
 * unfunded; the original hold stays bound to v1's milestone because nothing refunds or re-links
 * it. Asking about v2 must answer false, asking about v1 must answer true — from the same rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = EscrowHold.class)
@EnableJpaRepositories(
        basePackageClasses = EscrowHoldRepository.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!EscrowHoldRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:escrow_contract_scope_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class EscrowHoldRepositoryContractScopeTest {

    private static final String COLLAB = "01HCOLLAB0000000000001";
    private static final String WORKSPACE = "01HWORKSPACE000000001";
    private static final String CONTRACT_V1 = "01HCONTRACTV100000001";
    private static final String CONTRACT_V2 = "01HCONTRACTV200000002";
    private static final String MILESTONE_V1 = "01HMILESTONEV10000001";
    private static final String MILESTONE_V2 = "01HMILESTONEV20000002";
    private static final Set<EscrowStatus> FUNDED = Set.of(EscrowStatus.FUNDED);

    @Autowired private EscrowHoldRepository escrowHoldRepository;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    private PaymentMilestone milestone(String id, String contractId, int seq) {
        return PaymentMilestone.builder()
                .id(id)
                .contractId(contractId)
                .collaborationId(COLLAB)
                .sequenceNo(seq)
                .description("milestone " + seq)
                .amount(new BigDecimal("5000.00"))
                .currency("INR")
                .status(MilestoneStatus.PENDING)
                .releaseCondition(ReleaseCondition.ON_APPROVAL)
                .build();
    }

    @BeforeEach
    void setUp() {
        // v1's milestone and v2's replacement milestone both belong to the SAME collaboration —
        // which is exactly why the collaboration-scoped query could not tell them apart.
        entityManager.persist(milestone(MILESTONE_V1, CONTRACT_V1, 1));
        entityManager.persist(milestone(MILESTONE_V2, CONTRACT_V2, 1));

        // The original FUNDED hold, still bound to v1's milestone. Note collaborationId is NULL,
        // matching every ordinary brand-funded hold (CR-49/CR-35) — if it were set, the direct
        // branch would answer true for any contract and this test would prove nothing.
        entityManager.persist(
                EscrowHold.builder()
                        .id("01HESCROWHOLD00000001")
                        .workspaceId(WORKSPACE)
                        .milestoneId(MILESTONE_V1)
                        .amount(new BigDecimal("5000.00"))
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("f0656-scope-test-hold-1")
                        .build());
        entityManager.flush();
    }

    @Test
    @DisplayName("[F-0656] the superseded v1 hold does NOT make the current v2 contract funded")
    void testAmendedContractIsNotFundedByPredecessorsHold() {
        assertFalse(
                escrowHoldRepository.hasEscrowForContract(COLLAB, CONTRACT_V2, FUNDED),
                "a hold bound to v1's milestone must not report v2 as funded");
    }

    @Test
    @DisplayName("[F-0656] the contract that really is funded still reports true")
    void testFundedContractStillReportsTrue() {
        assertTrue(
                escrowHoldRepository.hasEscrowForContract(COLLAB, CONTRACT_V1, FUNDED),
                "v1's own milestone is funded, so v1 must report true");
    }

    @Test
    @DisplayName("[F-0656 control] the OLD collaboration-scoped query cannot tell the two apart")
    void testCollaborationScopedQueryIsTheDefect() {
        // The positive control for the whole finding: same rows, old query, wrong answer. Without
        // this, a green pair above could mean the fix works OR that the scenario never reproduced
        // the bug in the first place.
        assertTrue(
                escrowHoldRepository.hasEscrowForCollaboration(COLLAB, FUNDED),
                "the collaboration-scoped query answers true regardless of contract version —"
                        + " this is the F-0656 defect, kept executable so the fix stays meaningful");
    }
}
