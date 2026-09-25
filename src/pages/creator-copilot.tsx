import * as React from 'react';
import { useSearchParams } from 'react-router-dom';
import { Sparkles } from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { ChallengeCard } from '@/components/creator/challenge/ChallengeCard';
import { CopilotPreviewCard } from '@/components/creator/copilot/CopilotPreviewCard';
import { DailySuggestionSection } from '@/components/creator/copilot/DailySuggestionSection';
import { PasteBriefCard } from '@/components/creator/copilot/PasteBriefCard';
import { useDailySuggestion } from '@/hooks/useDailySuggestion';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { ConsentScreen } from '@/components/meera/ConsentScreen';
import { MeeraCopilotChat } from '@/components/creator/MeeraCopilotChat';
import { MeeraHero } from '@/components/creator/meera/MeeraHero';
import { HeroCreditsChip } from '@/components/creator/credits/HeroCreditsChip';
import { api, ApiError } from '@/lib/api';
import { getCreatorSession } from '@/lib/auth-session';

/** T-MEERA-CREATOR-PHASE-A (A10) — first word of the saved display name, "there" if none. */
function firstNameOf(displayName?: string): string {
  const trimmed = displayName?.trim();
  return trimmed ? trimmed.split(/\s+/)[0] : 'there';
}

/**
 * Creator AI Co-pilot — route: /creator/copilot (Ananya A2). Shown to creators as "Meera"
 * (RULINGS 3, 2026-09-26: "Co-pilot" is a Microsoft name, so it leaves the frontend copy; the
 * route, file and component names stay).
 *
 * `?camera=1` (the old /creator/shoot-check link redirects here with it) opens Meera through the
 * same consent path as "Open Meera", then mounts the chat with `openCameraOnMount` so the chat
 * opens its camera sheet once connected. The param is removed (replace) once handled, so a reload
 * does not reopen the camera.
 *
 * Previously the daily-idea experience only existed as a card embedded in
 * creator-deals.tsx with no route or nav link of its own (unlike brand's
 * full /brand/meera page). This gives it a proper home, still built on the
 * same `DailySuggestionSection` + `useDailySuggestion` data layer — no
 * forked logic, no second copy of the card.
 *
 * `status === 'idle'` covers both "never connected" and "connected but
 * wrong account type" (see useDailySuggestion.ts's `requiresBusinessAccount`
 * doc comment) — in both cases there's no real suggestion yet, so this page
 * additionally renders a non-blocking `CopilotPreviewCard` above the section
 * so the value is visible before a creator connects Instagram. The real
 * Connect Instagram CTA (`IGConnectPrompt`) still renders via
 * `DailySuggestionSection` itself — this only adds an illustrative example,
 * it doesn't replace or duplicate the connect flow.
 */
export default function CreatorCopilotPage() {
  // Separate hook instance from the one inside DailySuggestionSection — same
  // react-query key, so it's deduped, not a second network call. Reading
  // `status` here is the only way to know pre-connect vs. post-connect from
  // outside the section without forking its internals.
  const { status } = useDailySuggestion();
  // T-TSOFF-0920 — `status === 'idle'` alone used to be enough, but 'idle' no longer implies the
  // feature can deliver: `useDailySuggestion` now returns 'disabled' when trend ingest is off, and
  // this card is the single worst thing to render in that state. It shows a hand-written example
  // idea ("Turn your morning routine into a 30-second reel", tagged "Skincare Routine") that the
  // model did not produce. It is labelled "Preview", which is honest while the feature works and
  // the creator is one Instagram connect away from the real thing — but with the feature switched
  // off it becomes a fabricated sample advertising something that will never arrive, which is the
  // exact failure mode the audit flagged elsewhere. The 'disabled' status never satisfies
  // `=== 'idle'`, so this is already false; the comment records WHY it must stay that way.
  const showPreview = status === 'idle';

  // T-MEERA-CREATOR-PHASE-A (A10, SPEC.md §4.7) — "Talk to Meera" entry with a DPDP consent
  // gate. Checked once via GET /creator/agent-preferences (consent_accepted) rather than by
  // provoking the 403 on a real turn — cheaper, and MeeraCopilotChat still handles a
  // CONSENT_REQUIRED error defensively in case consent is revoked mid-session.
  const firstName = React.useMemo(() => firstNameOf(getCreatorSession()?.displayName), []);
  const [checkingConsent, setCheckingConsent] = React.useState(false);
  const [showConsent, setShowConsent] = React.useState(false);
  const [chatOpen, setChatOpen] = React.useState(false);

  // 2026-09-22 — the chat's "Analyse a brief" button: bring the brief card below into view and put
  // the cursor in its paste box. The card keeps its own flow and its own 3-credit charge.
  const focusBriefCard = React.useCallback(() => {
    const el = document.getElementById('paste-brief');
    if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    el.querySelector('textarea')?.focus({ preventScroll: true });
  }, []);
  // Swapnil 2026-09-23: English by default; the creator's stored creator_language wins as
  // soon as preferences load, and Meera follows the language they write in.
  const [language, setLanguage] = React.useState('en-IN');
  const [consentLoadError, setConsentLoadError] = React.useState<string | null>(null);
  // T-MEERA-CREATOR-PHASE-A gate review fix (item 3) — MEERA_CREATOR_ENABLED rollback flag.
  // GET /creator/agent-preferences 404s with { code: 'FEATURE_DISABLED' } in the standard
  // envelope when it's off. No toast, no retry affordance — the entry point is replaced with a
  // calm, static explanation once we know. `null` (not checked yet) and `false` (checked,
  // enabled) both render the normal "Talk to Meera" entry — only an explicit `true` swaps it
  // out, so the entry point never has to hide, then flash back, then hide again.
  const [featureDisabled, setFeatureDisabled] = React.useState<boolean | null>(null);
  // U-2 — what the preferences probe said about consent. `null` until a probe lands (or when it
  // failed), so the paste card only short-circuits to the consent screen when consent is KNOWN to
  // be missing; otherwise the server's CONSENT_REQUIRED refusal is the backstop.
  const [consentAccepted, setConsentAccepted] = React.useState<boolean | null>(null);
  // Which entry point asked for consent. Accepting from "Open Meera" opens the chat, as before;
  // accepting from the paste card must not open a chat the creator did not ask for.
  const consentForRef = React.useRef<'chat' | 'paste'>('chat');

  // U-5 (RULINGS-U-0917.md R-U1) — "Ask Meera about this brief" fills the chat's composer, it
  // never sends. `prefillTokenRef` increments on every ask so a second click on the same brief
  // still appends (see MeeraCopilotChat's own token doc). `pendingBriefPromptRef` carries the
  // prompt across a consent detour: set right before the consent screen opens, consumed by
  // Accept, and dropped by Decline so a later PLAIN "Open Meera" never inherits a stale brief ask.
  const [prefillMessage, setPrefillMessage] = React.useState<{ text: string; token: number } | null>(
    null,
  );
  const prefillTokenRef = React.useRef(0);
  const pendingBriefPromptRef = React.useRef<string | null>(null);

  // Photo check in Meera (SPEC §4) — `?camera=1` asks for the camera sheet. `openCamera` is what
  // the mounted chat receives as `openCameraOnMount`; `cameraRequestedRef` carries the ask across
  // a consent detour, the same way `pendingBriefPromptRef` carries a brief prompt. Every entry
  // that can reach the consent screen for the chat sets it afresh (`openMeera`, the prompt entries,
  // a mid-session consent refusal), so a declined ask never leaks into a later open.
  const [searchParams, setSearchParams] = useSearchParams();
  const cameraParam = searchParams.get('camera') === '1';
  const [openCamera, setOpenCamera] = React.useState(false);
  const cameraRequestedRef = React.useRef(false);
  // Guards the param effect against running twice for one arrival (StrictMode's double effect).
  const cameraParamHandledRef = React.useRef(false);

  const clearCameraParam = React.useCallback(() => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete('camera');
        return next;
      },
      { replace: true },
    );
  }, [setSearchParams]);

  // Probe once on mount so the entry point never renders (then disappears) for a disabled
  // account — the same GET the "Open Meera" click already made, just run earlier and only
  // watched for the one error code. Any other failure here is swallowed; the click handler
  // below still surfaces it (unchanged behavior) when the creator actually tries to open Meera.
  React.useEffect(() => {
    let cancelled = false;
    api.creatorAgentPrefs
      .getPreferences()
      .then((prefs) => {
        if (cancelled) return;
        setLanguage(prefs.creator_language || 'en-IN');
        setConsentAccepted(prefs.consent_accepted);
        setFeatureDisabled(false);
      })
      .catch((err) => {
        if (cancelled) return;
        setFeatureDisabled(err instanceof ApiError && err.code === 'FEATURE_DISABLED');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /** `withCamera` is true only for the `?camera=1` arrival; "Open Meera" never opens the camera. */
  const openMeera = async (withCamera = false) => {
    setConsentLoadError(null);
    setCheckingConsent(true);
    cameraRequestedRef.current = withCamera;
    try {
      const prefs = await api.creatorAgentPrefs.getPreferences();
      setLanguage(prefs.creator_language || 'en-IN');
      setConsentAccepted(prefs.consent_accepted);
      if (prefs.consent_accepted) {
        // Plain "Open Meera" never carries a leftover brief prompt from an earlier ask.
        setPrefillMessage(null);
        setOpenCamera(withCamera);
        setChatOpen(true);
      } else {
        consentForRef.current = 'chat';
        setShowConsent(true);
      }
    } catch (err) {
      if (err instanceof ApiError && err.code === 'FEATURE_DISABLED') {
        // Flipped off between the mount probe and this click — same calm, no-retry treatment,
        // no toast. The entry point hides itself on the next render.
        setFeatureDisabled(true);
      } else {
        setConsentLoadError(err instanceof ApiError ? err.message : "Couldn't reach Meera — try again.");
      }
    } finally {
      setCheckingConsent(false);
    }
  };

  // `?camera=1` arrival: open Meera through the normal consent-checked path, once per arrival.
  // Re-arms when the param is gone, so a second arrival (another tap on an old Shoot Check link)
  // is handled again.
  React.useEffect(() => {
    if (!cameraParam) {
      cameraParamHandledRef.current = false;
      return;
    }
    if (cameraParamHandledRef.current) return;
    cameraParamHandledRef.current = true;
    void openMeera(true);
    // openMeera is recreated every render; this must run per arrival of the param, not per render.
  }, [cameraParam]);

  // Drop the param once it is spent: the chat is open (it now holds `openCameraOnMount`), or the
  // feature is off (only the page shows). Declining consent drops it in the Decline handler.
  React.useEffect(() => {
    if (cameraParam && (chatOpen || featureDisabled === true)) clearCameraParam();
  }, [cameraParam, chatOpen, featureDisabled, clearCameraParam]);

  const handleAcceptConsent = async () => {
    await api.creatorAgentPrefs.recordConsent();
    setShowConsent(false);
    setConsentAccepted(true);
    if (consentForRef.current === 'chat') {
      if (pendingBriefPromptRef.current) {
        prefillTokenRef.current += 1;
        setPrefillMessage({ text: pendingBriefPromptRef.current, token: prefillTokenRef.current });
      } else {
        setPrefillMessage(null);
      }
      setOpenCamera(cameraRequestedRef.current);
      setChatOpen(true);
    }
    pendingBriefPromptRef.current = null;
    consentForRef.current = 'chat';
  };

  const requestConsentForPaste = () => {
    consentForRef.current = 'paste';
    setConsentAccepted(false);
    setShowConsent(true);
  };

  /**
   * U-5 (RULINGS-U-0917.md R-U1) — "Ask Meera about this brief". Re-checks consent with a fresh
   * `getPreferences` call, the same as `openMeera`, rather than trusting the page's last-known
   * `consentAccepted` — a successful paste already implies consent was true THEN, but consent can
   * be revoked between that paste and this click, and the fresh check is what actually decides
   * whether the chat opens straight away or through the consent screen first. Consent gates
   * opening the chat, not the prompt itself: the prompt is only ever handed to `MeeraCopilotChat`
   * once consent is (or becomes) true, through `prefillMessage`, and that component only fills
   * the composer — see its own doc comment for why an automatic send was rejected.
   */
  const askMeeraAboutBrief = (briefId: string) =>
    openMeeraWithPrompt((lang) => (lang.startsWith('hi') ? `Brief ${briefId} dekh lo.` : `Look at brief ${briefId}.`));

  /**
   * Same consent-checked, PREFILL-ONLY path as "Ask Meera about this brief" (R-U1), shared by that
   * button and the hero's "Ask Meera" bar: the text only ever fills the composer, never sends.
   */
  const openMeeraWithPrompt = async (prompt: string | ((lang: string) => string)) => {
    setConsentLoadError(null);
    setCheckingConsent(true);
    try {
      const prefs = await api.creatorAgentPrefs.getPreferences();
      const lang = prefs.creator_language || 'en-IN';
      setLanguage(lang);
      setConsentAccepted(prefs.consent_accepted);
      const text = typeof prompt === 'function' ? prompt(lang) : prompt;
      // A prompt entry never opens the camera, and drops a camera ask left by a declined arrival.
      cameraRequestedRef.current = false;
      if (prefs.consent_accepted) {
        prefillTokenRef.current += 1;
        setPrefillMessage({ text, token: prefillTokenRef.current });
        setOpenCamera(false);
        setChatOpen(true);
      } else {
        pendingBriefPromptRef.current = text;
        consentForRef.current = 'chat';
        setShowConsent(true);
      }
    } catch (err) {
      if (err instanceof ApiError && err.code === 'FEATURE_DISABLED') {
        setFeatureDisabled(true);
      } else {
        setConsentLoadError(err instanceof ApiError ? err.message : "Couldn't reach Meera — try again.");
      }
    } finally {
      setCheckingConsent(false);
    }
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-3xl">
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Meera</h1>
          <p className="text-muted-foreground">Your personal manager</p>
        </div>

        {/* Creator 7-day challenge (CHALLENGE-SPEC.md, 2026-09-23, Frontend §7) — top of the
            Co-pilot page. "Write the script" / "Give me an idea" reuse this same
            `openMeeraWithPrompt`, PREFILL-ONLY (ruling R-U1) — never sends on the creator's
            behalf, same contract as "Ask Meera about this brief" below. */}
        <ChallengeCard
          language={language}
          onAskMeera={(prompt) => void openMeeraWithPrompt(prompt)}
          className="mb-6"
        />

        {/* T-MEERA-CREATOR-PHASE-A (A10) — Meera chat entry. Conversational only in Phase A:
            deals/earnings/metrics Q&A, no drafting or sending on the creator's behalf.
            Gate review fix (item 3): MEERA_CREATOR_ENABLED off replaces this entirely with a
            calm, static state — no toast, no retry affordance. */}
        {featureDisabled === true ? (
          <Card className="mb-6">
            <CardHeader>
              <div className="flex items-center gap-2">
                <Sparkles className="h-5 w-5 text-muted-foreground" />
                <CardTitle className="text-base">Meera</CardTitle>
              </div>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">
                Meera for creators isn't available on your account yet.
              </p>
            </CardContent>
          </Card>
        ) : (
          <Card className="mb-6">
            {/* Round 2 QA — the chat panel has its own "Meera" header once open (dark band,
                orb, live status line); showing this outer title/description above it duplicated
                the same heading. Kept for the hero (closed) state, where it is the only heading. */}
            {!chatOpen && (
              <CardHeader>
                <div className="flex items-center gap-2">
                  <Sparkles className="h-5 w-5 text-primary" />
                  <CardTitle className="text-base">Talk to Meera</CardTitle>
                </div>
                <CardDescription>Your AI manager — ask about deals, earnings, and metrics.</CardDescription>
              </CardHeader>
            )}
            <CardContent>
              {chatOpen ? (
                <MeeraCopilotChat
                  firstName={firstName}
                  language={language}
                  prefillMessage={prefillMessage}
                  openCameraOnMount={openCamera}
                  onAnalyseBrief={focusBriefCard}
                  onClose={() => {
                    setChatOpen(false);
                    setPrefillMessage(null);
                  }}
                  onConsentRequired={() => {
                    setChatOpen(false);
                    // Consent revoked mid-session: an unfulfilled camera ask survives the detour.
                    cameraRequestedRef.current = openCamera;
                    consentForRef.current = 'chat';
                    setShowConsent(true);
                  }}
                />
              ) : (
                <MeeraHero
                  firstName={firstName}
                  onAsk={(question) => void openMeeraWithPrompt(question)}
                  onOpen={() => void openMeera()}
                  busy={checkingConsent}
                  error={consentLoadError}
                  topRight={<HeroCreditsChip language={language} />}
                />
              )}
            </CardContent>
          </Card>
        )}

        {/* U-2 (SPEC.md §8.5) — the paste card, between the Meera card and the consent screen.
            Hidden on the same explicit `featureDisabled === true` as the Meera card above, so a
            disabled account never sees a paste box whose every submit would 404. Consent gates
            the ANALYSE action, not the card: a creator who has not consented still sees what
            the feature is, and pressing Analyse opens the consent screen instead of sending. */}
        {featureDisabled !== true && (
          <div id="paste-brief" className="scroll-mt-20">
          <PasteBriefCard
            className="mb-6"
            needsConsent={consentAccepted === false}
            onConsentRequired={requestConsentForPaste}
            onFeatureDisabled={() => setFeatureDisabled(true)}
            language={language}
            onAskMeeraAboutBrief={askMeeraAboutBrief}
          />
          </div>
        )}

        <ConsentScreen
          open={showConsent}
          language={language}
          onAccept={handleAcceptConsent}
          onDecline={() => {
            setShowConsent(false);
            consentForRef.current = 'chat';
            // U-5 — a declined "Ask Meera about this brief" opens nothing, and the prompt is
            // dropped rather than surviving to a later, unrelated "Open Meera" click.
            pendingBriefPromptRef.current = null;
            // A declined `?camera=1` ask: drop the param so a reload does not bring the consent
            // screen back. (Every entry point sets the camera ask afresh, so no reset is needed.)
            if (cameraParam) clearCameraParam();
          }}
        />

        {showPreview && <CopilotPreviewCard className="mb-3" />}

        <DailySuggestionSection />
      </div>
    </CreatorLayout>
  );
}
