package com.influora.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.enums.CampaignStatus;
import java.util.List;
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
 * F-0901: {@code MetricsAuthorizationService} grants a brand access to a creator's private metrics
 * when {@link CollaborationRepository#findByWorkspaceIdAndCreatorId} finds a collaboration the
 * creator agreed to. Its unit test mocks this query, so a query that lost its workspace subquery
 * would let ANY workspace's deal grant access while every unit test stayed green. This runs the
 * real JPQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EntityScan(basePackageClasses = {Campaign.class, Collaboration.class})
@EnableJpaRepositories(
        basePackageClasses = {CampaignRepository.class, CollaborationRepository.class},
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.influora\\.repository\\.(?!CampaignRepository$|CollaborationRepository$).*"))
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:collab_workspace_creator_query_test;DB_CLOSE_DELAY=-1;MODE=MySQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
class CollaborationRepositoryWorkspaceCreatorQueryTest {

    private static final String BRAND_A = "01HWORKSPACEQUERYA0001";
    private static final String BRAND_B = "01HWORKSPACEQUERYB0001";
    private static final String CREATOR = "01HCREATORQUERY000001";
    private static final String OTHER_CREATOR = "01HCREATORQUERY000002";

    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CollaborationRepository collaborationRepository;

    private void campaign(String id, String workspaceId) {
        campaignRepository.save(
                Campaign.builder()
                        .id(id)
                        .workspaceId(workspaceId)
                        .title("Query test " + id)
                        .status(CampaignStatus.ACTIVE)
                        .currency("INR")
                        .createdBy("brand_user_1")
                        .build());
    }

    @Test
    @DisplayName("returns only the asking workspace's collaborations with that exact creator")
    void scopedToWorkspaceAndCreator() {
        campaign("01HCAMPAIGNQUERYA0001", BRAND_A);
        campaign("01HCAMPAIGNQUERYA0002", BRAND_A);
        campaign("01HCAMPAIGNQUERYB0001", BRAND_B);
        collaborationRepository.save(Collaboration.invite("01HCOLLABQUERY0000001", "01HCAMPAIGNQUERYA0001", CREATOR, null, "INR"));
        collaborationRepository.save(Collaboration.invite("01HCOLLABQUERY0000002", "01HCAMPAIGNQUERYA0002", CREATOR, null, "INR"));
        collaborationRepository.save(Collaboration.invite("01HCOLLABQUERY0000003", "01HCAMPAIGNQUERYB0001", CREATOR, null, "INR"));
        collaborationRepository.save(Collaboration.invite("01HCOLLABQUERY0000004", "01HCAMPAIGNQUERYA0001", OTHER_CREATOR, null, "INR"));

        List<String> brandA =
                collaborationRepository.findByWorkspaceIdAndCreatorId(BRAND_A, CREATOR).stream()
                        .map(Collaboration::getId)
                        .sorted()
                        .toList();
        assertEquals(List.of("01HCOLLABQUERY0000001", "01HCOLLABQUERY0000002"), brandA);

        List<String> brandB =
                collaborationRepository.findByWorkspaceIdAndCreatorId(BRAND_B, CREATOR).stream()
                        .map(Collaboration::getId)
                        .toList();
        assertEquals(List.of("01HCOLLABQUERY0000003"), brandB);

        assertTrue(
                collaborationRepository.findByWorkspaceIdAndCreatorId("01HWORKSPACENOCAMPAIGN", CREATOR).isEmpty(),
                "a workspace with no campaigns has no collaborations");
    }
}
