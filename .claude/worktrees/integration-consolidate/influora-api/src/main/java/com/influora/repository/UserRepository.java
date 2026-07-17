package com.influora.repository;

import com.influora.domain.entity.User;
import com.influora.domain.enums.UserType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Monthly-active-user proxy for AdminDashboardController's CEO Pulse ({@code mauBrands}/
     * {@code mauCreators}): users of the given type who have logged in since {@code since}. This
     * is a login-recency count, not a true engagement/activity MAU (no session/event tracking
     * table exists yet) — documented as an interim definition, same caveat as the GMV proxy in
     * {@code EscrowHoldRepository}.
     */
    long countByUserTypeAndLastLoginAtAfter(UserType userType, Instant since);
}
