import * as React from 'react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { ShootCheckPanel } from '@/components/creator/shoot-check/ShootCheckPanel';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ApiError, api } from '@/lib/api';
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
  // Camera knowledge v5 (2026-09-24): the phone they film on, saved on their Meera settings. The
  // frame check reads the SAVED value on the server (Spring forwards it), so the tips only fit
  // a phone once it is saved here or in Settings -> Meera -> My phone.
  const [phoneText, setPhoneText] = React.useState('');
  const [savedPhone, setSavedPhone] = React.useState<string | null>(null);
  const [phoneSaving, setPhoneSaving] = React.useState(false);
  const [phoneMessage, setPhoneMessage] = React.useState<string | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    api.creatorAgentPrefs
      .getPreferences()
      .then((prefs) => {
        if (cancelled) return;
        if (prefs.creator_language === 'hi-IN' || prefs.creator_language === 'en-IN') {
          setLang(prefs.creator_language);
        }
        setPhoneText(prefs.phone_model ?? '');
        setSavedPhone(prefs.phone_model ?? '');
      })
      .catch(() => {
        // Preference lookup failing is never fatal here — Shoot Check just stays in English.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const phoneChanged = savedPhone !== null && phoneText.trim() !== savedPhone;

  const savePhone = async () => {
    setPhoneSaving(true);
    setPhoneMessage(null);
    try {
      const prefs = await api.creatorAgentPrefs.updatePhoneModel(phoneText.trim() || null);
      setPhoneText(prefs.phone_model ?? '');
      setSavedPhone(prefs.phone_model ?? '');
      setPhoneMessage(prefs.phone_model ? 'Saved. Tips will fit this phone.' : 'Phone cleared.');
    } catch (err) {
      setPhoneMessage(err instanceof ApiError ? err.message : 'Could not save your phone.');
    } finally {
      setPhoneSaving(false);
    }
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-2xl px-4 py-6">
        <div className="mb-4">
          <h1 className="text-xl font-semibold text-foreground">Shoot check</h1>
          <p className="text-sm text-muted-foreground">Live camera coaching before you hit record.</p>
        </div>
        <div className="mb-4 space-y-1.5 rounded-lg border border-border bg-card p-3">
          <Label htmlFor="shoot-check-phone" className="text-sm font-medium">
            Which phone are you filming on?
          </Label>
          <div className="flex gap-2">
            <Input
              id="shoot-check-phone"
              value={phoneText}
              maxLength={80}
              placeholder="For example: OPPO Reno 14 Pro"
              onChange={(e) => setPhoneText(e.target.value)}
              disabled={savedPhone === null}
            />
            <Button type="button" onClick={savePhone} disabled={!phoneChanged || phoneSaving}>
              {phoneSaving ? 'Saving…' : 'Save'}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground" aria-live="polite">
            {phoneMessage ?? 'Optional. The photo check suggests only settings your phone has.'}
          </p>
        </div>
        <ShootCheckPanel lang={lang} />
      </div>
    </CreatorLayout>
  );
}
