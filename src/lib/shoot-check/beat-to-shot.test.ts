/**
 * shotFromBeat: a script-card beat becomes the shot the in-chat photo check is told about
 * (SPEC section 2a, Ash review item 10).
 */
import { describe, expect, it } from 'vitest';

import { serializeShotContext } from '@/lib/meera-api';
import type { ParsedMeeraScript } from '@/lib/meera-result-cards';
import {
  ON_CAMERA_FACE,
  ON_CAMERA_HANDS_ONLY,
  ON_CAMERA_VOICE_OVER,
  SHOT_LABEL_MAX_CHARS,
  shotFromBeat,
  shotFromLabel,
  targetForShot,
} from './beat-to-shot';

const SCRIPT: ParsedMeeraScript = {
  idea: '3 saffron mistakes to avoid',
  plan: 'for new saffron buyers; curiosity; grow followers; 30s',
  action: 'hold up the saffron box and speak to camera',
  setup: 'sit facing the window, light on your left; phone at eye height, an arm away, 1x lens',
  beats: [
    { from: 0, to: 3, shot: 'Close-up on your face - hold up the saffron box', say: 'Is your saffron even real?', onScreen: 'REAL vs FAKE' },
    { from: 3, to: 10, shot: 'Overhead on your hands - do the warm-water color test', say: 'Watch what happens in water', onScreen: 'THE WATER TEST' },
    { from: 10, to: 20, shot: 'Wide shot of the kitchen counter', say: 'This is where it all starts', onScreen: 'THE SET-UP' },
    { from: 20, to: 30, shot: 'Medium on the certificate - hold it next to the box', say: 'This is what real saffron looks like', onScreen: 'GI CERTIFIED' },
  ],
  caption: 'Would you have spotted the fake one?',
  beforeYouShoot: ['Charge your phone', 'Wipe the counter', 'Keep the certificate close'],
  whyThisWorks: 'Problem-agitate-solve',
};

describe('shotFromBeat', () => {
  it('labels the shot "<from>-<to>s · <shot>", with the beat length as seconds and its index', () => {
    const shot = shotFromBeat(SCRIPT, 1);
    expect(shot.label).toBe('3-10s · Overhead on your hands - do the warm-water color test');
    expect(shot.seconds).toBe(7);
    expect(shot.index).toBe(1);
  });

  it('clips the label at 120 characters, the same cut Java makes', () => {
    const long = { ...SCRIPT, beats: [{ ...SCRIPT.beats[0], shot: `Close-up - ${'y'.repeat(300)}` }] };
    const shot = shotFromBeat(long, 0);
    expect(shot.label.length).toBe(SHOT_LABEL_MAX_CHARS);
    expect(shot.label.startsWith('0-3s · Close-up - yyy')).toBe(true);
  });

  it('never leaves half an emoji at the 120 cut', () => {
    // "0-3s · " is 7 units; 112 x's put the emoji's high surrogate at unit 120.
    const long = { ...SCRIPT, beats: [{ ...SCRIPT.beats[0], shot: `${'x'.repeat(112)}\u{1F4F7} more` }] };
    const label = shotFromBeat(long, 0).label;
    expect(label.length).toBeLessThanOrEqual(SHOT_LABEL_MAX_CHARS);
    const last = label.charCodeAt(label.length - 1);
    expect(last >= 0xd800 && last <= 0xdbff).toBe(false);
  });

  it('splits "angle - action" on the first dash; line is the shot only, never the Say dialogue', () => {
    const { context } = shotFromBeat(SCRIPT, 0);
    expect(context?.angle).toBe('Close-up on your face');
    expect(context?.action).toBe('hold up the saffron box');
    expect(context?.line).toBe('Close-up on your face - hold up the saffron box');
    expect(JSON.stringify(context)).not.toContain('Is your saffron even real?');
  });

  it('leaves angle and action out when the shot has no " - " split', () => {
    const { context } = shotFromBeat(SCRIPT, 2);
    expect(context?.angle).toBeUndefined();
    expect(context?.action).toBeUndefined();
    expect(context?.line).toBe('Wide shot of the kitchen counter');
  });

  it('sends the script Set-up as where, and no where for a script without one', () => {
    expect(shotFromBeat(SCRIPT, 0).context?.where).toBe(SCRIPT.setup);
    expect(shotFromBeat({ ...SCRIPT, setup: undefined }, 0).context?.where).toBeUndefined();
  });

  it('maps the shot to a framing target', () => {
    expect(shotFromBeat(SCRIPT, 0).target).toBe('closeup');
    expect(shotFromBeat(SCRIPT, 1).target).toBe('hands-overhead');
    expect(shotFromBeat(SCRIPT, 2).target).toBe('wide');
    expect(shotFromBeat(SCRIPT, 3).target).toBe('medium');
    expect(targetForShot('Top-down on the plate')).toBe('hands-overhead');
    expect(targetForShot('Top down on the plate')).toBe('hands-overhead');
    expect(targetForShot('Full body walking in')).toBe('wide');
    // "close" wins over "hands", the order the spec gives.
    expect(targetForShot('Close-up on your hands')).toBe('closeup');
  });

  it('sets on_camera only when the beat settles it, in the coach bank option words', () => {
    expect(shotFromBeat(SCRIPT, 0).context?.on_camera).toBe(ON_CAMERA_FACE);
    expect(shotFromBeat(SCRIPT, 1).context?.on_camera).toBe(ON_CAMERA_HANDS_ONLY);
    expect(shotFromBeat(SCRIPT, 2).context?.on_camera).toBeUndefined();
    expect(shotFromBeat(SCRIPT, 3).context?.on_camera).toBeUndefined();
    const vo = { ...SCRIPT, beats: [{ ...SCRIPT.beats[3], shot: 'B-roll of the box, voice-over' }] };
    expect(shotFromBeat(vo, 0).context?.on_camera).toBe(ON_CAMERA_VOICE_OVER);
    // A bare "you" does not put the face in frame.
    const typing = { ...SCRIPT, beats: [{ ...SCRIPT.beats[3], shot: 'Over the shoulder as you type' }] };
    expect(shotFromBeat(typing, 0).context?.on_camera).toBeUndefined();
  });

  it('keeps on_camera through the serializer even when a long Set-up fills the budget', () => {
    // line, angle, action and where each fill their 300-character value cap: well over 1000.
    const shot = `Overhead on your hands ${'a'.repeat(300)} - ${'q'.repeat(400)}`;
    const longSetup = { ...SCRIPT, setup: 'z'.repeat(900), beats: [{ ...SCRIPT.beats[1], shot }] };
    const context = shotFromBeat(longSetup, 0).context!;
    expect(JSON.stringify(context).length).toBeGreaterThan(1000);
    const json = serializeShotContext(context);
    expect(json).not.toBeNull();
    expect(JSON.parse(json!).on_camera).toBe(ON_CAMERA_HANDS_ONLY);
  });

  it('collapses a multi-line shot to one line', () => {
    const multi = { ...SCRIPT, beats: [{ ...SCRIPT.beats[0], shot: 'Close-up\n on your face -\n smile' }] };
    const shot = shotFromBeat(multi, 0);
    expect(shot.label).toBe('0-3s · Close-up on your face - smile');
    expect(shot.label).not.toMatch(/[\r\n]/);
  });

  it('throws on a beat index the script does not have', () => {
    expect(() => shotFromBeat(SCRIPT, 9)).toThrow(RangeError);
  });
});

describe('shotFromLabel — "Check again" on a card rehydrated from history', () => {
  it('gives every beat of the script back its own target, angle/action and on_camera', () => {
    SCRIPT.beats.forEach((_, i) => {
      const fromBeat = shotFromBeat(SCRIPT, i);
      const fromLabel = shotFromLabel(fromBeat.label);
      expect(fromLabel.label).toBe(fromBeat.label);
      expect(fromLabel.target).toBe(fromBeat.target);
      expect(fromLabel.seconds).toBe(fromBeat.seconds);
      // Everything but the script's Set-up line, which a label does not carry.
      const beatContext = { ...fromBeat.context! };
      delete beatContext.where;
      expect(fromLabel.context).toEqual(beatContext);
    });
  });

  it('reads the committed history fixture\'s label as a face-to-camera medium shot', () => {
    const shot = shotFromLabel('0-3s · Talking to camera by the window');
    expect(shot).toEqual({
      index: 0,
      label: '0-3s · Talking to camera by the window',
      seconds: 3,
      target: 'medium',
      context: { line: 'Talking to camera by the window', on_camera: ON_CAMERA_FACE },
    });
    expect(shotFromLabel('3-6s · Khaane ka close-up').target).toBe('closeup');
    expect(shotFromLabel('10-20s · Overhead on your hands - pour the water').context?.on_camera).toBe(ON_CAMERA_HANDS_ONLY);
  });

  it('keeps a label with no timing prefix whole', () => {
    const shot = shotFromLabel('Wide shot of the shop');
    expect(shot.target).toBe('wide');
    expect(shot.seconds).toBe(0);
    expect(shot.context?.line).toBe('Wide shot of the shop');
  });
});
