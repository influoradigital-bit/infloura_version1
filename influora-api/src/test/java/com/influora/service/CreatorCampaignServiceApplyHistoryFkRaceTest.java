package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.ApplicationHistoryEventRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyRequest;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyResponse;
import jakarta.persistence.EntityManager;
import java.util.List;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-off review defect (BLOCKING, follow-on to {@code
 * BrandDeliverableServiceApprovalRollbackIsolationTest}) — real-transaction-manager, REAL-FOREIGN-
 * KEY proof that {@code CreatorCampaignService#apply}'s two apply-time history writes
 * (CAMPAIGN_APPLIED, APPLICATION_RECEIVED) survive against the actual {@code
 * fk_app_history_application} FK from V69, not just against Hibernate's own permissive
 * {@code ddl-auto=create-drop} schema (which has no FK at all, because {@link
 * com.influora.domain.entity.ApplicationHistoryEvent} carries no {@code @ManyToOne} — every other
 * test in this codebase, including the ones that already exercise this call site, passes with the
 * defect live for exactly that reason).
 *
 * <p><b>The race this proves closed.</b> {@link com.influora.domain.entity.Collaboration}'s
 * {@code @Id} is a pre-assigned ULID with no {@code @GeneratedValue}, so {@code
 * collaborationRepository.save(collaboration)} inside {@code apply()} routes through {@code
 * em.merge()} and defers the actual {@code INSERT} to flush — normally at {@code apply()}'s own
 * ambient-transaction commit. {@link ApplicationHistoryService#record} is {@code REQUIRES_NEW}: if
 * it is called synchronously, still inside {@code apply()}'s transaction, it opens a SEPARATE
 * connection that cannot see the not-yet-flushed {@code collaborations} row, and the real {@code
 * fk_app_history_application} FK (present here, absent from every {@code create-drop} test schema)
 * rejects the insert — silently swallowed by the call site's own best-effort catch, 200 returned,
 * timeline permanently missing its origin event. The fix defers the two {@code record(...)} calls
 * to an {@code AFTER_COMMIT} listener so they only ever run once {@code collaborations} is durable.
 *
 * <p><b>Why {@code ddl-auto=none} plus a hand-written {@code CREATE TABLE}, mirroring the pattern
 * {@code ApplicationHistoryEventOrderingTest} already uses.</b> Letting Hibernate generate the
 * schema from the entities (this codebase's usual {@code create-drop} test pattern) would produce
 * NO foreign key at all — {@code ApplicationHistoryEvent} has no {@code @ManyToOne} — so the one
 * thing this test exists to prove (does the real V69 FK survive) would silently not be exercised.
 * The DDL below mirrors {@code V69__application_history_events.sql} (both its FKs), {@code
 * V6__creators_collaborations.sql}'s {@code collaborations} table, and {@code
 * V4__campaigns.sql}'s {@code campaigns} table — trimmed of {@code collaborations}/{@code
 * campaigns}' OWN outbound FKs to {@code users}/{@code workspaces}, which this defect does not
 * touch and would otherwise drag in two more parent tables for no test value.
 *
 * <p><b>Why {@code @Transactional(propagation = NOT_SUPPORTED)} on the test method.</b> Same
 * confound as the sibling isolation tests: {@code @DataJpaTest} wraps every test method in its own
 * ambient transaction, auto-rolled-back only at teardown and never genuinely committed mid-test.
 * With that active, {@code apply()}'s own {@code @Transactional} would just PARTICIPATE instead of
 * being the genuine top-level transaction whose commit is what the {@code AFTER_COMMIT} listener
 * is waiting for — the listener would never fire during the test at all, and the assertions below
 * would pass or fail for the wrong reason (or hang forever waiting on a commit that never happens
 * within the test's lifetime). {@code NOT_SUPPORTED} suspends that ambient wrapping so {@code
 * apply()}'s transaction is the only real one in play, exactly like {@code
 * ApplicationHistoryServiceRollbackIsolationTest} and {@code
 * BrandDeliverableServiceApprovalRollbackIsolationTest} before it. The schema DDL is likewise run
 * inside its own explicit {@link TransactionTemplate} (not relying on any ambient transaction that
 * this method has suspended) — matching the same explicit-transaction idiom {@code
 * ApplicationHistoryServiceRollbackIsolationTest} uses for its business write.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(
        basePackageClasses = {
            Campaign.class,
            com.influora.domain.entity.Collaboration.class,
            ApplicationHistoryEvent.class
        })
@EnableJpaRepositories(
        basePackageClasses = {
            CampaignRepository.class,
            CollaborationRepository.class,
            ApplicationHistoryEventRepository.class
        },
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "com\\.influora\\.repository\\.(?!CampaignRepository$|CollaborationRepository$|ApplicationHistoryEventRepository$).*"))
@Import({CreatorCampaignService.class, ApplicationHistoryService.class})
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:creator_campaign_apply_history_fk_race_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class CreatorCampaignServiceApplyHistoryFkRaceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACEFKRACE0001";
    private static final String CAMPAIGN_ID = "01HCAMPAIGNFKRACE00001";
    private static final String CREATOR_USER_ID = "01HCREATORFKRACE000001";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILEFKRC1";

    /** REAL Spring-proxied bean (via {@code @Import}) — the actual method under test. */
    @Autowired private CreatorCampaignService creatorCampaignService;

    @Autowired private CampaignRepository campaignRepository;
    @Autowired private ApplicationHistoryEventRepository applicationHistoryEventRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private WorkspaceRepository workspaceRepository;
    @MockBean private CreatorContextService creatorContext;
    @MockBean private BrandContextService brandContext;
    @MockBean private CollaborationReviveService collaborationReviveService;
    @MockBean private DealMessageRepository dealMessageRepository;

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName(
            "apply(): CAMPAIGN_APPLIED and APPLICATION_RECEIVED survive the REAL"
                    + " fk_app_history_application FK against the not-yet-flushed Collaboration row")
    void applyPersistsHistoryDespiteRealForeignKey() {
        // Schema DDL in its own explicit, genuinely-committing transaction — ddl-auto=none means
        // nothing exists yet. Mirrors V4/V6/V69, minus collaborations/campaigns' own outbound FKs
        // to users/workspaces (irrelevant to this defect, would drag in two more parent tables).
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            entityManager
                                    .createNativeQuery(
                                            "CREATE TABLE campaigns ("
                                                    + "id VARCHAR(26) PRIMARY KEY,"
                                                    + "workspace_id VARCHAR(26) NOT NULL,"
                                                    + "title VARCHAR(300) NOT NULL,"
                                                    + "description CLOB,"
                                                    + "status VARCHAR(32) NOT NULL,"
                                                    + "budget_min DECIMAL(12,2),"
                                                    + "budget_max DECIMAL(12,2),"
                                                    + "currency VARCHAR(3) NOT NULL,"
                                                    + "start_date DATE,"
                                                    + "end_date DATE,"
                                                    + "application_deadline DATE,"
                                                    + "platforms JSON,"
                                                    + "content_types JSON,"
                                                    + "objectives JSON,"
                                                    + "requirements JSON,"
                                                    + "hashtags JSON,"
                                                    + "target_audience JSON,"
                                                    + "brand_guidelines CLOB,"
                                                    + "is_private BOOLEAN NOT NULL,"
                                                    + "max_collaborators INT,"
                                                    + "created_by VARCHAR(26) NOT NULL,"
                                                    + "campaign_type VARCHAR(32),"
                                                    + "hype_config JSON,"
                                                    + "commission_rate DECIMAL(5,4),"
                                                    + "created_at TIMESTAMP NOT NULL,"
                                                    + "updated_at TIMESTAMP NOT NULL)")
                                    .executeUpdate();
                            entityManager
                                    .createNativeQuery(
                                            "CREATE TABLE collaborations ("
                                                    + "id VARCHAR(26) PRIMARY KEY,"
                                                    + "campaign_id VARCHAR(26) NOT NULL,"
                                                    + "creator_id VARCHAR(26) NOT NULL,"
                                                    + "status VARCHAR(32) NOT NULL,"
                                                    + "source VARCHAR(32) NOT NULL,"
                                                    + "agreed_rate DECIMAL(12,2),"
                                                    + "currency VARCHAR(3),"
                                                    + "notes CLOB,"
                                                    + "usage_rights CLOB,"
                                                    + "created_at TIMESTAMP NOT NULL,"
                                                    + "applied_at TIMESTAMP NOT NULL,"
                                                    + "updated_at TIMESTAMP NOT NULL,"
                                                    + "CONSTRAINT fk_collab_campaign_test FOREIGN KEY (campaign_id)"
                                                    + " REFERENCES campaigns(id))")
                                    .executeUpdate();
                            // Mirrors V69 exactly, including BOTH its FKs — fk_app_history_application is
                            // the one this test exists to prove survives; fk_app_history_campaign comes
                            // for free since campaigns already has to exist as application_id's own
                            // grandparent for a realistic apply() flow.
                            entityManager
                                    .createNativeQuery(
                                            "CREATE TABLE application_history_events ("
                                                    + "id VARCHAR(26) PRIMARY KEY,"
                                                    + "sequence_no BIGINT NOT NULL AUTO_INCREMENT,"
                                                    + "campaign_id VARCHAR(26) NOT NULL,"
                                                    + "application_id VARCHAR(26) NOT NULL,"
                                                    + "deal_room_id VARCHAR(26),"
                                                    + "event_type VARCHAR(32) NOT NULL,"
                                                    + "event_status VARCHAR(32) NOT NULL,"
                                                    + "actor_type VARCHAR(16) NOT NULL,"
                                                    + "actor_id VARCHAR(26),"
                                                    + "description CLOB NOT NULL,"
                                                    + "metadata CLOB,"
                                                    + "target_route VARCHAR(255),"
                                                    + "target_id VARCHAR(26),"
                                                    + "created_at TIMESTAMP(3) NOT NULL,"
                                                    + "UNIQUE (sequence_no),"
                                                    + "CONSTRAINT fk_app_history_campaign_test FOREIGN KEY"
                                                    + " (campaign_id) REFERENCES campaigns(id),"
                                                    + "CONSTRAINT fk_app_history_application_test FOREIGN KEY"
                                                    + " (application_id) REFERENCES collaborations(id))")
                                    .executeUpdate();
                        });

        Campaign campaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("FK Race Campaign")
                        .status(CampaignStatus.ACTIVE)
                        .currency("INR")
                        .isPrivate(false)
                        .createdBy("brand_user_1")
                        .build();
        campaignRepository.save(campaign);

        CreatorProfile creator =
                CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "FK Race Creator");
        AuthPrincipal principal = mock(AuthPrincipal.class);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creator);
        // No prior collaboration for this (campaign, creator) pair — the NEW-application branch,
        // the one with the FK hazard (the revive branch targets an already-persisted row and never
        // hits this race).
        when(collaborationReviveService.reviveOrRefuse(
                        any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        when(brandContext.resolveBillingRecipient(WORKSPACE_ID)).thenReturn(null);

        ApplyResponse response =
                creatorCampaignService.apply(principal, CAMPAIGN_ID, new ApplyRequest(null));

        List<ApplicationHistoryEvent> events =
                applicationHistoryEventRepository.findByApplicationIdOrderByCreatedAtAscSequenceNoAsc(
                        response.collaborationId());

        // The actual defect this test exists to catch: before the fix, both record() calls threw
        // DataIntegrityViolationException against the real FK (the parent collaborations row was
        // not yet flushed on record()'s own separate REQUIRES_NEW connection), silently swallowed
        // by the call site's own catch — apply() still returned normally, but the timeline
        // permanently has zero rows for a real, successful application.
        assertEquals(
                2,
                events.size(),
                "CAMPAIGN_APPLIED and APPLICATION_RECEIVED must both survive the real"
                        + " fk_app_history_application FK — a missing count here means the FK race"
                        + " swallowed the history write while apply() itself still succeeded");
        assertTrue(
                events.stream().anyMatch(e -> e.getEventType() == ApplicationHistoryEventType.CAMPAIGN_APPLIED),
                "CAMPAIGN_APPLIED must be present");
        assertTrue(
                events.stream().anyMatch(e -> e.getEventType() == ApplicationHistoryEventType.APPLICATION_RECEIVED),
                "APPLICATION_RECEIVED must be present");
    }
}
