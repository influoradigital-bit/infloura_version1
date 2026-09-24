package com.influora.repository;

import com.influora.domain.entity.CreatorCreditAccount;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §4) — the lock-anchor repository. {@link #findByIdForUpdate} is
 * the ONE entry point every credit mutation (charge/release/grant/monthly-roll) takes before
 * touching any grant or ledger row (K-02) — same shape as {@code WalletTopUpRepository
 * #findByIdForUpdate} / {@code AdminEmailSendLockRepository#lockForUpdate}. A waiter that times
 * out or a deadlock victim surfaces as {@code PessimisticLockingFailureException} /
 * {@code CannotAcquireLockException}, which {@code GlobalExceptionHandler} already maps to a
 * retryable 409 {@code CONCURRENT_MODIFICATION} (EV-181).
 */
public interface CreatorCreditAccountRepository extends JpaRepository<CreatorCreditAccount, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT a FROM CreatorCreditAccount a WHERE a.creatorUserId = :creatorUserId")
    Optional<CreatorCreditAccount> findByIdForUpdate(@Param("creatorUserId") String creatorUserId);
}
