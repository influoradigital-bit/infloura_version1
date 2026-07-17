# Creator Payments & Wallet Specification

> **Owner:** Vikram (Backend) + Ananya (Frontend)  
> **Security:** Kabir  
> **QA:** Kavya

---

## 1. Payment Flow Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CREATOR PAYMENT FLOW                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  BRAND                           ESCROW                    CREATOR           │
│  ┌──────────┐               ┌──────────┐              ┌──────────┐         │
│  │ Signs    │ ────funds───→ │ Escrow   │              │ Creator  │         │
│  │ Contract │               │ Account  │              │ Wallet   │         │
│  └──────────┘               └──────────┘              └──────────┘         │
│                                    │                        ↑              │
│                                    │                        │              │
│  MILESTONE COMPLETION              │                        │              │
│  ┌──────────┐               ┌──────────┐                   │              │
│  │ Content  │ ──approves──→ │ Release  │ ─────────────────→│              │
│  │ Approved │               │ Milestone│                    │              │
│  └──────────┘               │ Payment  │                    │              │
│                              └──────────┘                    │              │
│                                                              │              │
│  WITHDRAWAL                                                  │              │
│  ┌──────────┐               ┌──────────┐              ┌──────────┐         │
│  │ Request  │ ←──────────── │ Creator  │ ←─withdraw── │ Creator  │         │
│  │ Bank/UPI │               │ Bank     │              │ Wallet   │         │
│  └──────────┘               └──────────┘              └──────────┘         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 1A. Platform Fee (Commission Model) — LOCKED by Priya (CTO), 2026-07-07

> **Business rule (Swapnil-approved):** Influora charges the **creator** a platform
> commission on every payout. **Default = 15%.** The rate is **admin-configurable**
> at three levels (global → plan → per-creator override), so ops can promote,
> discount, or negotiate enterprise rates without a code change.

### 1A.1 Where the fee is applied

The fee is deducted **at escrow-release time**, NOT at withdrawal. The creator's
wallet only ever shows their **net** earnings, so "available balance" is always
the real withdrawable amount (no nasty surprise at payout).

```
Brand funds escrow:            ₹10,000  (gross contract value)
        │
        ▼  milestone approved → release
Platform fee (15% default):   − ₹1,500  → Influora platform revenue wallet
        │
        ▼
Creator wallet (net):          + ₹8,500  (AVAILABLE)
```

**Rule:** the brand is charged/【holds in escrow】the **gross** amount; the creator
receives **gross × (1 − feeRate)**. The fee is Influora's take-rate revenue.

### 1A.2 Fee resolution order (most specific wins)

```
1. Per-creator override      (creator_profiles.platform_fee_override_bps)   ← if set
2. Plan/tier fee             (subscription_plan.platform_fee_bps)           ← else
3. Global default            (platform_fee_config.default_fee_bps = 1500)   ← else 15%
```

Stored in **basis points** (bps) to avoid float rounding: `1500 bps = 15.00%`.
Range guard: `0 ≤ fee ≤ 3000 bps` (0%–30%); anything above 30% requires a
Swapnil-signed override flag (`allow_high_fee = true`).

### 1A.3 Admin control surface

| Level | Field | Who can change | Effect |
|-------|-------|----------------|--------|
| Global | `platform_fee_config.default_fee_bps` | Admin (Swapnil/ops) | Applies to all creators with no override |
| Plan | `subscription_plan.platform_fee_bps` | Admin | e.g. "Pro creators pay 12%" |
| Creator | `creator_profiles.platform_fee_override_bps` | Admin only | Negotiated/enterprise rate, promo |
| Audit | `platform_fee_change_log` | System | Every change logged (who, old→new, when, reason) |

**Every fee change is versioned and audit-logged.** A fee change is **not retroactive** —
it applies only to escrow releases that happen *after* the change timestamp. Money
already released is never re-computed (Kabir requirement — no silent clawback).

---

## 2. Wallet Structure

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CREATOR WALLET                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  BALANCE TYPES:                                                              │
│                                                                              │
│  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐    │
│  │ AVAILABLE          │  │ PENDING            │  │ ESCROW             │    │
│  │ ₹45,000           │  │ ₹25,000            │  │ ₹80,000            │    │
│  │                    │  │                    │  │                    │    │
│  │ Can withdraw now   │  │ Processing         │  │ Locked in active   │    │
│  │                    │  │ (1-3 days)         │  │ contracts          │    │
│  └────────────────────┘  └────────────────────┘  └────────────────────┘    │
│                                                                              │
│  EARNINGS BREAKDOWN:                                                         │
│                                                                              │
│  Campaign Earnings      ₹1,20,000                                           │
│  Affiliate Earnings     ₹15,000                                             │
│  Bonuses                ₹5,000                                              │
│  ─────────────────────────────────                                          │
│  Total Lifetime         ₹1,40,000                                           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Database Schema

### 3.1 CreatorWallet Entity

```java
@Entity
@Table(name = "creator_wallets")
public class CreatorWallet {
    
    @Id
    private String id;
    
    @OneToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    // Balances (stored in paisa, displayed in rupees)
    private Long availableBalance;    // Can withdraw now
    private Long pendingBalance;       // Being processed
    private Long escrowBalance;        // Locked in contracts
    
    // Lifetime totals
    private Long totalEarned;
    private Long totalWithdrawn;
    private Long totalCampaignEarnings;
    private Long totalAffiliateEarnings;
    private Long totalBonuses;
    
    // Currency
    private String currency;  // INR
    
    // Withdrawal settings
    private BigDecimal minWithdrawalAmount;  // Default: 500
    
    // Timestamps
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastTransactionAt;
}
```

### 3.2 WalletTransaction Entity

```java
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "wallet_id")
    private CreatorWallet wallet;
    
    // Transaction type
    @Enumerated(EnumType.STRING)
    private TransactionType type;
    
    // Amount (always positive, type determines debit/credit)
    private Long amount;  // In paisa
    
    private String currency;
    
    // Balance after transaction
    private Long balanceAfter;
    
    // Reference
    @Enumerated(EnumType.STRING)
    private ReferenceType referenceType;  // CONTRACT, MILESTONE, AFFILIATE, WITHDRAWAL, ADJUSTMENT
    
    private String referenceId;  // Contract ID, Milestone ID, etc.
    
    // Status
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;
    
    // Description
    private String description;
    
    // Metadata
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> metadata;
    // { "campaignTitle": "...", "brandName": "...", "milestoneNumber": 1 }
    
    // Timestamps
    private Instant createdAt;
    private Instant processedAt;
    private Instant completedAt;
}

public enum TransactionType {
    CREDIT_ESCROW_RELEASE,    // Milestone payment released
    CREDIT_AFFILIATE,         // Affiliate commission
    CREDIT_BONUS,            // Platform bonus
    CREDIT_REFUND,           // Refund
    CREDIT_ADJUSTMENT,       // Manual adjustment
    
    DEBIT_WITHDRAWAL,        // Withdrawal to bank/UPI
    DEBIT_FEE,              // Platform fee
    DEBIT_ADJUSTMENT        // Manual adjustment
}

public enum TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REVERSED
}
```

### 3.3 WithdrawalRequest Entity

```java
@Entity
@Table(name = "withdrawal_requests")
public class WithdrawalRequest {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "wallet_id")
    private CreatorWallet wallet;
    
    @ManyToOne
    @JoinColumn(name = "payout_method_id")
    private PayoutMethod payoutMethod;
    
    // Amount
    private Long amount;  // In paisa
    private String currency;
    
    // Fees
    private Long platformFee;  // 0 for now
    private Long transferFee;  // Bank/UPI charges
    private Long netAmount;    // Amount - fees
    
    // Status
    @Enumerated(EnumType.STRING)
    private WithdrawalStatus status;
    
    // Processing
    private String payoutBatchId;      // Razorpay batch ID
    private String payoutTransactionId; // Razorpay payout ID
    
    // UTR/Reference
    private String utrNumber;          // Bank UTR
    private String failureReason;
    
    // Timestamps
    private Instant requestedAt;
    private Instant processedAt;
    private Instant completedAt;
    private Instant failedAt;
}

public enum WithdrawalStatus {
    PENDING,           // Just requested
    QUEUED,            // In payout queue
    PROCESSING,        // Being processed by payment gateway
    COMPLETED,         // Money sent
    FAILED,            // Transfer failed
    REVERSED,          // Reversed to wallet
    CANCELLED          // Cancelled by creator
}
```

### 3.4 PayoutMethod Entity

```java
@Entity
@Table(name = "payout_methods")
public class PayoutMethod {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    @Enumerated(EnumType.STRING)
    private PayoutType type;  // BANK_ACCOUNT, UPI
    
    // Bank account details (encrypted)
    @Column(columnDefinition = "BYTEA")
    private byte[] accountNumberEncrypted;
    
    private String accountNumberMasked;  // XXXX1234
    private String ifscCode;
    private String bankName;
    private String accountHolderName;
    
    // UPI details
    private String upiId;
    private String upiIdMasked;  // XXX@xxx
    
    // Verification
    private Boolean isVerified;
    private Instant verifiedAt;
    private String verificationMethod;  // PENNY_DROP, MANUAL
    
    // Status
    private Boolean isDefault;
    private Boolean isActive;
    
    // Razorpay fund account ID
    private String razorpayFundAccountId;
    
    private Instant createdAt;
    private Instant updatedAt;
}

public enum PayoutType {
    BANK_ACCOUNT,
    UPI
}
```

### 3.5 EscrowHold Entity

```java
@Entity
@Table(name = "escrow_holds")
public class EscrowHold {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "contract_id")
    private Contract contract;
    
    @ManyToOne
    @JoinColumn(name = "brand_wallet_id")
    private BrandWallet brandWallet;
    
    @ManyToOne
    @JoinColumn(name = "creator_wallet_id")
    private CreatorWallet creatorWallet;
    
    // Total amount in escrow
    private Long totalAmount;
    private Long releasedAmount;
    private Long remainingAmount;
    
    // Status
    @Enumerated(EnumType.STRING)
    private EscrowStatus status;  // HELD, PARTIALLY_RELEASED, FULLY_RELEASED, REFUNDED, DISPUTED
    
    // Release schedule
    @OneToMany(mappedBy = "escrowHold", cascade = CascadeType.ALL)
    private List<EscrowRelease> releases;
    
    private Instant createdAt;
    private Instant updatedAt;
}
```

### 3.5b Platform Fee Schema (NEW — migration Vxx)

```java
// Global default + range guard. Single row, admin-editable.
@Entity
@Table(name = "platform_fee_config")
public class PlatformFeeConfig {
    @Id
    private String id;                 // singleton: "default"
    private Integer defaultFeeBps;     // 1500 = 15.00%
    private Integer minFeeBps;         // 0
    private Integer maxFeeBps;         // 3000 (30%) — hard ceiling
    private Boolean allowHighFee;      // if true, per-creator override may exceed maxFeeBps
    private Instant updatedAt;
    private String updatedBy;          // admin user id
}
```

```java
// Immutable audit trail — every fee change, any level.
@Entity
@Table(name = "platform_fee_change_log")
public class PlatformFeeChangeLog {
    @Id
    private String id;
    private String scope;              // GLOBAL | PLAN | CREATOR
    private String scopeRefId;         // plan id / creator id / null for global
    private Integer oldFeeBps;
    private Integer newFeeBps;
    private String changedBy;          // admin user id
    private String reason;             // required free-text (e.g. "enterprise deal")
    private Instant changedAt;
}
```

Add to existing entities:

```java
// creator_profiles — nullable override (null = fall through to plan/global)
private Integer platformFeeOverrideBps;   // e.g. 1200 = negotiated 12%

// subscription_plan — nullable plan-level fee
private Integer platformFeeBps;           // e.g. Pro tier = 1200
```

Add to `EscrowRelease` / payout ledger so the fee is transparent per transaction:

```java
private Long grossAmount;      // released from escrow (brand-funded)
private Integer feeBpsApplied; // the rate used, frozen at release time
private Long platformFee;      // grossAmount * feeBps / 10000
private Long netAmount;        // grossAmount - platformFee → creator wallet
```

### 3.6 AffiliateEarning Entity

> **Note:** the same platform fee (§1A) applies to affiliate commission payouts —
> `feeBpsApplied` / `platformFee` / `netAmount` columns are added here too.

```java
@Entity
@Table(name = "affiliate_earnings")
public class AffiliateEarning {
    
    @Id
    private String id;
    
    @ManyToOne
    @JoinColumn(name = "creator_id")
    private CreatorProfile creator;
    
    @ManyToOne
    @JoinColumn(name = "referred_creator_id")
    private CreatorProfile referredCreator;
    
    // Source
    @Enumerated(EnumType.STRING)
    private AffiliateSource source;  // REFERRAL_SIGNUP, CAMPAIGN_COMMISSION
    
    // Campaign reference (if commission)
    private String campaignId;
    
    // Amount
    private Long amount;
    private Double commissionRate;  // e.g., 5% = 0.05
    private Long sourceAmount;      // Amount the commission is based on
    
    // Status
    @Enumerated(EnumType.STRING)
    private AffiliateEarningStatus status;  // PENDING, CREDITED, REVERSED
    
    // Lock period (for reversals)
    private LocalDate eligibleForCreditAt;  // After 30 days
    
    private Instant earnedAt;
    private Instant creditedAt;
}
```

---

## 4. API Endpoints

### 4.1 Get Wallet Summary

```
GET /api/v1/creator/wallet

Response:
{
    "wallet": {
        "id": "wal_xxx",
        "currency": "INR",
        "balances": {
            "available": 45000,
            "pending": 25000,
            "escrow": 80000,
            "total": 150000
        },
        "lifetime": {
            "totalEarned": 140000,
            "totalWithdrawn": 95000,
            "campaignEarnings": 120000,
            "affiliateEarnings": 15000,
            "bonuses": 5000
        },
        "lastTransaction": {
            "type": "CREDIT_ESCROW_RELEASE",
            "amount": 16500,
            "description": "Milestone 1 - Summer Fitness Challenge",
            "date": "2026-07-05T10:00:00Z"
        }
    },
    "pendingPayouts": [
        {
            "id": "wd_xxx",
            "amount": 20000,
            "status": "PROCESSING",
            "estimatedCompletion": "2026-07-08T18:00:00Z"
        }
    ],
    "activeEscrows": [
        {
            "contractId": "cont_xxx",
            "campaignTitle": "Summer Fitness Challenge",
            "totalAmount": 55000,
            "releasedAmount": 16500,
            "remainingAmount": 38500,
            "nextMilestone": "Brand Approval - ₹22,000"
        }
    ]
}
```

### 4.2 Get Transaction History

```
GET /api/v1/creator/wallet/transactions
Query Parameters:
  type          - Filter by type (credit, debit, all)
  reference     - Filter by reference type
  start_date    - From date
  end_date      - To date
  page, size

Response:
{
    "transactions": [
        {
            "id": "txn_xxx",
            "type": "CREDIT_ESCROW_RELEASE",
            "amount": 16500,
            "balanceAfter": 61500,
            "description": "Milestone 1 released - Summer Fitness Challenge",
            "reference": {
                "type": "MILESTONE",
                "id": "mile_xxx",
                "campaign": {
                    "id": "camp_xxx",
                    "title": "Summer Fitness Challenge"
                },
                "brand": {
                    "name": "HealthKart"
                }
            },
            "status": "COMPLETED",
            "date": "2026-07-05T10:00:00Z"
        }
    ],
    "summary": {
        "periodCredits": 55000,
        "periodDebits": 20000,
        "periodNet": 35000
    },
    "pagination": {...}
}
```

### 4.3 Get Payout Methods

```
GET /api/v1/creator/wallet/payout-methods

Response:
{
    "methods": [
        {
            "id": "pm_xxx",
            "type": "BANK_ACCOUNT",
            "accountNumberMasked": "XXXX1234",
            "ifscCode": "HDFC0001234",
            "bankName": "HDFC Bank",
            "accountHolderName": "Riya Sharma",
            "isVerified": true,
            "isDefault": true,
            "createdAt": "2026-06-15T10:00:00Z"
        },
        {
            "id": "pm_yyy",
            "type": "UPI",
            "upiIdMasked": "riya@okaxis",
            "isVerified": true,
            "isDefault": false,
            "createdAt": "2026-06-20T10:00:00Z"
        }
    ]
}
```

### 4.4 Add Bank Account

```
POST /api/v1/creator/wallet/payout-methods/bank
{
    "accountNumber": "1234567890123",
    "confirmAccountNumber": "1234567890123",
    "ifscCode": "HDFC0001234",
    "accountHolderName": "Riya Sharma"
}

Response:
{
    "id": "pm_xxx",
    "type": "BANK_ACCOUNT",
    "accountNumberMasked": "XXXX0123",
    "bankName": "HDFC Bank",
    "isVerified": false,
    "verificationStatus": "PENNY_DROP_INITIATED",
    "message": "A small amount will be credited. Please verify."
}
```

### 4.5 Add UPI

```
POST /api/v1/creator/wallet/payout-methods/upi
{
    "upiId": "riya@okaxis"
}

Response:
{
    "id": "pm_yyy",
    "type": "UPI",
    "upiIdMasked": "riya@okaxis",
    "isVerified": false,
    "verificationStatus": "PENDING_VERIFICATION",
    "message": "Please verify via UPI app."
}
```

### 4.6 Request Withdrawal

```
POST /api/v1/creator/wallet/withdraw
{
    "amount": 20000,
    "payoutMethodId": "pm_xxx"
}

Response:
{
    "withdrawalId": "wd_xxx",
    "amount": 20000,
    "fees": {
        "platformFee": 0,
        "transferFee": 0,
        "total": 0
    },
    "netAmount": 20000,
    "payoutMethod": {
        "type": "BANK_ACCOUNT",
        "accountNumberMasked": "XXXX0123",
        "bankName": "HDFC Bank"
    },
    "status": "QUEUED",
    "estimatedCompletion": "2026-07-09T18:00:00Z",
    "message": "Withdrawal request submitted. Expected in 1-3 business days."
}
```

### 4.7 Get Withdrawal History

```
GET /api/v1/creator/wallet/withdrawals
Query Parameters:
  status    - Filter by status
  page, size

Response:
{
    "withdrawals": [
        {
            "id": "wd_xxx",
            "amount": 20000,
            "netAmount": 20000,
            "payoutMethod": {...},
            "status": "COMPLETED",
            "utrNumber": "UTR12345678",
            "requestedAt": "2026-07-05T10:00:00Z",
            "completedAt": "2026-07-06T14:30:00Z"
        }
    ]
}
```

### 4.8 Get Escrow Details

```
GET /api/v1/creator/wallet/escrows

Response:
{
    "escrows": [
        {
            "id": "esc_xxx",
            "contract": {
                "id": "cont_xxx",
                "contractNumber": "INF-2026-001234"
            },
            "campaign": {
                "id": "camp_xxx",
                "title": "Summer Fitness Challenge",
                "brand": {...}
            },
            "totalAmount": 55000,
            "releases": [
                {
                    "milestone": "Content Submission",
                    "amount": 16500,
                    "percentage": 30,
                    "status": "RELEASED",
                    "releasedAt": "2026-07-05T10:00:00Z"
                },
                {
                    "milestone": "Brand Approval",
                    "amount": 22000,
                    "percentage": 40,
                    "status": "PENDING",
                    "estimatedReleaseDate": "2026-08-05"
                },
                {
                    "milestone": "Final Metrics",
                    "amount": 16500,
                    "percentage": 30,
                    "status": "PENDING",
                    "estimatedReleaseDate": "2026-08-15"
                }
            ],
            "status": "PARTIALLY_RELEASED"
        }
    ],
    "summary": {
        "totalInEscrow": 80000,
        "expectedNextRelease": {
            "amount": 22000,
            "date": "2026-08-05"
        }
    }
}
```

### 4.9 Get Affiliate Earnings

```
GET /api/v1/creator/wallet/affiliate-earnings
Query Parameters:
  status    - Filter by status
  page, size

Response:
{
    "earnings": [
        {
            "id": "aff_xxx",
            "source": "REFERRAL_SIGNUP",
            "referredCreator": {
                "id": "cr_yyy",
                "displayName": "Arjun Kumar",
                "username": "arjun_tech"
            },
            "amount": 500,
            "status": "CREDITED",
            "earnedAt": "2026-06-15T10:00:00Z",
            "creditedAt": "2026-07-15T10:00:00Z"
        },
        {
            "id": "aff_yyy",
            "source": "CAMPAIGN_COMMISSION",
            "referredCreator": {...},
            "campaign": {
                "id": "camp_xxx",
                "title": "Summer Fitness"
            },
            "amount": 2500,
            "commissionRate": 0.05,
            "sourceAmount": 50000,
            "status": "PENDING",
            "eligibleForCreditAt": "2026-08-15"
        }
    ],
    "summary": {
        "totalEarned": 15000,
        "totalPending": 2500,
        "totalReferrals": 8
    },
    "referralCode": "RIYA2026",
    "referralLink": "https://influora.com/join?ref=RIYA2026"
}
```

---

## 5. Backend Implementation

### 5.0 PlatformFeeService (NEW — resolves the admin-configurable rate)

```java
@Service
public class PlatformFeeService {

    private final PlatformFeeConfigRepository configRepo;
    private final CreatorProfileRepository creatorRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final PlatformFeeChangeLogRepository changeLogRepo;

    /** Resolve fee (in bps) for a creator: creator override → plan → global default. */
    public int resolveFeeBps(String creatorId) {
        var creator = creatorRepo.findById(creatorId).orElseThrow();

        // 1. Per-creator override (most specific)
        if (creator.getPlatformFeeOverrideBps() != null) {
            return creator.getPlatformFeeOverrideBps();
        }
        // 2. Plan/tier fee
        var plan = subscriptionRepo.findActivePlanForCreator(creatorId).orElse(null);
        if (plan != null && plan.getPlatformFeeBps() != null) {
            return plan.getPlatformFeeBps();
        }
        // 3. Global default (15%)
        return configRepo.getSingleton().getDefaultFeeBps();
    }

    /** Split a gross payout into (platformFee, net) using frozen bps. */
    public FeeSplit split(long grossPaisa, int feeBps) {
        long fee = Math.floorDiv(grossPaisa * feeBps, 10_000L);  // integer paisa, no float
        long net = grossPaisa - fee;
        return new FeeSplit(grossPaisa, feeBps, fee, net);
    }

    /** ADMIN ONLY — change a fee at any scope, always audit-logged. */
    @Transactional
    public void updateFee(FeeScope scope, String refId, int newBps,
                          String adminId, String reason) {
        var cfg = configRepo.getSingleton();
        int ceiling = cfg.getAllowHighFee() ? 10_000 : cfg.getMaxFeeBps();  // 30% unless flagged
        if (newBps < cfg.getMinFeeBps() || newBps > ceiling) {
            throw new ApiException(400, "FEE_OUT_OF_RANGE",
                "Fee must be between " + cfg.getMinFeeBps() + " and " + ceiling + " bps");
        }
        int oldBps = readCurrent(scope, refId);
        applyChange(scope, refId, newBps);   // write to config/plan/creator
        changeLogRepo.save(PlatformFeeChangeLog.builder()
            .id(Ulids.generate()).scope(scope.name()).scopeRefId(refId)
            .oldFeeBps(oldBps).newFeeBps(newBps)
            .changedBy(adminId).reason(reason).changedAt(Instant.now())
            .build());
    }

    public record FeeSplit(long gross, int feeBps, long platformFee, long net) {}
}
```

### 5.1 Wallet Service (fee applied at release)

```java
@Service
public class WalletService {
    
    private final WalletRepository walletRepo;
    private final TransactionRepository transactionRepo;
    private final NotificationService notificationService;
    private final PlatformFeeService platformFeeService;   // NEW
    private final PlatformRevenueService platformRevenue;   // NEW — Influora's take-rate wallet
    
    @Transactional
    public WalletTransaction creditMilestoneRelease(
        String creatorId,
        PaymentMilestone milestone,
        Contract contract
    ) {
        CreatorWallet wallet = walletRepo.findByCreatorId(creatorId)
            .orElseThrow(() -> new WalletNotFoundException(creatorId));
        
        Long grossPaisa = milestone.getAmount().multiply(new BigDecimal(100)).longValue();

        // ── PLATFORM FEE (§1A) — deduct 15% (or admin-configured) at release ──
        int feeBps = platformFeeService.resolveFeeBps(creatorId);
        var split = platformFeeService.split(grossPaisa, feeBps);
        Long netPaisa = split.net();          // → creator
        Long feePaisa = split.platformFee();  // → Influora
        
        // Credit creator's NET to available balance
        wallet.setAvailableBalance(wallet.getAvailableBalance() + netPaisa);
        // Debit full GROSS from escrow (brand funded the gross)
        wallet.setEscrowBalance(wallet.getEscrowBalance() - grossPaisa);
        // Totals reflect net earnings (what the creator actually keeps)
        wallet.setTotalEarned(wallet.getTotalEarned() + netPaisa);
        wallet.setTotalCampaignEarnings(wallet.getTotalCampaignEarnings() + netPaisa);
        wallet.setLastTransactionAt(Instant.now());
        wallet.setUpdatedAt(Instant.now());
        walletRepo.save(wallet);

        // Route the fee to Influora's platform revenue ledger (double-entry)
        platformRevenue.recordFee(feePaisa, feeBps, creatorId, milestone.getId(),
            "Platform commission — " + contract.getCampaign().getTitle());
        
        // Create creator transaction record (fee transparent in metadata)
        WalletTransaction transaction = WalletTransaction.builder()
            .id(Ulids.generate())
            .wallet(wallet)
            .type(TransactionType.CREDIT_ESCROW_RELEASE)
            .amount(netPaisa)                 // NET credited
            .currency("INR")
            .balanceAfter(wallet.getAvailableBalance())
            .referenceType(ReferenceType.MILESTONE)
            .referenceId(milestone.getId())
            .description(String.format("Milestone %d released - %s (net of %s%% fee)",
                milestone.getSequenceNumber(),
                contract.getCampaign().getTitle(),
                feeBps / 100.0))
            .metadata(Map.of(
                "campaignId", contract.getCampaign().getId(),
                "campaignTitle", contract.getCampaign().getTitle(),
                "brandName", contract.getBrand().getName(),
                "milestoneNumber", milestone.getSequenceNumber(),
                "milestoneName", milestone.getName(),
                "grossAmount", grossPaisa,
                "platformFeeBps", feeBps,
                "platformFee", feePaisa,
                "netAmount", netPaisa
            ))
            .status(TransactionStatus.COMPLETED)
            .createdAt(Instant.now())
            .completedAt(Instant.now())
            .build();
        
        transactionRepo.save(transaction);
        
        // Notify creator with NET amount (what actually landed)
        notificationService.notifyCreator(creatorId,
            NotificationType.PAYMENT_RECEIVED,
            Map.of(
                "grossAmount", milestone.getAmount(),
                "netAmount", new BigDecimal(netPaisa).divide(new BigDecimal(100)),
                "feePercent", feeBps / 100.0,
                "campaignTitle", contract.getCampaign().getTitle(),
                "milestoneName", milestone.getName()
            )
        );
        
        return transaction;
    }
    
    @Transactional
    public WithdrawalRequest requestWithdrawal(
        String creatorId,
        WithdrawalRequest request
    ) {
        CreatorWallet wallet = walletRepo.findByCreatorId(creatorId)
            .orElseThrow(() -> new WalletNotFoundException(creatorId));
        
        Long amountInPaisa = new BigDecimal(request.getAmount()).multiply(new BigDecimal(100)).longValue();
        
        // Validate amount
        if (amountInPaisa < wallet.getMinWithdrawalAmount().multiply(new BigDecimal(100)).longValue()) {
            throw new MinimumWithdrawalException(wallet.getMinWithdrawalAmount());
        }
        
        if (amountInPaisa > wallet.getAvailableBalance()) {
            throw new InsufficientBalanceException(wallet.getAvailableBalance() / 100.0);
        }
        
        // Validate payout method
        PayoutMethod payoutMethod = payoutMethodRepo.findByIdAndCreatorId(
            request.getPayoutMethodId(), creatorId)
            .filter(PayoutMethod::getIsVerified)
            .orElseThrow(() -> new InvalidPayoutMethodException());
        
        // Move from available to pending
        wallet.setAvailableBalance(wallet.getAvailableBalance() - amountInPaisa);
        wallet.setPendingBalance(wallet.getPendingBalance() + amountInPaisa);
        wallet.setUpdatedAt(Instant.now());
        walletRepo.save(wallet);
        
        // Create withdrawal request
        WithdrawalRequest withdrawal = WithdrawalRequest.builder()
            .id(Ulids.generate())
            .wallet(wallet)
            .payoutMethod(payoutMethod)
            .amount(amountInPaisa)
            .currency("INR")
            .platformFee(0L)
            .transferFee(0L)
            .netAmount(amountInPaisa)
            .status(WithdrawalStatus.QUEUED)
            .requestedAt(Instant.now())
            .build();
        
        withdrawalRepo.save(withdrawal);
        
        // Create debit transaction
        WalletTransaction transaction = WalletTransaction.builder()
            .id(Ulids.generate())
            .wallet(wallet)
            .type(TransactionType.DEBIT_WITHDRAWAL)
            .amount(amountInPaisa)
            .currency("INR")
            .balanceAfter(wallet.getAvailableBalance())
            .referenceType(ReferenceType.WITHDRAWAL)
            .referenceId(withdrawal.getId())
            .description("Withdrawal to " + payoutMethod.getAccountNumberMasked())
            .status(TransactionStatus.PENDING)
            .createdAt(Instant.now())
            .build();
        
        transactionRepo.save(transaction);
        
        // Queue for processing
        payoutQueueService.enqueue(withdrawal);
        
        return withdrawal;
    }
}
```

### 5.2 Payout Processing Service

```java
@Service
public class PayoutProcessingService {
    
    private final RazorpayPayoutClient razorpayClient;
    private final WithdrawalRepository withdrawalRepo;
    
    @Scheduled(fixedRate = 300000)  // Every 5 minutes
    public void processPayoutQueue() {
        List<WithdrawalRequest> queued = withdrawalRepo
            .findByStatusOrderByRequestedAtAsc(WithdrawalStatus.QUEUED);
        
        // Batch process (Razorpay supports batch payouts)
        if (queued.isEmpty()) return;
        
        try {
            // Create Razorpay batch payout
            BatchPayoutRequest batchRequest = BatchPayoutRequest.builder()
                .account_number(razorpayAccountNumber)
                .payouts(queued.stream().map(this::toBatchPayoutItem).toList())
                .build();
            
            BatchPayoutResponse response = razorpayClient.createBatchPayout(batchRequest);
            
            // Update withdrawal records
            for (int i = 0; i < queued.size(); i++) {
                WithdrawalRequest withdrawal = queued.get(i);
                PayoutItem payout = response.getItems().get(i);
                
                withdrawal.setPayoutBatchId(response.getId());
                withdrawal.setPayoutTransactionId(payout.getId());
                withdrawal.setStatus(WithdrawalStatus.PROCESSING);
                withdrawal.setProcessedAt(Instant.now());
                
                withdrawalRepo.save(withdrawal);
            }
        } catch (Exception e) {
            log.error("Batch payout failed", e);
            // Mark all as failed for retry
            queued.forEach(w -> {
                w.setStatus(WithdrawalStatus.FAILED);
                w.setFailureReason(e.getMessage());
                withdrawalRepo.save(w);
            });
        }
    }
    
    // Webhook handler for payout status updates
    @Transactional
    public void handlePayoutWebhook(RazorpayWebhookEvent event) {
        String payoutId = event.getPayload().getPayout().getId();
        String status = event.getPayload().getPayout().getStatus();
        
        WithdrawalRequest withdrawal = withdrawalRepo
            .findByPayoutTransactionId(payoutId)
            .orElseThrow();
        
        switch (status) {
            case "processed" -> {
                withdrawal.setStatus(WithdrawalStatus.COMPLETED);
                withdrawal.setUtrNumber(event.getPayload().getPayout().getUtr());
                withdrawal.setCompletedAt(Instant.now());
                
                // Update wallet
                CreatorWallet wallet = withdrawal.getWallet();
                wallet.setPendingBalance(wallet.getPendingBalance() - withdrawal.getAmount());
                wallet.setTotalWithdrawn(wallet.getTotalWithdrawn() + withdrawal.getAmount());
                walletRepo.save(wallet);
                
                // Update transaction
                transactionRepo.markCompleted(withdrawal.getId());
                
                // Notify creator
                notificationService.notifyCreator(
                    wallet.getCreator().getId(),
                    NotificationType.WITHDRAWAL_COMPLETED,
                    Map.of("amount", withdrawal.getNetAmount() / 100.0)
                );
            }
            case "failed", "cancelled", "reversed" -> {
                withdrawal.setStatus(WithdrawalStatus.FAILED);
                withdrawal.setFailureReason(event.getPayload().getPayout().getFailureReason());
                withdrawal.setFailedAt(Instant.now());
                
                // Reverse to available balance
                CreatorWallet wallet = withdrawal.getWallet();
                wallet.setPendingBalance(wallet.getPendingBalance() - withdrawal.getAmount());
                wallet.setAvailableBalance(wallet.getAvailableBalance() + withdrawal.getAmount());
                walletRepo.save(wallet);
                
                // Update transaction
                transactionRepo.markFailed(withdrawal.getId());
                
                // Notify creator
                notificationService.notifyCreator(
                    wallet.getCreator().getId(),
                    NotificationType.WITHDRAWAL_FAILED,
                    Map.of("reason", withdrawal.getFailureReason())
                );
            }
        }
        
        withdrawalRepo.save(withdrawal);
    }
}
```

### 5.3 Bank Account Verification

```java
@Service
public class BankVerificationService {
    
    private final RazorpayClient razorpayClient;
    private final PayoutMethodRepository methodRepo;
    
    @Transactional
    public PayoutMethod addBankAccount(String creatorId, AddBankAccountRequest request) {
        // Validate IFSC
        BankDetails bankDetails = ifscService.lookup(request.getIfscCode());
        if (bankDetails == null) {
            throw new InvalidIfscException(request.getIfscCode());
        }
        
        // Encrypt account number
        byte[] encryptedAccountNumber = encryptionService.encrypt(request.getAccountNumber());
        
        // Create payout method
        PayoutMethod method = PayoutMethod.builder()
            .id(Ulids.generate())
            .creatorId(creatorId)
            .type(PayoutType.BANK_ACCOUNT)
            .accountNumberEncrypted(encryptedAccountNumber)
            .accountNumberMasked(maskAccountNumber(request.getAccountNumber()))
            .ifscCode(request.getIfscCode())
            .bankName(bankDetails.getBankName())
            .accountHolderName(request.getAccountHolderName())
            .isVerified(false)
            .isActive(true)
            .createdAt(Instant.now())
            .build();
        
        methodRepo.save(method);
        
        // Create Razorpay fund account
        FundAccount fundAccount = razorpayClient.createFundAccount(
            FundAccountRequest.builder()
                .contact_id(getOrCreateRazorpayContact(creatorId))
                .account_type("bank_account")
                .bank_account(BankAccountDetails.builder()
                    .name(request.getAccountHolderName())
                    .ifsc(request.getIfscCode())
                    .account_number(request.getAccountNumber())
                    .build())
                .build()
        );
        
        method.setRazorpayFundAccountId(fundAccount.getId());
        
        // Initiate penny drop verification
        initiatePennyDrop(method);
        
        methodRepo.save(method);
        
        return method;
    }
    
    private void initiatePennyDrop(PayoutMethod method) {
        // Create a Rs. 1 payout for verification
        PayoutRequest payout = PayoutRequest.builder()
            .account_number(razorpayAccountNumber)
            .fund_account_id(method.getRazorpayFundAccountId())
            .amount(100)  // 1 rupee in paisa
            .currency("INR")
            .mode("IMPS")
            .purpose("verification")
            .reference_id("verify_" + method.getId())
            .notes(Map.of("type", "penny_drop_verification"))
            .build();
        
        razorpayClient.createPayout(payout);
        
        method.setVerificationMethod("PENNY_DROP");
    }
    
    // Called when penny drop succeeds
    public void confirmVerification(String methodId) {
        PayoutMethod method = methodRepo.findById(methodId).orElseThrow();
        method.setIsVerified(true);
        method.setVerifiedAt(Instant.now());
        
        // If no default, make this default
        if (!methodRepo.existsByCreatorIdAndIsDefaultTrue(method.getCreatorId())) {
            method.setIsDefault(true);
        }
        
        methodRepo.save(method);
    }
}
```

---

## 6. Frontend Components (Ananya)

### 6.1 Wallet Dashboard

```tsx
export function WalletDashboard() {
  const { data: wallet, isLoading } = useWallet();
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  
  if (isLoading) return <WalletSkeleton />;
  
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Wallet</h1>
          <p className="text-muted-foreground">Manage your earnings and payouts</p>
        </div>
        
        <Button onClick={() => setWithdrawOpen(true)}>
          <Wallet className="h-4 w-4 mr-2" />
          Withdraw
        </Button>
      </div>
      
      {/* Balance Cards */}
      <div className="grid grid-cols-3 gap-4">
        <Card>
          <CardContent className="pt-6">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">Available Balance</p>
                <p className="text-3xl font-bold">{formatCurrency(wallet.balances.available)}</p>
              </div>
              <div className="h-12 w-12 rounded-full bg-green-100 flex items-center justify-center">
                <CheckCircle className="h-6 w-6 text-green-600" />
              </div>
            </div>
            <p className="text-xs text-muted-foreground mt-2">
              Ready to withdraw
            </p>
          </CardContent>
        </Card>
        
        <Card>
          <CardContent className="pt-6">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">Pending</p>
                <p className="text-3xl font-bold">{formatCurrency(wallet.balances.pending)}</p>
              </div>
              <div className="h-12 w-12 rounded-full bg-yellow-100 flex items-center justify-center">
                <Clock className="h-6 w-6 text-yellow-600" />
              </div>
            </div>
            <p className="text-xs text-muted-foreground mt-2">
              Processing withdrawals
            </p>
          </CardContent>
        </Card>
        
        <Card>
          <CardContent className="pt-6">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-muted-foreground">In Escrow</p>
                <p className="text-3xl font-bold">{formatCurrency(wallet.balances.escrow)}</p>
              </div>
              <div className="h-12 w-12 rounded-full bg-blue-100 flex items-center justify-center">
                <Lock className="h-6 w-6 text-blue-600" />
              </div>
            </div>
            <p className="text-xs text-muted-foreground mt-2">
              Locked in active contracts
            </p>
          </CardContent>
        </Card>
      </div>
      
      {/* Pending Payouts */}
      {wallet.pendingPayouts.length > 0 && (
        <Alert>
          <Clock className="h-4 w-4" />
          <AlertTitle>Pending Withdrawals</AlertTitle>
          <AlertDescription>
            {wallet.pendingPayouts.map((payout) => (
              <div key={payout.id} className="flex items-center justify-between mt-2">
                <span>{formatCurrency(payout.amount)} - {payout.status}</span>
                <span className="text-xs">
                  Est. completion: {formatDate(payout.estimatedCompletion)}
                </span>
              </div>
            ))}
          </AlertDescription>
        </Alert>
      )}
      
      {/* Active Escrows */}
      <Card>
        <CardHeader>
          <CardTitle>Active Contracts</CardTitle>
        </CardHeader>
        <CardContent>
          {wallet.activeEscrows.length === 0 ? (
            <p className="text-muted-foreground text-center py-4">
              No active contracts
            </p>
          ) : (
            <div className="space-y-4">
              {wallet.activeEscrows.map((escrow) => (
                <EscrowCard key={escrow.contractId} escrow={escrow} />
              ))}
            </div>
          )}
        </CardContent>
      </Card>
      
      {/* Lifetime Stats */}
      <Card>
        <CardHeader>
          <CardTitle>Lifetime Earnings</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-4 gap-4 text-center">
            <div>
              <p className="text-2xl font-bold">{formatCurrency(wallet.lifetime.totalEarned)}</p>
              <p className="text-sm text-muted-foreground">Total Earned</p>
            </div>
            <div>
              <p className="text-2xl font-bold">{formatCurrency(wallet.lifetime.campaignEarnings)}</p>
              <p className="text-sm text-muted-foreground">Campaign Earnings</p>
            </div>
            <div>
              <p className="text-2xl font-bold">{formatCurrency(wallet.lifetime.affiliateEarnings)}</p>
              <p className="text-sm text-muted-foreground">Affiliate Earnings</p>
            </div>
            <div>
              <p className="text-2xl font-bold">{formatCurrency(wallet.lifetime.totalWithdrawn)}</p>
              <p className="text-sm text-muted-foreground">Total Withdrawn</p>
            </div>
          </div>
        </CardContent>
      </Card>
      
      {/* Withdraw Modal */}
      <WithdrawModal
        open={withdrawOpen}
        onClose={() => setWithdrawOpen(false)}
        availableBalance={wallet.balances.available}
      />
    </div>
  );
}
```

### 6.2 Withdraw Modal

```tsx
export function WithdrawModal({
  open,
  onClose,
  availableBalance,
}: {
  open: boolean;
  onClose: () => void;
  availableBalance: number;
}) {
  const [amount, setAmount] = useState('');
  const [methodId, setMethodId] = useState('');
  const { data: methods } = usePayoutMethods();
  
  const { mutate: withdraw, isLoading } = useMutation({
    mutationFn: (data: WithdrawRequest) => requestWithdrawal(data),
    onSuccess: () => {
      toast.success('Withdrawal request submitted!');
      onClose();
    },
  });
  
  const selectedMethod = methods?.find(m => m.id === methodId);
  
  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Withdraw Funds</DialogTitle>
          <DialogDescription>
            Available balance: {formatCurrency(availableBalance)}
          </DialogDescription>
        </DialogHeader>
        
        <div className="space-y-4">
          {/* Amount */}
          <div>
            <Label>Amount</Label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">
                
              </span>
              <Input
                type="number"
                className="pl-6"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="Enter amount"
                max={availableBalance}
              />
            </div>
            <div className="flex justify-between mt-1">
              <p className="text-xs text-muted-foreground">Min: Rs. 500</p>
              <Button
                variant="link"
                size="sm"
                className="text-xs p-0 h-auto"
                onClick={() => setAmount(availableBalance.toString())}
              >
                Withdraw All
              </Button>
            </div>
          </div>
          
          {/* Payout Method */}
          <div>
            <Label>Payout Method</Label>
            <RadioGroup value={methodId} onValueChange={setMethodId} className="mt-2">
              {methods?.map((method) => (
                <label
                  key={method.id}
                  className={cn(
                    "flex items-center gap-3 p-3 border rounded-lg cursor-pointer",
                    methodId === method.id && "border-primary bg-primary/5"
                  )}
                >
                  <RadioGroupItem value={method.id} />
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      {method.type === 'BANK_ACCOUNT' ? (
                        <Building className="h-4 w-4" />
                      ) : (
                        <Smartphone className="h-4 w-4" />
                      )}
                      <span className="font-medium">
                        {method.type === 'BANK_ACCOUNT' ? method.bankName : 'UPI'}
                      </span>
                      {method.isDefault && (
                        <Badge variant="secondary" className="text-xs">Default</Badge>
                      )}
                    </div>
                    <p className="text-sm text-muted-foreground">
                      {method.type === 'BANK_ACCOUNT' 
                        ? `A/c ${method.accountNumberMasked}`
                        : method.upiIdMasked}
                    </p>
                  </div>
                </label>
              ))}
            </RadioGroup>
            
            <Button variant="outline" size="sm" className="mt-2">
              <Plus className="h-4 w-4 mr-2" />
              Add New
            </Button>
          </div>
          
          {/* Summary */}
          {amount && selectedMethod && (
            <div className="p-4 bg-muted rounded-lg space-y-2">
              <div className="flex justify-between text-sm">
                <span>Withdrawal Amount</span>
                <span>{formatCurrency(parseInt(amount))}</span>
              </div>
              <div className="flex justify-between text-sm text-muted-foreground">
                <span>Platform Fee</span>
                {/* Fee already deducted at escrow release (§1A), NOT at withdrawal.
                    Available balance is already net. So withdrawal fee = 0. */}
                <span>Rs. 0 <span className="text-xs">(already deducted)</span></span>
              </div>
              <div className="flex justify-between text-sm text-muted-foreground">
                <span>Transfer Fee</span>
                <span>Rs. 0</span>
              </div>
              <Separator />
              <div className="flex justify-between font-semibold">
                <span>You'll Receive</span>
                <span>{formatCurrency(parseInt(amount))}</span>
              </div>
              <p className="text-xs text-muted-foreground">
                Expected in 1-3 business days
              </p>
            </div>
          )}
        </div>
        
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button
            onClick={() => withdraw({ amount: parseInt(amount), payoutMethodId: methodId })}
            disabled={!amount || !methodId || parseInt(amount) < 500 || isLoading}
          >
            {isLoading ? <Spinner /> : 'Withdraw'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

---

## 7. Security Requirements (Kabir)

### 7.1 Bank Account Security
- **Encryption:** Account numbers encrypted with AES-256-GCM
- **Masking:** Only show last 4 digits in UI
- **Verification:** Penny drop verification required

### 7.2 Transaction Security
- **Idempotency:** Prevent duplicate transactions
- **Audit Trail:** Complete log of all transactions
- **Double-Entry:** All transactions follow double-entry bookkeeping

### 7.3 Withdrawal Security
- **Rate Limiting:** Max 3 withdrawals per day
- **Amount Limits:** Max Rs. 1,00,000 per withdrawal initially
- **Cooling Period:** 24h delay for new payout methods

### 7.4 Webhook Security
- **Signature Verification:** Validate Razorpay webhook signatures
- **Replay Protection:** Check for duplicate webhook IDs
- **Timeout:** Process webhooks within SLA

### 7.5 Platform Fee Security (NEW)
- **Admin-only mutation:** Only users with `ADMIN` role may change any fee level
  (`@PreAuthorize("hasRole('ADMIN')")`). Creators/brands can READ their applicable
  fee but never change it.
- **Range guard:** fee clamped to `[minFeeBps, maxFeeBps]` (0–30% default); exceeding
  30% requires `allow_high_fee` flag + Swapnil sign-off.
- **Frozen at release:** the `feeBpsApplied` is written onto the transaction at
  release time and never recomputed — no retroactive clawback (audit integrity).
- **Full audit trail:** every change writes `platform_fee_change_log` (who, old→new,
  reason, timestamp). Reason is mandatory.
- **Fee routes to platform ledger:** platform commission is double-entry booked to
  Influora's revenue wallet — never silently dropped.

---

## 7A. Admin Fee Control API (NEW)

> All endpoints require `ADMIN` role. Creator/brand read-only endpoint included.

```
GET  /api/v1/admin/platform-fee/config
→ { defaultFeeBps: 1500, minFeeBps: 0, maxFeeBps: 3000, allowHighFee: false }

PUT  /api/v1/admin/platform-fee/config
→ { defaultFeeBps: 1200, reason: "Q3 creator-growth promo" }
→ Applies new global default (12%). Audit-logged.

PUT  /api/v1/admin/platform-fee/plan/{planId}
→ { feeBps: 1000, reason: "Pro tier perk" }

PUT  /api/v1/admin/platform-fee/creator/{creatorId}
→ { feeBps: 800, reason: "Enterprise creator, negotiated 8%" }
→ Set null to remove override and fall back to plan/global.

GET  /api/v1/admin/platform-fee/change-log?scope=CREATOR&refId=cr_xxx
→ Full audit history of fee changes.

# Read-only, for the creator to see their own applicable rate (transparency):
GET  /api/v1/creator/platform-fee
→ { feeBps: 1500, feePercent: 15.0, source: "GLOBAL_DEFAULT" }
```

```java
@RestController
@RequestMapping("/api/v1/admin/platform-fee")
@PreAuthorize("hasRole('ADMIN')")
public class PlatformFeeAdminController {

    private final PlatformFeeService feeService;

    @PutMapping("/config")
    public ResponseEntity<?> setGlobalFee(
            @RequestBody @Valid SetFeeRequest req,
            @AuthenticationPrincipal AuthPrincipal admin) {
        feeService.updateFee(FeeScope.GLOBAL, null, req.feeBps(), admin.getId(), req.reason());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/plan/{planId}")
    public ResponseEntity<?> setPlanFee(
            @PathVariable String planId,
            @RequestBody @Valid SetFeeRequest req,
            @AuthenticationPrincipal AuthPrincipal admin) {
        feeService.updateFee(FeeScope.PLAN, planId, req.feeBps(), admin.getId(), req.reason());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/creator/{creatorId}")
    public ResponseEntity<?> setCreatorFee(
            @PathVariable String creatorId,
            @RequestBody @Valid SetFeeRequest req,
            @AuthenticationPrincipal AuthPrincipal admin) {
        feeService.updateFee(FeeScope.CREATOR, creatorId, req.feeBps(), admin.getId(), req.reason());
        return ResponseEntity.ok().build();
    }

    // reason is @NotBlank — every change must be justified
    public record SetFeeRequest(@Min(0) @Max(10000) int feeBps, @NotBlank String reason) {}
}
```

### Admin Fee UI (Ananya — admin panel)

```
┌─────────────────────────────────────────────────────────────┐
│  Platform Fee Settings                          [Admin only] │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Global Default Fee (charged to creators)                    │
│  ┌────────────┐                                             │
│  │   15.0   % │   [Save]   Current: 15% • since 2026-07-07  │
│  └────────────┘                                             │
│  Allowed range: 0% – 30%                                    │
│                                                              │
│  Reason for change (required)                                │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                                                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ── Per-Plan Overrides ──                                    │
│  Starter creators   15.0 %   [Edit]                          │
│  Pro creators       12.0 %   [Edit]                          │
│                                                              │
│  ── Per-Creator Overrides (search) ──                        │
│  🔍 [ Search creator...            ]                         │
│  riya_fitness        8.0 %  (enterprise)  [Edit] [Remove]    │
│                                                              │
│  📜 View full change history →                               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 8. Test Cases (Kavya)

```java
// Platform Fee Tests (NEW)
@Test void shouldApplyDefault15PercentFeeOnRelease()
@Test void shouldCreditCreatorNetOfFee()          // 10000 → 8500 net, 1500 fee
@Test void shouldRouteFeeToPlatformRevenueLedger()
@Test void shouldResolveCreatorOverrideBeforePlan()
@Test void shouldResolvePlanBeforeGlobalDefault()
@Test void shouldFallBackToGlobalWhenNoOverride()
@Test void shouldRejectFeeAbove30PercentWithoutFlag()
@Test void shouldRejectFeeChangeByNonAdmin()
@Test void shouldAuditLogEveryFeeChange()
@Test void shouldFreezeFeeBpsAtReleaseNoRetroactiveRecompute()
@Test void shouldUseIntegerPaisaMathNoFloatRounding()

// Wallet Tests
@Test void shouldGetWalletBalance()
@Test void shouldCreditMilestoneRelease()
@Test void shouldUpdateEscrowOnRelease()
@Test void shouldTrackLifetimeEarnings()

// Withdrawal Tests
@Test void shouldRequestWithdrawal()
@Test void shouldValidateMinimumAmount()
@Test void shouldValidateSufficientBalance()
@Test void shouldProcessPayoutBatch()
@Test void shouldHandlePayoutWebhook()
@Test void shouldReverseFailedWithdrawal()

// Bank Account Tests
@Test void shouldAddBankAccount()
@Test void shouldEncryptAccountNumber()
@Test void shouldInitiatePennyDrop()
@Test void shouldVerifyBankAccount()

// Affiliate Tests
@Test void shouldTrackReferralSignup()
@Test void shouldCalculateCampaignCommission()
@Test void shouldCreditAfterLockPeriod()
```

---

## 9. API Reference

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/creator/wallet` | GET | JWT | Get wallet summary |
| `/creator/wallet/transactions` | GET | JWT | Get transaction history |
| `/creator/wallet/payout-methods` | GET | JWT | Get payout methods |
| `/creator/wallet/payout-methods/bank` | POST | JWT | Add bank account |
| `/creator/wallet/payout-methods/upi` | POST | JWT | Add UPI |
| `/creator/wallet/withdraw` | POST | JWT | Request withdrawal |
| `/creator/wallet/withdrawals` | GET | JWT | Get withdrawal history |
| `/creator/wallet/escrows` | GET | JWT | Get escrow details |
| `/creator/wallet/affiliate-earnings` | GET | JWT | Get affiliate earnings |
| `/creator/platform-fee` | GET | JWT | Get creator's applicable fee rate (read-only) |
| `/admin/platform-fee/config` | GET/PUT | ADMIN | Get/set global default fee |
| `/admin/platform-fee/plan/{planId}` | PUT | ADMIN | Set per-plan fee |
| `/admin/platform-fee/creator/{creatorId}` | PUT | ADMIN | Set/remove per-creator override |
| `/admin/platform-fee/change-log` | GET | ADMIN | Fee change audit history |
