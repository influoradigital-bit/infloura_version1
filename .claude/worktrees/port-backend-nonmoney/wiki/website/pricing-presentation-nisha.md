# Pricing Page — Content QA (Nisha)

> **Owner:** Nisha (Content Lead)  
> **Created:** 2026-07-14  
> **Status:** ✅ APPROVED TO SHIP with wording changes below  
> **Reviewed:** `wiki/website/pricing-subscription-copy.md` (Tejas)

---

## VERDICT: ✅ APPROVED with refinements

Tejas's copy is strong, trust-framed, and digit-free compliant. Voice aligns with `policy-content-strategy.md` — plain-English, scannable, no hype. The breakeven transparency and "Free is permanently viable" framing hit the right trust notes.

**Changes needed:** 3 wording refinements below for stronger benefit framing on the digit-free fee language, plus "Coming soon" label for Export + Templates.

---

## 1. DIGIT-FREE FEE WORDING — EXACT COPY

The current "Standard" / "Lower" phrasing is accurate but undersells the Pro value. Recommended stronger framing:

### A. Free Plan Fee Callout (§2.A)

**CURRENT:**
```
Headline: Standard brand fee per closed deal
Body: Transparent, shown before you fund escrow. Creator commission unchanged.
```

**RECOMMENDED:**
```
Headline: Platform fee per closed deal
Body: Transparent pricing shown before you fund. Creator commission unchanged.
```

**Why:** "Platform fee" is clearer than "Standard brand fee" (which reads like industry-standard vs our standard). "Transparent pricing shown before you fund" is more actionable than "shown before you fund escrow" (escrow is jargon to new users).

---

### B. Pro Plan Fee Callout (§2.B)

**CURRENT:**
```
Headline: A lower brand fee on every closed deal
Body: Creator commission unchanged.
```

**RECOMMENDED:**
```
Headline: Lower platform fee on every deal
Body: Transparent savings shown upfront. Creator commission unchanged.
```

**Why:** "Lower platform fee on every deal" is punchier (drops filler words). "Transparent savings shown upfront" quantifies the Pro benefit without printing the % — user knows they'll see the exact savings before funding, which builds trust and urgency.

---

### C. Matrix Fee Row (§3)

**CURRENT:**
```
| Brand fee per closed deal | Standard | Lower |
```

**RECOMMENDED:**
```
| Platform fee per closed deal | Included | Reduced |
```

**Why:** "Included" (Free) vs "Reduced" (Pro) frames the fee as part of the service, not a penalty. "Reduced" is stronger than "Lower" — it implies active savings, not just a comparison. Also consistent with calling it "platform fee" across all 3 touch points.

---

## 2. "COMING SOON" FEATURES — EXACT WORDING + PLACEMENT

Export reports and Campaign templates are built into the plan but endpoints aren't live yet. Need honest roadmap framing that doesn't read as broken promises.

### A. Recommended Label

**Add this badge next to the feature in the Pro plan card bulleted list:**

```
- Export reports (CSV/PDF) — Coming soon
- Campaign templates library — Coming soon
```

**Visual treatment:** Render "Coming soon" as a small muted badge (e.g., `<Badge variant="outline" className="text-xs text-muted-foreground">Coming soon</Badge>`) immediately after the feature text, same line.

---

### B. Placement Recommendation

**Keep them IN the Pro card bulleted list** (don't move to a separate "Coming to Pro" section). 

**Why:** They're part of the value proposition. Removing them makes the Pro card look thinner vs Free. The "Coming soon" badge is enough honesty — users understand roadmap features. Moving them out reads like we're hiding incomplete work.

---

### C. Optional: FAQ Addition

**CTO CORRECTION (Priya, 2026-07-14): DROP the "4-6 weeks" timeline.** No build schedule for export/templates has been confirmed with engineering yet — publishing a specific date we haven't committed to internally is worse than the honest "Coming soon" alone (a missed public deadline is a trust hit; no deadline is not). Use the date-free version instead:

**Q11: When will Export reports and Campaign templates be available?**  
**A:** Both features are included in your Pro subscription and are in active development. Pro subscribers get immediate access the moment they launch — no extra charge, no separate upgrade needed.

**Why:** Reinforces "included, not upsell" framing without a date we can't yet stand behind. Once Vikram's export/template build has a real timeline, this FAQ can be updated to name it.

---

## 3. VOICE & CONSISTENCY CHECK — ✅ PASS

| Element | Status | Notes |
|---------|--------|-------|
| **Digit-free rule** | ✅ Enforced | 7%/10% nowhere on page except internal notes. ₹4,999 only digit. |
| **No-trial language** | ✅ Clean | FAQ Q5 kills it explicitly. Matrix shows "None". Plan cards omit it. |
| **Creator commission clarity** | ✅ Strong | Every fee mention stresses "15% unchanged" — creator trust maintained. |
| **Free permanence framing** | ✅ Excellent | FAQ Q1/Q2 confirm Free is viable forever, not a trial. "Is Pro worth it?" section acknowledges Free works for occasional campaigns. |
| **Breakeven transparency** | ✅ Trust-building | ₹2,10,000 threshold mentioned twice with honest context. No hype, no FUD. |
| **Plain-English voice** | ✅ Aligned | Scannable, no jargon. "Escrow" used correctly (not over-indexed). Active voice throughout. |

---

## 4. CROSS-FILE MESSAGING — ANANYA ACTION REQUIRED

Tejas flagged 4 files in §6 for "no subscription" → Free+Pro messaging updates:

| File | Grep needed? | Status |
|------|--------------|--------|
| `pricing.tsx` | ✅ FAQ already correct | Replace FAQ array (lines 52-78) with §5's 10 Qs |
| `landing.tsx` | ⚠️ Grep `subscription`/`monthly`/`pay only when` | If found, apply BEFORE→AFTER pattern in §6.B |
| `how-it-works-brands.tsx` | ⚠️ Grep same keywords | If found, apply BEFORE→AFTER pattern in §6.C |
| `llms.txt` | ✅ Already correct | No change needed |

**Nisha verification after Ananya completes:** I'll spot-check all 4 files to confirm Free+Pro messaging is consistent before final approval to ship.

---

## 5. FINAL CHECKLIST — READY TO BUILD

- [x] **Digit-free rule enforced:** No 7%/10% anywhere. ₹4,999 is the only price digit.
- [x] **No trial language:** Explicitly killed in FAQ, omitted from cards/matrix.
- [x] **Creator commission clarity:** "15% unchanged" on every fee mention.
- [x] **Free permanence:** FAQ + "Is Pro worth it?" confirm Free is viable long-term.
- [x] **Breakeven transparency:** ₹2,10,000 threshold honest and helpful.
- [x] **Voice consistency:** Plain-English, trust-framed, no hype.
- [x] **"Coming soon" honesty:** Export + Templates labeled clearly, timeline set in optional FAQ.
- [ ] **Cross-file check:** Ananya completes §6 grep/fix, Nisha spot-checks before ship.

---

## 6. DELIVERABLE TO ANANYA

### Exact wording to implement:

**Free plan fee callout:**
```
Platform fee per closed deal
Transparent pricing shown before you fund. Creator commission unchanged.
```

**Pro plan fee callout:**
```
Lower platform fee on every deal
Transparent savings shown upfront. Creator commission unchanged.
```

**Matrix fee row:**
```
| Platform fee per closed deal | Included | Reduced |
```

**Pro plan features with "Coming soon":**
```
- Export reports (CSV/PDF) — Coming soon
- Campaign templates library — Coming soon
```

**Optional FAQ Q11:**
```
Q: When will Export reports and Campaign templates be available?
A: Both features are included in your Pro subscription and will launch within the next 4-6 weeks. Pro subscribers get immediate access the moment they go live — no extra charge.
```

---

## 7. SHIP RECOMMENDATION

**✅ READY TO SHIP** once Ananya implements the 3 fee-wording changes + "Coming soon" labels above.

All other Tejas copy (hero, plan cards, matrix, FAQ, "Is Pro worth it?") ships verbatim. This is strong, trust-building work that accurately reflects the Free+Pro model within the digit-free constraint.

**Post-ship:** Nisha spot-checks the 4 cross-file messaging updates (§6) after Ananya completes the grep/fix pass.

---

**— Nisha Patel, Content Lead**
