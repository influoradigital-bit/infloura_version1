package com.influora.repository;

import com.influora.domain.entity.PasswordResetToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);

    /**
     * Drops every reset token for a user, used or not (F-0702). Called from the account-delete
     * path: {@code softDelete()} nulls the email and password hash but leaves these rows behind,
     * and they stay resolvable by {@code userId} for up to their natural expiry -- 1 hour for
     * forgot-password, but 7 days for the sponsor-provisioning link.
     *
     * <p>Deletes rather than marks used, because the row's only remaining purpose after account
     * deletion is to be a way in. The {@code long} is Spring Data's row count; the production caller
     * ({@code AuthService#purgePasswordResetTokens}) discards it, and the tests assert on it.
     */
    long deleteByUserId(String userId);
}
