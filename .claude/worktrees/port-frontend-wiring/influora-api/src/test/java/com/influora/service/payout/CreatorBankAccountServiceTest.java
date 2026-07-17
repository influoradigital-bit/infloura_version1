package com.influora.service.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CreatorBankAccountRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.security.CreatorBankPiiCipher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Locks in the QA-caught fix: setPrimary/addInstrument now resolve the "is there already a
 * primary" / "is this the first instrument" question from the pessimistically-locked row set
 * returned by {@code lockAllForCreatorUpdate}, not from a separate unlocked read — and the
 * {@code uq_creator_bank_accounts_primary_marker} DB constraint (V62) surfaces as a clean
 * PRIMARY_CONFLICT ApiException if it's ever hit anyway.
 */
@ExtendWith(MockitoExtension.class)
class CreatorBankAccountServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";

    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorBankAccountRepository repository;
    @Mock private CreatorBankPiiCipher cipher;
    @Mock private AuthPrincipal principal;

    private CreatorBankAccountService service;

    @BeforeEach
    void setUp() {
        service = new CreatorBankAccountService(creatorContext, repository, cipher);
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
    }

    private CreatorBankAccount account(String id, boolean primary) {
        return CreatorBankAccount.createEncrypted(
                id, CREATOR_USER_ID, "cipher", null, "UPI", "****1234", primary, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("addInstrument: first instrument for a creator becomes primary, resolved from the locked row set")
    void addInstrument_firstInstrument_becomesPrimary() {
        when(repository.lockAllForCreatorUpdate(CREATOR_USER_ID)).thenReturn(List.of());
        when(cipher.encrypt(anyString())).thenReturn("cipher-value");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreatorBankAccount result = service.addInstrument(principal, "UPI", "jane@bank", null, null);

        assertTrue(result.isPrimary());
        verify(repository).lockAllForCreatorUpdate(CREATOR_USER_ID);
    }

    @Test
    @DisplayName("addInstrument: second instrument for a creator does NOT become primary")
    void addInstrument_secondInstrument_notPrimary() {
        when(repository.lockAllForCreatorUpdate(CREATOR_USER_ID))
                .thenReturn(List.of(account("existing-1", true)));
        when(cipher.encrypt(anyString())).thenReturn("cipher-value");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreatorBankAccount result = service.addInstrument(principal, "BANK", "123456", "HDFC0001", null);

        assertFalse(result.isPrimary());
    }

    @Test
    @DisplayName("addInstrument: a DB-level unique-constraint hit surfaces as a clean 409, not a raw SQL exception")
    void addInstrument_dbConflict_translatesToApiException() {
        when(repository.lockAllForCreatorUpdate(CREATOR_USER_ID)).thenReturn(List.of());
        when(cipher.encrypt(anyString())).thenReturn("cipher-value");
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.addInstrument(principal, "UPI", "jane@bank", null, null));
        assertEquals("PRIMARY_CONFLICT", ex.getCode());
    }

    @Test
    @DisplayName("setPrimary: clears the previous primary and sets the target, both from the locked row set")
    void setPrimary_clearsOldSetsNew() {
        CreatorBankAccount oldPrimary = account("old-primary", true);
        CreatorBankAccount target = account("target", false);
        when(repository.lockAllForCreatorUpdate(CREATOR_USER_ID)).thenReturn(List.of(oldPrimary, target));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreatorBankAccount result = service.setPrimary(principal, "target");

        assertTrue(result.isPrimary());
        assertFalse(oldPrimary.isPrimary());
        verify(repository).lockAllForCreatorUpdate(CREATOR_USER_ID);
    }

    @Test
    @DisplayName("setPrimary: already-primary target is a no-op (does not touch other rows)")
    void setPrimary_alreadyPrimary_isNoOp() {
        CreatorBankAccount alreadyPrimary = account("target", true);
        when(repository.lockAllForCreatorUpdate(CREATOR_USER_ID)).thenReturn(List.of(alreadyPrimary));

        CreatorBankAccount result = service.setPrimary(principal, "target");

        assertTrue(result.isPrimary());
    }

    @Test
    @DisplayName("setPrimary: unknown/foreign id 404s instead of leaking another creator's account existence")
    void setPrimary_unknownId_throws404() {
        when(repository.lockAllForCreatorUpdate(CREATOR_USER_ID)).thenReturn(List.of(account("mine", true)));

        ApiException ex =
                assertThrows(ApiException.class, () -> service.setPrimary(principal, "not-mine"));
        assertEquals("BANK_ACCOUNT_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("setPrimary: a DB-level unique-constraint hit surfaces as a clean 409, not a raw SQL exception")
    void setPrimary_dbConflict_translatesToApiException() {
        CreatorBankAccount oldPrimary = account("old-primary", true);
        CreatorBankAccount target = account("target", false);
        when(repository.lockAllForCreatorUpdate(CREATOR_USER_ID)).thenReturn(List.of(oldPrimary, target));
        when(repository.save(oldPrimary)).thenReturn(oldPrimary);
        when(repository.save(target)).thenThrow(new DataIntegrityViolationException("duplicate key"));

        ApiException ex = assertThrows(ApiException.class, () -> service.setPrimary(principal, "target"));
        assertEquals("PRIMARY_CONFLICT", ex.getCode());
    }
}
