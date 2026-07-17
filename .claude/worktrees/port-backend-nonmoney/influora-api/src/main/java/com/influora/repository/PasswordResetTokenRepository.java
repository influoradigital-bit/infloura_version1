package com.influora.repository;

import com.influora.domain.entity.PasswordResetToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);
}
