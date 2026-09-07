import { Easing, Interactive, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily, theme } from '../../theme';
import { Caption } from './Caption';
import { DealRoomShell } from './DealRoomShell';

/** Opening card for the creator film. */
export function CreatorHook() {
  const frame = useCurrentFrame();
  return (
    <Interactive.Div
      name="Creator hook background"
      style={{
        position: 'absolute',
        inset: 0,
        background: 'linear-gradient(160deg, #6d5ae6 0%, #4c3bc2 100%)',
        color: '#ffffff',
        fontFamily,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
      }}
    >
      <Interactive.Div
        name="Creator eyebrow"
        style={{
          fontSize: 30,
          fontWeight: 800,
          letterSpacing: 3,
          textTransform: 'uppercase',
          backgroundColor: 'rgba(255,255,255,0.18)',
          padding: '14px 34px',
          borderRadius: 999,
          opacity: interpolate(frame, [4, 22], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
        }}
      >
        Influora for creators
      </Interactive.Div>
      <Interactive.Div
        name="Creator title"
        style={{
          marginTop: 44,
          fontSize: 124,
          fontWeight: 900,
          letterSpacing: -4,
          lineHeight: 1.05,
          opacity: interpolate(frame, [14, 34], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
          translate: interpolate(frame, [14, 38], ['0px 30px', '0px 0px'], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
        }}
      >
        Know when you get paid
      </Interactive.Div>
      <Interactive.Div
        name="Creator chapters"
        style={{
          marginTop: 34,
          fontSize: 40,
          fontWeight: 600,
          lineHeight: 1.5,
          maxWidth: 1400,
          opacity: interpolate(frame, [34, 58], [0, 0.94], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
        }}
      >
        Find work → Apply → Agree the rate → Contract → Money secured → Deliver → Paid
      </Interactive.Div>
    </Interactive.Div>
  );
}

/** Stage 5 — contract, from the creator's side of the signature list. */
export function CreatorContractScene() {
  const frame = useCurrentFrame();
  const creatorSigned = frame >= 100;
  const brandSigned = frame >= 170;
  return (
    <>
      <Caption kicker="Stage 5 — Contract" text="Both sides sign" />
      <DealRoomShell
        active={1}
        title="Contract"
        subtitle="Diwali Glow Kit Launch · Bloomveda"
        url="app.influora.in/creator/deals/dl_8f21/room"
      >
        <div style={{ display: 'flex', gap: 26, height: '100%' }}>
          <div style={{ flex: 1.2 }}>
            <div
              style={{
                padding: '24px 28px',
                borderRadius: 16,
                backgroundColor: theme.card,
                border: `2px solid ${theme.border}`,
                opacity: interpolate(frame, [6, 22], [0, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }),
              }}
            >
              <div style={{ fontSize: 26, fontWeight: 800, color: theme.foreground, marginBottom: 16 }}>
                What you agreed
              </div>
              {[
                ['You get paid', '₹28,000'],
                ['You deliver', '2 Reels, 4 Stories, 1 Post'],
                ['Usage rights', '90 days, paid ads allowed'],
                ['First draft due', '19 Oct 2026'],
                ['Revisions included', '2'],
              ].map(([label, value], index) => (
                <div
                  key={label}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    padding: '13px 0',
                    borderBottom: `1px solid ${theme.border}`,
                    fontSize: 24,
                    opacity: interpolate(frame, [16 + index * 10, 32 + index * 10], [0, 1], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                      easing: Easing.bezier(0.16, 1, 0.3, 1),
                    }),
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
                opacity: interpolate(frame, [40, 58], [0, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }),
              }}
            >
              <div style={{ fontSize: 26, fontWeight: 800, color: theme.foreground, marginBottom: 20 }}>
                Signature progress
              </div>
              {[
                { party: 'Ritika Sharma', role: 'You', signed: creatorSigned },
                { party: 'Bloomveda', role: 'Brand', signed: brandSigned },
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
                  opacity: interpolate(frame, [178, 202], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                    easing: Easing.bezier(0.16, 1, 0.3, 1),
                  }),
                }}
              >
                Signed by both — it is on paper now
              </div>
            </div>
          </div>
        </div>
      </DealRoomShell>
    </>
  );
}

/** Closing card. */
export function CreatorOutro() {
  const frame = useCurrentFrame();
  const stages = ['Find work', 'Apply', 'Agree rate', 'Contract', 'Secured', 'Deliver', 'Paid'];
  return (
    <Interactive.Div
      name="Creator outro background"
      style={{
        position: 'absolute',
        inset: 0,
        background: 'linear-gradient(160deg, #6d5ae6 0%, #4c3bc2 100%)',
        color: '#ffffff',
        fontFamily,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
      }}
    >
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, justifyContent: 'center', maxWidth: 1500 }}>
        {stages.map((stage, index) => (
          <div
            key={stage}
            style={{
              padding: '16px 30px',
              borderRadius: 999,
              backgroundColor: 'rgba(255,255,255,0.16)',
              fontSize: 34,
              fontWeight: 700,
              opacity: interpolate(frame, [6 + index * 9, 24 + index * 9], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              scale: interpolate(frame, [6 + index * 9, 26 + index * 9], [0.86, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
                easing: Easing.bezier(0.16, 1, 0.3, 1),
                output: 'perceptual-scale',
              }),
            }}
          >
            {stage}
          </div>
        ))}
      </div>
      <Interactive.Div
        name="Creator outro line"
        style={{
          marginTop: 54,
          fontSize: 62,
          fontWeight: 900,
          letterSpacing: -1.5,
          opacity: interpolate(frame, [86, 110], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
        }}
      >
        No chasing invoices
      </Interactive.Div>
      <Interactive.Div
        name="Creator outro CTA"
        style={{
          marginTop: 44,
          backgroundColor: '#ffffff',
          color: '#4c3bc2',
          fontSize: 42,
          fontWeight: 800,
          padding: '26px 58px',
          borderRadius: 999,
          boxShadow: '0 24px 60px rgba(0,0,0,0.28)',
          opacity: interpolate(frame, [116, 140], [0, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
          }),
          scale: interpolate(frame, [116, 144], [0.88, 1], {
            extrapolateLeft: 'clamp',
            extrapolateRight: 'clamp',
            easing: Easing.bezier(0.16, 1, 0.3, 1),
            output: 'perceptual-scale',
          }),
        }}
      >
        Join Influora as a creator
      </Interactive.Div>
    </Interactive.Div>
  );
}
