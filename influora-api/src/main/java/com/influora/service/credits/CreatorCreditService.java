package com.influora.service.credits;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditAccount;
import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.entity.CreatorCreditLedgerEntry;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorVoiceSpeak;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.ChargeKind;
import com.influora.domain.enums.CreditBucket;
import com.influora.domain.enums.CreditLedgerReason;
import com.influora.repository.CreatorCreditAccountRepository;
import com.influora.repository.CreatorCreditGrantRepository;
import com.influora.repository.CreatorCreditLedgerRepository;
import com.influora.repository.CreatorCreditWelcomeClaimRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorVoiceSpeakRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.meera.MeeraSessionService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.1) — the ONLY writer of the credit repositories (K-22). Every
 * mutation (charge/release/grantWelcome/creditPurchase/claimVoiceSpeak/assertTurnNotReleased) takes
 * the {@link CreatorCreditAccount} row lock before touching a grant or ledger row for that
 * creator.
 */
@Service
public class CreatorCreditService {

    private static final Logger log = LoggerFactory.getLogger(CreatorCreditService.class);

    /** How long a voice-turn's {@code tts:} debit stays claimable by {@code speak} (SPEC.md §7.2 C5). */
    private static final Duration VOICE_CHARGE_WINDOW = Duration.ofMinutes(30);

    private final CreatorCreditAccountRepository accountRepository;
    private final CreatorCreditGrantRepository grantRepository;
    private final CreatorCreditLedgerRepository ledgerRepository;
    private final CreatorVoiceSpeakRepository voiceSpeakRepository;
    private final CreatorCreditAccountInitializer accountInitializer;
    private final CreatorCreditWelcomeClaimWriter welcomeClaimWriter;
    private final CreatorCreditWelcomeClaimRepository welcomeClaimRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final IdempotencyService idempotencyService;
    private final CreatorCreditProperties properties;
    private final java.time.Clock clock;

    public CreatorCreditService(
            CreatorCreditAccountRepository accountRepository,
            CreatorCreditGrantRepository grantRepository,
            CreatorCreditLedgerRepository ledgerRepository,
            CreatorVoiceSpeakRepository voiceSpeakRepository,
            CreatorCreditAccountInitializer accountInitializer,
            CreatorCreditWelcomeClaimWriter welcomeClaimWriter,
            CreatorCreditWelcomeClaimRepository welcomeClaimRepository,
            CreatorProfileRepository creatorProfileRepository,
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            IdempotencyService idempotencyService,
            CreatorCreditProperties properties,
            java.time.Clock clock) {
        this.accountRepository = accountRepository;
        this.grantRepository = grantRepository;
        this.ledgerRepository = ledgerRepository;
        this.voiceSpeakRepository = voiceSpeakRepository;
        this.accountInitializer = accountInitializer;
        this.welcomeClaimWriter = welcomeClaimWriter;
        this.welcomeClaimRepository = welcomeClaimRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.idempotencyService = idempotencyService;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Reference formats (SPEC.md §4) — all server-minted, <= 64 chars, no client input.
    // ------------------------------------------------------------------

    public static String turnRef(String ulid) {
        return requireShortEnough("turn:" + requireUlid(ulid));
    }

    public static String ttsRef(String ulid) {
        return requireShortEnough("tts:" + requireUlid(ulid));
    }

    public static String briefRef(String ulid) {
        return requireShortEnough("brief:" + requireUlid(ulid));
    }

    public static String monthlyRef(String yearMonth) {
        return requireShortEnough("m:" + yearMonth);
    }

    public static String welcomeRef() {
        return "signup";
    }

    public static String orderRef(String orderUlid) {
        return requireShortEnough("order:" + requireUlid(orderUlid));
    }

    private static String requireUlid(String ulid) {
        if (ulid == null || ulid.isBlank()) {
            throw new IllegalArgumentException("ulid must not be blank");
        }
        return ulid;
    }

    private static String requireShortEnough(String ref) {
        if (ref.length() > 64) {
            throw new IllegalArgumentException("reference exceeds 64 characters: " + ref);
        }
        return ref;
    }

    // ------------------------------------------------------------------
    // charge / release
    // ------------------------------------------------------------------

    /**
     * SPEC.md §5.1 — never throws for a business refusal (D5): a DAILY_CAP/INSUFFICIENT outcome
     * is returned, and the lazily-materialised monthly grant (steps 3-5) still commits because this
     * method returns rather than throwing.
     */
    @Transactional
    public ChargeResult charge(String creatorUserId, ChargeKind kind, String ulid) {
        // Review finding #18 — the LIVE configured cost, never ChargeKind's own fixed constant
        // (see costFor's javadoc): this is the single number used both to debit and to report.
        int cost = costFor(kind);
        if (!properties.isEnabled()) {
            return ChargeResult.disabled(kind, cost);
        }
        requireUlid(ulid);

        CreatorCreditAccount account = lockAccount(creatorUserId);
        Instant now = clock.instant();
        ZoneId zone = properties.zoneId();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        account.rollDayIfNeeded(today);
        materializeMonthly(account, now);
        grantWelcome(creatorUserId);

        String primaryRef = kind == ChargeKind.BRIEF ? briefRef(ulid) : turnRef(ulid);
        boolean alreadyCharged =
                ledgerRepository.lockByCreatorUserIdAndReferenceIdIn(creatorUserId, List.of(primaryRef)).stream()
                        .anyMatch(e -> isDebitReason(e.getReason()));
        if (alreadyCharged) {
            accountRepository.save(account);
            return ChargeResult.alreadyCharged(kind, cost, totalRemaining(creatorUserId, now), account.getDailyUsed());
        }

        if (account.getDailyUsed() + cost > properties.getDailyCap()) {
            accountRepository.save(account);
            return ChargeResult.dailyCap(kind, cost, totalRemaining(creatorUserId, now), account.getDailyUsed());
        }

        List<CreatorCreditGrant> spendable = GrantOrder.sort(grantRepository.lockSpendable(creatorUserId, now), now);
        int total = spendable.stream().mapToInt(CreatorCreditGrant::getCreditsRemaining).sum();
        if (total < cost) {
            accountRepository.save(account);
            return ChargeResult.insufficient(kind, cost, total, account.getDailyUsed());
        }

        if (kind == ChargeKind.VOICE_TURN) {
            // The two halves are each properties-driven and sum to `cost` — not a hardcoded 1+1 —
            // so a future turn-cost/voice-surcharge retune (finding #18) is reflected here too.
            debitAcrossGrants(
                    creatorUserId,
                    spendable,
                    properties.getTurnCost(),
                    CreditLedgerReason.DEBIT_TURN,
                    turnRef(ulid),
                    today,
                    now);
            debitAcrossGrants(
                    creatorUserId,
                    spendable,
                    properties.getVoiceSurcharge(),
                    CreditLedgerReason.DEBIT_VOICE,
                    ttsRef(ulid),
                    today,
                    now);
        } else {
            CreditLedgerReason reason =
                    kind == ChargeKind.BRIEF ? CreditLedgerReason.DEBIT_BRIEF : CreditLedgerReason.DEBIT_TURN;
            debitAcrossGrants(creatorUserId, spendable, cost, reason, primaryRef, today, now);
        }
        account.addDailyUsed(cost);
        accountRepository.save(account);

        return ChargeResult.charged(kind, cost, totalRemaining(creatorUserId, now), account.getDailyUsed());
    }

    /**
     * Review finding #18 — the source of truth for what a charge actually costs is the LIVE {@link
     * CreatorCreditProperties} (the same object {@code CreatorCreditController} reads for {@code
     * GET /creator/credits}'s {@code costs} field), never {@link ChargeKind#cost()}'s fixed nominal
     * constant. Keeping both in sync today (1/1/3) does not stop them diverging the moment either
     * `turn-cost`/`voice-surcharge`/`brief-cost` or the enum is edited on its own.
     */
    private int costFor(ChargeKind kind) {
        return switch (kind) {
            case TURN -> properties.getTurnCost();
            case VOICE_TURN -> properties.getVoiceTurnCost();
            case BRIEF -> properties.getBriefCost();
            case SCRIPT -> properties.getScriptCost();
            case PROFILE_REVIEW -> properties.getProfileReviewCost();
        };
    }

    private static boolean isDebitReason(CreditLedgerReason reason) {
        return reason == CreditLedgerReason.DEBIT_TURN
                || reason == CreditLedgerReason.DEBIT_VOICE
                || reason == CreditLedgerReason.DEBIT_BRIEF;
    }

    /** All-or-nothing debit of {@code amount} across the (already sorted, already lock-held) spendable grants. */
    private void debitAcrossGrants(
            String creatorUserId,
            List<CreatorCreditGrant> spendable,
            int amount,
            CreditLedgerReason reason,
            String referenceId,
            LocalDate istDate,
            Instant now) {
        int remaining = amount;
        for (CreatorCreditGrant grant : spendable) {
            if (remaining <= 0) {
                break;
            }
            if (grant.getCreditsRemaining() <= 0) {
                continue;
            }
            int applied = grant.debit(remaining);
            if (applied <= 0) {
                continue;
            }
            remaining -= applied;
            grantRepository.save(grant);
            ledgerRepository.save(
                    CreatorCreditLedgerEntry.of(
                            Ulids.newUlid(),
                            creatorUserId,
                            grant.getId(),
                            reason,
                            referenceId,
                            -applied,
                            grant.getCreditsRemaining(),
                            istDate,
                            now));
        }
        if (remaining > 0) {
            // Pre-checked by the caller (total >= cost) — reaching here means a logic error, not a
            // legitimate business outcome, so this fails loudly rather than silently under-charging.
            throw new IllegalStateException(
                    "debitAcrossGrants: insufficient spendable credit for " + referenceId + " (creator "
                            + creatorUserId + ")");
        }
    }

    /**
     * SPEC.md §5.1 release — NOT flag-gated (C19): a debit-less creator (flag was always off) hits
     * the empty-rows early return before this method ever locks the account or writes anything.
     */
    @Transactional
    public void release(String creatorUserId, String ulid, ReleaseScope scope) {
        List<String> refs = referencesForScope(ulid, scope);
        List<CreditLedgerReason> debitReasons = debitReasonsForScope(scope);

        // K-19/K-18 fix: the account row lock MUST be the first statement this transaction issues.
        // Under MySQL REPEATABLE READ, InnoDB's consistent-read view is fixed by the transaction's
        // first read; a locking read (SELECT ... FOR UPDATE) always reads the latest committed data
        // and re-anchors that view. Reading the ledger/grant rows *before* the lock (the previous
        // order) let a concurrent charge()/writeback that committed while this release() was blocked
        // on the lock go invisible to the later plain reads below, so this release would refund a
        // stale (pre-debit) balance and silently erase the other transaction's debit. Locking first is
        // necessary but NOT sufficient: under REPEATABLE READ a plain read still sees the snapshot of
        // this transaction's first read (which may predate the lock), so every read below that decides
        // a write is a LOCKING read (lock* repository methods), which always sees the latest commit.
        Optional<CreatorCreditAccount> maybeAccount = accountRepository.findByIdForUpdate(creatorUserId);
        if (maybeAccount.isEmpty()) {
            return;
        }
        CreatorCreditAccount account = maybeAccount.get();

        List<CreatorCreditLedgerEntry> rows =
                ledgerRepository.lockByCreatorUserIdAndReferenceIdIn(creatorUserId, refs);
        List<CreatorCreditLedgerEntry> debitRows =
                rows.stream().filter(e -> debitReasons.contains(e.getReason())).toList();
        if (debitRows.isEmpty()) {
            return;
        }

        Set<String> alreadyRefunded = new HashSet<>();
        for (CreatorCreditLedgerEntry e : rows) {
            if (e.getReason() == CreditLedgerReason.REFUND) {
                alreadyRefunded.add(e.getReferenceId() + "|" + e.getGrantId());
            }
        }
        List<CreatorCreditLedgerEntry> toRelease =
                debitRows.stream()
                        .filter(d -> !alreadyRefunded.contains(d.getReferenceId() + "|" + d.getGrantId()))
                        .toList();
        if (toRelease.isEmpty()) {
            return;
        }

        // K-15 fix: a TURN-scope release must not resurrect a turn whose write-back already
        // persisted. Does not apply to VOICE_ONLY (TTS runs after write-back) or BRIEF.
        //
        // This used to consult idempotencyService.isCompleted(..., PERSIST_WRITEBACK_SCOPE), but
        // that check has a real race window: the write-back's ASSISTANT row commits inside
        // MeeraSessionService's own TransactionTemplate (which also releases the account lock this
        // method is holding), and ONLY AFTER that commit does IdempotencyService mark the
        // reservation COMPLETED — in its own, separate REQUIRES_NEW transaction. A release() that
        // acquires the account lock in between (reply already durably persisted, idempotency row
        // still IN_PROGRESS) would see isCompleted()==false and wrongly refund a turn whose reply
        // the user already has (refund-and-keep-reply).
        //
        // Instead, check `rows` (already read under this SAME account lock, above) for the
        // WRITEBACK_MARKER row CreatorCreditService#markWritebackPersisted writes INSIDE the
        // write-back's own physical transaction, atomically with the ASSISTANT insert. Because
        // that write-back transaction holds this exact account row lock for its entire duration
        // (see MeeraSessionService#doPersistAssistantWriteback's javadoc), this method cannot even
        // reach this line until the write-back has either fully committed (marker + reply both
        // visible) or never touched the lock at all (marker absent) — there is no window where the
        // reply is visible but the marker is not, or vice versa.
        if (scope == ReleaseScope.TURN
                && rows.stream().anyMatch(e -> e.getReason() == CreditLedgerReason.WRITEBACK_MARKER)) {
            return;
        }

        Instant now = clock.instant();
        for (CreatorCreditLedgerEntry debit : toRelease) {
            CreatorCreditGrant grant = grantRepository.lockById(debit.getGrantId()).orElse(null);
            if (grant == null) {
                log.error(
                        "CreatorCreditService#release: grant {} for creator {} no longer exists — cannot"
                                + " restore {}",
                        debit.getGrantId(),
                        creatorUserId,
                        debit.getReferenceId());
                continue;
            }
            int amount = -debit.getDelta();
            grant.refund(amount);
            grantRepository.save(grant);
            ledgerRepository.save(
                    CreatorCreditLedgerEntry.of(
                            Ulids.newUlid(),
                            creatorUserId,
                            grant.getId(),
                            CreditLedgerReason.REFUND,
                            debit.getReferenceId(),
                            amount,
                            grant.getCreditsRemaining(),
                            debit.getIstDate(),
                            now));
            if (debit.getIstDate().equals(account.getDailyDate())) {
                account.refundDailyUsed(amount);
            }
        }
        accountRepository.save(account);
    }

    private static List<String> referencesForScope(String ulid, ReleaseScope scope) {
        return switch (scope) {
            case TURN -> List.of(turnRef(ulid), ttsRef(ulid));
            case VOICE_ONLY -> List.of(ttsRef(ulid));
            case BRIEF -> List.of(briefRef(ulid));
        };
    }

    private static List<CreditLedgerReason> debitReasonsForScope(ReleaseScope scope) {
        return switch (scope) {
            case TURN -> List.of(CreditLedgerReason.DEBIT_TURN, CreditLedgerReason.DEBIT_VOICE);
            case VOICE_ONLY -> List.of(CreditLedgerReason.DEBIT_VOICE);
            case BRIEF -> List.of(CreditLedgerReason.DEBIT_BRIEF);
        };
    }

    /** An unrefunded DEBIT_VOICE row for {@code tts:<turnUlid>}, younger than {@link #VOICE_CHARGE_WINDOW}. */
    @Transactional(readOnly = true)
    public boolean hasVoiceCharge(String creatorUserId, String turnUlid) {
        if (!properties.isEnabled()) {
            return false;
        }
        String ref = ttsRef(turnUlid);
        List<CreatorCreditLedgerEntry> rows =
                ledgerRepository.findByCreatorUserIdAndReferenceIdIn(creatorUserId, List.of(ref));
        Optional<CreatorCreditLedgerEntry> debit =
                rows.stream().filter(e -> e.getReason() == CreditLedgerReason.DEBIT_VOICE).findFirst();
        if (debit.isEmpty()) {
            return false;
        }
        boolean refunded = rows.stream().anyMatch(e -> e.getReason() == CreditLedgerReason.REFUND);
        if (refunded) {
            return false;
        }
        return debit.get().getCreatedAt().isAfter(clock.instant().minus(VOICE_CHARGE_WINDOW));
    }

    /** Claims one Sarvam call against this turn's voice debit, under the account lock; returns false at the per-turn cap. */
    @Transactional
    public boolean claimVoiceSpeak(String creatorUserId, String turnUlid) {
        lockAccount(creatorUserId);
        CreatorVoiceSpeak row =
                voiceSpeakRepository
                        .lockByCreatorUserIdAndTurnId(creatorUserId, turnUlid)
                        .orElseGet(() -> CreatorVoiceSpeak.newRow(creatorUserId, turnUlid));
        boolean claimed = row.tryIncrement(properties.getVoiceSpeaksPerTurn());
        if (claimed) {
            voiceSpeakRepository.save(row);
        }
        return claimed;
    }

    /**
     * K-15/round-2 review finding #3 — records, under the account lock, that a {@code speak} call
     * for this turn actually returned real, paid-for audio. Called from {@code
     * CreatorMeeraController#speak}'s success path, replacing the old process-local {@code
     * deliveredVoiceTurns} map. Idempotent (a second delivered attempt for the same turn is a
     * no-op) and a no-op if no claim row exists (should not happen — {@code speak} only reaches its
     * success path after {@link #claimVoiceSpeak} created one — but defensive rather than throwing).
     */
    @Transactional
    public void markVoiceDelivered(String creatorUserId, String turnUlid) {
        if (!properties.isEnabled()) {
            return;
        }
        accountRepository.findByIdForUpdate(creatorUserId);
        CreatorVoiceSpeak row =
                voiceSpeakRepository.lockByCreatorUserIdAndTurnId(creatorUserId, turnUlid).orElse(null);
        if (row == null || row.isDelivered()) {
            return;
        }
        row.markDelivered();
        voiceSpeakRepository.save(row);
    }

    /**
     * K-15/round-2 review finding #3 — the voice refund decision, made atomically under the
     * account lock instead of the controller reading a process-local map and calling {@link
     * #release} unconditionally. Replaces the vulnerable pattern where two parallel {@code speak}
     * calls for the same paid turn (one slow-and-succeeding, one fast-and-failing) could both reach
     * the refund call before either had recorded anything anywhere visible to the other — the fast
     * failure would refund the surcharge while the slow call still went on to deliver the paid
     * reply for free.
     *
     * <p>Refunds the {@code tts:} surcharge ONLY when ALL of the following hold, read from the SAME
     * row under the SAME lock {@link #release} itself takes:
     *
     * <ul>
     *   <li>a claim row exists for this turn (nothing to refund otherwise — {@link #claimVoiceSpeak}
     *       was never called, so no Sarvam attempt, paid or not, was ever made)
     *   <li>{@code delivered} is {@code false} — no attempt for this turn ever returned real audio
     *   <li>{@code refunded} is {@code false} — this turn's surcharge was never refunded before
     *   <li>{@code speakCount == 1} — exactly one claim was ever made for this turn. A count greater
     *       than one means another claim (concurrent or a later legitimate retry) exists that this
     *       method cannot rule out as still in flight and possibly about to deliver — rather than
     *       guess, it declines the refund. A count of one is unambiguous: no other claim can exist,
     *       so this failing attempt is exactly (and only) the whole story for this turn so far.
     * </ul>
     *
     * <p>On refunding, {@code refunded} is set {@code true} in the SAME transaction as the ledger
     * REFUND row, before this method returns — so a later claim for the same turn (e.g. a creator
     * retry) that also fails falls back to declining the refund instead of double-refunding.
     */
    @Transactional
    public void releaseVoiceIfUndelivered(String creatorUserId, String turnUlid) {
        if (!properties.isEnabled()) {
            return;
        }
        accountRepository.findByIdForUpdate(creatorUserId);
        CreatorVoiceSpeak row =
                voiceSpeakRepository.lockByCreatorUserIdAndTurnId(creatorUserId, turnUlid).orElse(null);
        if (row == null || row.isDelivered() || row.isRefunded() || row.getSpeakCount() != 1) {
            return;
        }
        row.markRefunded();
        voiceSpeakRepository.save(row);
        release(creatorUserId, turnUlid, ReleaseScope.VOICE_ONLY);
    }

    /** K-15 — called INSIDE write-back's own transaction, under the account lock; 409s if this turn was already released. */
    @Transactional
    public void assertTurnNotReleased(String creatorUserId, String turnUlid) {
        if (!properties.isEnabled()) {
            return;
        }
        accountRepository.findByIdForUpdate(creatorUserId);
        String ref = turnRef(turnUlid);
        boolean released =
                ledgerRepository.lockByCreatorUserIdAndReferenceIdIn(creatorUserId, List.of(ref)).stream()
                        .anyMatch(e -> e.getReason() == CreditLedgerReason.REFUND);
        if (released) {
            throw new ApiException(
                    "TURN_RELEASED", "This turn's credit was already released", HttpStatus.CONFLICT);
        }
    }

    /**
     * K-15 fix — called from {@code MeeraSessionService#doPersistAssistantWriteback}, immediately
     * after {@link #assertTurnNotReleased}, so it runs inside the SAME write-back physical
     * transaction and under the SAME account row lock. Writes a zero-delta {@link
     * CreditLedgerReason#WRITEBACK_MARKER} ledger row for this turn so a concurrent {@link
     * #release} — which cannot acquire this account lock until this write-back's transaction has
     * either committed or rolled back — can tell, from the SAME read it already takes under the
     * SAME lock, whether the reply was durably persisted. See that field's javadoc and {@link
     * #release}'s K-15 comment for the race this replaces (consulting {@code IdempotencyService}
     * instead had a window between the write-back's commit and a SEPARATE REQUIRES_NEW completion
     * write).
     *
     * <p>A no-op when the flag is off, when this turn was never actually charged (e.g. a BRAND
     * turn, or a CREATOR turn reached before {@code CreatorCreditService#charge} ran), or when
     * called twice for the same turn (idempotent — the account lock already serializes retries of
     * this method, but the explicit check keeps this safe to call from a replay path too).
     */
    @Transactional
    public void markWritebackPersisted(String creatorUserId, String turnUlid) {
        if (!properties.isEnabled()) {
            return;
        }
        accountRepository.findByIdForUpdate(creatorUserId);
        String ref = turnRef(turnUlid);
        List<CreatorCreditLedgerEntry> rows =
                ledgerRepository.lockByCreatorUserIdAndReferenceIdIn(creatorUserId, List.of(ref));
        boolean alreadyMarked =
                rows.stream().anyMatch(e -> e.getReason() == CreditLedgerReason.WRITEBACK_MARKER);
        if (alreadyMarked) {
            return;
        }
        CreatorCreditLedgerEntry debit =
                rows.stream().filter(e -> e.getReason() == CreditLedgerReason.DEBIT_TURN).findFirst().orElse(null);
        if (debit == null) {
            // Nothing was ever charged for this turn (flag was off at charge time, or this is a
            // BRAND turn that never goes through CreatorCreditService at all) — there is no debit
            // for release() to wrongly resurrect, so no marker is needed.
            return;
        }
        CreatorCreditGrant grant = grantRepository.lockById(debit.getGrantId()).orElse(null);
        int balanceAfter = grant == null ? debit.getBalanceAfter() : grant.getCreditsRemaining();
        ledgerRepository.save(
                CreatorCreditLedgerEntry.of(
                        Ulids.newUlid(),
                        creatorUserId,
                        debit.getGrantId(),
                        CreditLedgerReason.WRITEBACK_MARKER,
                        ref,
                        0,
                        balanceAfter,
                        debit.getIstDate(),
                        clock.instant()));
    }

    // ------------------------------------------------------------------
    // Free grants
    // ------------------------------------------------------------------

    /** SPEC.md §6 — idempotent; a no-op past the first successful grant. Flag-gated. */
    @Transactional
    public void grantWelcome(String creatorUserId) {
        if (!properties.isEnabled()) {
            return;
        }
        CreatorProfile profile = creatorProfileRepository.findByUserId(creatorUserId).orElse(null);
        if (profile == null) {
            return;
        }
        MetaOAuthToken token =
                metaOAuthTokenRepository
                        .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(profile.getId())
                        .orElse(null);
        if (token == null || token.getIgBusinessAccountId() == null || token.getIgBusinessAccountId().isBlank()) {
            // C4 — no (or no usable) Instagram connection: skip, the backstop retries on the next call.
            return;
        }

        CreatorCreditAccount account = lockAccount(creatorUserId);
        if (account.getWelcomeGrantedAt() != null) {
            return;
        }

        boolean claimed;
        try {
            claimed =
                    welcomeClaimWriter.insertClaims(
                            creatorUserId, token.getIgBusinessAccountId(), token.getMetaUserId());
        } catch (UnexpectedRollbackException racedAway) {
            // K-12 (see CreatorCreditWelcomeClaimWriter's javadoc): a genuine claim collision —
            // this IG/Meta id (or this creator) was already claimed, by this call or another one
            // — surfaces here as UnexpectedRollbackException, not as insertClaims' own `false`
            // return, because its REQUIRES_NEW commit fails even after it catches the underlying
            // DataIntegrityViolationException internally. Both outcomes mean the exact same thing:
            // no grant. insertClaims' own transaction is a separate physical transaction, so this
            // never touches OUR transaction (the account lock we are already holding stays valid).
            claimed = false;
        }
        if (!claimed) {
            return;
        }

        Instant now = clock.instant();
        String grantId = Ulids.newUlid();
        int amount = properties.getWelcomeGrant();
        grantRepository.save(
                CreatorCreditGrant.of(grantId, creatorUserId, CreditBucket.FREE_SIGNUP, amount, now, null, welcomeRef()));
        ledgerRepository.save(
                CreatorCreditLedgerEntry.of(
                        Ulids.newUlid(),
                        creatorUserId,
                        grantId,
                        CreditLedgerReason.GRANT_SIGNUP,
                        welcomeRef(),
                        amount,
                        amount,
                        LocalDate.now(clock.withZone(properties.zoneId())),
                        now));
        account.markWelcomeGranted(now);
        accountRepository.save(account);
    }

    /**
     * SPEC.md §6 — lazily materialises the monthly 15 for the current IST {@link YearMonth},
     * called from {@link #charge} and {@link #creditPurchase} while the account lock is already
     * held. Expires any older FREE_MONTHLY leftover first (never carried over).
     */
    private void materializeMonthly(CreatorCreditAccount account, Instant now) {
        ZoneId zone = properties.zoneId();
        YearMonth period = YearMonth.now(clock.withZone(zone));
        String periodStr = period.toString();
        if (periodStr.equals(account.getMonthlyPeriod())) {
            return;
        }

        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<CreatorCreditGrant> leftovers =
                grantRepository.lockWithRemainingByCreatorUserIdAndBucket(
                        account.getCreatorUserId(), CreditBucket.FREE_MONTHLY);
        for (CreatorCreditGrant grant : leftovers) {
            int remaining = grant.expireRemaining();
            if (remaining <= 0) {
                continue;
            }
            grantRepository.save(grant);
            ledgerRepository.save(
                    CreatorCreditLedgerEntry.of(
                            Ulids.newUlid(),
                            account.getCreatorUserId(),
                            grant.getId(),
                            CreditLedgerReason.EXPIRE,
                            grant.getSourceRef(),
                            -remaining,
                            grant.getCreditsRemaining(),
                            today,
                            now));
        }

        if (account.getWelcomeGrantedAt() != null) {
            YearMonth welcomeMonth = YearMonth.from(account.getWelcomeGrantedAt().atZone(zone));
            if (welcomeMonth.isBefore(period)) {
                String ref = monthlyRef(periodStr);
                Instant expiresAt = period.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
                String grantId = Ulids.newUlid();
                int amount = properties.getMonthlyGrant();
                grantRepository.save(
                        CreatorCreditGrant.of(
                                grantId, account.getCreatorUserId(), CreditBucket.FREE_MONTHLY, amount, now, expiresAt, ref));
                ledgerRepository.save(
                        CreatorCreditLedgerEntry.of(
                                Ulids.newUlid(),
                                account.getCreatorUserId(),
                                grantId,
                                CreditLedgerReason.GRANT_MONTHLY,
                                ref,
                                amount,
                                amount,
                                today,
                                now));
            }
        }
        account.setMonthlyPeriod(periodStr);
    }

    // ------------------------------------------------------------------
    // Balance projection (read-only — A7: performs zero writes)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BalanceView balance(String creatorUserId) {
        Instant now = clock.instant();
        ZoneId zone = properties.zoneId();
        CreatorCreditAccount account = accountRepository.findById(creatorUserId).orElse(null);
        List<CreatorCreditGrant> spendable = grantRepository.findSpendable(creatorUserId, now);

        int free =
                spendable.stream()
                        .filter(g -> g.getBucket() != CreditBucket.PAID)
                        .mapToInt(CreatorCreditGrant::getCreditsRemaining)
                        .sum();
        int paid =
                spendable.stream()
                        .filter(g -> g.getBucket() == CreditBucket.PAID)
                        .mapToInt(CreatorCreditGrant::getCreditsRemaining)
                        .sum();

        LocalDate today = LocalDate.now(clock.withZone(zone));
        int dailyUsed = (account != null && today.equals(account.getDailyDate())) ? account.getDailyUsed() : 0;
        Instant dailyResetsAt = today.plusDays(1).atStartOfDay(zone).toInstant();

        YearMonth currentPeriod = YearMonth.now(clock.withZone(zone));
        Instant nextMonthlyGrantAt = currentPeriod.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();

        boolean welcomeGranted = account != null && account.getWelcomeGrantedAt() != null;
        Instant welcomeGrantedAt = account != null ? account.getWelcomeGrantedAt() : null;
        boolean welcomeEligible = !welcomeGranted && isWelcomeEligible(creatorUserId);

        String monthlyPeriod = account != null ? account.getMonthlyPeriod() : null;
        boolean monthlyGrantedThisPeriod = currentPeriod.toString().equals(monthlyPeriod);

        boolean monthlyPending =
                welcomeGranted
                        && !monthlyGrantedThisPeriod
                        && account.getWelcomeGrantedAt() != null
                        && YearMonth.from(account.getWelcomeGrantedAt().atZone(zone)).isBefore(currentPeriod);

        List<BalanceView.PaidExpiring> paidExpiring =
                spendable.stream()
                        .filter(g -> g.getBucket() == CreditBucket.PAID && g.getExpiresAt() != null)
                        .sorted(Comparator.comparing(CreatorCreditGrant::getExpiresAt))
                        .map(g -> new BalanceView.PaidExpiring(g.getCreditsRemaining(), g.getExpiresAt()))
                        .toList();

        return new BalanceView(
                free + paid,
                free,
                paid,
                dailyUsed,
                properties.getDailyCap(),
                dailyResetsAt,
                nextMonthlyGrantAt,
                welcomeEligible,
                welcomeGranted,
                welcomeGrantedAt,
                monthlyPeriod,
                monthlyGrantedThisPeriod,
                welcomeEligible ? properties.getWelcomeGrant() : 0,
                monthlyPending ? properties.getMonthlyGrant() : 0,
                paidExpiring);
    }

    /**
     * Review finding #15 fix: {@code grantWelcome} permanently refuses a creator whose IG/Meta id
     * was already claimed by a DIFFERENT creator (K-12 anti-farming), so this projection must agree
     * — otherwise {@code balance()} shows "40 pending" forever for a grant that will never land.
     */
    private boolean isWelcomeEligible(String creatorUserId) {
        CreatorProfile profile = creatorProfileRepository.findByUserId(creatorUserId).orElse(null);
        if (profile == null) {
            return false;
        }
        MetaOAuthToken token =
                metaOAuthTokenRepository
                        .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(profile.getId())
                        .orElse(null);
        if (token == null || token.getIgBusinessAccountId() == null || token.getIgBusinessAccountId().isBlank()) {
            return false;
        }
        if (claimedByAnother(igClaimKey(token.getIgBusinessAccountId()), creatorUserId)) {
            return false;
        }
        if (token.getMetaUserId() != null
                && !token.getMetaUserId().isBlank()
                && claimedByAnother(metaClaimKey(token.getMetaUserId()), creatorUserId)) {
            return false;
        }
        return true;
    }

    private boolean claimedByAnother(String claimKey, String creatorUserId) {
        return welcomeClaimRepository
                .findById(claimKey)
                .map(c -> !c.getCreatorUserId().equals(creatorUserId))
                .orElse(false);
    }

    private static String igClaimKey(String igBusinessAccountId) {
        return "ig:" + igBusinessAccountId;
    }

    private static String metaClaimKey(String metaUserId) {
        return "meta:" + metaUserId;
    }

    // ------------------------------------------------------------------
    // Purchases
    // ------------------------------------------------------------------

    /**
     * SPEC.md §5.4 — called from {@code CreatorCreditOrderService#confirmPaid} INSIDE its own
     * locked transaction (the order row is already {@code findByIdForUpdate}'d there). NOT
     * flag-gated (K-24) — a purchase always credits, even with the flag off. Returns the new PAID
     * grant's id, so the caller can stamp it onto the order.
     */
    @Transactional
    public String creditPurchase(CreatorCreditOrder lockedOrder, Instant paidAt) {
        String creatorUserId = lockedOrder.getCreatorUserId();
        grantWelcome(creatorUserId); // backstop (§6) — no-op if the flag is off or already granted.

        CreatorCreditAccount account = lockAccount(creatorUserId);
        materializeMonthly(account, paidAt);

        Instant expiresAt = paidAt.plus(Duration.ofDays(properties.getPaidValidityDays()));
        String ref = orderRef(lockedOrder.getId());
        String grantId = Ulids.newUlid();
        int credits = lockedOrder.getCredits();
        grantRepository.save(
                CreatorCreditGrant.of(grantId, creatorUserId, CreditBucket.PAID, credits, paidAt, expiresAt, ref));
        ledgerRepository.save(
                CreatorCreditLedgerEntry.of(
                        Ulids.newUlid(),
                        creatorUserId,
                        grantId,
                        CreditLedgerReason.GRANT_PURCHASE,
                        ref,
                        credits,
                        credits,
                        LocalDate.now(clock.withZone(properties.zoneId())),
                        paidAt));
        accountRepository.save(account);
        return grantId;
    }

    // ------------------------------------------------------------------
    // Refusal templates (B22, en/hi — SPEC.md §9.1)
    // ------------------------------------------------------------------

    private static final String EN_EXHAUSTED =
            "You're out of credits, so I can't answer this one yet. Buy 60 credits to keep chatting.";
    private static final String HI_EXHAUSTED =
            "आपके क्रेडिट्स खत्म हो गए हैं, इसलिए मैं अभी इसका जवाब नहीं दे सकती। चैट जारी रखने के लिए 60 क्रेडिट्स लें।";
    private static final String EN_CAP =
            "You've used today's 30 credits. Your limit resets at midnight (IST). Credits you buy now"
                    + " stay in your balance for tomorrow.";
    private static final String HI_CAP =
            "आपने आज के 30 क्रेडिट्स इस्तेमाल कर लिए हैं। आपकी लिमिट आधी रात (IST) को रीसेट होगी। अभी लिए गए"
                    + " क्रेडिट्स कल के लिए आपके बैलेंस में रहेंगे।";
    private static final String EN_CAP_VOICE =
            "A voice reply needs 2 credits and you have 1 left today. Turn off voice replies to send"
                    + " this as text.";
    private static final String HI_CAP_VOICE =
            "वॉइस रिप्लाई में 2 क्रेडिट्स लगते हैं और आज आपका 1 बचा है। इसे टेक्स्ट में भेजने के लिए वॉइस"
                    + " रिप्लाई बंद करें।";

    /** SPEC.md §9.1/§8 — the server-authored 402/429 template in the creator's own language. */
    public static ApiException refusal(ChargeResult result, String creatorLanguage) {
        boolean hindi = creatorLanguage != null && creatorLanguage.toLowerCase(Locale.ROOT).startsWith("hi");
        boolean action = result.kind() == ChargeKind.SCRIPT || result.kind() == ChargeKind.PROFILE_REVIEW;
        if (action && result.outcome() == ChargeResult.Outcome.DAILY_CAP) {
            // A 3-credit button can be refused while a 1-credit message would still go through, so
            // say what it costs and that a normal message is still possible.
            String what = actionLabel(result.kind(), hindi);
            String message = hindi
                    ? what + " में " + result.cost() + " क्रेडिट्स लगते हैं और आज की लिमिट में इतने नहीं बचे। सामान्य"
                            + " मैसेज में 1 क्रेडिट लगता है, और आपकी लिमिट आधी रात (IST) को रीसेट होगी।"
                    : what + " uses " + result.cost() + " credits and today's limit doesn't have that many left."
                            + " A normal message uses 1 credit, and your limit resets at midnight (IST).";
            return new ApiException("CREATOR_DAILY_CAP_REACHED", message, HttpStatus.TOO_MANY_REQUESTS);
        }
        if (action && result.outcome() == ChargeResult.Outcome.INSUFFICIENT && result.balanceAfter() > 0) {
            String what = actionLabel(result.kind(), hindi);
            String message = hindi
                    ? what + " में " + result.cost() + " क्रेडिट्स लगते हैं और आपके पास " + result.balanceAfter()
                            + " हैं। इसके लिए 60 क्रेडिट्स लें, या 1 क्रेडिट में सामान्य मैसेज भेजें।"
                    : what + " uses " + result.cost() + " credits and you have " + result.balanceAfter()
                            + ". Buy 60 credits to use it, or send a normal message for 1 credit.";
            return new ApiException("CREATOR_CREDITS_EXHAUSTED", message, HttpStatus.PAYMENT_REQUIRED);
        }
        if (result.outcome() == ChargeResult.Outcome.DAILY_CAP) {
            boolean voiceCapVariant =
                    result.kind() == ChargeKind.VOICE_TURN && result.dailyUsedAfter() == 29;
            String message =
                    voiceCapVariant ? (hindi ? HI_CAP_VOICE : EN_CAP_VOICE) : (hindi ? HI_CAP : EN_CAP);
            return new ApiException("CREATOR_DAILY_CAP_REACHED", message, HttpStatus.TOO_MANY_REQUESTS);
        }
        return new ApiException(
                "CREATOR_CREDITS_EXHAUSTED", hindi ? HI_EXHAUSTED : EN_EXHAUSTED, HttpStatus.PAYMENT_REQUIRED);
    }

    private static String actionLabel(ChargeKind kind, boolean hindi) {
        if (kind == ChargeKind.SCRIPT) {
            return hindi ? "स्क्रिप्ट लिखने" : "A script";
        }
        return hindi ? "प्रोफ़ाइल रिव्यू" : "A profile review";
    }

    // ------------------------------------------------------------------

    private CreatorCreditAccount lockAccount(String creatorUserId) {
        // Connection-pool safety (found by CreatorCreditConcurrencyIntegrationTest on real MySQL):
        // ensureAccount is REQUIRES_NEW, i.e. a SECOND pooled connection while this transaction
        // already holds one. Calling it unconditionally made every charge hold two connections, so
        // as many concurrent charges as the pool size starved Hikari and froze the whole API. The
        // existence check therefore runs here first, in THIS transaction, as a plain non-locking
        // read (a FOR UPDATE on a missing PK would take a gap lock that blocks ensureAccount's own
        // insert from the other connection - the same self-deadlock as the application-history
        // stall). Steady state: one connection. Only a creator's very first touch pays for two.
        if (!accountRepository.existsById(creatorUserId)) {
            try {
                accountInitializer.ensureAccount(creatorUserId);
            } catch (UnexpectedRollbackException racedAway) {
            // K-02 (see CreatorCreditAccountInitializer's javadoc): a concurrent first-touch for
            // this same creator already created the row between our existsById check and our own
            // insert attempt. That row is exactly what we wanted to exist, so this is success,
            // not a failure — ensureAccount's REQUIRES_NEW transaction is its own, separate
            // physical transaction, so this never touches OUR transaction.
                log.debug("CreatorCreditService#lockAccount: account for {} already created concurrently", creatorUserId);
            }
        }
        return accountRepository
                .findByIdForUpdate(creatorUserId)
                .orElseThrow(() -> new IllegalStateException("creator credit account missing for " + creatorUserId));
    }

    private int totalRemaining(String creatorUserId, Instant now) {
        return grantRepository.lockSpendable(creatorUserId, now).stream()
                .mapToInt(CreatorCreditGrant::getCreditsRemaining)
                .sum();
    }
}
