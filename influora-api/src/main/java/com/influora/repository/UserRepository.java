package com.influora.repository;

import com.influora.domain.entity.User;
import com.influora.domain.enums.UserType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** PHONE-0829 — upfront duplicate check before {@code CreatorProfileService} sets a normalized
     * phone number, mirroring {@link #existsByEmailIgnoreCase}'s TOCTOU-aware pattern (see that
     * call site's javadoc in {@code AuthService}): this check narrows the common case, but the DB's
     * own {@code UNIQUE(phone_number)} constraint (V2__core_auth.sql) is what actually prevents a
     * race, so the write path still wraps the save in a try/catch for {@code
     * DataIntegrityViolationException}. */
    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * Monthly-active-user proxy for AdminDashboardController's CEO Pulse ({@code mauBrands}/
     * {@code mauCreators}): users of the given type who have logged in since {@code since}. This
     * is a login-recency count, not a true engagement/activity MAU (no session/event tracking
     * table exists yet) — documented as an interim definition, same caveat as the GMV proxy in
     * {@code EscrowHoldRepository}.
     */
    long countByUserTypeAndLastLoginAtAfter(UserType userType, Instant since);
}
