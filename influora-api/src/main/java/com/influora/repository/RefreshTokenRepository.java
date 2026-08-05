package com.influora.repository;

import com.influora.domain.entity.RefreshToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false")
    void revokeAllForUser(@Param("userId") String userId);

    /**
     * Same as {@link #revokeAllForUser} but leaves one token untouched — the caller's own current
     * session. Used by {@code AuthService#changePassword} (Priya audit, e60d249 follow-up): a
     * password change should sign out every OTHER device/session, not the user who just changed it.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
            "UPDATE RefreshToken r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false"
                    + " AND r.id <> :keepId")
    void revokeAllForUserExcept(@Param("userId") String userId, @Param("keepId") String keepId);
}
