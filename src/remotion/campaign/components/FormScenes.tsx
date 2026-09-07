import { Easing, interpolate, useCurrentFrame } from 'remotion';

import { theme } from '../../theme';
import { TYPE } from '../theme';
import { Caption } from './Caption';
import { ChipRow, Field } from './Field';
import { FormShell } from './FormShell';

/**
 * Steps 1-4 of the real form. Every `label` prop below is the literal
 * `<Label>` text from `src/components/brand/campaigns/campaign-form.tsx`, and
 * every `placeholder` is that field's real placeholder. Values are invented
 * demo data for a fictional D2C skincare brand, "Bloomveda".
 */

/** Basics, part one — Campaign Title (L912) and Description (L926). */
export function BasicsTitleScene() {
  return (
    <>
      <Caption kicker="Step 1 — Basics" text="Campaign Title and Description" />
      <FormShell active={0} heading="Basics" sub="Campaign details">
        <Field
          label="Campaign Title"
          placeholder="e.g., Summer Collection Launch 2024"
          value="Diwali Glow Kit Launch"
          from={18}
        />
        <Field
          label="Description"
          placeholder="Describe your campaign goals, target audience, and what you're looking for from creators..."
          value="Launching our new Diwali gift sets. Looking for skincare and beauty creators to show real routines and help us reach new customers this festive season."
          from={72}
          multiline
          height={150}
        />
      </FormShell>
    </>
  );
}

/** Basics, part two — End Brand Name (L946), Category (L959), Objectives (L986). */
export function BasicsBrandScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Step 1 — Basics" text="End Brand and Objectives" />
      <FormShell active={0} heading="Basics" sub="Campaign details">
        <div style={{ display: 'flex', gap: 26 }}>
          <div style={{ flex: 1 }}>
            <Field
              label="End Brand Name"
              placeholder="e.g., Kavala Skincare"
              value="Bloomveda"
              from={16}
            />
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: TYPE.label, fontWeight: 600, color: theme.foreground, marginBottom: 10 }}>
              End Brand Category
            </div>
            <div
              style={{
                height: 64,
                borderRadius: 12,
                border: `2px solid ${frame >= 58 ? theme.ring : theme.border}`,
                backgroundColor: theme.card,
                padding: '0 20px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                fontSize: TYPE.input,
                color: frame >= 58 ? theme.foreground : theme.mutedForeground,
              }}
            >
              <span>{frame >= 58 ? 'Beauty' : 'Select a category'}</span>
              <span style={{ color: theme.mutedForeground, fontSize: 22 }}>▾</span>
            </div>
          </div>
        </div>
        <div style={{ marginTop: 16 }}>
          <ChipRow
            label="Campaign Objectives"
            options={[
              'Brand Awareness',
              'Drive Sales',
              'Product Launch',
              'Engagement Growth',
              'Lead Generation',
              'App Downloads',
            ]}
            selected={['Product Launch', 'Drive Sales']}
            from={88}
            stagger={20}
          />
        </div>
      </FormShell>
    </>
  );
}

/** Content — Target Platforms (L1046) and Content Types (L1081). */
export function ContentScene() {
  return (
    <>
      <Caption kicker="Step 2 — Content" text="Platforms and content types" />
      <FormShell active={1} heading="Content" sub="Platforms & formats">
        <ChipRow
          label="Target Platforms"
          options={['Instagram', 'YouTube', 'TikTok', 'X/Twitter', 'LinkedIn', 'Facebook', 'Twitch']}
          selected={['Instagram', 'YouTube']}
          from={22}
          stagger={22}
        />
        <div style={{ marginTop: 24 }}>
          <ChipRow
            label="Content Types"
            options={['Static Post', 'Story', 'Reel/Short', 'Long Video', 'Live Stream', 'Blog/Article', 'Podcast']}
            selected={['Reel/Short', 'Static Post', 'Story']}
            from={112}
            stagger={22}
          />
        </div>
      </FormShell>
    </>
  );
}

/** Budget — Start Date (L1132), End Date (L1174), Budget Range (L1222), Maximum Collaborators (L1313). */
export function BudgetScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Step 3 — Budget" text="Timeline, budget, collaborators" />
      <FormShell active={2} heading="Budget" sub="Timeline & budget">
        <div style={{ display: 'flex', gap: 26 }}>
          <div style={{ flex: 1 }}>
            <Field label="Start Date" placeholder="Pick a date" value="12 Oct 2026" from={14} />
          </div>
          <div style={{ flex: 1 }}>
            <Field label="End Date" placeholder="Pick a date" value="06 Nov 2026" from={44} />
          </div>
        </div>

        <div style={{ marginTop: 6, marginBottom: 30 }}>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'baseline',
              marginBottom: 16,
            }}
          >
            <span style={{ fontSize: TYPE.label, fontWeight: 600, color: theme.foreground }}>Budget Range</span>
            <span style={{ fontSize: 32, fontWeight: 800, color: theme.accentForeground }}>
              ₹15,000 – ₹
              {Math.round(
                interpolate(frame, [80, 140], [25000, 60000], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }) / 1000,
              )}
              ,000
            </span>
          </div>
          <div
            style={{
              position: 'relative',
              height: 14,
              borderRadius: 999,
              backgroundColor: theme.muted,
            }}
          >
            <div
              style={{
                position: 'absolute',
                left: '12%',
                height: 14,
                borderRadius: 999,
                backgroundColor: theme.primary,
                width: interpolate(frame, [80, 140], ['30%', '76%'], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }),
              }}
            />
            <div
              style={{
                position: 'absolute',
                top: -9,
                width: 32,
                height: 32,
                borderRadius: 999,
                backgroundColor: theme.card,
                border: `4px solid ${theme.primary}`,
                boxShadow: '0 4px 14px rgba(34,30,53,0.18)',
                left: interpolate(frame, [80, 140], ['41%', '87%'], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }),
              }}
            />
          </div>
        </div>

        <div>
          <div style={{ fontSize: TYPE.label, fontWeight: 600, color: theme.foreground, marginBottom: 12 }}>
            Maximum Collaborators
          </div>
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 26,
              padding: '12px 26px',
              borderRadius: 14,
              border: `2px solid ${theme.border}`,
              backgroundColor: theme.card,
            }}
          >
            <span style={{ fontSize: 30, color: theme.mutedForeground }}>−</span>
            <span style={{ fontSize: 40, fontWeight: 800, color: theme.foreground, minWidth: 60, textAlign: 'center' }}>
              {Math.round(
                interpolate(frame, [156, 196], [10, 12], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                }),
              )}
            </span>
            <span style={{ fontSize: 30, color: theme.mutedForeground }}>+</span>
          </div>
          <span style={{ fontSize: TYPE.helper, color: theme.mutedForeground, marginLeft: 20 }}>
            creators on this campaign
          </span>
        </div>
      </FormShell>
    </>
  );
}

/** Requirements — Content Requirements (L1345), Campaign Hashtags (L1388), Target Audience (L1432). */
export function RequirementsScene() {
  const frame = useCurrentFrame();
  return (
    <>
      <Caption kicker="Step 4 — Requirements" text="Rules, hashtags and audience" />
      <FormShell active={3} heading="Requirements" sub="Creator criteria">
        <div style={{ fontSize: TYPE.label, fontWeight: 600, color: theme.foreground, marginBottom: 12 }}>
          Content Requirements
        </div>
        {['Must mention Bloomveda by name', 'Show the product being used'].map((item, index) => (
          <div
            key={item}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 14,
              padding: '15px 20px',
              marginBottom: 10,
              borderRadius: 12,
              backgroundColor: theme.card,
              border: `2px solid ${theme.border}`,
              fontSize: TYPE.input,
              color: theme.foreground,
              opacity: interpolate(frame, [14 + index * 26, 34 + index * 26], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(frame, [14 + index * 26, 38 + index * 26], ['-16px 0px', '0px 0px'], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            <span style={{ color: theme.successForeground, fontSize: 26 }}>✓</span>
            {item}
          </div>
        ))}

        <div style={{ marginTop: 26 }}>
          <ChipRow
            label="Campaign Hashtags"
            options={['#BloomvedaGlow', '#DiwaliSkincare']}
            selected={['#BloomvedaGlow', '#DiwaliSkincare']}
            from={82}
            stagger={20}
          />
        </div>

        <div style={{ marginTop: 14 }}>
          <ChipRow
            label="Target Audience"
            options={['skincare enthusiasts', 'beauty lovers', 'festive gifting']}
            selected={['skincare enthusiasts', 'beauty lovers', 'festive gifting']}
            from={140}
            stagger={18}
          />
        </div>
      </FormShell>
    </>
  );
}
