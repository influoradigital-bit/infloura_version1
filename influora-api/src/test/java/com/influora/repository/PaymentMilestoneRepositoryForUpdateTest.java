package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.MilestoneStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EV-002 — real Hibernate + H2 (MODE=MySQL) execution of {@link
 * PaymentMilestoneRepository#findByIdAndWorkspaceIdForUpdate}, the row lock {@code
 * EscrowService#initiateFund} relies on to stop two fund attempts for one milestone from both
 * debiting the brand. The service test mocks this repository, so without this class the JPQL, its
 * workspace scoping and the {@code @Lock} annotation would have no execution coverage at all.
 *
 * <p>The concurrency test is the one that matters: while transaction A holds the lock and has not
 * yet committed its FUNDED state, transaction B's locking read must WAIT and then see FUNDED. A
 * non-locking read in B would return immediately with the stale PENDING row, which is exactly how
 * a second debit got through. H2 is not InnoDB; this proves the query takes a blocking row lock
 * and returns the committed state after the wait, not MySQL's gap-lock behaviour.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = {Campaign.class, Collaboration.class, PaymentMilestone.class})
@EnableJpaRepositories(
        basePackageClasses = {
            CampaignRepository.class, CollaborationRepository.class, PaymentMilestoneRepository.class
        },
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "com\\.influora\\.repository\\.(?!CampaignRepository$|CollaborationRepository$"
                                        + "|PaymentMilestoneRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:milestone_for_update_test;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=10000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class PaymentMilestoneRepositoryForUpdateTest {

    private static final String WORKSPACE = "01HWORKSPACELOCK00001";
    private static final String OTHER_WORKSPACE = "01HWORKSPACELOCK00002";
    private static final long HOLD_MILLIS = 700;

    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CollaborationRepository collaborationRepository;
    @Autowired private PaymentMilestoneRepository milestoneRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private void seed(String suffix) {
        String campaignId = "01HCAMPAIGNLOCK" + suffix;
        String collabId = "01HCOLLABLOCK00" + suffix;
        campaignRepository.save(
                Campaign.builder()
                        .id(campaignId)
                        .workspaceId(WORKSPACE)
                        .title("Lock test " + suffix)
                        .status(CampaignStatus.ACTIVE)
                        .currency("INR")
                        .createdBy("brand_user_1")
                        .build());
        collaborationRepository.save(Collaboration.invite(collabId, campaignId, "01HCREATORLOCK000001", null, "INR"));
        milestoneRepository.save(
                PaymentMilestone.builder()
                        .id(milestoneId(suffix))
                        .contractId("01HCONTRACTLOCK" + suffix)
                        .collaborationId(collabId)
                        .sequenceNo(1)
                        .amount(new BigDecimal("5000.00"))
                        .currency("INR")
                        .build());
    }

    private static String milestoneId(String suffix) {
        return "01HMILESTONELOC" + suffix;
    }

    @Test
    @DisplayName("resolves the milestone in its own workspace, under a PESSIMISTIC_WRITE lock")
    void resolvesOwnWorkspaceUnderLock() {
        seed("000001");
        entityManager.flush();
        entityManager.clear();

        PaymentMilestone locked =
                milestoneRepository
                        .findByIdAndWorkspaceIdForUpdate(milestoneId("000001"), WORKSPACE)
                        .orElseThrow();

        assertEquals(MilestoneStatus.PENDING, locked.getStatus());
        assertEquals(LockModeType.PESSIMISTIC_WRITE, entityManager.getLockMode(locked));
    }

    @Test
    @DisplayName("another workspace's milestone id resolves to empty (same scoping as the plain lookup)")
    void otherWorkspaceResolvesEmpty() {
        seed("000002");
        entityManager.flush();
        entityManager.clear();

        assertTrue(
                milestoneRepository
                        .findByIdAndWorkspaceIdForUpdate(milestoneId("000002"), OTHER_WORKSPACE)
                        .isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("a second locking read waits for the first transaction and then sees its FUNDED state")
    void secondLockingReadWaitsAndSeesCommittedFundedState() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String suffix = "000003";
        String id = milestoneId(suffix);
        tx.executeWithoutResult(s -> seed(suffix));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch aHoldsLock = new CountDownLatch(1);
        try {
            Future<?> a =
                    pool.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            s -> {
                                                PaymentMilestone m =
                                                        milestoneRepository
                                                                .findByIdAndWorkspaceIdForUpdate(id, WORKSPACE)
                                                                .orElseThrow();
                                                aHoldsLock.countDown();
                                                sleep(HOLD_MILLIS);
                                                m.markFunded("01HHOLDFROMTXA0000001");
                                                milestoneRepository.save(m);
                                            }));
            assertTrue(aHoldsLock.await(10, TimeUnit.SECONDS), "transaction A never took the lock");

            long started = System.nanoTime();
            PaymentMilestone seenByB =
                    tx.execute(
                            s -> {
                                PaymentMilestone m =
                                        milestoneRepository
                                                .findByIdAndWorkspaceIdForUpdate(id, WORKSPACE)
                                                .orElseThrow();
                                // The entity backstop refuses a second funding inside B too.
                                assertThrows(IllegalStateException.class, () -> m.markFunded("01HHOLDFROMTXB0000001"));
                                return m;
                            });
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            a.get(10, TimeUnit.SECONDS);

            assertTrue(
                    waitedMillis >= HOLD_MILLIS / 2,
                    "B's locking read returned after " + waitedMillis + "ms; it must block on A's row lock");
            assertEquals(MilestoneStatus.FUNDED, seenByB.getStatus(), "B must see A's committed state, not PENDING");
            assertEquals("01HHOLDFROMTXA0000001", seenByB.getEscrowHoldId());
        } finally {
            pool.shutdownNow();
            tx.executeWithoutResult(
                    s -> {
                        milestoneRepository.deleteById(id);
                        collaborationRepository.deleteById("01HCOLLABLOCK00" + suffix);
                        campaignRepository.deleteById("01HCAMPAIGNLOCK" + suffix);
                    });
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
