/**
 * Generates Meera's voice clips for the demo composition with Sarvam TTS.
 *
 * Run:   SARVAM_API_KEY=... npx vite-node scripts/gen-meera-voice.ts --lang hi|en|mr [--force]
 *        add `--speaker neha --pace 1.0 --temp 0.6 --only setup-2 --suffix _neha` for A/B samples.
 *
 * Mirrors the request influora-ai makes for Meera's real voice
 * (`influora-ai/app/providers/sarvam.py` `speak`): bulbul:v3, 24 kHz WAV.
 * Speaker "neha" (Swapnil's pick, 2026-09-05), pace 1.0, temperature 0.6.
 * `target_language_code` comes from the locale (`hi-IN`, `en-IN`, `mr-IN`).
 *
 * Output: `public/meera-voice/<lang>/<id>.wav` (served by Vite and by
 * Remotion's `staticFile`) and the `<lang>` section of
 * `src/remotion/voice-manifest.ts` with each clip's length, which
 * `timing.ts` uses to hold every bubble at least as long as its audio.
 * Existing clips are reused unless `--force` is passed. The voice reads `say`
 * when present (native script for Hindi), otherwise the on-screen text
 * verbatim, so every spoken line is the complete sentence shown.
 */
import { mkdirSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

import { LOCALES } from '../src/remotion/locales';
import type { LangCode } from '../src/remotion/script';

const ROOT = path.resolve(__dirname, '..');
const MANIFEST = path.join(ROOT, 'src', 'remotion', 'voice-manifest.ts');
const FORCE = process.argv.includes('--force');
const argOf = (flag: string): string | undefined => {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : undefined;
};
const LANG = (argOf('--lang') ?? 'hi') as LangCode;
const SPEAKER = argOf('--speaker') ?? 'neha';
const PACE = Number(argOf('--pace') ?? '1.0');
const TEMP = Number(argOf('--temp') ?? '0.6');
const ONLY = argOf('--only');
const SUFFIX = argOf('--suffix') ?? '';

const locale = LOCALES[LANG];
if (!locale) {
  console.error(`unknown --lang ${LANG}`);
  process.exit(1);
}
const OUT_DIR = path.join(ROOT, 'public', 'meera-voice', LANG);

const apiKey = process.env.SARVAM_API_KEY;
if (!apiKey) {
  console.error('SARVAM_API_KEY is not set');
  process.exit(1);
}

interface Line {
  id: string;
  text: string;
}

/** Turn on-screen copy into something a TTS voice should say. */
function speakable(text: string): string {
  const rupees = LANG === 'en' ? '$1 rupees' : '$1 रुपये';
  return text
    .replace(/₹\s?([\d,]+)/g, rupees)
    .replace(/#ad/gi, 'hashtag ad')
    .replace(/…/g, '.')
    .replace(/·/g, ',')
    .replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/gu, '')
    .replace(/\s+/g, ' ')
    .trim();
}

const lines: Line[] = [{ id: 'intro', text: locale.intro.say ?? `${locale.intro.sub} ${locale.intro.line}` }];
for (const scene of locale.scenes) {
  scene.beats.forEach((beat, i) => {
    if (beat.kind === 'meera' || beat.kind === 'wa') {
      lines.push({ id: `${scene.id}-${i}`, text: beat.say ?? beat.text });
    }
  });
}
lines.push({
  id: 'outro',
  text: locale.outro.say ?? `${locale.outro.title}. ${locale.outro.sub} ${locale.outro.bullets.join('. ')}.`,
});

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
      target_language_code: locale.tts,
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

type Manifest = Partial<Record<LangCode, Record<string, { file: string; seconds: number }>>>;

function readManifest(): Manifest {
  if (!existsSync(MANIFEST)) return {};
  const src = readFileSync(MANIFEST, 'utf8');
  const json = src.slice(src.indexOf('= ') + 2).trim().replace(/;$/, '');
  return JSON.parse(json) as Manifest;
}

async function main() {
  mkdirSync(OUT_DIR, { recursive: true });
  const section: Record<string, { file: string; seconds: number }> = {};
  let generated = 0;
  for (const line of lines) {
    if (ONLY && line.id !== ONLY) continue;
    const file = path.join(OUT_DIR, `${line.id}${SUFFIX}.wav`);
    let buf: Buffer;
    if (!FORCE && existsSync(file)) {
      buf = readFileSync(file);
    } else {
      buf = await synth(speakable(line.text));
      writeFileSync(file, buf);
      generated += 1;
      console.log(`generated ${LANG}/${line.id} (${buf.length} bytes)`);
    }
    section[line.id] = { file: `meera-voice/${LANG}/${line.id}.wav`, seconds: Number(wavSeconds(buf).toFixed(3)) };
  }
  if (!ONLY) {
    const manifest = readManifest();
    manifest[LANG] = section;
    const body =
      '// Generated by scripts/gen-meera-voice.ts — do not edit by hand.\n' +
      "export const VOICE_MANIFEST: Partial<Record<'hi' | 'en' | 'mr', Record<string, { file: string; seconds: number }>>> = " +
      JSON.stringify(manifest, null, 2) +
      ';\n';
    writeFileSync(MANIFEST, body);
  }
  console.log(`${LANG}: ${lines.length} lines, ${generated} newly generated${ONLY ? '' : ', manifest written'}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
