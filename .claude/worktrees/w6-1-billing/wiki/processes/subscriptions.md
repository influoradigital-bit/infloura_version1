# Sage Digital — Fixed Monthly Tool Costs (Building & Running Influora)

> **Owner:** Rohan (CFO)
> **Date:** 2026-07-09
> **Note:** These are the AI/tooling costs to RUN Sage Digital's agent team building Influora — separate from Influora's own per-campaign Razorpay costs (see `wiki/processes/PLATFORM_COST_STRUCTURE.md`). Figures marked *(est.)* are market-rate estimates pending actual invoice confirmation — flag to me if any differ from what's actually billed.

---

## Fixed Monthly Subscriptions

| Tool | Plan | Used By | Cost (USD) | Cost (₹, @₹83/$) |
|---|---|---|---|---|
| Claude Max | 5x tier | All agents (Arjun, Priya, Vikram, Ananya, Kabir, Kavya, etc.) | $100/mo | ~₹8,300/mo |
| Cursor Pro | 2 seats | Ananya (frontend), Vikram (backend) | $20/mo × 2 = $40/mo | ~₹3,320/mo |
| Canva Pro | 1 seat | Zara (graphics) | ~$13/mo *(est.)* | ~₹1,080/mo |
| n8n | Self-hosted (Docker) | Dev (automation) | Software: $0 — needs a VPS | ~₹500–800/mo *(est., hosting)* |
| Postiz | Self-hosted (Docker) | Dev (scheduling) | Software: $0 — can share n8n's VPS or needs own | ~₹0–500/mo *(est.)* |
| Ollama (glm4:9b) | Local | Rohan (CFO analysis) | $0 (runs on existing hardware) | ₹0 |
| Z.ai (GLM-5.2) | Free/Lite tier | Backup/fallback model | $0 (Lite tier) | ₹0 |
| Cloudflare R2 | Pay-as-you-go | File uploads (deliverables, media kits) | ~$5–10/mo *(est., low volume at launch)* | ~₹400–800/mo |

**Total fixed monthly (estimate): ~$160–170/mo (~₹13,300–14,300/mo)**

---

## What's NOT in this list (billed separately, usage-based)

- **Razorpay payment gateway + payout fees** — these scale with campaign volume, not fixed. See `wiki/processes/PLATFORM_COST_STRUCTURE.md` for the full per-campaign breakdown.
- **SMS/OTP gateway** (creator/brand auth) — not yet selected, needs a vendor (e.g., MSG91, Twilio) — flagging as an unbudgeted line item, will add once Vikram confirms the OTP provider.
- **Email delivery (transactional)** — same status, not yet selected/budgeted.

---

## Alert Thresholds (unchanged from standing policy)

- 🟢 GREEN: <70% of monthly budget used
- 🟡 YELLOW: 70–85% — I flag to Swapnil
- 🔴 RED: >85% — pause non-critical spend, escalate immediately

I'll true these numbers up against the first real invoices once billing starts, and correct this file rather than carry estimates indefinitely.
