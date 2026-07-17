# Feature: Contracts & Milestones

**Business Purpose** — Formalizes a deal into a signed contract with payment milestones. The contract is the legal artifact (PDF, tamper hash, signatures), and its milestones are the units that escrow funds against and releases from. It bridges negotiation and money.

**Who uses it** — Brands (generate + sign) and creators (sign).

## User Roles
Brand (generate contract, sign brand side), Creator (sign creator side).

## Permissions
Generate → OWNER/ADMIN/MANAGER. Creator signs their own (IDOR-safe `findByIdAndCreatorId`); brand-relayed creator signature restricted to elevated members.

## Business Flow
```
Brand generates contract (milestones required) → total = Σ milestone.amount (server-computed) → DRAFT
  → brand signs + creator signs → both signed → ACTIVE → PDF generated to R2 + events
  → escrow funded per milestone → milestones released on delivery
```
`ContractStatus`: DRAFT → PENDING_SIGNATURES → ACTIVE → COMPLETED → CANCELLED. `MilestoneStatus`: PENDING → FUNDED → RELEASED / REFUNDED / FROZEN.

## Frontend
- **Pages**: `brand-contracts`.
- **Components**: `brand/contracts/contracts-and-deliverables`, `brand/timeline/panels/contract-panel`, `creator/deal-room/{creator-contract-card,panel,tab}`.

## Backend
- **Controller**: `ContractController` (`/contracts`).
- **Service**: `ContractService` (generate, recordSignature, recordSignatureForCreator, PDF).

## Database
`contracts` (V10; `terms` stores a SHA-256 tamper hash, `pdf_r2_key`), `payment_milestones` (V10, +V52 release_condition — unmapped). See [../database.md](../database.md).

## APIs
`POST /contracts`, `GET /contracts`, `GET /contracts/unsigned` (creator), `GET /contracts/{id}`, `POST /contracts/{id}/sign` (Idempotency-Key), `GET /contracts/{id}/pdf-download-url`.

## AI
Not involved.

## Notifications
`ContractSignedEvent` (both parties), `ContractReadyForEscrowEvent` (escrow-funding prompt — **note: this event currently has no listener**, see [../known-limitations.md](../known-limitations.md)).

## Dependencies
- **Depends on**: collaborations/deals (source), R2 (PDF), escrow (milestones fund/release).
- **Depended on by**: escrow, payouts, disputes.

## Connected Files
`ContractController`, `ContractService`, `domain/entity/{Contract,PaymentMilestone}`, `web/dto/*`, `integration/storage/R2StorageService`.

## Execution Flow
```
Generate: POST /contracts → ContractService.generate (validate milestones, total = Σ amount, SHA-256 hash) → DRAFT + milestones
Sign: POST /contracts/{id}/sign (Idempotency-Key) → record signature (idempotent, already-signed no-op)
  → both signed → ACTIVE → best-effort PDF → R2 + ContractSignedEvent + ContractReadyForEscrowEvent
```

## Error Handling
`COLLABORATION_NOT_FOUND` (404), `MILESTONES_REQUIRED`/`INVALID_CONTRACT_TOTAL` (400), `INVALID_SIGNER_ROLE` (400), `CONTRACT_PDF_NOT_READY` (404), `STORAGE_UNAVAILABLE` (503). Side effects (PDF/events) are swallowed on error so they never roll back a signature.

## Security
`total_amount` is server-computed (never client), tamper hash on terms, IDOR-safe creator signing. Residual risk: brand-relayed creator signature isn't the creator's own cryptographic assent (flagged).

## Performance
PDF generation is best-effort/off the critical path; presigned PDF download.

## Testing
Contract service tests cover generate/sign idempotency. Regression risks: total computation, both-signed transition.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~80%
- **Known issues**: `release_condition` (V52) unmapped on the entity; `ContractReadyForEscrowEvent` has no listener; brand-relay creator signature caveat.
- **Last verified**: 2026-07-15
