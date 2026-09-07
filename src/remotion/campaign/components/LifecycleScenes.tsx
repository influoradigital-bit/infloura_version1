import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily, theme } from '../../theme';
import { BrowserFrame } from './BrowserFrame';
import { Caption } from './Caption';
import { DealRoomShell } from './DealRoomShell';

/**
 * Stages 2-8 of the lifecycle video. Every status word, phase name, button
 * label and metric name below is taken from the product, not invented — the
 * per-scene comments cite where.
 *
 * The demo campaign is the same fictional one the creation video uses:
 * "Diwali Glow Kit Launch" for the D2C skincare brand Bloomveda.
 */

function fade(frame: number, at: number, shift = 18) {
  return {
    opacity: interpolate(frame, [at, at + 16], [0, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
    translate: interpolate(frame, [at, at + 22], [`0px ${shift}px`, '0px 0px'], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.bezier(0.16, 1, 0.3, 1),
    }),
  };
}

/**
 * Stage 2 — applications land.
 *
 * [Corrected 2026-09-07] An application carries a MESSAGE ONLY: `ApplyRequest`
 * is `record ApplyRequest(String message)` and `Collaboration.apply()` sets no
 * amount. So this list deliberately shows name, handle, their note and the
 * bucket status — no quote, no timeline, no match score. `matchScore` exists
 * only on the mock fixtures; the live `dealToBidView` never sets it and the
 * badge is gated on `>= 90` anyway. Fed by `api.deals.list('brand','all')` :641.
 */
export function BidsScene() {
  const frame = useCurrentFrame();
  const bids = [
    { name: 'Ritika Sharma', handle: '@ritika.skin', note: 'Been using Bloomveda since summer — easy yes.', status: 'Applied' },
    { name: 'Aarav Nair', handle: '@aaravtries', note: 'Festive skincare is my best format every year.', status: 'Applied' },
    { name: 'Meher Kaul', handle: '@meherglow', note: 'Micro audience, very high save rate on routines.', status: 'Shortlisted' },
    { name: 'Sana Qureshi', handle: '@sanaeveryday', note: 'I do gifting content every Diwali for my mum.', status: 'Applied' },
  ];

  return (
    <>
      <Caption kicker="Stage 2 — Applications" text="Creators apply to you" />
      <BrowserFrame url="app.influora.in/brand/campaigns/cmp_4a7c">
        <div style={{ flex: 1, padding: '34px 46px', fontFamily }}>
          <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 6 }}>
            <span style={{ fontSize: 36, fontWeight: 800, color: theme.foreground, letterSpacing: -0.6 }}>
              Diwali Glow Kit Launch
            </span>
            <span
              style={{
                fontSize: 22,
                fontWeight: 800,
                padding: '7px 18px',
                borderRadius: 999,
                backgroundColor: theme.success,
                color: theme.successForeground,
              }}
            >
              ACTIVE
            </span>
          </div>
          <div style={{ fontSize: 24, color: theme.mutedForeground, marginBottom: 26 }}>
            {Math.round(
              interpolate(frame, [10, 70], [0, 4], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            )}{' '}
            applications
          </div>

          {bids.map((bid, index) => (
            <div
              key={bid.handle}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 20,
                padding: '18px 24px',
                marginBottom: 13,
                borderRadius: 14,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                ...fade(frame, 18 + index * 22),
              }}
            >
              <div
                style={{
                  width: 58,
                  height: 58,
                  flexShrink: 0,
                  borderRadius: 999,
                  backgroundColor: theme.accent,
                  color: theme.accentForeground,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 26,
                  fontWeight: 800,
                }}
              >
                {bid.name.charAt(0)}
              </div>
              <div style={{ width: 300 }}>
                <div style={{ fontSize: 27, fontWeight: 700, color: theme.foreground }}>{bid.name}</div>
                <div style={{ fontSize: 21, color: theme.mutedForeground }}>{bid.handle}</div>
              </div>
              <div style={{ flex: 1, minWidth: 0, paddingRight: 20 }}>
                <div style={{ fontSize: 19, color: theme.mutedForeground }}>Their note</div>
                <div style={{ fontSize: 23, color: theme.foreground }}>{bid.note}</div>
              </div>
              <div
                style={{
                  width: 130,
                  textAlign: 'center',
                  padding: '7px 14px',
                  borderRadius: 999,
                  fontSize: 21,
                  fontWeight: 700,
                  backgroundColor: bid.status === 'Shortlisted' ? theme.info : theme.muted,
                  color: bid.status === 'Shortlisted' ? theme.infoForeground : theme.mutedForeground,
                }}
              >
                {bid.status}
              </div>
            </div>
          ))}
        </div>
      </BrowserFrame>
    </>
  );
}

/**
 * Stage 3 — read the pitch, then shortlist / counter / accept. All three
 * controls are wired to real endpoints (`api.deals.accept` :758,
 * `api.deals.reject` :781, `api.deals.counter` :807).
 */
export function AcceptScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Stage 3 — Negotiate" text="Shortlist, counter, accept" />
      <DealRoomShell active={0} title="Ritika Sharma" subtitle="Diwali Glow Kit Launch · applied 14 Oct">
        <div style={{ display: 'flex', gap: 26, height: '100%' }}>
          <div style={{ flex: 1.3 }}>
            <div style={{ fontSize: 22, fontWeight: 700, color: theme.mutedForeground, marginBottom: 12 }}>
              Their pitch
            </div>
            <div
              style={{
                padding: '22px 26px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                fontSize: 25,
                lineHeight: 1.5,
                color: theme.foreground,
                ...fade(frame, 8),
              }}
            >
              “I have been using Bloomveda since summer, so this is an easy yes. My audience is 78% women
              aged 18 to 34 in metros, and festive skincare is my best performing format every year.”
            </div>

            <div style={{ fontSize: 22, fontWeight: 700, color: theme.mutedForeground, margin: '24px 0 12px' }}>
              What they will deliver
            </div>
            {['2 × Instagram Reel', '4 × Instagram Story', '1 × Static Post'].map((item, index) => (
              <div
                key={item}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 14,
                  padding: '13px 22px',
                  marginBottom: 10,
                  borderRadius: 12,
                  backgroundColor: theme.card,
                  border: `2px solid ${theme.border}`,
                  fontSize: 24,
                  color: theme.foreground,
                  ...fade(frame, 34 + index * 14, 12),
                }}
              >
                <span style={{ color: theme.accentForeground, fontSize: 22 }}>●</span>
                {item}
              </div>
            ))}
          </div>

          <div style={{ flex: 1 }}>
            <div
              style={{
                padding: '24px 26px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                ...fade(frame, 18),
              }}
            >
              <div style={{ fontSize: 21, color: theme.mutedForeground }}>Their quote</div>
              <div style={{ fontSize: 52, fontWeight: 900, color: theme.foreground, letterSpacing: -1.5 }}>
                ₹28,000
              </div>
              <div style={{ fontSize: 22, color: theme.mutedForeground, marginTop: 4 }}>
                Delivered in 2 weeks
              </div>
            </div>

            <div style={{ marginTop: 22, display: 'flex', flexDirection: 'column', gap: 13 }}>
              {[
                { label: 'Shortlist', primary: false },
                { label: 'Send counter offer', primary: false },
                { label: 'Accept quote', primary: true },
              ].map((action, index) => (
                <div
                  key={action.label}
                  style={{
                    padding: '19px 26px',
                    borderRadius: 14,
                    textAlign: 'center',
                    fontSize: 27,
                    fontWeight: 800,
                    border: action.primary ? 'none' : `2px solid ${theme.border}`,
                    backgroundColor: action.primary ? theme.primary : theme.card,
                    color: action.primary ? theme.primaryForeground : theme.foreground,
                    boxShadow: action.primary ? '0 16px 40px rgba(109,90,230,0.34)' : 'none',
                    ...fade(frame, 52 + index * 12, 12),
                    scale: action.primary
                      ? interpolate(frame, [214, 226, 240], [1, 0.94, 1], {
                          extrapolateLeft: 'clamp',
                          extrapolateRight: 'clamp',
                          easing: Easing.bezier(0.16, 1, 0.3, 1),
                          output: 'perceptual-scale',
                        })
                      : 1,
                  }}
                >
                  {action.label}
                </div>
              ))}
            </div>
          </div>
        </div>
      </DealRoomShell>
    </>
  );
}

/** Stage 4 — contract generated and signed by both parties. */
export function ContractScene() {
  const frame = useCurrentFrame();
  const brandSigned = frame >= 96;
  const creatorSigned = frame >= 168;
  return (
    <>
      <Caption kicker="Stage 4 — Contract" text="Both sides sign" />
      <DealRoomShell active={1} title="Contract" subtitle="Diwali Glow Kit Launch · Ritika Sharma">
        <div style={{ display: 'flex', gap: 26, height: '100%' }}>
          <div style={{ flex: 1.2 }}>
            <div
              style={{
                padding: '24px 28px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                ...fade(frame, 6),
              }}
            >
              <div style={{ fontSize: 26, fontWeight: 800, color: theme.foreground, marginBottom: 16 }}>
                Terms
              </div>
              {[
                ['Fee', '₹28,000'],
                ['Deliverables', '2 Reels, 4 Stories, 1 Post'],
                ['Usage rights', '90 days, paid ads allowed'],
                ['First draft due', '19 Oct 2026'],
              ].map(([label, value], index) => (
                <div
                  key={label}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    padding: '13px 0',
                    borderBottom: `1px solid ${theme.border}`,
                    fontSize: 24,
                    ...fade(frame, 16 + index * 10, 10),
                  }}
                >
                  <span style={{ color: theme.mutedForeground }}>{label}</span>
                  <span style={{ fontWeight: 700, color: theme.foreground }}>{value}</span>
                </div>
              ))}
            </div>
          </div>

          <div style={{ flex: 1 }}>
            <div
              style={{
                padding: '24px 28px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                ...fade(frame, 40),
              }}
            >
              <div style={{ fontSize: 26, fontWeight: 800, color: theme.foreground, marginBottom: 20 }}>
                Signature progress
              </div>
              {[
                { party: 'Bloomveda', role: 'Brand', signed: brandSigned },
                { party: 'Ritika Sharma', role: 'Creator', signed: creatorSigned },
              ].map((party) => (
                <div
                  key={party.party}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 16,
                    padding: '17px 20px',
                    marginBottom: 13,
                    borderRadius: 12,
                    border: `2px solid ${party.signed ? theme.successForeground : theme.border}`,
                    backgroundColor: party.signed ? theme.success : theme.background,
                  }}
                >
                  <div
                    style={{
                      width: 42,
                      height: 42,
                      borderRadius: 999,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: 24,
                      fontWeight: 900,
                      backgroundColor: party.signed ? theme.successForeground : theme.muted,
                      color: party.signed ? '#ffffff' : theme.mutedForeground,
                    }}
                  >
                    {party.signed ? '✓' : '…'}
                  </div>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 25, fontWeight: 700, color: theme.foreground }}>{party.party}</div>
                    <div style={{ fontSize: 20, color: theme.mutedForeground }}>{party.role}</div>
                  </div>
                  <div
                    style={{
                      fontSize: 21,
                      fontWeight: 800,
                      color: party.signed ? theme.successForeground : theme.mutedForeground,
                    }}
                  >
                    {party.signed ? 'Signed' : 'Awaiting'}
                  </div>
                </div>
              ))}
              <div
                style={{
                  marginTop: 18,
                  padding: '16px 20px',
                  borderRadius: 12,
                  backgroundColor: theme.accent,
                  color: theme.accentForeground,
                  fontSize: 23,
                  fontWeight: 700,
                  textAlign: 'center',
                  opacity: interpolate(frame, [176, 200], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }),
                }}
              >
                Contract executed — work can begin
              </div>
            </div>
          </div>
        </div>
      </DealRoomShell>
    </>
  );
}

/** Stage 5 — the brand secures the funds before any work starts. */
export function SecureFundsScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Stage 5 — Secure funds" text="Money in before work starts" />
      <DealRoomShell active={2} title="Secure funds" subtitle="Diwali Glow Kit Launch · Ritika Sharma">
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            height: '100%',
            gap: 22,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 40, ...fade(frame, 8) }}>
            <div
              style={{
                width: 300,
                padding: '26px 30px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                textAlign: 'center',
              }}
            >
              <div style={{ fontSize: 21, color: theme.mutedForeground }}>Brand wallet</div>
              <div style={{ fontSize: 40, fontWeight: 900, color: theme.foreground, marginTop: 6 }}>
                ₹
                {Math.round(
                  interpolate(frame, [60, 120], [120000, 92000], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }) / 1000,
                )}
                ,000
              </div>
            </div>
            <div
              style={{
                fontSize: 44,
                color: theme.primary,
                translate: interpolate(frame, [60, 120], ['-14px 0px', '14px 0px'], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }),
              }}
            >
              →
            </div>
            <div
              style={{
                width: 340,
                padding: '26px 30px',
                borderRadius: 16,
                backgroundColor: theme.accent,
                border: `3px solid ${theme.primary}`,
                textAlign: 'center',
              }}
            >
              <div style={{ fontSize: 21, color: theme.accentForeground, fontWeight: 700 }}>
                Secured for this deal
              </div>
              <div style={{ fontSize: 44, fontWeight: 900, color: theme.accentForeground, marginTop: 6 }}>
                ₹
                {Math.round(
                  interpolate(frame, [60, 120], [0, 28000], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }) / 1000,
                )}
                ,000
              </div>
            </div>
          </div>

          <div
            style={{
              marginTop: 14,
              maxWidth: 1000,
              textAlign: 'center',
              fontSize: 27,
              lineHeight: 1.45,
              color: theme.mutedForeground,
              ...fade(frame, 130),
            }}
          >
            Held against the campaign, not paid out. The creator can see the budget is real —
            and nothing is released until you approve the work.
          </div>
        </div>
      </DealRoomShell>
    </>
  );
}

/**
 * Stage 6 — the creator submits. Status words are the real `DeliverableStatus`
 * enum values (PENDING / SUBMITTED / APPROVED …).
 */
export function DeliverScene() {
  const frame = useCurrentFrame();
  const items = [
    { name: 'Instagram Reel 1', status: 'SUBMITTED', at: 20 },
    { name: 'Instagram Reel 2', status: 'PENDING', at: 40 },
    { name: 'Story set (4)', status: 'PENDING', at: 56 },
    { name: 'Static Post', status: 'PENDING', at: 72 },
  ];
  return (
    <>
      <Caption kicker="Stage 6 — Deliver" text="Creator submits the work" />
      <DealRoomShell active={3} title="Deliverables" subtitle="0 of 4 approved">
        {items.map((item) => (
          <div
            key={item.name}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 20,
              padding: '20px 26px',
              marginBottom: 14,
              borderRadius: 14,
              backgroundColor: theme.card,
              border: `2px solid ${item.status === 'SUBMITTED' ? theme.primary : theme.border}`,
              ...fade(frame, item.at),
            }}
          >
            <div
              style={{
                width: 54,
                height: 54,
                borderRadius: 12,
                flexShrink: 0,
                backgroundColor: item.status === 'SUBMITTED' ? theme.accent : theme.muted,
                color: item.status === 'SUBMITTED' ? theme.accentForeground : theme.mutedForeground,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 26,
              }}
            >
              ▶
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 27, fontWeight: 700, color: theme.foreground }}>{item.name}</div>
              <div style={{ fontSize: 21, color: theme.mutedForeground }}>
                {item.status === 'SUBMITTED' ? 'Submitted 18 Oct · awaiting your review' : 'Not started'}
              </div>
            </div>
            <div
              style={{
                padding: '9px 20px',
                borderRadius: 999,
                fontSize: 21,
                fontWeight: 800,
                letterSpacing: 0.6,
                backgroundColor: item.status === 'SUBMITTED' ? theme.info : theme.muted,
                color: item.status === 'SUBMITTED' ? theme.infoForeground : theme.mutedForeground,
              }}
            >
              {item.status}
            </div>
          </div>
        ))}
      </DealRoomShell>
    </>
  );
}

/**
 * Stage 7 — approving releases that deliverable's payment. Verified against
 * `BrandDeliverableService.approve()`, which calls
 * `EscrowService.tryReleaseOnApproval` in the same transaction.
 */
export function ApproveScene() {
  const frame = useCurrentFrame();
  const approved = frame >= 150;
  return (
    <>
      <Caption kicker="Stage 7 — Approve and pay" text="Approve releases the payment" />
      <DealRoomShell active={4} title="Instagram Reel 1" subtitle="Submitted 18 Oct 2026">
        <div style={{ display: 'flex', gap: 28, height: '100%' }}>
          <div
            style={{
              width: 330,
              borderRadius: 16,
              backgroundColor: theme.phoneBezel,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'rgba(255,255,255,0.5)',
              fontSize: 60,
              ...fade(frame, 6),
            }}
          >
            ▶
          </div>

          <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
            <div
              style={{
                padding: '20px 24px',
                borderRadius: 14,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                fontSize: 24,
                lineHeight: 1.5,
                color: theme.foreground,
                ...fade(frame, 16),
              }}
            >
              Caption: “My festive skin prep, start to finish, with the Bloomveda Diwali Glow Kit.
              #BloomvedaGlow #DiwaliSkincare”
            </div>

            <div style={{ display: 'flex', gap: 16, marginTop: 22, ...fade(frame, 34) }}>
              <div
                style={{
                  flex: 1,
                  padding: '18px 24px',
                  borderRadius: 14,
                  border: `2px solid ${theme.border}`,
                  backgroundColor: theme.card,
                  textAlign: 'center',
                  fontSize: 26,
                  fontWeight: 700,
                  color: theme.mutedForeground,
                }}
              >
                Request revision
              </div>
              <div
                style={{
                  flex: 1,
                  padding: '18px 24px',
                  borderRadius: 14,
                  backgroundColor: approved ? theme.successForeground : theme.primary,
                  color: '#ffffff',
                  textAlign: 'center',
                  fontSize: 26,
                  fontWeight: 800,
                  boxShadow: '0 14px 36px rgba(109,90,230,0.32)',
                  scale: interpolate(frame, [128, 140, 152], [1, 0.94, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                    output: 'perceptual-scale',
                  }),
                }}
              >
                {approved ? 'Approved ✓' : 'Approve'}
              </div>
            </div>

            <div
              style={{
                marginTop: 24,
                padding: '22px 26px',
                borderRadius: 14,
                backgroundColor: theme.success,
                border: `2px solid ${theme.successForeground}`,
                opacity: interpolate(frame, [158, 186], [0, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }),
                translate: interpolate(frame, [158, 190], ['0px 16px', '0px 0px'], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }),
              }}
            >
              <div style={{ fontSize: 28, fontWeight: 800, color: theme.successForeground }}>
                ₹7,000 released to Ritika
              </div>
              <div style={{ fontSize: 23, color: theme.successForeground, marginTop: 6, opacity: 0.9 }}>
                Secured balance for this deal: ₹21,000 · 1 of 4 approved
              </div>
            </div>
          </div>
        </div>
      </DealRoomShell>
    </>
  );
}

/**
 * Stage 8 — results. Deliberately split into two panels: creator-reported
 * numbers on the left (CampaignAnalytics.source is always "CREATOR_REPORTED",
 * `src/lib/api.ts:1838`) and genuinely measured tracking-link/coupon numbers on
 * the right. There is no impression tracking in this system, so nothing here
 * claims platform-verified reach.
 */
export function AnalyticsScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Stage 8 — Results" text="Reported reach, measured sales" />
      <BrowserFrame url="app.influora.in/brand/campaigns/cmp_4a7c/tracking">
        <div style={{ flex: 1, padding: '30px 46px', fontFamily }}>
          <div style={{ display: 'flex', gap: 26 }}>
            <div
              style={{
                flex: 1,
                padding: '24px 28px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                ...fade(frame, 6),
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4 }}>
                <span style={{ fontSize: 27, fontWeight: 800, color: theme.foreground }}>Reach</span>
                <span
                  style={{
                    fontSize: 18,
                    fontWeight: 800,
                    letterSpacing: 0.8,
                    padding: '5px 12px',
                    borderRadius: 999,
                    backgroundColor: theme.warning,
                    color: theme.warningForeground,
                  }}
                >
                  CREATOR-REPORTED
                </span>
              </div>
              <div style={{ fontSize: 20, color: theme.mutedForeground, marginBottom: 18 }}>
                Reported by the creator from their own account
              </div>
              {[
                ['Total reach', 412000, ''],
                ['Impressions', 587000, ''],
                ['Engagements', 31400, ''],
              ].map(([label, value], index) => (
                <div
                  key={String(label)}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'baseline',
                    padding: '14px 0',
                    borderBottom: `1px solid ${theme.border}`,
                    ...fade(frame, 22 + index * 12, 10),
                  }}
                >
                  <span style={{ fontSize: 24, color: theme.mutedForeground }}>{String(label)}</span>
                  <span style={{ fontSize: 34, fontWeight: 800, color: theme.foreground }}>
                    {Math.round(
                      interpolate(frame, [26 + index * 12, 86 + index * 12], [0, Number(value)], {
                        extrapolateLeft: 'clamp',
                        extrapolateRight: 'clamp',
                        easing: Easing.bezier(0.16, 1, 0.3, 1),
                      }),
                    ).toLocaleString('en-IN')}
                  </span>
                </div>
              ))}
              <div style={{ display: 'flex', justifyContent: 'space-between', paddingTop: 14 }}>
                <span style={{ fontSize: 24, color: theme.mutedForeground }}>Engagement rate</span>
                <span style={{ fontSize: 34, fontWeight: 800, color: theme.accentForeground }}>5.3%</span>
              </div>
            </div>

            <div
              style={{
                flex: 1,
                padding: '24px 28px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `3px solid ${theme.primary}`,
                ...fade(frame, 100),
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4 }}>
                <span style={{ fontSize: 27, fontWeight: 800, color: theme.foreground }}>Sales</span>
                <span
                  style={{
                    fontSize: 18,
                    fontWeight: 800,
                    letterSpacing: 0.8,
                    padding: '5px 12px',
                    borderRadius: 999,
                    backgroundColor: theme.success,
                    color: theme.successForeground,
                  }}
                >
                  MEASURED
                </span>
              </div>
              <div style={{ fontSize: 20, color: theme.mutedForeground, marginBottom: 18 }}>
                Tracking links and coupon codes, counted by Influora
              </div>
              {[
                ['Link clicks', 8940],
                ['Conversions', 412],
                ['Coupon redemptions', 268],
              ].map(([label, value], index) => (
                <div
                  key={String(label)}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'baseline',
                    padding: '14px 0',
                    borderBottom: `1px solid ${theme.border}`,
                    ...fade(frame, 116 + index * 12, 10),
                  }}
                >
                  <span style={{ fontSize: 24, color: theme.mutedForeground }}>{String(label)}</span>
                  <span style={{ fontSize: 34, fontWeight: 800, color: theme.foreground }}>
                    {Math.round(
                      interpolate(frame, [120 + index * 12, 180 + index * 12], [0, Number(value)], {
                        extrapolateLeft: 'clamp',
                        extrapolateRight: 'clamp',
                        easing: Easing.bezier(0.16, 1, 0.3, 1),
                      }),
                    ).toLocaleString('en-IN')}
                  </span>
                </div>
              ))}
              <div style={{ display: 'flex', justifyContent: 'space-between', paddingTop: 14 }}>
                <span style={{ fontSize: 24, color: theme.mutedForeground }}>Revenue</span>
                <span style={{ fontSize: 34, fontWeight: 900, color: theme.successForeground }}>₹4,86,200</span>
              </div>
            </div>
          </div>
        </div>
      </BrowserFrame>
    </>
  );
}
