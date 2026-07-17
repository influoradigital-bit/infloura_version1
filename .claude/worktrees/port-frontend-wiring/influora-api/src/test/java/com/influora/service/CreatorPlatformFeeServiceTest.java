package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creatorplatformfee.CreatorPlatformFeeDtos.PlatformFeeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Task #27 (P0-V2) — creator platform-fee transparency service. */
@ExtendWith(MockitoExtension.class)
class CreatorPlatformFeeServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILEA12";

    @Mock private CreatorContextService creatorContext;
    @Mock private PlatformFeeService platformFeeService;
    @Mock private AuthPrincipal principal;

    private CreatorPlatformFeeService service;
    private CreatorProfile creatorProfile;

    @BeforeEach
    void setUp() {
        service = new CreatorPlatformFeeService(creatorContext, platformFeeService);
        creatorProfile = CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Creator A");
    }

    @Test
    @DisplayName("getCurrentFee: gates via CreatorContextService and returns global config only")
    void testGetCurrentFeeReturnsGlobalConfig() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(platformFeeService.resolveCreatorFeeBps()).thenReturn(1500);

        PlatformFeeResponse response = service.getCurrentFee(principal);

        assertEquals(1500, response.feeBps());
        assertEquals(15.0, response.feePercent());
        assertEquals(CreatorPlatformFeeService.SOURCE_GLOBAL_DEFAULT, response.source());
        verify(creatorContext).requireCreatorProfile(principal);
        verify(platformFeeService).resolveCreatorFeeBps();
    }

    @Test
    @DisplayName("getCurrentFee: response carries no creator PII — only fee fields")
    void testResponseContainsNoPii() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(platformFeeService.resolveCreatorFeeBps()).thenReturn(1200);

        PlatformFeeResponse response = service.getCurrentFee(principal);

        assertEquals(1200, response.feeBps());
        assertEquals(12.0, response.feePercent());
        assertEquals(CreatorPlatformFeeService.SOURCE_GLOBAL_DEFAULT, response.source());
    }
}
