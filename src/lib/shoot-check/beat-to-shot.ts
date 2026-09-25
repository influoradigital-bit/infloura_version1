/**
 * A script card's beat as a photo-check shot (SPEC section 2a, with Ash's review item 10).
 *
 * "Check my set-up" on a script-card beat opens the in-chat camera on that beat; the shot built
 * here is what the check is told the creator is trying to film:
 *   - `label` "<from>-<to>s · <shot>", one line, at most 120 UTF-16 units (Java's own cut);
 *   - `context.line` is the beat's `shot` ONLY. The Say dialogue does nothing for framing, and
 *     every character it took pushed the keys that matter out of the 1000-character budget;
 *   - `context.angle` / `context.action` come from the "angle - action" split of `shot`;
 *   - `context.where` is the script's Set-up line when it has one;
 *   - `context.on_camera` is set when the beat itself settles it (the coach question bank's own
 *     option words: "Hands only", "Voice-over", "Face on camera"), so the check does not ask a
 *     question the script already answers. When the beat does not say, it is left out and the
 *     check may still ask;
 *   - `target` picks the framing guide: close -> closeup, overhead/top-down/hands ->
 *     hands-overhead, wide/full body -> wide, anything else -> medium.
 */
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import type { MeeraShotContext } from '@/lib/meera-api';
import type { ParsedMeeraScript } from '@/lib/meera-result-cards';
import type { ShotTarget } from '@/lib/shoot-check/metrics';

/** Java's cut for `shot_label` (CreatorMeeraController). */
export const SHOT_LABEL_MAX_CHARS = 120;

/** The coach bank's `on_camera` option words (influora-ai video_content_concepts.jsonl). */
export const ON_CAMERA_HANDS_ONLY = 'Hands only';
export const ON_CAMERA_VOICE_OVER = 'Voice-over';
export const ON_CAMERA_FACE = 'Face on camera';

function oneLine(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

/** At most `max` UTF-16 units — Java's `String.length()`, so Spring's own 120 cut never cuts it
 *  again — without leaving half a surrogate pair at the end. */
function clip(text: string, max: number): string {
  if (text.length <= max) return text;
  let out = text.slice(0, max);
  const last = out.charCodeAt(out.length - 1);
  if (last >= 0xd800 && last <= 0xdbff) out = out.slice(0, -1);
  return out.trimEnd();
}

export function targetForShot(shot: string): ShotTarget {
  if (/close/i.test(shot)) return 'closeup';
  if (/overhead|top.?down|hands/i.test(shot)) return 'hands-overhead';
  if (/wide|full body/i.test(shot)) return 'wide';
  return 'medium';
}

function onCameraFor(shot: string, target: ShotTarget): string | undefined {
  if (target === 'hands-overhead') return ON_CAMERA_HANDS_ONLY;
  if (/voice.?over/i.test(shot)) return ON_CAMERA_VOICE_OVER;
  // Only words that clearly put the face in frame. A bare "you" does not ("over the shoulder as you
  // type"), and a wrong answer here would silence the one question that would have caught it.
  if (/\b(?:face|faces|selfie|talking head|to camera|eye contact)\b/i.test(shot)) return ON_CAMERA_FACE;
  return undefined;
}

/** `shot` split on its first " - " (also an en or em dash): "angle - action". */
function splitAngleAction(shot: string): { angle?: string; action?: string } {
  const match = /^(.*?)\s+[-–—]\s+(.+)$/.exec(shot);
  if (!match) return {};
  const angle = match[1].trim();
  const action = match[2].trim();
  return { ...(angle ? { angle } : {}), ...(action ? { action } : {}) };
}

export function shotFromBeat(script: ParsedMeeraScript, beatIndex: number): ShootCheckShot {
  const beat = script.beats[beatIndex];
  if (!beat) throw new RangeError(`shotFromBeat: no beat ${beatIndex} in a script of ${script.beats.length}`);

  const shot = oneLine(beat.shot);
  const target = targetForShot(shot);
  const context: MeeraShotContext = { line: shot, ...splitAngleAction(shot) };
  const where = script.setup ? oneLine(script.setup) : '';
  if (where) context.where = where;
  const onCamera = onCameraFor(shot, target);
  if (onCamera) context.on_camera = onCamera;

  return {
    index: beatIndex,
    label: clip(`${beat.from}-${beat.to}s · ${shot}`, SHOT_LABEL_MAX_CHARS),
    seconds: Math.max(0, beat.to - beat.from),
    target,
    context,
  };
}

/** "<from>-<to>s · " at the start of a label `shotFromBeat` built. */
const LABEL_TIMING_RE = /^(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)s\s*·\s*/;

/**
 * A shot rebuilt from a stored photo-check label, for "Check again" on a card that came back from
 * history (it keeps only its label, not the beat). The "<from>-<to>s · " prefix gives `seconds`;
 * the rest is the beat's shot line, which gives the framing target, `angle` / `action` and
 * `on_camera` exactly as `shotFromBeat` does — so a close-up or overhead beat gets its own framing
 * guide again instead of "medium", and the check does not re-ask what the shot line settles. The
 * script's Set-up line (`where`) is not part of a label, so it is left out.
 */
export function shotFromLabel(label: string): ShootCheckShot {
  const clean = oneLine(label);
  const timing = LABEL_TIMING_RE.exec(clean);
  const shot = timing ? clean.slice(timing[0].length).trim() : clean;
  const target = targetForShot(shot);
  const context: MeeraShotContext = shot ? { line: shot, ...splitAngleAction(shot) } : {};
  const onCamera = shot ? onCameraFor(shot, target) : undefined;
  if (onCamera) context.on_camera = onCamera;

  return {
    index: 0,
    label: clip(clean, SHOT_LABEL_MAX_CHARS),
    seconds: timing ? Math.max(0, Number(timing[2]) - Number(timing[1])) : 0,
    target,
    ...(Object.keys(context).length > 0 ? { context } : {}),
  };
}
