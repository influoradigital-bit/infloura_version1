# Feature: Collaborations & Deals

**Business Purpose** — A "deal" is the negotiation and working relationship between a brand and a creator for a campaign, modeled on the `Collaboration` entity with a `DealMessage` timeline (the "deal room"). It carries the offer, counter-offers, agreed rate, usage rights, and links to the contract, escrow, and deliverables.

**Who uses it** — Brands and creators (both sides of the deal room).

## User Roles
Brand (propose/accept/counter/reject, chat), Creator (apply/accept/counter/reject, chat).

## Permissions
Dual-role; ownership enforced (`findByIdAndWorkspaceId` for brand, `findByIdAndCreatorId` for creator). Path ids never trusted.

## Business Flow
```
Origin: brand invite (discovery) OR brand proposal (POST /deals) OR creator apply (campaign)
  → negotiate: accept / counter (updates agreed rate) / reject
  → TERMS_AGREED → contract generated → signed → escrow funded → in progress → deliverables → completed
```
`CollaborationStatus` (13 states): INVITED, APPLIED, SHORTLISTED, IN_NEGOTIATION, TERMS_AGREED, CONTRACT_PENDING, CONTRACTED, IN_PROGRESS, REVIEW_PENDING, REVISION_REQUESTED, COMPLETED, CANCELLED, DISPUTED.

## Frontend
- **Pages**: brand `brand-chat`, `brand-deals` (→ `DealRoomDashboard`), `brand-messages`; creator `creator-deals` (unified hub), `creator-chat`, `creator-inbox`.
- **Components**: `brand/deal-room/*` and `creator/deal-room/*` (step-progress, counter/proposal cards, tabs), `brand/timeline/*`.

## Backend
- **Controller**: `DealController` (`/deals`).
- **Service**: `DealService` (dual-role; `createProposal`, `accept`, `reject`, `counter`, `sendMessage`, `markRead`, `listMessages`).

## Database
`collaborations` (V6, +V64 usage_rights), `deal_messages` (V33). See [../database.md](../database.md).

## APIs
`GET /deals`, `GET /deals/{id}`, `POST /deals`, `POST /deals/{id}/{accept,reject,counter}` (Idempotency-Key), `GET/POST /deals/{dealId}/messages`, `POST /deals/{dealId}/messages/read`, `GET /deals/{dealId}/deliverables`, `POST /deals/{dealId}/disputes`.

## AI
Not directly (Meera creates campaigns/invites upstream).

## Notifications
`ProposalSentEvent` → `creator.proposal_received`; `BidAcceptedEvent`; system messages persisted into the timeline.

## Dependencies
- **Depends on**: campaigns (deal is per-campaign), discovery (invite), contracts/escrow/deliverables (downstream).
- **Depended on by**: contracts, deliverables, disputes, reviews.

## Connected Files
`DealController`, `DealService`, `domain/entity/{Collaboration,DealMessage}`, `web/dto/deal/*`; frontend deal-room components.

## Execution Flow
```
Accept: POST /deals/{id}/accept (Idempotency-Key) → DealService.accept
  → canAccept() gate + anti-self-accept (can't accept your own last offer) → TERMS_AGREED + system message (idempotent)
Counter: → IN_NEGOTIATION, updates agreedRate, persists proposal message
```

## Error Handling
`DEAL_NOT_FOUND` (404), `DEAL_NOT_ACCEPTABLE`/`NOT_REJECTABLE`/`NOT_NEGOTIABLE` (409), `CANNOT_ACCEPT_OWN_OFFER` (409), `INVALID_AMOUNT`/`AMOUNT_EXCEEDS_BUDGET`/`AMOUNT_BELOW_BUDGET` (400), `INVALID_BEFORE_CURSOR` (400).

## Security
Ownership by role; message `kind` forced to `text` from clients (can't spoof privileged kinds); notes/messages sanitized; currency taken from the campaign, not the request.

## Performance
Messages paginated (size 50, `before` cursor); idempotent accept/counter.

## Testing
Deal service tests. Regression risks: anti-self-accept, idempotency, currency source.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~80%
- **Known issues**: `DealResponse.deliverablesDone/Total/nextDeadline` are hardcoded stubs (drift); some chat surfaces mock-backed.
- **Last verified**: 2026-07-15
