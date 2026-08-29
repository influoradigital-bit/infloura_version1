package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.R2Properties;
import com.influora.domain.entity.Workspace;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import com.influora.web.dto.onboarding.OnboardingDtos.KycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [F-0390 D4] Covers the new read-path key resolution ({@link OnboardingService#resolveKycDocUrl})
 * added when {@code POST /uploads} gained a KYC-aware {@code purpose} param — plus a happy-path
 * regression guard for {@code submitBrandKyc}, which previously had no dedicated test file.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE1234567AB";

    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandContextService brandContext;
    @Mock private WorkspaceSlugService slugService;
    @Mock private AnalyzeSiteTriggerService analyzeSiteTrigger;
    @Mock private R2StorageService r2StorageService;
    @Mock private R2Properties r2Properties;
    @Mock private AuthPrincipal principal;

    private OnboardingService service;

    @BeforeEach
    void setUp() {
        service =
                new OnboardingService(
                        userRepository,
                        workspaceRepository,
                        brandContext,
                        slugService,
                        analyzeSiteTrigger,
                        r2StorageService,
                        r2Properties);
    }

    private Workspace brandWorkspace() {
        return Workspace.newBrand(WORKSPACE_ID, "Acme Co", "acme-co", "Retail", "11-50");
    }

    @Test
    @DisplayName(
            "submitBrandKyc persists whatever gstinDocUrl/panDocUrl it's given verbatim — a bare R2"
                    + " key (post-D4 uploadForPurpose) or a legacy URL, this write path is agnostic")
    void testSubmitBrandKycPersistsGivenValuesVerbatim() {
        Workspace workspace = brandWorkspace();
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        KycRequest req =
                new KycRequest(
                        "27ABCDE1234F1Z5",
                        "ABCDE1234F",
                        "uploads/brand/u1/gstin-key.pdf", // bare key, not a URL
                        "uploads/brand/u1/pan-key.pdf");

        KycResponse response = service.submitBrandKyc(principal, req);

        assertEquals("PENDING", response.kycStatus());
        assertEquals("uploads/brand/u1/gstin-key.pdf", workspace.getKycGstinDocUrl());
        assertEquals("uploads/brand/u1/pan-key.pdf", workspace.getKycPanDocUrl());
        verify(workspaceRepository).save(workspace);
    }

    @Test
    @DisplayName("[F-0390 D4] resolveKycDocUrl: a bare R2 key (new, post-D4 upload) resolves to a presigned GET")
    void testResolveKycDocUrlPresignsBareKey() {
        when(r2StorageService.isAvailable()).thenReturn(true);
        when(r2StorageService.presignGet("uploads/brand/w1/gstin.pdf"))
                .thenReturn(
                        new R2StorageService.PresignResult(
                                "https://r2.influora.com/presigned/uploads/brand/w1/gstin.pdf",
                                "uploads/brand/w1/gstin.pdf",
                                "influora-dev",
                                Instant.now().plusSeconds(900),
                                0L));

        String resolved = service.resolveKycDocUrl("uploads/brand/w1/gstin.pdf");

        assertEquals("https://r2.influora.com/presigned/uploads/brand/w1/gstin.pdf", resolved);
    }

    @Test
    @DisplayName(
            "[F-0390 D4] resolveKycDocUrl: a legacy absolute public URL that does not match the"
                    + " configured R2 public base passes through unchanged")
    void testResolveKycDocUrlPassesThroughUnmatchedLegacyUrl() {
        when(r2Properties.getPublicUrl()).thenReturn("https://r2.influora.com");

        String resolved = service.resolveKycDocUrl("https://some-other-cdn.example.com/old-gstin.pdf");

        assertEquals("https://some-other-cdn.example.com/old-gstin.pdf", resolved);
        verify(r2StorageService, never()).presignGet(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName(
            "[F-0390 D4] resolveKycDocUrl: a legacy absolute URL matching the configured R2 public"
                    + " base is still resolved to a fresh presigned GET, not left permanently public")
    void testResolveKycDocUrlResolvesMatchingLegacyUrl() {
        when(r2Properties.getPublicUrl()).thenReturn("https://r2.influora.com");
        when(r2StorageService.isAvailable()).thenReturn(true);
        when(r2StorageService.presignGet("uploads/brand/w1/legacy-gstin.pdf"))
                .thenReturn(
                        new R2StorageService.PresignResult(
                                "https://r2.influora.com/presigned/uploads/brand/w1/legacy-gstin.pdf",
                                "uploads/brand/w1/legacy-gstin.pdf",
                                "influora-dev",
                                Instant.now().plusSeconds(900),
                                0L));

        String resolved =
                service.resolveKycDocUrl("https://r2.influora.com/uploads/brand/w1/legacy-gstin.pdf");

        assertEquals("https://r2.influora.com/presigned/uploads/brand/w1/legacy-gstin.pdf", resolved);
    }

    @Test
    @DisplayName(
            "[F-0390 D4] resolveKycDocUrl: R2 unavailable falls back to returning the stored value"
                    + " unchanged rather than throwing")
    void testResolveKycDocUrlFallsBackWhenR2Unavailable() {
        when(r2StorageService.isAvailable()).thenReturn(false);

        String resolved = service.resolveKycDocUrl("uploads/brand/w1/gstin.pdf");

        assertEquals("uploads/brand/w1/gstin.pdf", resolved);
        verify(r2StorageService, never()).presignGet(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("[F-0390 D4] resolveKycDocUrl: null/blank input returns null")
    void testResolveKycDocUrlNullForBlank() {
        assertEquals(null, service.resolveKycDocUrl(null));
        assertEquals(null, service.resolveKycDocUrl(""));
    }
}
