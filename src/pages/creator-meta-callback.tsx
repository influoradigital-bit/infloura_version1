import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, CheckCircle2, XCircle, AlertTriangle } from 'lucide-react';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { api, ApiError, type MetaAuthPath } from '@/lib/api';

type CallbackState = 'loading' | 'success' | 'error';

/**
 * Landing route Meta redirects the browser to after the OAuth dialog
 * (influora.meta.redirect-uri = /creator/settings/meta/callback).
 *
 * GET /meta/oauth/callback returns JSON, not a 302 (see MetaOAuthController
 * javadoc) — there is no backend redirect to follow. This route reads
 * `code`/`state` off its own query string and calls the callback endpoint
 * itself as a normal API request, then routes the user back to settings.
 */
/** Set by creator-onboarding.tsx right before the OAuth redirect (CR-120). When
 *  present, this connect was started from onboarding, so route back there. */
const META_ONBOARDING_RESUME_KEY = 'creator_onboarding_meta_resume';

/**
 * CR-118 — Meta's `error`/`error_description` query params are attacker-controlled: this route
 * carries no auth guard (`App.tsx:391`), so anyone can navigate here directly with an arbitrary
 * `?error=` value and have it rendered back on an authenticated-looking page. Only a known Meta
 * OAuth error code gets its own message; anything else — including the raw `error_description`
 * text, which was reflected verbatim before this fix — falls through to one generic message.
 * Codes per Meta's documented OAuth dialog errors + the ones this app has actually observed.
 */
const KNOWN_OAUTH_ERROR_MESSAGES: Record<string, string> = {
  access_denied: 'You declined the connection request.',
  user_denied: 'You declined the connection request.',
  server_error: 'Instagram/Facebook had a temporary issue on their end. Please try again.',
  temporarily_unavailable: 'Instagram/Facebook is temporarily unavailable. Please try again shortly.',
};
const GENERIC_OAUTH_ERROR_MESSAGE = 'Could not connect your account. Please try again.';

export default function CreatorMetaCallbackPage() {
  const navigate = useNavigate();
  const [state, setState] = React.useState<CallbackState>('loading');
  const [errorMessage, setErrorMessage] = React.useState('');
  // CR-103/F-0116: the callback previously moved to the 'success' state (and the "Brands can
  // now see your verified Instagram and Facebook metrics" copy) unconditionally after ANY 200
  // response — including the NO_BUSINESS_ACCOUNT case, where the server correctly reports
  // `connected: false` for a personal Instagram account. Tracked separately from `state` so the
  // success ICON/heading/routing stay unchanged and only the claim being made changes.
  const [connectedAccountType, setConnectedAccountType] = React.useState<
    'personal' | 'business' | null
  >(null);
  const [serverReportedConnected, setServerReportedConnected] = React.useState(true);
  const [isRetrying, setIsRetrying] = React.useState(false);
  // Read once, on mount, before anything clears it.
  const [resumingOnboarding] = React.useState(
    () => localStorage.getItem(META_ONBOARDING_RESUME_KEY) === '1',
  );
  // CR-65 — general "return to where this connect started" marker (Co-pilot, Deal Room, ...),
  // read once up front for the same reason as the onboarding flag: a routing decision must be
  // pinned to what was true when this page mounted, not to whatever the storage holds later.
  const [generalReturnPath] = React.useState(() => api.metaOAuth.consumeConnectReturnTo());
  const returnPath = resumingOnboarding
    ? '/creator/onboarding'
    : (generalReturnPath ?? '/creator/settings');

  // Consume the marker immediately — routing decisions use the captured `resumingOnboarding`
  // state, not the live flag. This guarantees a stale marker can never misroute a *later*
  // Settings-initiated connect, even if the creator abandons an error page without clicking.
  React.useEffect(() => {
    localStorage.removeItem(META_ONBOARDING_RESUME_KEY);
  }, []);

  const handleRetry = async (authPath?: MetaAuthPath) => {
    setIsRetrying(true);
    try {
      // F-0168 — the general-return-path marker was already read-and-cleared at mount
      // (generalReturnPath above), so a plain re-authorize() here would redirect through a
      // SECOND callback mount with nothing left to consume, silently falling back to Settings
      // even though this retry started from the same deal-room/Co-pilot context as the
      // original attempt. Re-persist it (and the onboarding flag, which was cleared the same
      // way) before redirecting, so the retried round-trip returns to the same place the first
      // one would have.
      if (resumingOnboarding) {
        localStorage.setItem(META_ONBOARDING_RESUME_KEY, '1');
      } else if (generalReturnPath) {
        api.metaOAuth.setConnectReturnTo(generalReturnPath);
      }
      // CR-66 — re-run the OAuth authorize flow itself rather than just navigating back to
      // Settings and stranding the creator to find the Connect button again by hand.
      const { authorizationUrl } = await api.metaOAuth.authorize(authPath);
      window.location.href = authorizationUrl;
    } catch (err) {
      setIsRetrying(false);
      setErrorMessage(
        err instanceof ApiError ? err.message : 'Could not restart the connection. Please try again.',
      );
    }
  };

  React.useEffect(() => {
    let cancelled = false;

    async function run() {
      const params = new URLSearchParams(window.location.search);
      const code = params.get('code');
      const state = params.get('state');
      const oauthError = params.get('error') || params.get('error_description');

      // CR-117 — strip code/state/error off the URL immediately after reading them, before
      // the async callback request even starts. The backend already treats `state` as
      // single-use (so a stale copy left in history/bfcache can't be replayed), but leaving
      // the authorization code sitting in the visible URL and browser history is still a
      // hygiene gap worth closing independent of that. `replaceState` (not `pushState`) so
      // this doesn't add a back-button entry.
      if (window.location.search) {
        window.history.replaceState(null, '', window.location.pathname);
      }

      // Meta itself can redirect here with an error instead of a code, e.g.
      // when the user cancels the dialog — surface that rather than crashing
      // on a missing `code` param.
      if (oauthError) {
        if (!cancelled) {
          // CR-118 / F-0181 — never echo the raw param; only a recognized code gets its own
          // message. A bare `KNOWN_OAUTH_ERROR_MESSAGES[errorCode]` bracket lookup is NOT
          // enough: this route carries no auth guard, so `errorCode` is fully
          // attacker-controlled, and a value matching an Object.prototype member
          // (`__proto__`, `constructor`, `toString`, `valueOf`, `hasOwnProperty`, ...) returns
          // a truthy non-string that short-circuits the `||` fallback below — measured to
          // crash the render (`?error=__proto__`), render the literal string
          // "[object Undefined]" (`?error=toString`), or silently render nothing
          // (`?error=constructor`). `hasOwnProperty.call` + a runtime `typeof` guard is what
          // actually makes "every other value falls through to the generic message" hold —
          // TypeScript's static `string` typing does not catch this at compile time.
          const errorCode = params.get('error');
          const knownMessage =
            errorCode && Object.prototype.hasOwnProperty.call(KNOWN_OAUTH_ERROR_MESSAGES, errorCode)
              ? KNOWN_OAUTH_ERROR_MESSAGES[errorCode]
              : undefined;
          setErrorMessage(typeof knownMessage === 'string' ? knownMessage : GENERIC_OAUTH_ERROR_MESSAGE);
          // CR-109 — a failed (re)connect attempt must not leave a stale connected:true mirror
          // from a previous successful connection; the app would keep gating features as if
          // still connected when this attempt just failed.
          api.metaOAuth.setLocalConnectionState(false, [], null);
          setState('error');
        }
        return;
      }

      if (!code || !state) {
        if (!cancelled) {
          setErrorMessage('Missing or invalid parameters from Instagram/Facebook. Please try connecting again.');
          setState('error');
        }
        return;
      }

      try {
        const result = await api.metaOAuth.callback(code, state);
        if (cancelled) return;
        // CR-105 — accountType was previously dropped here (called with only 2 args), which is
        // why `requiresBusinessAccount` (useDailySuggestion.ts) could never observe 'personal'
        // and the explanatory screen for a personal-IG creator never rendered — they looped on
        // the connect prompt forever. Same root cause CR-63 names on the High-severity row.
        api.metaOAuth.setLocalConnectionState(result.connected, result.grantedScopes, result.accountType ?? null);
        setServerReportedConnected(result.connected);
        setConnectedAccountType(result.accountType ?? null);
        setState('success');
        // Started from onboarding? Send the creator straight back into the wizard,
        // which reads the persisted connection state and advances Step 1. (CR-120)
        // F-0164: this fired regardless of result.connected — a personal-account creator who
        // started the connect from onboarding was redirected straight back into the wizard
        // before ever seeing the "Business account needed" explanation above (F-0116), silently
        // skipping the one screen that would tell them why. Only auto-advance on an actual
        // connection; a `connected: false` response leaves this screen up so the creator sees
        // it, and the "Back to onboarding" button below still gets them back manually.
        if (resumingOnboarding && result.connected) {
          localStorage.removeItem(META_ONBOARDING_RESUME_KEY);
          navigate('/creator/onboarding', { replace: true });
        }
      } catch (err) {
        if (cancelled) return;
        setErrorMessage(
          err instanceof ApiError ? err.message : 'Could not complete the connection. Please try again.',
        );
        // CR-109 — see the identical fix/reasoning in the oauthError branch above.
        api.metaOAuth.setLocalConnectionState(false, [], null);
        setState('error');
      }
    }

    run();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <CreatorLayout>
      <div className="container mx-auto flex max-w-md flex-col items-center px-4 py-16">
        <Card className="w-full">
          <CardHeader className="items-center text-center">
            {state === 'loading' && (
              <Loader2 className="h-10 w-10 animate-spin text-muted-foreground" aria-hidden="true" />
            )}
            {state === 'success' && serverReportedConnected && (
              <CheckCircle2 className="h-10 w-10 text-success-foreground" aria-hidden="true" />
            )}
            {state === 'success' && !serverReportedConnected && (
              <AlertTriangle className="h-10 w-10 text-warning-foreground" aria-hidden="true" />
            )}
            {state === 'error' && (
              <XCircle className="h-10 w-10 text-destructive-foreground" aria-hidden="true" />
            )}
            <CardTitle className="text-lg">
              {state === 'loading' && 'Connecting your account…'}
              {state === 'success' &&
                (serverReportedConnected
                  ? 'Account connected'
                  : connectedAccountType === 'personal'
                    ? 'Business account needed'
                    : 'Connection incomplete')}
              {state === 'error' && 'Connection failed'}
            </CardTitle>
            <CardDescription role="status" aria-live="polite">
              {state === 'loading' && 'Hang tight while we finish linking Instagram and Facebook.'}
              {state === 'success' &&
                (serverReportedConnected
                  ? 'Brands can now see your verified Instagram and Facebook metrics.'
                  : connectedAccountType === 'personal'
                    ? "We couldn't find an Instagram professional account linked to a Facebook Page on this login. If your Instagram isn't linked to a Page, connect with Instagram directly instead — no Page required."
                    : "We couldn't verify full access to your Instagram metrics. Please try reconnecting.")}
              {state === 'error' && errorMessage}
            </CardDescription>
          </CardHeader>
          {state !== 'loading' && (
            <CardContent className="flex flex-col items-center gap-2">
              {/*
                T-IGLOGIN-0820 — the recovery for a wrong "yes, I have a Facebook Page". The
                Facebook path reports connected:false / personal when it finds no linked
                professional account, which is exactly what a creator with no Page hits. Offer
                the path that does not need one, instead of telling them to go change settings
                in the Instagram app and come back.
              */}
              {state === 'success' && !serverReportedConnected && connectedAccountType === 'personal' && (
                <Button
                  className="w-full"
                  onClick={() => void handleRetry('INSTAGRAM_LOGIN')}
                  disabled={isRetrying}
                >
                  Connect with Instagram instead (no Facebook Page)
                </Button>
              )}
              <Button
                onClick={() => {
                  if (state === 'error' && !resumingOnboarding) {
                    // CR-66 — retry the connection itself, not just navigate away from the
                    // failure. `resumingOnboarding` keeps its own behavior: the wizard has its
                    // own Connect button, so returning there already offers a real retry.
                    void handleRetry();
                    return;
                  }
                  localStorage.removeItem(META_ONBOARDING_RESUME_KEY);
                  navigate(returnPath);
                }}
                disabled={isRetrying}
              >
                {isRetrying ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                ) : state === 'success' ? (
                  resumingOnboarding ? 'Back to onboarding' : returnPath === '/creator/settings' ? 'Back to Settings' : 'Back'
                ) : resumingOnboarding ? (
                  'Back to onboarding'
                ) : (
                  'Try Again'
                )}
              </Button>
              {state === 'error' && !resumingOnboarding && (
                <Button variant="ghost" size="sm" onClick={() => navigate(returnPath)}>
                  {returnPath === '/creator/settings' ? 'Back to Settings' : 'Back'}
                </Button>
              )}
            </CardContent>
          )}
        </Card>
      </div>
    </CreatorLayout>
  );
}
