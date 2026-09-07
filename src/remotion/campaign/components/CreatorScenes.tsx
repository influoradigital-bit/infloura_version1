import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily, theme } from '../../theme';
import { BrowserFrame } from './BrowserFrame';
import { Caption } from './Caption';
import { DealRoomShell } from './DealRoomShell';

/**
 * Creator-side lifecycle scenes.
 *
 * Wallet figures use the product's own three labels and their tooltip
 * definitions (`src/pages/creator-wallet.tsx:738/:752/:764`). Application
 * statuses are the real `APPLICATION_BUCKETS` words. Our fictional creator is
 * Ritika Sharma, applying to Bloomveda's Diwali campaign — the same deal the
 * brand film shows from the other side.
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

/** Stage 1 — browse open briefs. */
export function BrowseScene() {
  const frame = useCurrentFrame();
  const briefs = [
    { brand: 'Bloomveda', title: 'Diwali Glow Kit Launch', budget: '₹15,000 – ₹60,000', tags: 'Instagram · Reel, Story', open: true },
    { brand: 'Kettle & Co', title: 'Winter Brew Sampling', budget: '₹8,000 – ₹25,000', tags: 'Instagram · Story', open: false },
    { brand: 'Marigold Home', title: 'Festive Table Styling', budget: '₹20,000 – ₹45,000', tags: 'Instagram, YouTube', open: false },
  ];
  return (
    <>
      <Caption kicker="Stage 1 — Find work" text="Briefs that fit you" />
      <BrowserFrame url="app.influora.in/creator/campaigns">
        <div style={{ flex: 1, padding: '32px 46px', fontFamily }}>
          <div style={{ fontSize: 34, fontWeight: 800, color: theme.foreground, marginBottom: 6 }}>
            Open campaigns
          </div>
          <div style={{ fontSize: 23, color: theme.mutedForeground, marginBottom: 26 }}>
            Briefs matching beauty and skincare
          </div>
          {briefs.map((brief, index) => (
            <div
              key={brief.title}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 22,
                padding: '22px 26px',
                marginBottom: 14,
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `${brief.open ? 3 : 2}px solid ${brief.open ? theme.primary : theme.border}`,
                ...fade(frame, 12 + index * 24),
              }}
            >
              <div
                style={{
                  width: 60,
                  height: 60,
                  flexShrink: 0,
                  borderRadius: 14,
                  backgroundColor: theme.accent,
                  color: theme.accentForeground,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 26,
                  fontWeight: 800,
                }}
              >
                {brief.brand.charAt(0)}
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 28, fontWeight: 700, color: theme.foreground }}>{brief.title}</div>
                <div style={{ fontSize: 21, color: theme.mutedForeground, marginTop: 3 }}>
                  {brief.brand} · {brief.tags}
                </div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontSize: 19, color: theme.mutedForeground }}>Budget</div>
                <div style={{ fontSize: 27, fontWeight: 800, color: theme.foreground }}>{brief.budget}</div>
              </div>
            </div>
          ))}
        </div>
      </BrowserFrame>
    </>
  );
}

/**
 * Stage 2 — apply. A note and nothing else: `ApplyRequest` is a single
 * `message` field capped at 2000 characters, and no amount is set on the
 * collaboration at this point.
 */
export function ApplyScene() {
  const frame = useCurrentFrame();
  const note =
    'I have been using Bloomveda since summer, so this is an easy yes. My audience is 78% women aged 18 to 34 in metros, and festive skincare is my best performing format every year.';
  const shown = note.slice(0, Math.max(0, Math.floor((frame - 20) * 2.1)));
  const sent = frame >= 240;
  return (
    <>
      <Caption kicker="Stage 2 — Apply" text="Apply with a note" />
      <BrowserFrame url="app.influora.in/creator/campaigns/cmp_4a7c">
        <div style={{ flex: 1, padding: '32px 46px', fontFamily }}>
          <div style={{ fontSize: 32, fontWeight: 800, color: theme.foreground }}>Diwali Glow Kit Launch</div>
          <div style={{ fontSize: 22, color: theme.mutedForeground, marginBottom: 24 }}>
            Bloomveda · ₹15,000 – ₹60,000 · applications close 6 Nov
          </div>

          <div style={{ fontSize: 25, fontWeight: 600, color: theme.foreground, marginBottom: 10 }}>
            Why are you a good fit?
          </div>
          <div
            style={{
              minHeight: 190,
              borderRadius: 14,
              border: `2px solid ${sent ? theme.border : theme.ring}`,
              backgroundColor: theme.card,
              padding: '18px 22px',
              fontSize: 25,
              lineHeight: 1.5,
              color: shown ? theme.foreground : theme.mutedForeground,
              boxShadow: sent ? 'none' : '0 0 0 6px rgba(109,90,230,0.14)',
            }}
          >
            {shown || 'Tell the brand why this campaign is a fit for you…'}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginTop: 24 }}>
            <div
              style={{
                padding: '18px 42px',
                borderRadius: 14,
                backgroundColor: sent ? theme.successForeground : theme.primary,
                color: '#ffffff',
                fontSize: 28,
                fontWeight: 800,
                boxShadow: '0 14px 36px rgba(109,90,230,0.32)',
                scale: interpolate(frame, [220, 232, 244], [1, 0.94, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                  output: 'perceptual-scale',
                }),
              }}
            >
              {sent ? 'Applied ✓' : 'Send application'}
            </div>
            <div style={{ fontSize: 23, color: theme.mutedForeground }}>
              Free to apply — no quote needed yet
            </div>
          </div>
        </div>
      </BrowserFrame>
    </>
  );
}

/**
 * Stage 3 — the application status ladder. Words are the literal
 * `APPLICATION_BUCKETS` labels from `src/lib/application-status.ts:51`.
 */
export function ShortlistScene() {
  const frame = useCurrentFrame();
  const stages = ['Applied', 'Shortlisted', 'In negotiation'];
  const reached = frame < 70 ? 0 : frame < 150 ? 1 : 2;
  return (
    <>
      <Caption kicker="Stage 3 — Get picked" text="Applied, shortlisted, talking" />
      <BrowserFrame url="app.influora.in/creator/applications">
        <div style={{ flex: 1, padding: '32px 46px', fontFamily }}>
          <div style={{ fontSize: 34, fontWeight: 800, color: theme.foreground, marginBottom: 24 }}>
            Your applications
          </div>

          <div
            style={{
              padding: '26px 30px',
              borderRadius: 16,
              backgroundColor: theme.card,
              border: `2px solid ${theme.border}`,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
              <span style={{ fontSize: 30, fontWeight: 800, color: theme.foreground }}>
                Diwali Glow Kit Launch
              </span>
              <span
                style={{
                  fontSize: 23,
                  fontWeight: 800,
                  padding: '8px 20px',
                  borderRadius: 999,
                  backgroundColor: reached === 2 ? theme.accent : theme.info,
                  color: reached === 2 ? theme.accentForeground : theme.infoForeground,
                }}
              >
                {stages[reached]}
              </span>
            </div>
            <div style={{ fontSize: 22, color: theme.mutedForeground, marginTop: 4 }}>Bloomveda</div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 0, marginTop: 34 }}>
              {stages.map((stage, index) => (
                <div key={stage} style={{ display: 'flex', alignItems: 'center', flex: index < 2 ? 1 : 0 }}>
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
                    <div
                      style={{
                        width: 54,
                        height: 54,
                        borderRadius: 999,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: 26,
                        fontWeight: 900,
                        backgroundColor: index <= reached ? theme.primary : theme.muted,
                        color: index <= reached ? theme.primaryForeground : theme.mutedForeground,
                        scale: interpolate(
                          frame,
                          [index === 1 ? 70 : index === 2 ? 150 : 10, (index === 1 ? 70 : index === 2 ? 150 : 10) + 12],
                          [index <= reached ? 0.7 : 1, 1],
                          {
                            extrapolateLeft: 'clamp',
                            extrapolateRight: 'clamp',
                            easing: Easing.bezier(0.16, 1, 0.3, 1),
                            output: 'perceptual-scale',
                          },
                        ),
                      }}
                    >
                      {index < reached ? '✓' : index + 1}
                    </div>
                    <div
                      style={{
                        fontSize: 23,
                        fontWeight: 700,
                        color: index <= reached ? theme.foreground : theme.mutedForeground,
                      }}
                    >
                      {stage}
                    </div>
                  </div>
                  {index < 2 ? (
                    <div
                      style={{
                        flex: 1,
                        height: 4,
                        margin: '0 18px',
                        marginBottom: 34,
                        borderRadius: 999,
                        backgroundColor: index < reached ? theme.primary : theme.border,
                      }}
                    />
                  ) : null}
                </div>
              ))}
            </div>
          </div>

          <div
            style={{
              marginTop: 26,
              fontSize: 25,
              color: theme.mutedForeground,
              ...fade(frame, 190),
            }}
          >
            Every application shows where it actually stands — including the ones that go nowhere.
          </div>
        </div>
      </BrowserFrame>
    </>
  );
}

/**
 * Stage 4 — the proposal. Fields mirror the real proposal form: typed
 * deliverables with quantities, Amount (INR), and a Deadline
 * (`proposal-form.tsx:235, :319, :363`).
 */
export function NegotiateScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Stage 4 — Agree the rate" text="Your rate, your deliverables" />
      <DealRoomShell
        active={0}
        title="Bloomveda"
        subtitle="Diwali Glow Kit Launch · your proposal"
        url="app.influora.in/creator/deals/dl_8f21/room"
      >
        <div style={{ display: 'flex', gap: 28, height: '100%' }}>
          <div style={{ flex: 1.2 }}>
            <div style={{ fontSize: 23, fontWeight: 700, color: theme.mutedForeground, marginBottom: 14 }}>
              What you will deliver
            </div>
            {[
              ['Instagram Reel', '2'],
              ['Instagram Story', '4'],
              ['Static Post', '1'],
            ].map(([type, qty], index) => (
              <div
                key={type}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 16,
                  padding: '15px 22px',
                  marginBottom: 12,
                  borderRadius: 12,
                  backgroundColor: theme.card,
                  border: `2px solid ${theme.border}`,
                  ...fade(frame, 10 + index * 16, 12),
                }}
              >
                <span style={{ flex: 1, fontSize: 25, color: theme.foreground }}>{type}</span>
                <span
                  style={{
                    padding: '6px 18px',
                    borderRadius: 10,
                    backgroundColor: theme.muted,
                    fontSize: 24,
                    fontWeight: 800,
                    color: theme.foreground,
                  }}
                >
                  ×{qty}
                </span>
              </div>
            ))}

            <div style={{ fontSize: 23, fontWeight: 700, color: theme.mutedForeground, margin: '24px 0 12px' }}>
              Deadline
            </div>
            <div
              style={{
                padding: '15px 22px',
                borderRadius: 12,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                fontSize: 25,
                color: theme.foreground,
                ...fade(frame, 70, 12),
              }}
            >
              1 Nov 2026
            </div>
          </div>

          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 23, fontWeight: 700, color: theme.mutedForeground, marginBottom: 14 }}>
              Amount (INR)
            </div>
            <div
              style={{
                padding: '30px 30px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `3px solid ${theme.primary}`,
                ...fade(frame, 90),
              }}
            >
              <div style={{ fontSize: 60, fontWeight: 900, color: theme.foreground, letterSpacing: -2 }}>
                ₹
                {Math.round(
                  interpolate(frame, [100, 170], [0, 28000], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }) / 1000,
                )}
                ,000
              </div>
              <div style={{ fontSize: 22, color: theme.mutedForeground, marginTop: 8 }}>
                Your rate for the work above
              </div>
            </div>

            <div
              style={{
                marginTop: 22,
                padding: '19px 26px',
                borderRadius: 14,
                backgroundColor: theme.primary,
                color: theme.primaryForeground,
                textAlign: 'center',
                fontSize: 27,
                fontWeight: 800,
                boxShadow: '0 16px 40px rgba(109,90,230,0.34)',
                ...fade(frame, 186, 12),
              }}
            >
              Send proposal
            </div>
            <div
              style={{
                marginTop: 16,
                fontSize: 22,
                color: theme.mutedForeground,
                textAlign: 'center',
                ...fade(frame, 208, 10),
              }}
            >
              The brand can accept it, or send a counter offer back
            </div>
          </div>
        </div>
      </DealRoomShell>
    </>
  );
}

/** Stage 6 — the money is secured before the creator starts. */
export function SecuredScene() {
  const frame = useCurrentFrame();
  const funded = frame >= 110;
  return (
    <>
      <Caption kicker="Stage 6 — Money secured" text="Paid in before you start" />
      <BrowserFrame url="app.influora.in/creator/wallet">
        <div style={{ flex: 1, padding: '30px 46px', fontFamily }}>
          <div
            style={{
              borderRadius: 20,
              padding: '30px 36px',
              background: 'linear-gradient(140deg, #6d5ae6 0%, #4c3bc2 100%)',
              color: '#ffffff',
              display: 'flex',
              gap: 40,
              alignItems: 'flex-end',
            }}
          >
            <div style={{ flex: 1.2 }}>
              <div style={{ fontSize: 24, opacity: 0.85 }}>Available Balance</div>
              <div style={{ fontSize: 62, fontWeight: 900, letterSpacing: -2 }}>₹4,500</div>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 22, opacity: 0.85 }}>Secured</div>
              <div
                style={{
                  fontSize: 46,
                  fontWeight: 900,
                  letterSpacing: -1,
                  scale: interpolate(frame, [110, 122, 136], funded ? [0.8, 1.08, 1] : [1, 1, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                    output: 'perceptual-scale',
                  }),
                }}
              >
                ₹
                {Math.round(
                  interpolate(frame, [110, 150], [0, 28000], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }) / 1000,
                )}
                ,000
              </div>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 22, opacity: 0.85 }}>Pending Payouts</div>
              <div style={{ fontSize: 46, fontWeight: 900, letterSpacing: -1 }}>₹0</div>
            </div>
          </div>

          <div
            style={{
              marginTop: 30,
              padding: '26px 30px',
              borderRadius: 16,
              backgroundColor: theme.card,
              border: `3px solid ${theme.primary}`,
              ...fade(frame, 160),
            }}
          >
            <div style={{ fontSize: 28, fontWeight: 800, color: theme.foreground, marginBottom: 10 }}>
              Bloomveda secured ₹28,000 for this deal
            </div>
            <div style={{ fontSize: 25, lineHeight: 1.5, color: theme.mutedForeground }}>
              Not withdrawable yet — it moves to Available Balance once you deliver and it is approved.
              But it is in, and you can see it, before you shoot a single frame.
            </div>
          </div>
        </div>
      </BrowserFrame>
    </>
  );
}

/** Stage 7 — submit the work for review. */
export function CreatorDeliverScene() {
  const frame = useCurrentFrame();
  const submitted = frame >= 150;
  return (
    <>
      <Caption kicker="Stage 7 — Deliver" text="Submit for review" />
      <DealRoomShell
        active={3}
        title="Deliverables"
        subtitle="Diwali Glow Kit Launch · Bloomveda"
        url="app.influora.in/creator/deals/dl_8f21/room"
      >
        {[
          { name: 'Instagram Reel 1', state: submitted ? 'SUBMITTED' : 'DRAFT', at: 14 },
          { name: 'Instagram Reel 2', state: 'PENDING', at: 34 },
          { name: 'Story set (4)', state: 'PENDING', at: 50 },
          { name: 'Static Post', state: 'PENDING', at: 66 },
        ].map((item) => (
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
              border: `2px solid ${item.state === 'PENDING' ? theme.border : theme.primary}`,
              ...fade(frame, item.at),
            }}
          >
            <div
              style={{
                width: 54,
                height: 54,
                borderRadius: 12,
                flexShrink: 0,
                backgroundColor: item.state === 'PENDING' ? theme.muted : theme.accent,
                color: item.state === 'PENDING' ? theme.mutedForeground : theme.accentForeground,
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
                {item.state === 'SUBMITTED'
                  ? 'Sent to Bloomveda for review'
                  : item.state === 'DRAFT'
                    ? 'Ready to send'
                    : 'Not started'}
              </div>
            </div>
            <div
              style={{
                padding: '9px 20px',
                borderRadius: 999,
                fontSize: 21,
                fontWeight: 800,
                letterSpacing: 0.6,
                backgroundColor: item.state === 'SUBMITTED' ? theme.info : theme.muted,
                color: item.state === 'SUBMITTED' ? theme.infoForeground : theme.mutedForeground,
              }}
            >
              {item.state}
            </div>
          </div>
        ))}
        <div
          style={{
            marginTop: 8,
            fontSize: 24,
            color: theme.mutedForeground,
            ...fade(frame, 200),
          }}
        >
          If they ask for a revision, you see exactly what they want changed.
        </div>
      </DealRoomShell>
    </>
  );
}

/**
 * Stage 8 — approved money becomes withdrawable, then a payout is queued.
 *
 * Two distinct steps on purpose: the escrow release lands in the Influora
 * wallet (Available Balance); the withdrawal is a separate RazorpayX payout
 * that is only ever QUEUED and confirmed later by webhook — hence
 * "Pending Payouts", never "paid instantly".
 */
export function PaidScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Stage 8 — Get paid" text="Approved becomes withdrawable" />
      <BrowserFrame url="app.influora.in/creator/wallet">
        <div style={{ flex: 1, padding: '30px 46px', fontFamily }}>
          <div
            style={{
              borderRadius: 20,
              padding: '30px 36px',
              background: 'linear-gradient(140deg, #6d5ae6 0%, #4c3bc2 100%)',
              color: '#ffffff',
              display: 'flex',
              gap: 40,
              alignItems: 'flex-end',
            }}
          >
            <div style={{ flex: 1.2 }}>
              <div style={{ fontSize: 24, opacity: 0.85 }}>Available Balance</div>
              {/*
                Rises when the release lands, then falls again when the withdrawal is
                requested — "Pending Payouts" is money already deducted from Available
                Balance (creator-wallet.tsx:764), so leaving it in both would count it twice.
              */}
              <div style={{ fontSize: 62, fontWeight: 900, letterSpacing: -2 }}>
                ₹
                {Math.round(
                  interpolate(frame, [40, 100, 250, 300], [4500, 11500, 11500, 4500], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }),
                ).toLocaleString('en-IN')}
              </div>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 22, opacity: 0.85 }}>Secured</div>
              <div style={{ fontSize: 46, fontWeight: 900, letterSpacing: -1 }}>
                ₹
                {Math.round(
                  interpolate(frame, [40, 100], [28000, 21000], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }) / 1000,
                )}
                ,000
              </div>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 22, opacity: 0.85 }}>Pending Payouts</div>
              <div style={{ fontSize: 46, fontWeight: 900, letterSpacing: -1 }}>
                ₹
                {Math.round(
                  interpolate(frame, [250, 300], [0, 7000], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }),
                ).toLocaleString('en-IN')}
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 24, marginTop: 28 }}>
            <div
              style={{
                flex: 1,
                padding: '22px 26px',
                borderRadius: 16,
                backgroundColor: theme.success,
                border: `2px solid ${theme.successForeground}`,
                ...fade(frame, 108),
              }}
            >
              <div style={{ fontSize: 26, fontWeight: 800, color: theme.successForeground }}>
                Reel 1 approved — ₹7,000 released
              </div>
              <div style={{ fontSize: 22, color: theme.successForeground, marginTop: 6, opacity: 0.9 }}>
                Moved from Secured into Available Balance. Yours now.
              </div>
            </div>
            <div
              style={{
                flex: 1,
                padding: '22px 26px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `3px solid ${theme.primary}`,
                ...fade(frame, 230),
              }}
            >
              <div style={{ fontSize: 26, fontWeight: 800, color: theme.foreground }}>
                Withdrawn to HDFC ••4417
              </div>
              <div style={{ fontSize: 22, color: theme.mutedForeground, marginTop: 6 }}>
                Sits in Pending Payouts until your bank confirms it landed.
              </div>
            </div>
          </div>
        </div>
      </BrowserFrame>
    </>
  );
}
