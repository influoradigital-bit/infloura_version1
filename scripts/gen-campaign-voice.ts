/**
 * Generates the narration for the "How to create a campaign" demo with Sarvam TTS.
 *
 * Run:   SARVAM_API_KEY=... npx vite-node scripts/gen-campaign-voice.ts [--force]
 *        add `--only publish` to regenerate a single line while iterating on copy.
 *
 * Same request shape as `scripts/gen-meera-voice.ts` so both demos sound like the
 * same product: bulbul:v3, 24 kHz WAV, speaker "neha" (Swapnil's pick, 2026-09-05),
 * pace 1.0, temperature 0.6, `en-IN`.
 *
 * Output: `public/campaign-voice/<scene id>.wav` (served by Vite and by Remotion's
 * `staticFile`) plus `src/remotion/campaign/voice-manifest.ts` with each clip's
 * length, which `scene-meta.ts` uses to stretch any scene whose authored duration
 * is shorter than its narration. Existing clips are reused unless `--force`.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

import { CREATOR_SCRIPT } from '../src/remotion/campaign/creator-script';
import { LIFECYCLE_SCRIPT } from '../src/remotion/campaign/lifecycle-script';
import { CAMPAIGN_SCRIPT } from '../src/remotion/campaign/script';

const ROOT = path.resolve(__dirname, '..');

const FORCE = process.argv.includes('--force');
const argOf = (flag: string): string | undefined => {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : undefined;
};
const ONLY = argOf('--only');
/** Which film to narrate: the creation deep-dive, or the full lifecycle. */
const WHICH = argOf('--script') ?? 'campaign';
const SCRIPTS = {
  campaign: {
    lines: CAMPAIGN_SCRIPT,
    dir: path.join(ROOT, 'public', 'campaign-voice'),
    prefix: 'campaign-voice',
    manifest: path.join(ROOT, 'src', 'remotion', 'campaign', 'voice-manifest.ts'),
    constName: 'CAMPAIGN_VOICE',
  },
  lifecycle: {
    lines: LIFECYCLE_SCRIPT,
    dir: path.join(ROOT, 'public', 'lifecycle-voice'),
    prefix: 'lifecycle-voice',
    manifest: path.join(ROOT, 'src', 'remotion', 'campaign', 'lifecycle-voice-manifest.ts'),
    constName: 'LIFECYCLE_VOICE',
  },
  creator: {
    lines: CREATOR_SCRIPT,
    dir: path.join(ROOT, 'public', 'creator-voice'),
    prefix: 'creator-voice',
    manifest: path.join(ROOT, 'src', 'remotion', 'campaign', 'creator-voice-manifest.ts'),
    constName: 'CREATOR_VOICE',
  },
} as const;
const chosen = SCRIPTS[WHICH as keyof typeof SCRIPTS];
if (!chosen) {
  console.error(`unknown --script ${WHICH} (expected campaign|lifecycle|creator)`);
  process.exit(1);
}
const OUT_DIR = chosen.dir;
const MANIFEST = chosen.manifest;
const SPEAKER = argOf('--speaker') ?? 'neha';
const PACE = Number(argOf('--pace') ?? '1.0');
const TEMP = Number(argOf('--temp') ?? '0.6');

const apiKey = process.env.SARVAM_API_KEY;
if (!apiKey) {
  console.error('SARVAM_API_KEY is not set');
  process.exit(1);
}

/** Turn on-screen copy into something a TTS voice should say. */
function speakable(text: string): string {
  return text
    .replace(/₹\s?([\d,]+)/g, '$1 rupees')
    .replace(/…/g, '.')
    .replace(/·/g, ',')
    .replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/gu, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function wavSeconds(buf: Buffer): number {
  const channels = buf.readUInt16LE(22);
  const sampleRate = buf.readUInt32LE(24);
  const bitsPerSample = buf.readUInt16LE(34);
  let offset = 12;
  while (offset + 8 <= buf.length) {
    const id = buf.toString('ascii', offset, offset + 4);
    const size = buf.readUInt32LE(offset + 4);
    if (id === 'data') {
      return size / (sampleRate * channels * (bitsPerSample / 8));
    }
    offset += 8 + size + (size % 2);
  }
  throw new Error('no data chunk');
}

async function synth(text: string): Promise<Buffer> {
  const res = await fetch('https://api.sarvam.ai/text-to-speech', {
    method: 'POST',
    headers: { 'api-subscription-key': apiKey as string, 'content-type': 'application/json' },
    body: JSON.stringify({
      inputs: [text],
      target_language_code: 'en-IN',
      speaker: SPEAKER,
      model: 'bulbul:v3',
      pace: PACE,
      temperature: TEMP,
      speech_sample_rate: 24000,
    }),
  });
  if (!res.ok) {
    throw new Error(`Sarvam ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }
  const data = (await res.json()) as { audios?: string[] };
  const b64 = data.audios?.[0];
  if (!b64) throw new Error('Sarvam returned no audio');
  return Buffer.from(b64, 'base64');
}

async function main() {
  mkdirSync(OUT_DIR, { recursive: true });
  const manifest: Record<string, { file: string; seconds: number }> = {};
  let generated = 0;

  for (const scene of chosen.lines) {
    if (ONLY && scene.id !== ONLY) continue;
    const file = path.join(OUT_DIR, `${scene.id}.wav`);
    let buf: Buffer;
    if (!FORCE && existsSync(file)) {
      buf = readFileSync(file);
    } else {
      buf = await synth(speakable(scene.voice));
      writeFileSync(file, buf);
      generated += 1;
      console.log(`generated ${scene.id} (${buf.length} bytes)`);
    }
    const seconds = Number(wavSeconds(buf).toFixed(3));
    manifest[scene.id] = { file: `${chosen.prefix}/${scene.id}.wav`, seconds };
    console.log(`  ${scene.id.padEnd(14)} authored ${scene.seconds}s  spoken ${seconds}s`);
  }

  if (!ONLY) {
    const body =
      '// Generated by scripts/gen-campaign-voice.ts — do not edit by hand.\n' +
      `export const ${chosen.constName}: Record<string, { file: string; seconds: number }> = ` +
      JSON.stringify(manifest, null, 2) +
      ';\n';
    writeFileSync(MANIFEST, body);
  }
  console.log(
    `${WHICH}: ${chosen.lines.length} lines, ${generated} newly generated${ONLY ? '' : ', manifest written'}`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
