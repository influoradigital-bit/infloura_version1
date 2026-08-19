/**
 * Where the product walkthrough video lives, and how to turn a share URL into an embeddable one.
 *
 * In `lib/` rather than beside the component so both are testable without React, and so the
 * first-run checklist can ask "is there a video?" without importing a player it will not render.
 */

export type WalkthroughRole = 'brand' | 'creator';

/**
 * The configured URL for a role, or `null` when none is set.
 *
 * The URL is **configuration, not code** — set it per role in `.env.local`:
 *
 *     VITE_WALKTHROUGH_VIDEO_BRAND_URL=https://www.youtube.com/watch?v=...
 *     VITE_WALKTHROUGH_VIDEO_CREATOR_URL=https://www.youtube.com/watch?v=...
 *
 * With nothing set every consumer renders nothing — no empty player, no "coming soon" box, no
 * broken iframe. An absent video is silence, not a placeholder apologising for itself.
 */
export function walkthroughVideoUrl(role: WalkthroughRole): string | null {
  const env = import.meta.env as unknown as Record<string, string | undefined>;
  const raw =
    role === 'brand'
      ? env.VITE_WALKTHROUGH_VIDEO_BRAND_URL
      : env.VITE_WALKTHROUGH_VIDEO_CREATOR_URL;
  const trimmed = raw?.trim();
  return trimmed ? trimmed : null;
}

export type WalkthroughEmbed =
  | { kind: 'iframe'; src: string }
  | { kind: 'file'; src: string };

/**
 * Hosts we serve our own media from. The `file` branch is gated on this, not on the filename:
 * a `.mp4` extension says nothing about who is serving it, so matching on the path alone would
 * accept `https://anywhere.example.com/x.mp4` and load media from a host nobody here chose.
 */
const SELF_HOSTED = (host: string): boolean =>
  host === 'influora.in' || host.endsWith('.influora.in');

/**
 * Resolve a share URL to something embeddable.
 *
 * An **allowlist**, deliberately. This value arrives from environment configuration, and piping
 * an arbitrary string into an `<iframe src>` is how one wrong line in a deploy turns a help page
 * into a frame for someone else's content. `https:` only, known video hosts only, or a media
 * file we serve ourselves. Anything unrecognised returns `null` and the component renders
 * nothing rather than embedding what it could not identify.
 */
export function resolveWalkthroughEmbed(url: string): WalkthroughEmbed | null {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'https:') return null;

  const host = parsed.hostname.replace(/^www\./, '');

  // YouTube — watch?v=, and already-embed URLs.
  if (host === 'youtube.com' || host === 'm.youtube.com') {
    const id = parsed.searchParams.get('v');
    if (id && /^[\w-]+$/.test(id)) return { kind: 'iframe', src: `https://www.youtube.com/embed/${id}` };
    const embedMatch = parsed.pathname.match(/^\/embed\/([\w-]+)$/);
    if (embedMatch) return { kind: 'iframe', src: `https://www.youtube.com/embed/${embedMatch[1]}` };
    return null;
  }
  if (host === 'youtu.be') {
    const id = parsed.pathname.slice(1);
    return /^[\w-]+$/.test(id) ? { kind: 'iframe', src: `https://www.youtube.com/embed/${id}` } : null;
  }

  // Vimeo — vimeo.com/<id> and player.vimeo.com/video/<id>.
  if (host === 'vimeo.com') {
    const id = parsed.pathname.slice(1).split('/')[0];
    return /^\d+$/.test(id) ? { kind: 'iframe', src: `https://player.vimeo.com/video/${id}` } : null;
  }
  if (host === 'player.vimeo.com') {
    const m = parsed.pathname.match(/^\/video\/(\d+)$/);
    return m ? { kind: 'iframe', src: `https://player.vimeo.com/video/${m[1]}` } : null;
  }

  // A media file we host ourselves — BOTH conditions, host and extension. Checking the
  // extension alone let any host through as a <video src>; caught by the "never returns a src
  // pointing anywhere but an allowlisted host" property test, not by any of the case-by-case
  // ones, which is the argument for stating the property directly.
  if (SELF_HOSTED(host) && /\.(mp4|webm|mov)$/i.test(parsed.pathname)) {
    return { kind: 'file', src: parsed.toString() };
  }

  return null;
}
