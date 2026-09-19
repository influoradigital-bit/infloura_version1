package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
 * EV-176 / EV-178 — real Hibernate + H2 (MODE=MySQL) execution of {@link
 * EscrowHoldRepository#findActiveCampaignLevelHoldsForUpdate}, the query {@code
 * EscrowService#initiateFund} uses, under the campaign row lock, to refuse a second campaign-level
 * pool fund. The service tests mock this repository, so without this class the JPQL filter
 * (campaign, {@code milestone_id IS NULL}, active statuses) and its {@code @Lock} would have no
 * execution coverage at all. H2 is not InnoDB: the REPEATABLE READ snapshot argument for why the
 * read must be a locking one is documented on the repository method, not proven here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = {Campaign.class, EscrowHold.class})
@EnableJpaRepositories(
        basePackageClasses = {CampaignRepository.class, EscrowHoldRepository.class},
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CampaignRepository$|EscrowHoldRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:escrow_pool_for_update_test;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=10000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class EscrowHoldRepositoryCampaignLevelForUpdateTest {

    private static final String WORKSPACE = "01HWORKSPACEPOOL00001";
    private static final Set<EscrowStatus> ACTIVE = EnumSet.of(EscrowStatus.PENDING, EscrowStatus.FUNDED);
    private static final long HOLD_MILLIS = 700;

    @Autowired private CampaignRepository campaignRepository;
    @Autowired private EscrowHoldRepository escrowHoldRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private void seedCampaign(String campaignId) {
        campaignRepository.save(
                Campaign.builder()
                        .id(campaignId)
                        .workspaceId(WORKSPACE)
                        .title("Pool lock test " + campaignId)
                        .status(CampaignStatus.ACTIVE)
                        .currency("INR")
                        .createdBy("brand_user_1")
                        .build());
    }

    private EscrowHold hold(String id, String campaignId, String milestoneId, EscrowStatus status) {
        return escrowHoldRepository.save(
                EscrowHold.builder()
                        .id(id)
                        .workspaceId(WORKSPACE)
                        .campaignId(campaignId)
                        .milestoneId(milestoneId)
                        .amount(new BigDecimal("50000.00"))
                        .currency("INR")
                        .status(status)
                        .idempotencyKey("key-" + id)
                        .build());
    }

    @Test
    @DisplayName(
            "returns only this campaign's ACTIVE campaign-level holds (not milestone, not RELEASED/REFUNDED,"
                    + " not another campaign's), under a PESSIMISTIC_WRITE lock")
    void returnsOnlyActiveCampaignLevelHoldsUnderLock() {
        String campaign = "01HCAMPAIGNPOOL000001";
        String other = "01HCAMPAIGNPOOL000002";
        seedCampaign(campaign);
        seedCampaign(other);
        hold("01HHOLDPOOLFUNDED0001", campaign, null, EscrowStatus.FUNDED);
        hold("01HHOLDPOOLPENDING001", campaign, null, EscrowStatus.PENDING);
        hold("01HHOLDPOOLRELEASED01", campaign, null, EscrowStatus.RELEASED);
        hold("01HHOLDPOOLREFUNDED01", campaign, null, EscrowStatus.REFUNDED);
        hold("01HHOLDMILESTONE00001", campaign, "01HMILESTONEPOOL00001", EscrowStatus.FUNDED);
        hold("01HHOLDOTHERCAMPAIGN1", other, null, EscrowStatus.FUNDED);
        entityManager.flush();
        entityManager.clear();

        List<EscrowHold> active = escrowHoldRepository.findActiveCampaignLevelHoldsForUpdate(campaign, ACTIVE);

        assertEquals(
                Set.of("01HHOLDPOOLFUNDED0001", "01HHOLDPOOLPENDING001"),
                Set.copyOf(active.stream().map(EscrowHold::getId).toList()));
        for (EscrowHold h : active) {
            assertEquals(LockModeType.PESSIMISTIC_WRITE, entityManager.getLockMode(h));
        }
        assertTrue(
                escrowHoldRepository
                        .findActiveCampaignLevelHoldsForUpdate("01HCAMPAIGNPOOL000003", ACTIVE)
                        .isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "a second attempt blocked on the campaign lock sees the pool hold the first attempt committed"
                    + " while it waited")
    void secondAttemptSeesPoolHoldCommittedWhileItWaited() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String campaign = "01HCAMPAIGNPOOL000009";
        tx.executeWithoutResult(s -> seedCampaign(campaign));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch aHoldsLock = new CountDownLatch(1);
        try {
            Future<?> a =
                    pool.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            s -> {
                                                campaignRepository.findByIdForUpdate(campaign).orElseThrow();
                                                assertTrue(
                                                        escrowHoldRepository
                                                                .findActiveCampaignLevelHoldsForUpdate(campaign, ACTIVE)
                                                                .isEmpty());
                                                aHoldsLock.countDown();
                                                sleep(HOLD_MILLIS);
                                                hold("01HHOLDPOOLFROMTXA001", campaign, null, EscrowStatus.FUNDED);
                                            }));
            assertTrue(aHoldsLock.await(10, TimeUnit.SECONDS), "transaction A never took the campaign lock");

            long started = System.nanoTime();
            List<String> seenByB =
                    tx.execute(
                            s -> {
                                // A plain read BEFORE waiting on the lock, as initiateFund does
                                // (membership lookup, key replay) before reaching this point.
                                escrowHoldRepository.findByCampaignId(campaign);
                                campaignRepository.findByIdForUpdate(campaign).orElseThrow();
                                return escrowHoldRepository
                                        .findActiveCampaignLevelHoldsForUpdate(campaign, ACTIVE)
                                        .stream()
                                        .map(EscrowHold::getId)
                                        .toList();
                            });
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            a.get(10, TimeUnit.SECONDS);

            assertTrue(
                    waitedMillis >= HOLD_MILLIS / 2,
                    "B returned after " + waitedMillis + "ms; it must block on A's campaign row lock");
            assertEquals(List.of("01HHOLDPOOLFROMTXA001"), seenByB, "B must see A's committed pool hold");
        } finally {
            pool.shutdownNow();
            tx.executeWithoutResult(
                    s -> {
                        escrowHoldRepository.deleteAll(escrowHoldRepository.findByCampaignId(campaign));
                        campaignRepository.deleteById(campaign);
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
