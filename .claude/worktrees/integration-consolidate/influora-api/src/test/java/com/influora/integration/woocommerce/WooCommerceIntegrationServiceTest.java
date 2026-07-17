package com.influora.integration.woocommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.WooCommerceProperties;
import com.influora.domain.entity.WooCommerceIntegration;
import com.influora.repository.WooCommerceIntegrationRepository;
import com.influora.service.AuditLogService;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WooCommerceIntegrationService} -- mirrors {@code
 * ShopifyTokenStorageTest}'s structure. Covers encrypt/decrypt round-trip (the genuine
 * write-then-read test any new structured secret-at-rest requires, per the Wave C4 lesson every
 * other integration's test suite in this codebase applies), rotation-vs-fresh-insert branching,
 * revoked filtering, and audit-log verification. No real database -- mock the repository.
 */
@ExtendWith(MockitoExtension.class)
class WooCommerceIntegrationServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String SITE_URL = "https://my-test-store.example.com";
    private static final String INTEGRATION_ID = "01HWXYZWOOCOMMERCE1234";
    // Valid 32-byte (256-bit) AES key in base64
    private static final String TEST_ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Mock private WooCommerceIntegrationRepository repository;
    @Mock private AuditLogService auditLog;

    @Captor private ArgumentCaptor<WooCommerceIntegration> integrationCaptor;
    @Captor private ArgumentCaptor<Map<String, Object>> detailCaptor;

    private WooCommerceIntegrationService service;

    @BeforeEach
    void setUp() {
        WooCommerceProperties props = createTestProperties();
        service = new WooCommerceIntegrationService(repository, auditLog, props);
    }

    private WooCommerceProperties createTestProperties() {
        WooCommerceProperties props = new WooCommerceProperties();
        props.setTokenEncryptionKey(TEST_ENCRYPTION_KEY);
        return props;
    }

    @Test
    @DisplayName("connect: encrypts secret and saves to repository (fresh insert)")
    void testConnectEncryptsAndSaves() {
        String plainSecret = "wc_webhook_secret_12345";

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        service.connect(WORKSPACE_ID, SITE_URL, plainSecret);

        verify(repository).save(integrationCaptor.capture());
        WooCommerceIntegration saved = integrationCaptor.getValue();

        assertNotNull(saved.getId());
        assertEquals(WORKSPACE_ID, saved.getWorkspaceId());
        assertEquals(SITE_URL, saved.getSiteUrl());
        assertFalse(saved.getEncryptedWebhookSecret().equals(plainSecret));
        assertFalse(saved.isRevoked());
    }

    @Test
    @DisplayName("connect: records audit log with site url, never the secret")
    void testConnectRecordsAuditLog() {
        String plainSecret = "super-secret-value";

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        service.connect(WORKSPACE_ID, SITE_URL, plainSecret);

        verify(auditLog)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("WOOCOMMERCE_WEBHOOK_SECRET_STORED"),
                        eq("SENSITIVE"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        detailCaptor.capture());

        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals(SITE_URL, detail.get("siteUrl"));
        assertFalse(detail.containsValue(plainSecret));
    }

    @Test
    @DisplayName("connect: rotates existing connection instead of creating a duplicate row")
    void testConnectRotatesExisting() {
        String newSecret = "new-secret";

        WooCommerceIntegration existing =
                WooCommerceIntegration.builder()
                        .id(INTEGRATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .siteUrl(SITE_URL)
                        .encryptedWebhookSecret("old-encrypted")
                        .build();

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(existing));

        service.connect(WORKSPACE_ID, SITE_URL, newSecret);

        verify(repository).save(integrationCaptor.capture());
        WooCommerceIntegration saved = integrationCaptor.getValue();

        assertEquals(INTEGRATION_ID, saved.getId());
        assertFalse(saved.getEncryptedWebhookSecret().equals("old-encrypted"));
    }

    // ------------------------------------------------------------------------------------------
    // Genuine round-trip test [Wave C4 lesson applied]: feeds the REAL connect() write path into
    // the REAL decryptSecret() read path -- not two isolated unit tests each asserting against a
    // hand-built mocked shape.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "ROUND-TRIP: connect's real ciphertext survives a real decryptSecret decrypt back to the exact original plaintext")
    void testEncryptDecryptRoundTrip() {
        String plainSecret = "wc_my-secret-webhook-key-12345";

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(repository.save(any(WooCommerceIntegration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WooCommerceIntegration saved = service.connect(WORKSPACE_ID, SITE_URL, plainSecret);

        String decrypted = service.decryptSecret(saved);

        assertEquals(plainSecret, decrypted);
    }

    @Test
    @DisplayName("ROUND-TRIP: two different sites' secrets never collide to the same ciphertext or cross-decrypt")
    void testRoundTripDoesNotCrossContaminateDifferentSecrets() {
        String secretA = "secret-for-site-a";
        String secretB = "secret-for-site-b";
        String workspaceB = "01HWORKSPACEB12345678AB";
        String siteB = "https://other-store.example.com";

        when(repository.save(any(WooCommerceIntegration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());
        WooCommerceIntegration savedA = service.connect(WORKSPACE_ID, SITE_URL, secretA);

        when(repository.findByWorkspaceIdAndRevokedFalse(workspaceB)).thenReturn(Optional.empty());
        WooCommerceIntegration savedB = service.connect(workspaceB, siteB, secretB);

        assertFalse(savedA.getEncryptedWebhookSecret().equals(savedB.getEncryptedWebhookSecret()));
        assertEquals(secretA, service.decryptSecret(savedA));
        assertEquals(secretB, service.decryptSecret(savedB));
    }

    @Test
    @DisplayName("revoke: marks connection as revoked and records audit log")
    void testRevokeMarksConnectionAndLogsAudit() {
        WooCommerceIntegration existing =
                WooCommerceIntegration.builder()
                        .id(INTEGRATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .siteUrl(SITE_URL)
                        .encryptedWebhookSecret("encrypted")
                        .build();

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(existing));

        service.revoke(WORKSPACE_ID);

        verify(repository).save(integrationCaptor.capture());
        WooCommerceIntegration saved = integrationCaptor.getValue();
        assertTrue(saved.isRevoked());

        verify(auditLog)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("WOOCOMMERCE_WEBHOOK_SECRET_REVOKED"),
                        eq("SENSITIVE"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        detailCaptor.capture());

        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals(SITE_URL, detail.get("siteUrl"));
    }

    @Test
    @DisplayName("revoke: no-op if connection doesn't exist")
    void testRevokeNoOpForNonExistent() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        service.revoke(WORKSPACE_ID);

        verify(repository, never()).save(any());
        verify(auditLog, never()).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("constructor: validates encryption key is exactly 32 bytes")
    void testConstructorValidatesKeyLength() {
        WooCommerceProperties propsInvalidKey = createTestProperties();
        propsInvalidKey.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));

        assertThrows(
                IllegalStateException.class,
                () -> new WooCommerceIntegrationService(repository, auditLog, propsInvalidKey));
    }

    @Test
    @DisplayName("constructor: throws if encryption key is missing")
    void testConstructorThrowsIfKeyMissing() {
        WooCommerceProperties propsNoKey = createTestProperties();
        propsNoKey.setTokenEncryptionKey(null);

        assertThrows(
                IllegalStateException.class, () -> new WooCommerceIntegrationService(repository, auditLog, propsNoKey));
    }
}
