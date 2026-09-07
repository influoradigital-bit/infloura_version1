/**
 * Where the product walkthrough video lives, and how to turn a share URL into an embeddable one.
 *
 * In `lib/` rather than beside the component so both are testable without React, and so the
 * first-run checklist can ask "is there a video?" without importing a player it will not render.
 */

/**
 * Which walkthrough a surface wants.
 *
 * `brand`/`creator` are the whole-lifecycle films the how-it-works pages show. `brandCampaign`
 * is the narrower "how do I write a brief" film, shown on the new-campaign screen where that is
 * the actual question — a union member rather than a second mechanism, so every consumer keeps
 * using one lookup and one allowlist.
 */
export type WalkthroughRole = 'brand' | 'creator' | 'brandCampaign';

/**
 * The films we render ourselves and ship in `public/videos/` (sources in
 * `src/remotion/campaign/`, rendered with `npx remotion render <id>`).
 *
 * These are the fallback, not the rule: an env var still wins, so a deployment can point at a
 * YouTube cut or a CDN without a code change. Unlike the env value these paths are **code** —
 * written here, reviewed here, and not settable by whoever edits a deploy file — which is why
 * hardcoding them does not weaken the allowlist below. They still go through
 * `resolveWalkthroughEmbed` like any other URL.
 */
const BUNDLED: Record<WalkthroughRole, string> = {
  brand: '/videos/campaign-lifecycle.mp4',
  creator: '/videos/creator-lifecycle.mp4',
  brandCampaign: '/videos/how-to-create-a-campaign.mp4',
};

/**
 * The URL to play for a role, or `null` when the deployment has switched it off.
 *
 * Precedence, in order:
 *  1. The role's `VITE_WALKTHROUGH_VIDEO_*_URL` when it is set to a non-empty value.
 *  2. Set-but-empty means **off** — `null`, and every consumer renders nothing. That is the
 *     only way to suppress a film, and it has to be explicit.
 *  3. Unset falls back to the bundled film in `BUNDLED`.
 *
 *     VITE_WALKTHROUGH_VIDEO_BRAND_URL=https://www.youtube.com/watch?v=...
 *     VITE_WALKTHROUGH_VIDEO_CREATOR_URL=https://influora.in/videos/creator-lifecycle.mp4
 *     VITE_WALKTHROUGH_VIDEO_BRAND_CAMPAIGN_URL=
 *
 * A URL the allowlist does not recognise also renders nothing at all: no empty player, no
 * "coming soon" box, no broken iframe. An absent video is silence, not a placeholder
 * apologising for itself.
 */
export function walkthroughVideoUrl(role: WalkthroughRole): string | null {
  const env = import.meta.env as unknown as Record<string, string | undefined>;
  const raw =
    role === 'brand'
      ? env.VITE_WALKTHROUGH_VIDEO_BRAND_URL
      : role === 'creator'
        ? env.VITE_WALKTHROUGH_VIDEO_CREATOR_URL
        : env.VITE_WALKTHROUGH_VIDEO_BRAND_CAMPAIGN_URL;
  // `undefined` (never configured) and `''` (configured off) are different answers.
  if (raw === undefined) return BUNDLED[role];
  const trimmed = raw.trim();
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
  // A media file this same origin serves — how the bundled films in `public/videos/` are played,
  // so they work on localhost, on a preview host and in production without three configurations.
  //
  // `/` exactly once at the start is load-bearing. A PROTOCOL-RELATIVE `//evil.example.com/x.mp4`
  // is also "starts with a slash" and resolves to a completely different origin, which would
  // reintroduce the hole the host allowlist exists to close. `..` is excluded for the same
  // reason a path should not be able to climb out of where we put it.
  if (/^\/[^/]/.test(url) && !url.includes('..') && /\.(mp4|webm|mov)$/i.test(url.split('?')[0])) {
    return { kind: 'file', src: url };
  }

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
