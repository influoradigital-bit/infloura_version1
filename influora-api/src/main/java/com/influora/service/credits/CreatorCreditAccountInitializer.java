package com.influora.service.credits;

import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.repository.CreatorCreditAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.1) — ensures the lock-anchor row exists, on a SEPARATE bean
 * with {@code REQUIRES_NEW} (a separate bean, not a private/self-invoked method, because Spring's
 * {@code @Transactional} self-invocation limitation would otherwise make the {@code REQUIRES_NEW}
 * boundary a no-op when called from inside {@code CreatorCreditService}'s own active
 * transaction).
 *
 * <p>K-02 fix: a plain {@code save()}/{@code saveAndFlush()} inside a try/catch for {@link
 * DataIntegrityViolationException} used NOT to be enough on its own: that exception, once it
 * crosses the repository method's OWN transactional proxy boundary (a joined, REQUIRED
 * participation in this method's {@code REQUIRES_NEW} transaction), marks that WHOLE physical
 * transaction rollback-only before our catch block ever runs — this is how Spring's default
 * transaction management responds to any exception escaping a joined {@code @Transactional}
 * call, regardless of whether the caller catches it afterward. The commit at method exit then
 * failed with {@code UnexpectedRollbackException} despite the catch — NOT a {@code
 * DataIntegrityViolationException} the caller was already prepared for. {@code
 * Propagation.NESTED} (a savepoint instead of a second physical transaction) would sidestep this
 * cleanly, but {@code HibernateJpaDialect}/{@code JpaTransactionManager} does not support
 * savepoints (confirmed against this codebase's own H2 test harness: {@code
 * NestedTransactionNotSupported ... JpaDialect does not support savepoints}), so the fix instead
 * moves one frame up: {@link com.influora.service.credits.CreatorCreditService#lockAccount}
 * catches the resulting {@link org.springframework.transaction.UnexpectedRollbackException} —
 * which, being a SEPARATE physical transaction (REQUIRES_NEW suspends the caller's), never
 * touches the caller's own transaction or its rollback-only flag — and treats it exactly like the
 * "row already exists" case it in fact is.
 */
@Service
public class CreatorCreditAccountInitializer {

    private static final Logger log = LoggerFactory.getLogger(CreatorCreditAccountInitializer.class);

    private final CreatorCreditAccountRepository accountRepository;

    public CreatorCreditAccountInitializer(CreatorCreditAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureAccount(String creatorUserId) {
        // Fast path: on every call after the very first, the row already exists — skip the
        // insert attempt entirely rather than paying for one on every charge/release/speak.
        if (accountRepository.existsById(creatorUserId)) {
            return;
        }
        try {
            accountRepository.saveAndFlush(CreatorCreditAccount.newAccount(creatorUserId));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent charge/release/purchase for the same creator already inserted the row
            // — fine, that is exactly the row we wanted to exist. See the class javadoc: this
            // catch alone is not sufficient — the caller must also catch
            // UnexpectedRollbackException, because this method's own commit can still fail even
            // though we return normally from here.
            log.debug("CreatorCreditAccountInitializer: account for {} already exists", creatorUserId);
        }
    }
}
