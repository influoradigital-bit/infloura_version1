package com.influora.integration.tracking.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.ConversionWebhookProperties;
import com.influora.domain.entity.ConversionWebhookSecret;
import com.influora.repository.ConversionWebhookSecretRepository;
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
 * [SEC: Kabir Wave E4 capstone red-team, HIGH -- re-escalated finding, {@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1] Unit tests for {@link
 * ConversionWebhookSecretService} -- mirrors {@code WooCommerceIntegrationServiceTest}'s structure
 * (encrypt/decrypt round-trip, rotation-vs-fresh-insert branching, revoked filtering, audit-log
 * verification). No real database -- mock the repository.
 */
@ExtendWith(MockitoExtension.class)
class ConversionWebhookSecretServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String SECRET_ROW_ID = "01HWXYZCONVWEBHOOKSEC1";
    // Valid 32-byte (256-bit) AES key in base64
    private static final String TEST_ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Mock private ConversionWebhookSecretRepository repository;
    @Mock private AuditLogService auditLog;

    @Captor private ArgumentCaptor<ConversionWebhookSecret> secretCaptor;
    @Captor private ArgumentCaptor<Map<String, Object>> detailCaptor;

    private ConversionWebhookSecretService service;

    @BeforeEach
    void setUp() {
        service = new ConversionWebhookSecretService(repository, auditLog, createTestProperties());
    }

    private ConversionWebhookProperties createTestProperties() {
        ConversionWebhookProperties props = new ConversionWebhookProperties();
        props.setTokenEncryptionKey(TEST_ENCRYPTION_KEY);
        return props;
    }

    @Test
    @DisplayName("generate: creates a fresh row, encrypts the generated secret, and returns the PLAINTEXT to the caller")
    void generate_freshInsert_returnsPlaintext() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        String plaintext = service.generate(WORKSPACE_ID);

        assertNotNull(plaintext);
        assertFalse(plaintext.isBlank());

        verify(repository).save(secretCaptor.capture());
        ConversionWebhookSecret saved = secretCaptor.getValue();
        assertNotNull(saved.getId());
        assertEquals(WORKSPACE_ID, saved.getWorkspaceId());
        assertFalse(saved.isRevoked());
        // The persisted value is ciphertext, never the plaintext this method returned.
        assertNotEquals(plaintext, saved.getEncryptedSecret());
    }

    @Test
    @DisplayName("generate: two calls produce two DIFFERENT random secrets (not a fixed/deterministic value)")
    void generate_producesDifferentSecretsEachCall() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        String first = service.generate(WORKSPACE_ID);
        String second = service.generate(WORKSPACE_ID);

        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("generate: rotates an existing secret in place instead of creating a duplicate row")
    void generate_rotatesExisting() {
        ConversionWebhookSecret existing =
                ConversionWebhookSecret.builder()
                        .id(SECRET_ROW_ID)
                        .workspaceId(WORKSPACE_ID)
                        .encryptedSecret("old-encrypted")
                        .build();
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(existing));

        service.generate(WORKSPACE_ID);

        verify(repository).save(secretCaptor.capture());
        ConversionWebhookSecret saved = secretCaptor.getValue();
        assertEquals(SECRET_ROW_ID, saved.getId());
        assertFalse(saved.getEncryptedSecret().equals("old-encrypted"));
        assertFalse(saved.isRevoked());
    }

    @Test
    @DisplayName("generate: records audit log with no secret value in the detail map")
    void generate_recordsAuditLogWithNoSecretLeak() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        String plaintext = service.generate(WORKSPACE_ID);

        verify(auditLog)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("CONVERSION_WEBHOOK_SECRET_GENERATED"),
                        eq("SENSITIVE"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        detailCaptor.capture());
        assertFalse(detailCaptor.getValue().containsValue(plaintext));
    }

    // ------------------------------------------------------------------------------------------
    // Genuine round-trip test [Wave C4 lesson applied]: feeds the REAL generate() write path into
    // the REAL decryptSecretForWorkspace() read path -- not two isolated unit tests each asserting
    // against a hand-built mocked shape.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "ROUND-TRIP: generate's real ciphertext survives a real decryptSecretForWorkspace decrypt back to the exact original plaintext")
    void roundTrip_generateThenDecrypt_returnsExactOriginalPlaintext() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.of(secretCaptor.getValue()));
        when(repository.save(any(ConversionWebhookSecret.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String plaintext = service.generate(WORKSPACE_ID);
        verify(repository).save(secretCaptor.capture());

        String decrypted = service.decryptSecretForWorkspace(WORKSPACE_ID);

        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("ROUND-TRIP: two different workspaces' secrets never collide to the same ciphertext or cross-decrypt")
    void roundTrip_doesNotCrossContaminateDifferentWorkspaces() {
        String workspaceB = "01HWORKSPACEB12345678AB";

        when(repository.save(any(ConversionWebhookSecret.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());
        String plaintextA = service.generate(WORKSPACE_ID);
        ArgumentCaptor<ConversionWebhookSecret> captorA = ArgumentCaptor.forClass(ConversionWebhookSecret.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captorA.capture());
        ConversionWebhookSecret savedA = captorA.getValue();

        when(repository.findByWorkspaceIdAndRevokedFalse(workspaceB)).thenReturn(Optional.empty());
        String plaintextB = service.generate(workspaceB);
        ArgumentCaptor<ConversionWebhookSecret> captorB = ArgumentCaptor.forClass(ConversionWebhookSecret.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captorB.capture());
        ConversionWebhookSecret savedB = captorB.getValue();

        assertNotEquals(savedA.getEncryptedSecret(), savedB.getEncryptedSecret());

        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(savedA));
        when(repository.findByWorkspaceIdAndRevokedFalse(workspaceB)).thenReturn(Optional.of(savedB));

        assertEquals(plaintextA, service.decryptSecretForWorkspace(WORKSPACE_ID));
        assertEquals(plaintextB, service.decryptSecretForWorkspace(workspaceB));
    }

    // ------------------------------------------------------------------------------------------
    // decryptSecretForWorkspace -- fail-closed shape (returns null, never throws, on any unresolved case)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("decryptSecretForWorkspace: returns null (never throws) for a null workspaceId")
    void decryptSecretForWorkspace_nullWorkspaceId_returnsNull() {
        assertNull(service.decryptSecretForWorkspace(null));
        verify(repository, never()).findByWorkspaceIdAndRevokedFalse(any());
    }

    @Test
    @DisplayName("decryptSecretForWorkspace: returns null for a workspace with no configured secret")
    void decryptSecretForWorkspace_noSecretConfigured_returnsNull() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertNull(service.decryptSecretForWorkspace(WORKSPACE_ID));
    }

    // ------------------------------------------------------------------------------------------
    // revoke
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("revoke: marks secret as revoked and records audit log")
    void revoke_marksSecretAndLogsAudit() {
        ConversionWebhookSecret existing =
                ConversionWebhookSecret.builder()
                        .id(SECRET_ROW_ID)
                        .workspaceId(WORKSPACE_ID)
                        .encryptedSecret("encrypted")
                        .build();
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.of(existing));

        service.revoke(WORKSPACE_ID);

        verify(repository).save(secretCaptor.capture());
        assertTrue(secretCaptor.getValue().isRevoked());

        verify(auditLog)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("CONVERSION_WEBHOOK_SECRET_REVOKED"),
                        eq("SENSITIVE"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        any());
    }

    @Test
    @DisplayName("revoke: no-op if secret doesn't exist")
    void revoke_noOpForNonExistent() {
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID)).thenReturn(Optional.empty());

        service.revoke(WORKSPACE_ID);

        verify(repository, never()).save(any());
        verify(auditLog, never()).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("revoke then decryptSecretForWorkspace: a revoked secret is treated as unconfigured (fail closed)")
    void revoke_thenDecrypt_treatedAsUnconfigured() {
        ConversionWebhookSecret existing =
                ConversionWebhookSecret.builder()
                        .id(SECRET_ROW_ID)
                        .workspaceId(WORKSPACE_ID)
                        .encryptedSecret("encrypted")
                        .build();
        when(repository.findByWorkspaceIdAndRevokedFalse(WORKSPACE_ID))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.empty());

        service.revoke(WORKSPACE_ID);

        assertNull(service.decryptSecretForWorkspace(WORKSPACE_ID));
    }

    // ------------------------------------------------------------------------------------------
    // Constructor validation -- same discipline as every other encrypted-secret service
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("constructor: validates encryption key is exactly 32 bytes")
    void constructor_validatesKeyLength() {
        ConversionWebhookProperties propsInvalidKey = createTestProperties();
        propsInvalidKey.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));

        assertThrows(
                IllegalStateException.class,
                () -> new ConversionWebhookSecretService(repository, auditLog, propsInvalidKey));
    }

    @Test
    @DisplayName("constructor: throws if encryption key is missing")
    void constructor_throwsIfKeyMissing() {
        ConversionWebhookProperties propsNoKey = createTestProperties();
        propsNoKey.setTokenEncryptionKey(null);

        assertThrows(
                IllegalStateException.class,
                () -> new ConversionWebhookSecretService(repository, auditLog, propsNoKey));
    }
}
