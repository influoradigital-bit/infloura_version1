import * as React from 'react';

import {
  resolveWalkthroughEmbed,
  walkthroughVideoUrl,
  type WalkthroughRole,
} from '@/lib/walkthrough-video';

/**
 * The product walkthrough video, embedded where the confusion actually happens.
 *
 * A video that lives on a drive, in a deck, or on a social post reaches nobody who is already
 * logged in and stuck — which is the only audience it was recorded for. This puts it at the top
 * of the in-app how-it-works pages, which the first-run checklist links to.
 *
 * Renders **nothing** until a URL is configured for the role, and nothing if that URL is not one
 * this app knows how to embed. See `@/lib/walkthrough-video` for the setting and the allowlist.
 */
interface WalkthroughVideoProps {
  role: WalkthroughRole;
  /** Accessible name for the player. */
  title: string;
  className?: string;
}

export function WalkthroughVideo({ role, title, className }: WalkthroughVideoProps) {
  const url = walkthroughVideoUrl(role);
  const embed = url ? resolveWalkthroughEmbed(url) : null;
  if (!embed) return null;

  return (
    <div className={className}>
      <div className="relative aspect-video w-full overflow-hidden rounded-xl border border-border bg-muted">
        {embed.kind === 'iframe' ? (
          <iframe
            src={embed.src}
            title={title}
            className="absolute inset-0 h-full w-full"
            allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
            allowFullScreen
            loading="lazy"
            referrerPolicy="strict-origin-when-cross-origin"
          />
        ) : (
          // No caption track ships with the walkthrough yet. The step list rendered directly
          // below repeats every point the video makes, in full, as text — which is the
          // accessible equivalent this particular content needs. Add a <track> here the moment
          // captions exist.
          <video
            src={embed.src}
            title={title}
            controls
            preload="metadata"
            className="absolute inset-0 h-full w-full"
          />
        )}
      </div>
    </div>
  );
}

export default WalkthroughVideo;
