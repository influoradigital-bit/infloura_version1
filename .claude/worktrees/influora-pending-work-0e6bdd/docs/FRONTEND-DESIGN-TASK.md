# FRONTEND DESIGN TASK — INFLUORA COMPLETE UI BUILD
> Issued by: Swapnil (CEO) + Priya (CTO)
> Assigned to: **Ananya** (Frontend Developer) + **Kabir** (Security Audit)
> Date: 2026-07-04
> Skills required: `/3d-cinematic-web`, `DESIGN_SYSTEM.md`, `TECH-STACK.md`

---

## 🎯 OBJECTIVE

Build the **complete, production-quality frontend** for Influora — the dual-sided influencer marketing platform. This is NOT a prototype. This is the UI that brands and creators will use to sign ₹1L+ deals with real money.

**PAN-KYC flows are OPTIONAL for this sprint** — scaffold the routes but don't block on full KYC implementation. We'll plug that in M2 when Razorpay integration lands.

---

## 🎨 DESIGN SYSTEM — LOCKED (DO NOT CHANGE)

### Fonts
| Usage | Font | Fallback |
|-------|------|----------|
| Headings | **Inter** (SemiBold 600, Bold 700) | system-ui, -apple-system, sans-serif |
| Body | **Inter** (Regular 400, Medium 500) | system-ui, -apple-system, sans-serif |
| Code/Numbers | **JetBrains Mono** (Regular 400) | SF Mono, Monaco, monospace |

**Add to `index.html`:**
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono&display=swap" rel="stylesheet">
```

**Update Tailwind:**
```js
fontFamily: {
  sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
  mono: ['JetBrains Mono', 'SF Mono', 'Monaco', 'monospace'],
}
```

### Colors (from `DESIGN_SYSTEM.md` — dark theme)
| Token | Hex | Usage |
|-------|-----|-------|
| `--color-bg` | `#0f172a` (Slate-950) | Main page background |
| `--color-surface` | `#1e293b` (Slate-800) | Cards, containers |
| `--color-surface-hover` | `#334155` (Slate-700) | Hover states |
| `--color-border` | `#334155` (Slate-700) | Borders, dividers |
| `--color-text` | `#f1f5f9` (Slate-100) | Primary text |
| `--color-text-muted` | `#94a3b8` (Slate-400) | Secondary text |
| `--color-primary` | `#3b82f6` (Blue-500) | CTAs, links, active states |
| `--color-primary-dark` | `#1e40af` (Blue-800) | Hover on primary |
| `--color-accent` | `#06b6d4` (Cyan-500) | Secondary accent, Hype Campaign badge |
| `--color-success` | `#10b981` (Emerald-500) | Approved, connected, paid |
| `--color-warning` | `#f59e0b` (Amber-500) | Pending, review needed |
| `--color-error` | `#ef4444` (Red-500) | Error, rejected |

**Rule:** NEVER use raw Tailwind color classes (`blue-500`, `slate-800`) — always reference CSS variables or the design tokens.

### Spacing
4px grid. Use only: `4, 8, 12, 16, 24, 32, 48, 64, 80, 96, 128`.

### Border Radius
- Small (inputs, badges): `rounded-md` (6px)
- Medium (cards): `rounded-lg` (8px)
- Large (modals, hero elements): `rounded-xl` (12px) or `rounded-2xl` (16px)

### Shadows
```css
/* Card */
box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
/* Modal / Elevated */
box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3);
/* Glow (Hype badge) */
box-shadow: 0 0 20px rgba(6, 182, 212, 0.3);
```

---

## 🔥 VISUAL DIRECTION — HOW IT SHOULD LOOK

### Brand Side (agency feel)
- **Clean, professional dashboard aesthetic** — think Linear, Notion, Figma dashboard
- Data-dense but not cluttered — clear hierarchy, breathing room
- Charts/graphs in campaign analytics — use Recharts
- Pipeline view (brand-pipeline.tsx) = Kanban-style deal stages
- Deal Room = chat-first, like Slack/Intercom but with embedded proposal cards

### Creator Side (mobile-first, approachable)
- **Lighter feel** — still dark theme but more "friendly" than brand side
- Inbox = opportunity cards with brand logo, pay range, deadline
- One-tap actions where possible (Accept Hype invite = single tap)
- Portfolio page = public-facing, link-in-bio energy, shareable URL
- Earnings breakdown = pie chart + line chart (gross → net)

### Landing/Marketing Pages (cinematic)
- **Use `/3d-cinematic-web` skills** for:
  - Hero section: subtle 3D globe or particle system (trade routes / creator network visual)
  - Scroll-pinned section explaining escrow flow with scroll-scrubbed animation
  - Stagger reveals on feature cards
- DO NOT overload — one WebGL context max, `useReducedMotion()` bypass mandatory
- Performance target: Lighthouse mobile ≥ 85

### Hype Campaign UI (special treatment)
- **Cyan accent** (`#06b6d4`) for all Hype-related badges, buttons, borders
- Pulsing "LIVE" indicator when campaign is in 72-hr window
- Progress bar showing slots filled vs total
- Creator inbox card for Hype = cyan glow border, "⚡ Hype" badge, one-tap Accept

---

## 📄 PAGES TO BUILD / COMPLETE

### BRAND SIDE (18 pages — most exist, need polish + real data wiring)
| Page | Status | Notes |
|------|--------|-------|
| `brand-login.tsx` | Exists | Polish: add forgot-password link, loading states |
| `brand-register.tsx` | Exists | Polish: validation UX, password strength |
| `brand-forgot-password.tsx` | Exists | Polish: success state |
| `brand-onboarding.tsx` | Exists | Polish: step indicator, GST/PAN fields (optional for now) |
| `brand-dashboard.tsx` | Exists | Add: campaign summary cards, recent activity, wallet balance |
| `brand-campaigns.tsx` | Exists | Add: filter/sort, campaign type badges (Open/Direct/Hype) |
| `brand-campaign-detail.tsx` | Exists | Add: analytics tab, deliverable review, escrow status |
| `brand-new-campaign.tsx` | Exists | Add: Hype Campaign type option (Step 1 selector) |
| `brand-new-hype-campaign.tsx` | **NEW** | Hype-specific form: source reel URL, audio/hashtag, format lanes, per-reel rate, slot cap |
| `brand-edit-campaign.tsx` | Exists | Polish |
| `brand-discover.tsx` | Exists | Add: verified badge, performance tier indicators |
| `brand-creator-profile.tsx` | Exists | Add: Instagram stats, past collaborations, "Start Deal" CTA |
| `brand-deals.tsx` | Exists | Add: deal cards with stage badges, link to Deal Room |
| `brand-pipeline.tsx` | Exists | Kanban: Invited → Negotiating → Contracted → In Progress → Delivered → Paid |
| `brand-chat.tsx` | Exists | Wire: proposal cards, counter-proposal UX, contract card embed |
| `brand-messages.tsx` | Exists | Inbox-style list of all Deal Room threads |
| `brand-contracts.tsx` | Exists | List of signed contracts + PDF download |
| `brand-wallet.tsx` | Exists | Balance, escrow holds, transaction history, "Add Funds" CTA |
| `brand-settings.tsx` | Exists | Profile, team members, notification prefs |

### CREATOR SIDE (13 pages)
| Page | Status | Notes |
|------|--------|-------|
| `creator-login.tsx` | Exists | Polish |
| `creator-register.tsx` | Exists | Polish: phone OTP flow |
| `creator-onboarding.tsx` | Exists | Add: Instagram connect CTA (OAuth scaffold), niche tags |
| `creator-inbox.tsx` | Exists | **Key page:** campaign cards (Open/Direct/Hype), filters, one-tap Hype accept |
| `creator-deals.tsx` | Exists | Active deals with stage badges |
| `creator-active.tsx` | Exists | Current deliverable in progress — upload, revision status |
| `creator-chat.tsx` | Exists | Deal Room from creator POV |
| `creator-wallet.tsx` | Exists | Earnings, pending, withdrawable, transaction history, TDS line-item |
| `creator-profile.tsx` | Exists | Edit profile, stats, portfolio settings |
| `creator-portfolio-editor.tsx` | Exists | Drag-drop portfolio builder |
| `creator-portfolio-public.tsx` | Exists | **Public page:** shareable URL, mobile-first, gorgeous |
| `creator-settings.tsx` | Exists | Notification, payout settings, KYC status (optional display) |

### NEW COMPONENTS TO BUILD
| Component | Location | Purpose |
|-----------|----------|---------|
| `HypeCampaignCard.tsx` | `components/brand/` | Cyan-glow campaign card for Hype type |
| `HypeInboxCard.tsx` | `components/creator/` | One-tap accept, cyan glow, "⚡ Hype" badge |
| `ProposalCard.tsx` | `components/shared/` | Embedded in Deal Room chat — shows terms, deliverables, rate |
| `CounterProposalCard.tsx` | `components/shared/` | Shows brand/creator edit, diff highlight |
| `ContractCard.tsx` | `components/shared/` | Embedded contract with e-sign CTA |
| `DeliverableCard.tsx` | `components/shared/` | Media preview, approval/revision buttons, revision count |
| `EscrowStatusBar.tsx` | `components/shared/` | Visual: Funded → Locked → Released / Disputed |
| `WalletBalanceCard.tsx` | `components/shared/` | Balance, escrow holds, add funds |
| `TransactionRow.tsx` | `components/shared/` | Row in transaction history — TDS shown inline |
| `VerifiedBadge.tsx` | `components/ui/` | Instagram OAuth verified checkmark |
| `HypeLiveIndicator.tsx` | `components/ui/` | Pulsing cyan dot + "LIVE" text |
| `SlotProgressBar.tsx` | `components/ui/` | X/100 slots filled |
| `TdsBreakdownTooltip.tsx` | `components/creator/` | Hover to see Gross - TDS = Net |

### 3D / MOTION COMPONENTS (use `/3d-cinematic-web`)
| Component | Location | Purpose |
|-----------|----------|---------|
| `HeroGlobe.tsx` | `components/3d/` | Landing page hero — trade routes / creator network |
| `EscrowFlowAnimation.tsx` | `components/motion/` | Scroll-pinned section explaining funds flow |
| `FadeUp.tsx` | `components/motion/` | Viewport entry animation (already exists — verify) |
| `StaggerContainer.tsx` | `components/motion/` | Grid reveals (already exists — verify) |
| `WordReveal.tsx` | `components/motion/` | Hero headline animation |

---

## 🛡️ SECURITY AUDIT SCOPE — KABIR

After Ananya completes each page batch, Kabir runs:

1. **XSS check:** All user inputs rendered safely? No `dangerouslySetInnerHTML` without sanitization?
2. **Auth state:** Are protected routes actually protected? Token stored in httpOnly cookie, not localStorage?
3. **CSRF:** State-changing actions have CSRF protection?
4. **API keys:** No secrets in frontend code? No `VITE_*` env vars containing secrets?
5. **Input validation:** Client-side validation matches server expectations? No SQLi vectors in search/filter params?
6. **File uploads:** Only allowed types? Size limits? No path traversal?
7. **CORS:** Properly configured for the API?
8. **Content Security Policy:** CSP headers set?

Output: `wiki/security/frontend-audit-{batch}.md` with PASS/FAIL + fix list.

---

## 📋 BUILD SEQUENCE

### Batch 1 (Days 1–3): Core Brand Flow
- `brand-login`, `brand-register`, `brand-onboarding` — polish
- `brand-dashboard` — real data layout
- `brand-campaigns`, `brand-new-campaign` — add Hype type selector
- → Kabir: audit auth flows

### Batch 2 (Days 4–6): Deal Room + Discovery
- `brand-discover`, `brand-creator-profile` — verified badge, "Start Deal"
- `brand-chat`, `brand-messages` — ProposalCard, CounterProposalCard, ContractCard components
- `brand-deals`, `brand-pipeline` — Kanban
- → Kabir: audit Deal Room XSS + auth

### Batch 3 (Days 7–9): Money + Hype
- `brand-wallet`, `brand-contracts` — EscrowStatusBar, WalletBalanceCard
- `brand-new-hype-campaign` — NEW PAGE
- HypeCampaignCard, SlotProgressBar, HypeLiveIndicator
- → Kabir: audit wallet/escrow UI + Hype inputs

### Batch 4 (Days 10–12): Creator Side
- All creator pages: login, register, onboarding, inbox, deals, active, chat, wallet, profile, portfolio, settings
- HypeInboxCard, TdsBreakdownTooltip, VerifiedBadge
- `creator-portfolio-public` — make it gorgeous, shareable
- → Kabir: audit creator auth + file uploads (portfolio)

### Batch 5 (Days 13–15): Landing + 3D
- Landing page sections with 3D hero (HeroGlobe)
- EscrowFlowAnimation scroll section
- WordReveal hero headline
- Lighthouse audit — target mobile ≥ 85
- → Kabir: final security pass

---

## ✅ DEFINITION OF DONE

A page is DONE when:
1. All listed features are implemented
2. Responsive: works at 375px (mobile), 768px (tablet), 1280px (desktop)
3. Accessibility: keyboard nav, focus states, ARIA labels
4. `useReducedMotion()` bypass on all animations
5. No console errors
6. Kabir's security audit PASS
7. Lighthouse mobile ≥ 80 (landing pages ≥ 85)

---

## 📁 FILES TO READ BEFORE STARTING

1. `TECH-STACK.md` — do NOT violate (no `any`, no inline styles, no raw color classes)
2. `DESIGN_SYSTEM.md` — follow exactly
3. `docs/BUSINESS-BLUEPRINT.md` — understand what you're building and why
4. `docs/BUSINESS-IDEA-THAT-STANDS.md` — the product logic
5. `components/ui/` — use existing shadcn components, don't reinvent
6. `components/motion/` — check what exists before building new

---

## 🚨 ESCALATION

- **Unclear requirement?** → Escalate to Arjun (Eng Lead)
- **Need new npm package?** → Arjun → Priya (approval required)
- **Security blocker found?** → Kabir → Swapnil (CEO)
- **Architecture question?** → Priya (CTO)

---

## SIGN-OFF

**Swapnil (CEO):** Approved — ship this. Hype Campaign UI is the demo-day hero; make it look like ₹10Cr raised.

**Priya (CTO):** Approved — stack is locked. One WebGL context. `useReducedMotion()` on everything. No secrets in frontend. Kabir audits before merge.
