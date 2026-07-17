package com.influora.integration.meta.oauth;

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

import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import java.time.Duration;
import java.time.Instant;
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
 * Unit tests for MetaTokenStorage (KAVYA_QA_TEST_PLAN §2.2, token encryption/storage).
 * Covers encrypt/decrypt round-trip, expiry filtering, findTokensExpiringSoon, audit-log verification.
 * No real database — mock the repository.
 */
@ExtendWith(MockitoExtension.class)
class MetaTokenStorageTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String CREATOR_PROFILE_ID = "01HWXYZCREATOR12345678";
    private static final String TOKEN_ID = "01HWXYZTOKEN1234567890";
    // Valid 32-byte (256-bit) AES key in base64
    private static final String TEST_ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Mock private MetaOAuthTokenRepository repository;
    @Mock private AuditLogService auditLog;

    @Captor private ArgumentCaptor<MetaOAuthToken> tokenCaptor;
    @Captor private ArgumentCaptor<Map<String, Object>> detailCaptor;

    private MetaTokenStorage storage;

    @BeforeEach
    void setUp() {
        MetaApiProperties props = createTestProperties();
        storage = new MetaTokenStorage(repository, auditLog, props);
    }

    private MetaApiProperties createTestProperties() {
        MetaApiProperties props = new MetaApiProperties();
        props.setAppId("app-id");
        props.setAppSecret("app-secret");
        props.setRedirectUri("redirect-uri");
        props.setGraphApiVersion("v25.0");
        props.setTokenRefreshDaysBeforeExpiry(7);
        props.setRateLimitAlertThreshold(80);
        props.setRateLimitThrottleThreshold(90);
        props.setTokenEncryptionKey(TEST_ENCRYPTION_KEY);
        return props;
    }

    @Test
    @DisplayName("storeToken: encrypts token and saves to repository")
    void testStoreTokenEncryptsAndSaves() {
        String plainToken = "plain-access-token-12345";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic", "instagram_manage_insights");

        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, plainToken, expiresAt, scopes);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();

        assertNotNull(saved.getId());
        assertEquals(WORKSPACE_ID, saved.getWorkspaceId());
        assertEquals(CREATOR_PROFILE_ID, saved.getCreatorProfileId());
        // Encrypted token should not equal plaintext
        assertFalse(saved.getEncryptedAccessToken().equals(plainToken));
        assertEquals(expiresAt, saved.getExpiresAt());
        assertTrue(saved.getGrantedScopesJson().contains("instagram_basic"));
    }

    @Test
    @DisplayName("storeToken: records audit log with scope count, never the token")
    void testStoreTokenRecordsAuditLog() {
        String plainToken = "secret-token";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic", "instagram_manage_insights", "pages_show_list");

        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, plainToken, expiresAt, scopes);

        verify(auditLog).recordToolCall(
                eq(WORKSPACE_ID),
                eq("META_OAUTH_TOKEN_STORED"),
                eq("SENSITIVE"),
                eq(AuditLogService.OUTCOME_ALLOWED),
                eq(null),
                eq(null),
                eq(null),
                detailCaptor.capture());

        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals(CREATOR_PROFILE_ID, detail.get("creatorProfileId"));
        assertEquals(3, detail.get("scopeCount"));
        // Verify token itself is NOT in the detail map
        assertFalse(detail.containsValue("secret-token"));
    }

    @Test
    @DisplayName("storeToken: rotates existing token instead of creating duplicate")
    void testStoreTokenRotatesExisting() {
        String newToken = "new-token";
        Instant newExpiry = Instant.now().plus(Duration.ofDays(60));
        List<String> newScopes = List.of("instagram_basic");

        MetaOAuthToken existing = MetaOAuthToken.builder()
                .id(TOKEN_ID)
                .workspaceId(WORKSPACE_ID)
                .creatorProfileId(CREATOR_PROFILE_ID)
                .encryptedAccessToken("old-encrypted")
                .expiresAt(Instant.now().plus(Duration.ofDays(1)))
                .grantedScopesJson("[\"instagram_basic\"]")
                .lastRefreshedAt(Instant.now().minus(Duration.ofDays(30)))
                .build();

        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(existing));

        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, newToken, newExpiry, newScopes);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();

        // Should be same entity, not a new one
        assertEquals(TOKEN_ID, saved.getId());
        // Token should be rotated
        assertFalse(saved.getEncryptedAccessToken().equals("old-encrypted"));
        assertEquals(newExpiry, saved.getExpiresAt());
    }

    @Test
    @DisplayName("encrypt/decrypt: round-trip produces original plaintext")
    void testEncryptDecryptRoundTrip() {
        String plainToken = "my-secret-token-12345";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic");

        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        // Store (encrypts)
        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, plainToken, expiresAt, scopes);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();

        // Mock retrieval
        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(saved));

        // Retrieve (decrypts)
        Optional<String> decrypted = storage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID);

        assertTrue(decrypted.isPresent());
        assertEquals(plainToken, decrypted.get());
    }

    @Test
    @DisplayName("getValidToken: returns empty for expired token")
    void testGetValidTokenReturnsEmptyForExpired() {
        MetaOAuthToken expired = MetaOAuthToken.builder()
                .id(TOKEN_ID)
                .workspaceId(WORKSPACE_ID)
                .creatorProfileId(CREATOR_PROFILE_ID)
                .encryptedAccessToken("encrypted")
                .expiresAt(Instant.now().minus(Duration.ofDays(1))) // Expired yesterday
                .grantedScopesJson("[]")
                .lastRefreshedAt(Instant.now())
                .build();

        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(expired));

        Optional<String> result = storage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("getValidToken: returns empty for revoked token")
    void testGetValidTokenReturnsEmptyForRevoked() {
        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty()); // Repo doesn't return revoked tokens

        Optional<String> result = storage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("getValidToken: returns empty for non-existent token")
    void testGetValidTokenReturnsEmptyForNonExistent() {
        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        Optional<String> result = storage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("findTokensExpiringSoon: returns tokens within threshold")
    void testFindTokensExpiringSoon() {
        Instant soon = Instant.now().plus(Duration.ofDays(5));
        List<MetaOAuthToken> mockTokens = List.of(
                MetaOAuthToken.builder()
                        .id("token1")
                        .workspaceId(WORKSPACE_ID)
                        .creatorProfileId("creator1")
                        .encryptedAccessToken("enc1")
                        .expiresAt(soon)
                        .grantedScopesJson("[]")
                        .lastRefreshedAt(Instant.now())
                        .build());

        when(repository.findByExpiresAtBeforeAndRevokedFalse(any(Instant.class))).thenReturn(mockTokens);

        List<MetaOAuthToken> result = storage.findTokensExpiringSoon(7);

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(repository).findByExpiresAtBeforeAndRevokedFalse(any(Instant.class));
    }

    @Test
    @DisplayName("revoke: marks token as revoked and records audit log")
    void testRevokeMarksTokenAndLogsAudit() {
        MetaOAuthToken existing = MetaOAuthToken.builder()
                .id(TOKEN_ID)
                .workspaceId(WORKSPACE_ID)
                .creatorProfileId(CREATOR_PROFILE_ID)
                .encryptedAccessToken("encrypted")
                .expiresAt(Instant.now().plus(Duration.ofDays(30)))
                .grantedScopesJson("[]")
                .lastRefreshedAt(Instant.now())
                .build();

        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(existing));

        storage.revoke(WORKSPACE_ID, CREATOR_PROFILE_ID);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();
        assertTrue(saved.isRevoked());

        verify(auditLog).recordToolCall(
                eq(WORKSPACE_ID),
                eq("META_OAUTH_TOKEN_REVOKED"),
                eq("SENSITIVE"),
                eq(AuditLogService.OUTCOME_ALLOWED),
                eq(null),
                eq(null),
                eq(null),
                detailCaptor.capture());

        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals(CREATOR_PROFILE_ID, detail.get("creatorProfileId"));
    }

    @Test
    @DisplayName("revoke: no-op if token doesn't exist")
    void testRevokeNoOpForNonExistent() {
        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.revoke(WORKSPACE_ID, CREATOR_PROFILE_ID);

        verify(repository, never()).save(any());
        verify(auditLog, never()).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("constructor: validates encryption key is exactly 32 bytes")
    void testConstructorValidatesKeyLength() {
        MetaApiProperties propsInvalidKey = createTestProperties();
        propsInvalidKey.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[16])); // Only 16 bytes (128-bit)

        assertThrows(IllegalStateException.class, () -> new MetaTokenStorage(repository, auditLog, propsInvalidKey));
    }

    @Test
    @DisplayName("constructor: throws if encryption key is missing")
    void testConstructorThrowsIfKeyMissing() {
        MetaApiProperties propsNoKey = createTestProperties();
        propsNoKey.setTokenEncryptionKey(null);

        assertThrows(IllegalStateException.class, () -> new MetaTokenStorage(repository, auditLog, propsNoKey));
    }
}
