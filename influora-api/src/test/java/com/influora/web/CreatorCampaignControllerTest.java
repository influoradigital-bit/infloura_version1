package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.common.PageMeta;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorCampaignService;
import com.influora.service.CreatorCampaignService.PagedCreatorCampaigns;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyRequest;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.BudgetDto;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignDetailResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignListItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Task #7 — pure delegation tests, same shape as {@code MeCreatorProfileControllerTest}. */
@ExtendWith(MockitoExtension.class)
class CreatorCampaignControllerTest {

    @Mock private CreatorCampaignService creatorCampaignService;
    @Mock private AuthPrincipal principal;

    private CreatorCampaignController controller;

    @BeforeEach
    void setUp() {
        controller = new CreatorCampaignController(creatorCampaignService);
    }

    @Test
    @DisplayName("GET /creator/campaigns delegates to service and forwards paging params")
    void testBrowse() {
        CreatorCampaignListItem item =
                new CreatorCampaignListItem(
                        "camp1",
                        "Summer Fitness",
                        "desc",
                        null,
                        new BudgetDto(java.math.BigDecimal.TEN, java.math.BigDecimal.valueOf(100), "INR"),
                        List.of("INSTAGRAM"),
                        List.of(),
                        null,
                        null,
                        null,
                        10,
                        null,
                        Instant.now());
        PagedCreatorCampaigns paged =
                new PagedCreatorCampaigns(List.of(item), new PageMeta(1, 20, 1, false));
        when(creatorCampaignService.browse(principal, "fitness", null, null, "INSTAGRAM", 1, 20))
                .thenReturn(paged);

        ResponseEntity<ApiResponse<List<CreatorCampaignListItem>>> response =
                controller.browse(principal, "fitness", null, null, "INSTAGRAM", 1, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().data().size());
        assertEquals("camp1", response.getBody().data().get(0).id());
        verify(creatorCampaignService).browse(principal, "fitness", null, null, "INSTAGRAM", 1, 20);
    }

    @Test
    @DisplayName("GET /creator/campaigns/{id} delegates to service")
    void testGet() {
        CreatorCampaignDetailResponse detail =
                new CreatorCampaignDetailResponse(
                        "camp1",
                        "Summer Fitness",
                        "desc",
                        null,
                        List.of(),
                        null,
                        List.of("INSTAGRAM"),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        10,
                        null,
                        Instant.now());
        when(creatorCampaignService.getDetail(principal, "camp1")).thenReturn(detail);

        ResponseEntity<ApiResponse<CreatorCampaignDetailResponse>> response =
                controller.get(principal, "camp1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("camp1", response.getBody().data().id());
        verify(creatorCampaignService).getDetail(principal, "camp1");
    }

    @Test
    @DisplayName("POST /creator/campaigns/{id}/apply delegates to service and returns 201")
    void testApply() {
        ApplyRequest body = new ApplyRequest("Pick me!");
        ApplyResponse applyResponse = new ApplyResponse("collab1", "APPLIED", Instant.now());
        when(creatorCampaignService.apply(principal, "camp1", body)).thenReturn(applyResponse);

        ResponseEntity<ApiResponse<ApplyResponse>> response = controller.apply(principal, "camp1", body);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("collab1", response.getBody().data().collaborationId());
        assertEquals("APPLIED", response.getBody().data().status());
        verify(creatorCampaignService).apply(principal, "camp1", body);
    }
}
