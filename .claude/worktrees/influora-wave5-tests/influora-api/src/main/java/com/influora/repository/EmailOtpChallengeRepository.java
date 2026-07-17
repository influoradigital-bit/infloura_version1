package com.influora.repository;

import com.influora.domain.entity.EmailOtpChallenge;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailOtpChallengeRepository extends JpaRepository<EmailOtpChallenge, String> {

    Optional<EmailOtpChallenge> findFirstByEmailOrderByCreatedAtDesc(String email);

    long countByEmailAndCreatedAtAfter(String email, Instant since);

    @Modifying
    @Query("UPDATE EmailOtpChallenge e SET e.verified = false WHERE e.email = :email AND e.verified = true")
    void clearVerifiedForEmail(@Param("email") String email);
}
