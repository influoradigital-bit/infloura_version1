package com.influora.integration.meta.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, plainToken, expiresAt, scopes, null);

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
    @DisplayName(
            "CR-110 regression: storeToken's INSERT branch (new row, not an update) persists"
                    + " igBusinessAccountId instead of silently dropping it")
    void testStoreTokenInsertBranchPersistsIgBusinessAccountId() {
        String plainToken = "plain-access-token-12345";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic");
        String igBusinessAccountId = "17841400000000000";

        // No existing row for this (workspaceId, creatorProfileId) pair — forces the INSERT branch,
        // the same branch MetaTokenRefreshService.refreshOne hits if its lookup ever misses a
        // creator-owned row (workspaceId == null) instead of landing on the UPDATE branch.
        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, plainToken, expiresAt, scopes, igBusinessAccountId);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();

        assertEquals(
                igBusinessAccountId,
                saved.getIgBusinessAccountId(),
                "INSERT branch must carry igBusinessAccountId through, same as storeCreatorToken does");
    }

    @Test
    @DisplayName("storeToken: records audit log with scope count, never the token")
    void testStoreTokenRecordsAuditLog() {
        String plainToken = "secret-token";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic", "instagram_manage_insights", "pages_show_list");

        when(repository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, plainToken, expiresAt, scopes, null);

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

        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, newToken, newExpiry, newScopes, null);

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
        storage.storeToken(CREATOR_PROFILE_ID, WORKSPACE_ID, plainToken, expiresAt, scopes, null);

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

    // ===========================================================================================
    // CR-113 coverage: storeCreatorToken (creator-owned tokens, workspaceId=null) had zero tests
    // ===========================================================================================

    @Test
    @DisplayName("storeCreatorToken: INSERT branch (no existing row) encrypts and persists new token")
    void testStoreCreatorTokenInsertBranchEncryptsAndSaves() {
        String plainToken = "creator-access-token-123";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic", "instagram_manage_insights");
        String igBusinessAccountId = "17841400000000099";

        // No existing creator-owned token for this creator
        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeCreatorToken(CREATOR_PROFILE_ID, plainToken, expiresAt, scopes, igBusinessAccountId);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();

        assertNotNull(saved.getId());
        assertEquals(CREATOR_PROFILE_ID, saved.getCreatorProfileId());
        // workspaceId should be null (creator-owned row)
        assertEquals(null, saved.getWorkspaceId());
        // CR-110 pairing: igBusinessAccountId must be persisted
        assertEquals(igBusinessAccountId, saved.getIgBusinessAccountId());
        // Token should be encrypted (not plaintext)
        assertFalse(saved.getEncryptedAccessToken().equals(plainToken));
        assertEquals(expiresAt, saved.getExpiresAt());
        assertTrue(saved.getGrantedScopesJson().contains("instagram_basic"));
    }

    @Test
    @DisplayName(
            "storeCreatorToken: UPDATE branch (existing row) revokes old token then inserts new one")
    void testStoreCreatorTokenUpdateBranchRevokesAndReinserts() {
        String newToken = "new-creator-token";
        Instant newExpiry = Instant.now().plus(Duration.ofDays(60));
        List<String> newScopes = List.of("instagram_basic");
        String igBusinessAccountId = "17841400000000088";

        MetaOAuthToken existingToken = MetaOAuthToken.builder()
                .id("01EXISTING_CREATOR_TOKEN")
                .creatorProfileId(CREATOR_PROFILE_ID)
                .workspaceId(null) // Creator-owned
                .igBusinessAccountId("17841400000000077") // Old IG account
                .encryptedAccessToken("old-encrypted-token")
                .expiresAt(Instant.now().plus(Duration.ofDays(1)))
                .grantedScopesJson("[\"instagram_basic\"]")
                .lastRefreshedAt(Instant.now().minus(Duration.ofDays(30)))
                .build();

        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(existingToken));

        storage.storeCreatorToken(CREATOR_PROFILE_ID, newToken, newExpiry, newScopes, igBusinessAccountId);

        // Verify two saves: one to revoke old, one to insert new
        verify(repository, times(2)).save(tokenCaptor.capture());
        List<MetaOAuthToken> savedTokens = tokenCaptor.getAllValues();

        // First save: revoked old token
        MetaOAuthToken revoked = savedTokens.get(0);
        assertEquals("01EXISTING_CREATOR_TOKEN", revoked.getId());
        assertTrue(revoked.isRevoked());

        // Second save: new token inserted
        MetaOAuthToken newRow = savedTokens.get(1);
        assertFalse(newRow.getId().equals("01EXISTING_CREATOR_TOKEN")); // New ID
        assertEquals(CREATOR_PROFILE_ID, newRow.getCreatorProfileId());
        assertEquals(null, newRow.getWorkspaceId());
        assertEquals(igBusinessAccountId, newRow.getIgBusinessAccountId());
        assertEquals(newExpiry, newRow.getExpiresAt());
    }

    @Test
    @DisplayName(
            "storeCreatorToken: F-0173 regression — a refresh (existing row revoked + reinserted)"
                    + " preserves the ORIGINAL row's createdAt on the new row, rather than stamping a"
                    + " fresh 'now' that would silently advance the creator's 'connected since' date"
                    + " every ~55-day auto-refresh cycle")
    void testStoreCreatorTokenPreservesOriginalCreatedAtAcrossRefresh() {
        Instant originalConnectedAt = Instant.now().minus(Duration.ofDays(120));
        MetaOAuthToken existingToken =
                MetaOAuthToken.builder()
                        .id("01EXISTING_CREATOR_TOKEN")
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .workspaceId(null)
                        .igBusinessAccountId("17841400000000077")
                        .encryptedAccessToken("old-encrypted-token")
                        .expiresAt(Instant.now().plus(Duration.ofDays(1)))
                        .grantedScopesJson("[\"instagram_basic\"]")
                        .lastRefreshedAt(Instant.now().minus(Duration.ofDays(30)))
                        // build() normally stamps "now" — override to simulate a row that was
                        // actually created 120 days ago, the case this fix protects.
                        .createdAt(originalConnectedAt)
                        .build();
        assertEquals(originalConnectedAt, existingToken.getCreatedAt()); // sanity: override took

        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(existingToken));

        storage.storeCreatorToken(
                CREATOR_PROFILE_ID,
                "refreshed-token",
                Instant.now().plus(Duration.ofDays(60)),
                List.of("instagram_basic"),
                "17841400000000088");

        verify(repository, times(2)).save(tokenCaptor.capture());
        MetaOAuthToken newRow = tokenCaptor.getAllValues().get(1);

        assertFalse(newRow.getId().equals("01EXISTING_CREATOR_TOKEN")); // still a genuinely new row
        assertEquals(
                originalConnectedAt,
                newRow.getCreatedAt(),
                "the new row's createdAt must be the ORIGINAL row's createdAt, not a fresh 'now'");
    }

    @Test
    @DisplayName(
            "storeCreatorToken: F-0173 — first-time connect (no existing row) still stamps a real,"
                    + " current createdAt — the preservation logic must not leak into first connects")
    void testStoreCreatorTokenFirstConnectStampsFreshCreatedAt() {
        Instant before = Instant.now();
        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeCreatorToken(
                CREATOR_PROFILE_ID,
                "first-connect-token",
                Instant.now().plus(Duration.ofDays(60)),
                List.of("instagram_basic"),
                "17841400000000099");
        Instant after = Instant.now();

        verify(repository).save(tokenCaptor.capture());
        Instant createdAt = tokenCaptor.getValue().getCreatedAt();
        assertNotNull(createdAt);
        assertFalse(createdAt.isBefore(before));
        assertFalse(createdAt.isAfter(after));
    }

    @Test
    @DisplayName(
            "storeCreatorToken: CR-110 regression — igBusinessAccountId is persisted, not dropped")
    void testStoreCreatorTokenPersistsIgBusinessAccountId() {
        String plainToken = "token-with-ig-id";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic");
        String igBusinessAccountId = "17841400000000123";

        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeCreatorToken(CREATOR_PROFILE_ID, plainToken, expiresAt, scopes, igBusinessAccountId);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();

        assertEquals(
                igBusinessAccountId,
                saved.getIgBusinessAccountId(),
                "CR-110: storeCreatorToken must persist igBusinessAccountId so jobs can use the correct numeric ID");
    }

    @Test
    @DisplayName("storeCreatorToken: records audit log with scope count, never the token")
    void testStoreCreatorTokenRecordsAuditLog() {
        String plainToken = "secret-creator-token";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic", "instagram_manage_insights");

        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeCreatorToken(CREATOR_PROFILE_ID, plainToken, expiresAt, scopes, null);

        verify(auditLog).recordToolCall(
                eq(null), // workspaceId is null for creator-owned tokens
                eq("META_OAUTH_TOKEN_STORED"),
                eq("SENSITIVE"),
                eq(AuditLogService.OUTCOME_ALLOWED),
                eq(null),
                eq(null),
                eq(null),
                detailCaptor.capture());

        Map<String, Object> detail = detailCaptor.getValue();
        assertEquals(CREATOR_PROFILE_ID, detail.get("creatorProfileId"));
        assertEquals(2, detail.get("scopeCount"));
        // Verify token itself is NOT in the detail map
        assertFalse(detail.containsValue("secret-creator-token"));
    }

    @Test
    @DisplayName(
            "storeCreatorToken + getValidCreatorToken: encrypt/decrypt round-trip produces original"
                    + " plaintext")
    void testStoreCreatorTokenEncryptDecryptRoundTrip() {
        String plainToken = "my-creator-secret-token";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic");

        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        // Store (encrypts)
        storage.storeCreatorToken(CREATOR_PROFILE_ID, plainToken, expiresAt, scopes, null);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();

        // Mock retrieval
        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(saved));

        // Retrieve (decrypts)
        Optional<String> decrypted = storage.getValidCreatorToken(CREATOR_PROFILE_ID);

        assertTrue(decrypted.isPresent());
        assertEquals(plainToken, decrypted.get());
    }

    @Test
    @DisplayName(
            "storeCreatorToken: null igBusinessAccountId is allowed (token exists but IG account not"
                    + " yet linked)")
    void testStoreCreatorTokenAllowsNullIgBusinessAccountId() {
        String plainToken = "token-without-ig-link";
        Instant expiresAt = Instant.now().plus(Duration.ofDays(60));
        List<String> scopes = List.of("instagram_basic");

        when(repository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        storage.storeCreatorToken(CREATOR_PROFILE_ID, plainToken, expiresAt, scopes, null);

        verify(repository).save(tokenCaptor.capture());
        MetaOAuthToken saved = tokenCaptor.getValue();

        assertEquals(null, saved.getIgBusinessAccountId());
        // Token should still be saved and encrypted
        assertNotNull(saved.getId());
        assertFalse(saved.getEncryptedAccessToken().equals(plainToken));
    }
}
