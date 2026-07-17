package com.influora.repository;

import com.influora.domain.entity.AdminRefreshToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AdminRefreshTokenRepository extends JpaRepository<AdminRefreshToken, String> {

    Optional<AdminRefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE AdminRefreshToken t SET t.revoked = true WHERE t.adminId = :adminId AND t.revoked = false")
    void revokeAllForAdmin(@Param("adminId") String adminId);
}
