package com.influora.integration.shopify.oauth;

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

import com.influora.config.ShopifyProperties;
import com.influora.domain.entity.ShopifyIntegration;
import com.influora.repository.ShopifyIntegrationRepository;
import com.influora.service.AuditLogService;
import java.util.Base64;
import java.util.List;
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
 * Unit tests for {@link ShopifyTokenStorage} — mirrors {@code MetaTokenStorageTest}'s structure.
 * Covers encrypt/decrypt round-trip (the genuine write-then-read test this task's brief requires
 * for any new structured data one part of the feature writes and another reads back — same lesson
 * Wave C4's retrospective flagged), rotation-vs-fresh-insert branching, revoked filtering, and
 * audit-log verification. No real database — mock the repository.
 */
@ExtendWith(MockitoExtension.class)
class ShopifyTokenStorageTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String SHOP_DOMAIN = "my-test-store.myshopify.com";
    private static final String INTEGRATION_ID = "01HWXYZSHOPIFY12345678";
    // Valid 32-byte (256-bit) AES key in base64
    private static final String TEST_ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Mock private ShopifyIntegrationRepository repository;
    @Mock private AuditLogService auditLog;

    @Captor private ArgumentCaptor<ShopifyIntegration> integrationCaptor;
    @Captor private ArgumentCaptor<Map<String, Object>> detailCaptor;

    private ShopifyTokenStorage storage;

    @BeforeEach
    void setUp() {
        ShopifyProperties props = createTestProperties();
        storage = new ShopifyTokenStorage(repository, auditLog, props);
    }

    private ShopifyProperties createTestProperties() {
        ShopifyProperties props = new ShopifyProperties();
        props.setApiKey("api-key");
        props.setApiSecret("api-secret");
        props.setRedirectUri("redirect-uri");
        props.setTokenEncryptionKey(TEST_ENCRYPTION_KEY);
        return props;
    }

    @Test
    @DisplayName("storeToken: encrypts token and saves to repository (fresh insert)")
    void testStoreTokenEncryptsAndSaves() {
        String plainToken = "shpat_plain-access-token-12345";
        List<String> scopes = List.of("read_orders", "read_products");

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        storage.storeToken(WORKSPACE_ID, SHOP_DOMAIN, plainToken, scopes);

        verify(repository).save(integrationCaptor.capture());
        ShopifyIntegration saved = integrationCaptor.getValue();

        assertNotNull(saved.getId());
        assertEquals(WORKSPACE_ID, saved.getWorkspaceId());
        assertEquals(SHOP_DOMAIN, saved.getShopDomain());
        // Encrypted token should not equal plaintext
        assertFalse(saved.getEncryptedAccessToken().equals(plainToken));
        assertTrue(saved.getGrantedScopesJson().contains("read_orders"));
        assertFalse(saved.isRevoked());
    }

    @Test
    @DisplayName("storeToken: records audit log with shop domain and scope count, never the token")
    void testStoreTokenRecordsAuditLog() {
        String plainToken = "shpat_secret-token";
        List<String> scopes = List.of("read_orders", "read_products", "write_orders");

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        storage.storeToken(WORKSPACE_ID, SHOP_DOMAIN, plainToken, scopes);

        verify(auditLog)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("SHOPIFY_OAUTH_TOKEN_STORED"),
                        eq("SENSITIVE"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        detailCaptor.capture());

        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals(SHOP_DOMAIN, detail.get("shopDomain"));
        assertEquals(3, detail.get("scopeCount"));
        assertFalse(detail.containsValue(plainToken));
    }

    @Test
    @DisplayName("storeToken: rotates existing connection instead of creating a duplicate row")
    void testStoreTokenRotatesExisting() {
        String newToken = "shpat_new-token";
        List<String> newScopes = List.of("read_orders");

        ShopifyIntegration existing =
                ShopifyIntegration.builder()
                        .id(INTEGRATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .shopDomain(SHOP_DOMAIN)
                        .encryptedAccessToken("old-encrypted")
                        .grantedScopesJson("[\"read_orders\"]")
                        .build();

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(existing));

        storage.storeToken(WORKSPACE_ID, SHOP_DOMAIN, newToken, newScopes);

        verify(repository).save(integrationCaptor.capture());
        ShopifyIntegration saved = integrationCaptor.getValue();

        // Should be same entity, not a new one
        assertEquals(INTEGRATION_ID, saved.getId());
        assertFalse(saved.getEncryptedAccessToken().equals("old-encrypted"));
    }

    // ------------------------------------------------------------------------------------------
    // Genuine round-trip test [task brief requirement, Wave C4 lesson applied]: feeds the REAL
    // storeToken() write path into the REAL getValidToken() read path -- not two isolated unit
    // tests that each assert against a hand-built mocked shape, but one test proving the actual
    // ciphertext this class produces is the actual ciphertext this class can decrypt back to the
    // original plaintext, through the same ShopifyIntegration entity instance the repository would
    // persist and return.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "ROUND-TRIP: storeToken's real ciphertext survives a real getValidToken decrypt back to the exact original plaintext")
    void testEncryptDecryptRoundTrip() {
        String plainToken = "shpat_my-secret-token-12345";
        List<String> scopes = List.of("read_orders");

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        // WRITE side: real storeToken() call, real AES-256-GCM encryption.
        storage.storeToken(WORKSPACE_ID, SHOP_DOMAIN, plainToken, scopes);

        verify(repository).save(integrationCaptor.capture());
        ShopifyIntegration saved = integrationCaptor.getValue();

        // The row the repository would actually return on a subsequent read — feeding the WRITE
        // side's real output into the READ side's real input, not a fabricated ciphertext string.
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(saved));

        // READ side: real getValidToken() call, real AES-256-GCM decryption of the exact ciphertext
        // storeToken produced above.
        Optional<String> decrypted = storage.getValidToken(WORKSPACE_ID);

        assertTrue(decrypted.isPresent());
        assertEquals(plainToken, decrypted.get());
    }

    @Test
    @DisplayName("ROUND-TRIP: two different plaintexts never collide to the same ciphertext or cross-decrypt")
    void testRoundTripDoesNotCrossContaminateDifferentTokens() {
        String tokenA = "shpat_token-for-shop-a";
        String tokenB = "shpat_token-for-shop-b";
        String workspaceB = "01HWORKSPACEB12345678AB";
        String shopB = "other-store.myshopify.com";

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());
        storage.storeToken(WORKSPACE_ID, SHOP_DOMAIN, tokenA, List.of("read_orders"));
        verify(repository).save(integrationCaptor.capture());
        ShopifyIntegration savedA = integrationCaptor.getValue();

        when(repository.findByWorkspaceIdAndRevokedFalse(workspaceB)).thenReturn(Optional.empty());
        storage.storeToken(workspaceB, shopB, tokenB, List.of("read_orders"));
        verify(repository, org.mockito.Mockito.times(2)).save(integrationCaptor.capture());
        ShopifyIntegration savedB = integrationCaptor.getValue();

        assertFalse(savedA.getEncryptedAccessToken().equals(savedB.getEncryptedAccessToken()));

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(savedA));
        when(repository.findByWorkspaceIdAndRevokedFalse(workspaceB)).thenReturn(Optional.of(savedB));

        assertEquals(tokenA, storage.getValidToken(WORKSPACE_ID).orElseThrow());
        assertEquals(tokenB, storage.getValidToken(workspaceB).orElseThrow());
    }

    @Test
    @DisplayName("getValidToken: returns empty for revoked/non-existent connection")
    void testGetValidTokenReturnsEmptyForRevokedOrMissing() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        Optional<String> result = storage.getValidToken(WORKSPACE_ID);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("revoke: marks connection as revoked and records audit log")
    void testRevokeMarksConnectionAndLogsAudit() {
        ShopifyIntegration existing =
                ShopifyIntegration.builder()
                        .id(INTEGRATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .shopDomain(SHOP_DOMAIN)
                        .encryptedAccessToken("encrypted")
                        .grantedScopesJson("[]")
                        .build();

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(existing));

        storage.revoke(WORKSPACE_ID);

        verify(repository).save(integrationCaptor.capture());
        ShopifyIntegration saved = integrationCaptor.getValue();
        assertTrue(saved.isRevoked());

        verify(auditLog)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("SHOPIFY_OAUTH_TOKEN_REVOKED"),
                        eq("SENSITIVE"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        detailCaptor.capture());

        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals(SHOP_DOMAIN, detail.get("shopDomain"));
    }

    @Test
    @DisplayName("revoke: no-op if connection doesn't exist")
    void testRevokeNoOpForNonExistent() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        storage.revoke(WORKSPACE_ID);

        verify(repository, never()).save(any());
        verify(auditLog, never()).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("constructor: validates encryption key is exactly 32 bytes")
    void testConstructorValidatesKeyLength() {
        ShopifyProperties propsInvalidKey = createTestProperties();
        propsInvalidKey.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));

        assertThrows(IllegalStateException.class, () -> new ShopifyTokenStorage(repository, auditLog, propsInvalidKey));
    }

    @Test
    @DisplayName("constructor: throws if encryption key is missing")
    void testConstructorThrowsIfKeyMissing() {
        ShopifyProperties propsNoKey = createTestProperties();
        propsNoKey.setTokenEncryptionKey(null);

        assertThrows(IllegalStateException.class, () -> new ShopifyTokenStorage(repository, auditLog, propsNoKey));
    }
}
