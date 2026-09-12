package com.influora.repository;

import com.influora.domain.entity.EmailOtpChallenge;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailOtpChallengeRepository extends JpaRepository<EmailOtpChallenge, String> {

    /**
     * [F6] Newest challenge for an address, with a DETERMINISTIC tie-break.
     *
     * <p>{@code created_at} is a plain {@code TIMESTAMP} (V5__email_otp.sql) — second resolution.
     * Two challenges minted for the same address inside one second therefore carry identical
     * {@code created_at}, and ordering by that column alone let InnoDB return either one: a
     * coin-flip over which row {@code verifyOtp} compares the code against. {@code id DESC} settles
     * it at no cost and with no migration, because ids are ULIDs — lexicographic order IS time
     * order for them, so this is the same intent the column ordering expresses, just decided.
     */
    @Query(
            "SELECT e FROM EmailOtpChallenge e WHERE e.email = :email"
                    + " ORDER BY e.createdAt DESC, e.id DESC LIMIT 1")
    Optional<EmailOtpChallenge> findNewestForEmail(@Param("email") String email);

    /**
     * [F6] Does ANY still-usable verified challenge exist for this address?
     *
     * <p>Deliberately not "is the NEWEST one verified". Reading only the newest row handed a
     * stranger a way to revoke someone else's completed verification: request a code for an address
     * that has just verified, and the fresh unverified row becomes the newest, so the real owner's
     * registration fails EMAIL_NOT_VERIFIED while the code they need sits in the attacker's chosen
     * moment. Asking whether the address has a live proof at all cannot be shadowed that way — a new
     * unverified row alongside it changes nothing.
     */
    boolean existsByEmailAndVerifiedTrueAndExpiresAtAfter(String email, Instant usableSince);

    long countByEmailAndCreatedAtAfter(String email, Instant since);

    @Modifying
    @Query("UPDATE EmailOtpChallenge e SET e.verified = false WHERE e.email = :email AND e.verified = true")
    void clearVerifiedForEmail(@Param("email") String email);
}
