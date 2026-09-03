import * as React from 'react';
import { Loader2, Sparkles } from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { CopilotPreviewCard } from '@/components/creator/copilot/CopilotPreviewCard';
import { DailySuggestionSection } from '@/components/creator/copilot/DailySuggestionSection';
import { useDailySuggestion } from '@/hooks/useDailySuggestion';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { ConsentScreen } from '@/components/meera/ConsentScreen';
import { MeeraCopilotChat } from '@/components/creator/MeeraCopilotChat';
import { api, ApiError } from '@/lib/api';
import { getCreatorSession } from '@/lib/auth-session';

/** T-MEERA-CREATOR-PHASE-A (A10) — first word of the saved display name, "there" if none. */
function firstNameOf(displayName?: string): string {
  const trimmed = displayName?.trim();
  return trimmed ? trimmed.split(/\s+/)[0] : 'there';
}

/**
 * Creator AI Co-pilot — route: /creator/copilot (Ananya A2).
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
  const showPreview = status === 'idle';

  // T-MEERA-CREATOR-PHASE-A (A10, SPEC.md §4.7) — "Talk to Meera" entry with a DPDP consent
  // gate. Checked once via GET /creator/agent-preferences (consent_accepted) rather than by
  // provoking the 403 on a real turn — cheaper, and MeeraCopilotChat still handles a
  // CONSENT_REQUIRED error defensively in case consent is revoked mid-session.
  const firstName = React.useMemo(() => firstNameOf(getCreatorSession()?.displayName), []);
  const [checkingConsent, setCheckingConsent] = React.useState(false);
  const [showConsent, setShowConsent] = React.useState(false);
  const [chatOpen, setChatOpen] = React.useState(false);
  const [language, setLanguage] = React.useState('hi-IN');
  const [consentLoadError, setConsentLoadError] = React.useState<string | null>(null);

  const openMeera = async () => {
    setConsentLoadError(null);
    setCheckingConsent(true);
    try {
      const prefs = await api.creatorAgentPrefs.getPreferences();
      setLanguage(prefs.creator_language || 'hi-IN');
      if (prefs.consent_accepted) {
        setChatOpen(true);
      } else {
        setShowConsent(true);
      }
    } catch (err) {
      setConsentLoadError(err instanceof ApiError ? err.message : "Couldn't reach Meera — try again.");
    } finally {
      setCheckingConsent(false);
    }
  };

  const handleAcceptConsent = async () => {
    await api.creatorAgentPrefs.recordConsent();
    setShowConsent(false);
    setChatOpen(true);
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-3xl">
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Co-pilot</h1>
          <p className="text-muted-foreground">Your AI content partner</p>
        </div>

        {/* T-MEERA-CREATOR-PHASE-A (A10) — Meera chat entry. Conversational only in Phase A:
            deals/earnings/metrics Q&A, no drafting or sending on the creator's behalf. */}
        <Card className="mb-6">
          <CardHeader>
            <div className="flex items-center gap-2">
              <Sparkles className="h-5 w-5 text-primary" />
              <CardTitle className="text-base">Talk to Meera</CardTitle>
            </div>
            <CardDescription>Your AI manager — ask about deals, earnings, and metrics.</CardDescription>
          </CardHeader>
          <CardContent>
            {chatOpen ? (
              <MeeraCopilotChat
                firstName={firstName}
                language={language}
                onClose={() => setChatOpen(false)}
                onConsentRequired={() => {
                  setChatOpen(false);
                  setShowConsent(true);
                }}
              />
            ) : (
              <>
                <Button onClick={openMeera} disabled={checkingConsent}>
                  {checkingConsent ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Connecting…
                    </>
                  ) : (
                    'Open Meera'
                  )}
                </Button>
                {consentLoadError && (
                  <p className="mt-2 text-sm text-destructive-foreground">{consentLoadError}</p>
                )}
              </>
            )}
          </CardContent>
        </Card>

        <ConsentScreen
          open={showConsent}
          language={language}
          onAccept={handleAcceptConsent}
          onDecline={() => setShowConsent(false)}
        />

        {showPreview && <CopilotPreviewCard className="mb-3" />}

        <DailySuggestionSection />
      </div>
    </CreatorLayout>
  );
}
