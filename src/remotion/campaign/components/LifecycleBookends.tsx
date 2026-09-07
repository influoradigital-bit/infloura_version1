import { Easing, Interactive, interpolate, useCurrentFrame } from 'remotion';

import { fontFamily, theme } from '../../theme';
import { Caption } from './Caption';
import { ChipRow, Field } from './Field';
import { FormShell } from './FormShell';

/** Opening card for the lifecycle film. */
export function LifecycleHook() {
  const frame = useCurrentFrame();
  return (
    <Interactive.Div
      name="Lifecycle hook background"
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
        name="Lifecycle eyebrow"
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
        Influora for brands
      </Interactive.Div>
      <Interactive.Div
        name="Lifecycle title"
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
        How a campaign works
      </Interactive.Div>
      <Interactive.Div
        name="Lifecycle chapters"
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
        Brief → Applications → Negotiate → Contract → Secure funds → Deliver → Pay → Results
      </Interactive.Div>
    </Interactive.Div>
  );
}

/**
 * Stage 1, compressed. The full walkthrough is the `CampaignDemo` composition;
 * here the five steps flick past so the runtime can go to the later stages.
 * Step labels are the real ones from `campaign-form.tsx:228`.
 */
export function CreateMontageScene() {
  const frame = useCurrentFrame();
  const step = frame < 60 ? 0 : frame < 110 ? 1 : frame < 160 ? 2 : frame < 210 ? 3 : 4;
  return (
    <>
      <Caption kicker="Stage 1 — Create" text="Brief in five steps" />
      {step === 0 ? (
        <FormShell active={0} heading="Basics" sub="Campaign details">
          <Field
            label="Campaign Title"
            placeholder="e.g., Summer Collection Launch 2024"
            value="Diwali Glow Kit Launch"
            from={10}
          />
          <Field label="End Brand Name" placeholder="e.g., Kavala Skincare" value="Bloomveda" from={38} />
        </FormShell>
      ) : null}
      {step === 1 ? (
        <FormShell active={1} heading="Content" sub="Platforms & formats">
          <ChipRow
            label="Target Platforms"
            options={['Instagram', 'YouTube', 'TikTok', 'X/Twitter', 'LinkedIn']}
            selected={['Instagram', 'YouTube']}
            from={64}
            stagger={12}
          />
          <ChipRow
            label="Content Types"
            options={['Static Post', 'Story', 'Reel/Short', 'Long Video']}
            selected={['Reel/Short', 'Story', 'Static Post']}
            from={82}
            stagger={10}
          />
        </FormShell>
      ) : null}
      {step === 2 ? (
        <FormShell active={2} heading="Budget" sub="Timeline & budget">
          <div style={{ display: 'flex', gap: 26 }}>
            <div style={{ flex: 1 }}>
              <Field label="Start Date" placeholder="Pick a date" value="12 Oct 2026" from={114} />
            </div>
            <div style={{ flex: 1 }}>
              <Field label="End Date" placeholder="Pick a date" value="06 Nov 2026" from={128} />
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <span style={{ fontSize: 26, fontWeight: 600, color: theme.foreground }}>Budget Range</span>
            <span style={{ fontSize: 32, fontWeight: 800, color: theme.accentForeground }}>
              ₹15,000 – ₹60,000
            </span>
          </div>
        </FormShell>
      ) : null}
      {step === 3 ? (
        <FormShell active={3} heading="Requirements" sub="Creator criteria">
          <ChipRow
            label="Campaign Hashtags"
            options={['#BloomvedaGlow', '#DiwaliSkincare']}
            selected={['#BloomvedaGlow', '#DiwaliSkincare']}
            from={166}
            stagger={12}
          />
          <ChipRow
            label="Target Audience"
            options={['skincare enthusiasts', 'beauty lovers', 'festive gifting']}
            selected={['skincare enthusiasts', 'beauty lovers', 'festive gifting']}
            from={186}
            stagger={10}
          />
        </FormShell>
      ) : null}
      {step === 4 ? (
        <FormShell active={4} heading="Review Campaign" sub="Review your campaign details before publishing">
          <div
            style={{
              position: 'absolute',
              inset: 0,
              backgroundColor: theme.background,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 18,
              opacity: interpolate(frame, [214, 236], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            <div
              style={{
                width: 100,
                height: 100,
                borderRadius: 999,
                backgroundColor: theme.success,
                color: theme.successForeground,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 52,
                fontWeight: 900,
              }}
            >
              ✓
            </div>
            <div style={{ fontSize: 42, fontWeight: 800, color: theme.foreground }}>Campaign is live</div>
          </div>
        </FormShell>
      ) : null}
    </>
  );
}

/** Closing card: the whole arc, then the call to action. */
export function LifecycleOutro() {
  const frame = useCurrentFrame();
  const stages = ['Brief', 'Applications', 'Contract', 'Secure funds', 'Deliver', 'Pay', 'Results'];
  return (
    <Interactive.Div
      name="Lifecycle outro background"
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
        name="Lifecycle outro line"
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
        One campaign, one place
      </Interactive.Div>
      <Interactive.Div
        name="Lifecycle outro CTA"
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
        Start your campaign on Influora
      </Interactive.Div>
    </Interactive.Div>
  );
}
