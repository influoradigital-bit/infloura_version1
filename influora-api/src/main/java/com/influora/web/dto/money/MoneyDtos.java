package com.influora.web.dto.money;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Money-domain request/response records (escrow, contracts, milestones, wallet, payouts,
 * webhooks). Grouped in one file, matching the {@code CampaignDtos} convention.
 *
 * <p><b>[SEC: MF-1 / Guardrail 1]</b> {@link EscrowFundRequest} intentionally carries NO
 * {@code amount} field. The fund amount is always re-derived server-side from the campaign's
 * persisted budget / milestone row — never accepted from the caller.
 */
public final class MoneyDtos {

    private MoneyDtos() {}

    // ---------------------------------------------------------------------
    // Wallet
    // ---------------------------------------------------------------------

    /**
     * {@code runwayDays} is {@code null} whenever it cannot be honestly computed (no trailing
     * spend, e.g. a brand new or dormant wallet) — the frontend must render an explicit
     * "healthy" / "—" state and must NEVER substitute a fabricated placeholder number. See
     * {@code WalletService#computeRunwayDays} for the calc.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletBalanceResponse(
            String walletId,
            BigDecimal balance,
            BigDecimal escrowBalance,
            String currency,
            Integer runwayDays) {}

    /**
     * Wallet summary for the canonical {@code GET /wallet} (BACKEND-API-SPEC §33.5.5) — shared by
     * both the brand dashboard ({@code WalletService#getSummary}) and the creator wallet ({@code
     * WalletService#getSummaryForUser}), which derive the SAME three field names from DIFFERENT
     * sources because they answer different questions. Field names are the brand-client aliases:
     * {@code availableBalance} ≡ {@code balance}, {@code escrowLocked} ≡ {@code escrowBalance}.
     *
     * <ul>
     *   <li><b>Brand:</b> {@code escrowLocked} = live sum of the workspace's FUNDED {@code
     *       EscrowHold}s. {@code pendingPayouts} = sum of FUNDED milestone amounts across the
     *       workspace's collaborations — money already committed into escrow, not yet released to
     *       creators.
     *   <li><b>Creator</b> [F-0281/F-0336]: {@code escrowLocked} = sum of the creator's OWN FUNDED
     *       milestones (brand-funded, not yet approved/released to them — the analogous "committed
     *       but not released" figure, since a per-creator sum over {@code EscrowHold} would miss
     *       holds not yet bound to a collaboration). {@code pendingPayouts} = sum of the creator's
     *       {@code Payout} rows still in flight to their bank ({@code confirmedAt IS NULL}) — a
     *       withdrawal that has already been requested/queued but not yet gateway-confirmed. See
     *       {@code WalletService#getSummaryForUser}'s javadoc for the full three-bucket mapping and
     *       why {@code pendingPayouts} cannot mean the same thing here as it does for a brand.
     * </ul>
     *
     * {@code runwayDays} follows the same honest-null contract as {@link WalletBalanceResponse}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletSummaryResponse(
            BigDecimal availableBalance,
            BigDecimal escrowLocked,
            BigDecimal pendingPayouts,
            Integer runwayDays) {}

    /** Creator withdrawal request — amount is validated server-side against ledger balance. */
    public record CreatorWithdrawRequest(@NotNull @DecimalMin("1.00") BigDecimal amount) {}

    /**
     * Brand wallet top-up request (B0). {@code amount} IS caller-supplied here — unlike escrow's
     * fund amount (always re-derived from a campaign/milestone), a top-up is the brand depositing
     * their own chosen amount into their own wallet, not a movement of already-persisted platform
     * money. It is validated positive here and re-cross-checked against Razorpay's actual captured
     * amount at webhook time ({@code WalletTopUpService#confirmCredited}) before any credit is
     * applied — same discipline as {@code EscrowService#validateWebhookAmount}.
     *
     * <p>{@code pan}/{@code gstin} are optional, CFO-required for later TDS reconciliation (LOCKED
     * ruling item 6) — persisted onto {@code Workspace.pan}/{@code Workspace.gstin} (the existing
     * KYC field, fill-if-blank only; see {@code Workspace#applyTopUpTaxIds}). Patterns mirror
     * {@code OnboardingDtos.KycRequest} exactly.
     */
    public record WalletTopUpRequest(
            // [SEC: Kabir Option-1 audit P1 must-fix] Defense-in-depth only — this hardcoded
            // 1000000.00 MUST match influora.wallet.max-topup-amount (WalletProperties). Bean
            // validation runs before Spring config is available to a record component, so it
            // can't read the config value; the AUTHORITATIVE ceiling is the config-driven check
            // in WalletTopUpService#initiateTopUp, right after the positive-amount check.
            @NotNull @DecimalMin("1.00") @DecimalMax("1000000.00") @Digits(integer = 12, fraction = 2)
                    BigDecimal amount,
            @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]{1}$", message = "Invalid PAN format") String pan,
            @Pattern(
                            regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
                            message = "Invalid GSTIN format")
                    String gstin) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletTopUpResponse(
            String topUpId, BigDecimal amount, String currency, String razorpayOrderId, String status) {}

    /** Response for POST /wallet/withdraw (creator). {@code payoutId} is the ledger reference id. */
    public record CreatorWithdrawResponse(String payoutId) {}

    /**
     * Ledger row for GET /wallet/transactions. {@code type} is the {@code WalletTransactionType}
     * enum name; {@code amount} is always positive — use {@code direction} sign in UI if needed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalletTransactionRowResponse(
            String id,
            String type,
            String direction,
            BigDecimal amount,
            String currency,
            String description,
            String status,
            Instant createdAt,
            BigDecimal balanceAfter) {}

    /**
     * CR-77 — one row of the creator's real payout history, from the {@code payouts} table.
     *
     * <p>WHY THIS EXISTS RATHER THAN REUSING {@link WalletTransactionRowResponse}. The Payouts tab
     * used to derive itself by filtering wallet transactions to {@code WITHDRAWAL} debits. Those
     * debits are ledger entries and the ledger is append-only and correct — but they record that
     * money left the creator's Influora wallet, NOT that it arrived in their bank. When RazorpayX
     * reports {@code reversed}/{@code rejected}/{@code cancelled}, {@code
     * PayoutReconciliationService#reCreditReversedPayout} posts a SEPARATE compensating credit and
     * deliberately leaves the original debit standing. The derived tab therefore kept showing a
     * bounced payout as money paid out, with the offsetting credit invisible to it (a credit is
     * not a WITHDRAWAL). This row carries the gateway's own terminal state instead, so a failed
     * payout can render as failed.
     *
     * <p>FIELDS DELIBERATELY ABSENT — do not add them without the data existing first. CR-77 also
     * asks for a TDS / GST / platform-fee split, the brand and campaign name, and a bank UTR.
     * None of those are on {@code Payout}: TDS and GST are unimplemented platform-wide, the
     * platform fee is a separate ledger row rather than a payout attribute, {@code milestoneId} is
     * {@code null} for lump-sum wallet withdrawals (see {@code WalletService#doProcessWithdrawal})
     * so there is no campaign to name on this path, and a UTR would have to be parsed out of the
     * raw {@code webhookPayload} blob whose shape is not pinned by anything. Shipping any of them
     * as a nullable placeholder would put a field on the wire that the UI must then render as an
     * em-dash forever, which is how "not implemented" quietly becomes "looks broken". They are
     * omitted, and the UI says plainly that a detailed breakdown is not available yet.
     *
     * @param reference the RazorpayX payout id — the identifier a creator can quote to support
     *     when chasing a payment. Their own row only; never another creator's.
     * @param status the raw gateway status, passed through unmapped so the UI is never lying about
     *     a state the platform did not observe.
     * @param failed terminal-failure flag derived from {@code
     *     PayoutReconciliationService#FAILURE_STATUSES} — the single signal the old derived tab
     *     could not express at all.
     * @param settledAt when the money actually reached the bank; {@code null} while in flight, and
     *     {@code null} forever for a failed payout. Distinct from {@code requestedAt}.
     */
    public record CreatorPayoutRowResponse(
            String id,
            String reference,
            BigDecimal amount,
            String currency,
            String status,
            boolean failed,
            Instant requestedAt,
            Instant settledAt) {}

    // ---------------------------------------------------------------------
    // Escrow
    // ---------------------------------------------------------------------

    /**
     * [SEC: MF-1] No {@code amount} field by design — the server derives the fund amount from
     * the campaign's persisted budget (or the named milestone's amount). A caller-supplied
     * amount would be a Guardrail 1 violation and is not accepted anywhere in this DTO.
     */
    public record EscrowFundRequest(@NotBlank String campaignId, String milestoneId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EscrowFundResponse(
            String escrowHoldId,
            BigDecimal amount,
            String currency,
            String razorpayOrderId,
            EscrowStatus status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EscrowStatusResponse(
            String escrowHoldId,
            String workspaceId,
            String campaignId,
            String milestoneId,
            BigDecimal amount,
            String currency,
            EscrowStatus status,
            Instant fundedAt,
            Instant releasedAt) {}

    /**
     * [P-1' fix, BrandF.md §47a] Exactly one of {@code milestoneId} / {@code escrowHoldId} must be
     * supplied — the controller enforces this (bean validation can't express "exactly one of").
     * {@code milestoneId} keeps routing to {@code EscrowService#release} (the B5
     * release_condition-gated path); {@code escrowHoldId} is the new path for holds that have no
     * milestone at all ({@code EscrowService#releaseByHoldId}), e.g. escrow Meera funds at the
     * campaign level before any contract/milestone exists.
     */
    public record EscrowReleaseRequest(String milestoneId, String escrowHoldId) {}

    public record EscrowRefundRequest(@NotBlank String escrowHoldId, String reason) {}

    // ---------------------------------------------------------------------
    // Contracts + milestones
    // ---------------------------------------------------------------------

    /**
     * [F-0292] {@code brandSignerName}/{@code creatorSignerName} surface the typed full name
     * each party signed with, so the UI can show who actually signed instead of only a
     * timestamp -- the name is the value the e-sign copy calls legally binding, and previously
     * had no read path back to the client at all.
     *
     * <p><b>[F-0283, contract-terms-never-persisted]</b> {@code terms} is the free-text terms
     * captured (if any) at {@code ContractService#generate} time -- see {@link
     * ContractGenerateRequest#terms}. {@code null}/omitted (via {@code @JsonInclude.NON_NULL})
     * when no terms were supplied; never fabricated at this layer.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContractResponse(
            String id,
            String collaborationId,
            String workspaceId,
            int version,
            ContractStatus status,
            BigDecimal totalAmount,
            String currency,
            String pdfR2Key,
            Instant brandSignedAt,
            String brandSignerName,
            Instant creatorSignedAt,
            String creatorSignerName,
            LocalDate effectiveDate,
            LocalDate expirationDate,
            String terms,
            List<MilestoneDto> milestones,
            Instant createdAt,
            Instant updatedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MilestoneDto(
            String id,
            String contractId,
            String collaborationId,
            int sequenceNo,
            String description,
            BigDecimal amount,
            String currency,
            LocalDate dueDate,
            MilestoneStatus status,
            String escrowHoldId) {}

    /**
     * [SEC: Kabir MEDIUM-2, contract-flow-architecture-2026-07-23 review] {@code amount} was
     * previously unconstrained -- {@code generate} only checked the SUMMED total was positive, so
     * a negative milestone (e.g. {@code [500, -200]}) could slip through as long as the net was
     * positive. {@code @DecimalMin("0.01")} rejects zero/negative amounts at the request-validation
     * boundary (`@Valid` cascades via {@link ContractGenerateRequest#milestones}); {@code
     * ContractService#generate} additionally re-checks per-milestone defensively (same
     * belt-and-suspenders discipline as {@code EscrowService#adminSplitForDispute}'s own
     * boundary re-check) in case a caller ever bypasses bean validation.
     */
    public record MilestoneWriteRequest(
            int sequenceNo,
            String description,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            LocalDate dueDate) {}

    /**
     * [F-0283, contract-terms-never-persisted] {@code terms} is the free-text terms of the
     * agreement the two parties are agreeing to when they e-sign under the statutory binding
     * notice ("legally bound ... under the IT Act 2000", {@code contracts-and-deliverables.tsx}).
     * Before this fix nothing in this DTO, {@link com.influora.domain.entity.Contract}, or
     * {@link ContractResponse} captured, persisted or returned that text at all -- the {@code
     * terms} column on {@code contracts} held only a SHA-256 tamper hash of this very request
     * ({@code ContractService#sha256TamperHash}), not terms.
     *
     * <p>Optional and nullable by design. No default clause set has been authored or approved
     * for this platform (see {@code wiki/errors/BRAND-FRONTEND-UX-AUDIT-0817.md} -- {@code
     * F-0237} was exactly a fabricated 5-item clause list shipped as though someone had). A
     * caller that supplies text gets it persisted and echoed back verbatim; a caller that
     * supplies nothing gets a contract whose terms are honestly absent -- never a filler value
     * invented at this layer. What a contract's terms should say, who authors them, and whether
     * they are per-campaign or per-platform remains an open product/legal decision.
     *
     * <p>The 2-arg constructor below exists only so every pre-existing caller that built this
     * record positionally before {@code terms} existed keeps compiling unchanged.
     */
    /**
     * [F-0322, validation-cap-exceeds-column-bytes] {@code max = 16383} is a BYTE-derived cap,
     * not a round number: {@code contracts.terms_text} is MySQL {@code TEXT} -- 65,535 BYTES
     * (V20260817140000__contract_terms_text.sql) -- on a {@code utf8mb4} schema (up to 4
     * bytes/char), while {@code @Size} counts CHARACTERS. The previous cap of 20000 characters
     * allowed up to 80,000 bytes of 4-byte content (heavy emoji; unreachable with Latin or
     * Devanagari, which run 1-3 bytes/char) through bean validation straight into a column that
     * can only hold 65,535 -- contract generation 500'd on insert. 16383 = floor(65535 / 4), so
     * even worst-case 4-byte-per-character content at this cap is 65,532 bytes, inside the
     * column. The column itself is intentionally left at TEXT: widening it to MEDIUMTEXT would
     * require also changing {@link com.influora.domain.entity.Contract#getTermsText()}'s
     * {@code columnDefinition} to match (this project runs {@code ddl-auto=validate}, which
     * checks the entity mapping against the live schema at boot), for a limit contract terms
     * text realistically never needs -- lowering the cap to genuinely fit the existing column is
     * the smaller, safer fix.
     */
    public record ContractGenerateRequest(
            @NotBlank String collaborationId,
            @Size(max = 16383) String terms,
            @Valid List<MilestoneWriteRequest> milestones) {

        public ContractGenerateRequest(String collaborationId, List<MilestoneWriteRequest> milestones) {
            this(collaborationId, null, milestones);
        }
    }

    /**
     * Presigned, time-limited GET link for downloading the generated contract PDF. Requested
     * on-demand (not baked into {@link ContractResponse}) so the expiry is always fresh at click
     * time rather than a URL that may have already expired by the time the frontend renders it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContractPdfDownloadResponse(String downloadUrl, Instant expiresAt) {}

    /**
     * `role` is OPTIONAL (brand-feature-audit.md #2 fix). The FE's real call path
     * (`api.ts:1466` -> `signContract` -> {@code POST /contracts/:id/sign}) sends only
     * {@code {name, agreedAt}} -- no `role` -- for a brand principal self-signing their own
     * contract. Requiring `role` here 400'd every brand signature. {@link
     * com.influora.web.ContractController#sign} now server-derives the default from the
     * authenticated principal's own userType and only falls back to this field for the
     * (currently FE-unused) elevated-member relay path documented on {@link
     * com.influora.service.ContractService#recordSignature}.
     *
     * <p><b>[F-0292, signature-name-discarded-server-side]</b> {@code name} and {@code
     * agreedAt} were previously accepted from the wire and silently dropped -- this record had
     * no fields for them, so Jackson discarded both. The brand (and creator) e-sign UI gates its
     * Sign button on a non-empty typed full name and tells the user that typing it and clicking
     * "Sign Contract" is the legally binding act under the IT Act 2000
     * ({@code contracts-and-deliverables.tsx}); the value that copy names as binding must
     * actually be kept. {@code name} is now read and persisted (see {@link
     * com.influora.domain.entity.Contract#recordBrandSignature(String)}). {@code agreedAt} is
     * accepted for completeness but is NOT trusted as authoritative -- the server still stamps
     * its own {@code Instant.now()} for {@code brand_signed_at}/{@code creator_signed_at}; a
     * client clock is not evidence of when a signature actually happened.
     */
    public record ContractSignRequest(String role, String name, Instant agreedAt) {}

    // ---------------------------------------------------------------------
    // Payouts (Razorpay)
    // ---------------------------------------------------------------------

    public record PayoutRequest(@NotBlank String milestoneId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PayoutResponse(
            String payoutId, String milestoneId, BigDecimal amount, String currency, String status) {}

    // ---------------------------------------------------------------------
    // Webhooks (Razorpay)
    // ---------------------------------------------------------------------

    public record WebhookPayload(String event, String payloadJson, String signatureHeader) {}
}
