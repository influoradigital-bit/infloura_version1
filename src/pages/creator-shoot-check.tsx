import * as React from 'react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { ShootCheckPanel } from '@/components/creator/shoot-check/ShootCheckPanel';
import { api } from '@/lib/api';
import type { ShootCheckLang } from '@/lib/shoot-check/advice-copy';

/**
 * Creator Shoot Check — route: /creator/shoot-check.
 *
 * Level 1 (live camera coaching via `useShootCheck`) + Level 2 ("Check my frame", a single still
 * sent to `/ai/shoot-check/frame`) both live inside `ShootCheckPanel`; this page's only job is
 * the `CreatorLayout` chrome and reading the creator's language preference the same way
 * `creator-copilot.tsx` does, so spoken cues and on-screen fix text follow the creator's actual
 * language choice instead of always defaulting to English.
 *
 * No shot script wiring yet — `ShootCheckPanel` falls back to a single `medium`-target shot on
 * its own when `shots` is omitted. A future caller (e.g. a campaign brief's shot list) can pass
 * `shots` once that data exists.
 */
export default function CreatorShootCheckPage() {
  // Swapnil 2026-09-23 ruling (see creator-copilot.tsx): English is the default; the stored
  // creator_language wins as soon as preferences load.
  const [lang, setLang] = React.useState<ShootCheckLang>('en-IN');

  React.useEffect(() => {
    let cancelled = false;
    api.creatorAgentPrefs
      .getPreferences()
      .then((prefs) => {
        if (cancelled) return;
        if (prefs.creator_language === 'hi-IN' || prefs.creator_language === 'en-IN') {
          setLang(prefs.creator_language);
        }
      })
      .catch(() => {
        // Preference lookup failing is never fatal here — Shoot Check just stays in English.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-2xl px-4 py-6">
        <div className="mb-4">
          <h1 className="text-xl font-semibold text-foreground">Shoot check</h1>
          <p className="text-sm text-muted-foreground">Live camera coaching before you hit record.</p>
        </div>
        <ShootCheckPanel lang={lang} />
      </div>
    </CreatorLayout>
  );
}
