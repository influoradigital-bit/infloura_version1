/**
 * `resolveWalkthroughEmbed` — the allowlist standing between an environment variable and an
 * `<iframe src>`.
 *
 * The threat is mundane and therefore likely: this URL is set per-deploy in `.env`, by hand.
 * A typo, a copy-paste from the wrong tab, a stale value carried into production, or an
 * `http://` link — piped straight into an iframe, any of those turns a help page into a frame
 * for content nobody at Influora chose. A denylist cannot enumerate what to block, so this is
 * an allowlist and unrecognised input must resolve to `null`, not "probably fine".
 *
 * Run: npx vitest run src/lib/__tests__/walkthrough-video.test.ts
 */

import { describe, it, expect } from 'vitest';
import { resolveWalkthroughEmbed } from '@/lib/walkthrough-video';

describe('resolveWalkthroughEmbed — accepted hosts', () => {
  it('turns a YouTube watch URL into its embed form', () => {
    expect(resolveWalkthroughEmbed('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toEqual({
      kind: 'iframe',
      src: 'https://www.youtube.com/embed/dQw4w9WgXcQ',
    });
  });

  it('accepts a youtu.be short link', () => {
    expect(resolveWalkthroughEmbed('https://youtu.be/dQw4w9WgXcQ')).toEqual({
      kind: 'iframe',
      src: 'https://www.youtube.com/embed/dQw4w9WgXcQ',
    });
  });

  it('accepts an already-embed YouTube URL without double-wrapping it', () => {
    expect(resolveWalkthroughEmbed('https://www.youtube.com/embed/dQw4w9WgXcQ')).toEqual({
      kind: 'iframe',
      src: 'https://www.youtube.com/embed/dQw4w9WgXcQ',
    });
  });

  it('accepts Vimeo in both share and player form', () => {
    expect(resolveWalkthroughEmbed('https://vimeo.com/123456789')).toEqual({
      kind: 'iframe',
      src: 'https://player.vimeo.com/video/123456789',
    });
    expect(resolveWalkthroughEmbed('https://player.vimeo.com/video/123456789')).toEqual({
      kind: 'iframe',
      src: 'https://player.vimeo.com/video/123456789',
    });
  });

  it('accepts a media file we serve ourselves, as a <video> not an iframe', () => {
    const r = resolveWalkthroughEmbed('https://cdn.influora.in/walkthrough/brand.mp4');
    expect(r).toEqual({ kind: 'file', src: 'https://cdn.influora.in/walkthrough/brand.mp4' });
  });
});

describe('resolveWalkthroughEmbed — rejected input', () => {
  it.each([
    ['an arbitrary host', 'https://evil.example.com/embed/whatever'],
    ['a lookalike host', 'https://youtube.com.evil.example.com/watch?v=abc'],
    ['plain http', 'http://www.youtube.com/watch?v=dQw4w9WgXcQ'],
    ['a javascript: URL', 'javascript:alert(1)'],
    ['a data: URL', 'data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=='],
    ['a YouTube URL with no video id', 'https://www.youtube.com/'],
    ['a Vimeo URL with a non-numeric id', 'https://vimeo.com/notanid'],
    ['a non-video file', 'https://cdn.influora.in/walkthrough/brand.pdf'],
    ['a .mp4 on a host we do not serve', 'https://evil.example.com/walkthrough.mp4'],
    ['a .mp4 on a lookalike of our own domain', 'https://influora.in.evil.example.com/a.mp4'],
    ['nonsense', 'not a url at all'],
    ['an empty string', ''],
  ])('rejects %s', (_label, url) => {
    expect(resolveWalkthroughEmbed(url)).toBeNull();
  });

  it('never returns a src pointing anywhere but an allowlisted host', () => {
    // The property that matters, stated directly: whatever comes back, its origin is one we
    // chose — never one the input chose.
    const ALLOWED = ['https://www.youtube.com/', 'https://player.vimeo.com/', 'https://cdn.influora.in/'];
    const inputs = [
      'https://www.youtube.com/watch?v=abc123',
      'https://youtu.be/abc123',
      'https://vimeo.com/999',
      'https://cdn.influora.in/a.mp4',
      'https://evil.example.com/x.mp4?a=https://www.youtube.com/',
    ];
    for (const input of inputs) {
      const r = resolveWalkthroughEmbed(input);
      if (r === null) continue;
      expect(ALLOWED.some((prefix) => r.src.startsWith(prefix))).toBe(true);
    }
  });
});
