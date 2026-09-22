package com.influora.service.credits;

import com.influora.domain.entity.CreatorCreditWelcomeClaim;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §6, Kabir K-12) — inserts the permanent welcome-grant claim rows
 * ({@code user:}, {@code ig:}, optionally {@code meta:}), all-or-nothing, on a SEPARATE bean with
 * {@code REQUIRES_NEW} so a raced/colliding claim never poisons {@code
 * CreatorCreditService#grantWelcome}'s own outer transaction (self-invocation would make the
 * annotation a no-op if this lived on that class instead).
 *
 * <p>K-12 fix, part 1 ({@link CreatorCreditWelcomeClaim}): this entity sets its own {@code @Id},
 * which by default makes a plain {@code save}/{@code saveAndFlush} a Hibernate {@code merge} —
 * SELECT-then-UPDATE instead of INSERT — so a second user claiming an already-claimed key
 * silently overwrote it, with no exception and no blocked farming. {@link
 * CreatorCreditWelcomeClaim} now implements {@code Persistable} with {@code isNew()} always
 * {@code true}, forcing a true {@code persist}/INSERT every time.
 *
 * <p>K-12 fix, part 2 (the catch here is NOT sufficient on its own): {@link
 * DataIntegrityViolationException}, once it escapes the repository's own joined {@code
 * @Transactional} call, marks the CURRENT physical (this method's {@code REQUIRES_NEW})
 * transaction rollback-only — before this catch block ever runs, and unaffected by it. So even
 * though this method returns {@code false} normally, its own commit at method exit still fails
 * with {@code UnexpectedRollbackException}, which is what the caller actually receives instead of
 * {@code false}. {@code Propagation.NESTED} (a savepoint instead of a second physical
 * transaction) would sidestep this cleanly, but {@code HibernateJpaDialect}/{@code
 * JpaTransactionManager} does not support savepoints (confirmed against this codebase's own H2
 * test harness), so the fix instead moves one frame up: {@link
 * com.influora.service.credits.CreatorCreditService#grantWelcome} catches {@link
 * org.springframework.transaction.UnexpectedRollbackException} — which, being a SEPARATE physical
 * transaction (REQUIRES_NEW suspends the caller's), never touches the caller's own transaction —
 * and treats it exactly like the "a claim already existed" {@code false} it in fact is.
 */
@Service
public class CreatorCreditWelcomeClaimWriter {

    private static final Logger log = LoggerFactory.getLogger(CreatorCreditWelcomeClaimWriter.class);

    private final CreatorCreditWelcomeClaimRepository claimRepository;

    public CreatorCreditWelcomeClaimWriter(CreatorCreditWelcomeClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    /**
     * @return true if every applicable claim now belongs to {@code creatorUserId} (freshly
     *     inserted here, or already theirs from an earlier attempt); false if any claim key already
     *     belongs to a DIFFERENT creator (no grant).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertClaims(String creatorUserId, String igBusinessAccountId, String metaUserId) {
        List<String> keys = new ArrayList<>();
        keys.add("user:" + creatorUserId);
        keys.add("ig:" + igBusinessAccountId);
        if (metaUserId != null && !metaUserId.isBlank()) {
            keys.add("meta:" + metaUserId);
        }

        // Review findings #12/#14 fix: this REQUIRES_NEW transaction commits independently of
        // CreatorCreditService#grantWelcome's own (outer) transaction — a prior attempt for THIS
        // EXACT creator can have committed some/all of these claim rows here already, even though
        // the outer transaction then rolled back for an unrelated reason (e.g. confirmPaid's
        // uk_cco_rzp_payment collision) and so never wrote the FREE_SIGNUP grant. Without this
        // check, retrying would hit these same claim rows' PK and permanently refuse the grant —
        // the creator would never get their 40 credits. A claim already owned by THIS creator is
        // therefore treated as already-claimed (skip re-inserting it, which would itself violate
        // the PK); a claim owned by a DIFFERENT creator still blocks the grant, unchanged.
        Map<String, CreatorCreditWelcomeClaim> existing =
                claimRepository.findAllById(keys).stream()
                        .collect(Collectors.toMap(CreatorCreditWelcomeClaim::getClaimKey, c -> c));
        for (String key : keys) {
            CreatorCreditWelcomeClaim row = existing.get(key);
            if (row != null && !row.getCreatorUserId().equals(creatorUserId)) {
                log.info(
                        "CreatorCreditWelcomeClaimWriter: claim {} already belongs to a different"
                                + " creator than {} — no grant given",
                        key,
                        creatorUserId);
                return false;
            }
        }

        try {
            Instant now = Instant.now();
            for (String key : keys) {
                if (existing.containsKey(key)) {
                    continue; // already claimed by this SAME creator in an earlier attempt
                }
                claimRepository.saveAndFlush(CreatorCreditWelcomeClaim.of(key, creatorUserId, now));
            }
            return true;
        } catch (DataIntegrityViolationException raced) {
            // A genuinely concurrent claim (a different session/creator racing for the same key
            // between the read above and this insert). See the class javadoc: this catch alone is
            // not sufficient — the caller must also catch UnexpectedRollbackException, because this
            // method's own commit can still fail even though we return false normally from here.
            log.info(
                    "CreatorCreditWelcomeClaimWriter: a welcome claim for creator {} (ig={}) already"
                            + " exists — no grant given",
                    creatorUserId,
                    igBusinessAccountId);
            return false;
        }
    }
}
