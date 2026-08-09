import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, CheckCircle2, XCircle } from 'lucide-react';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { api, ApiError } from '@/lib/api';

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

export default function CreatorMetaCallbackPage() {
  const navigate = useNavigate();
  const [state, setState] = React.useState<CallbackState>('loading');
  const [errorMessage, setErrorMessage] = React.useState('');
  // Read once, on mount, before anything clears it.
  const [resumingOnboarding] = React.useState(
    () => localStorage.getItem(META_ONBOARDING_RESUME_KEY) === '1',
  );
  const returnPath = resumingOnboarding ? '/creator/onboarding' : '/creator/settings';

  // Consume the marker immediately — routing decisions use the captured `resumingOnboarding`
  // state, not the live flag. This guarantees a stale marker can never misroute a *later*
  // Settings-initiated connect, even if the creator abandons an error page without clicking.
  React.useEffect(() => {
    localStorage.removeItem(META_ONBOARDING_RESUME_KEY);
  }, []);

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
          setErrorMessage(oauthError);
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
        api.metaOAuth.setLocalConnectionState(result.connected, result.grantedScopes);
        setState('success');
        // Started from onboarding? Send the creator straight back into the wizard,
        // which reads the persisted connection state and advances Step 1. (CR-120)
        if (resumingOnboarding) {
          localStorage.removeItem(META_ONBOARDING_RESUME_KEY);
          navigate('/creator/onboarding', { replace: true });
        }
      } catch (err) {
        if (cancelled) return;
        setErrorMessage(
          err instanceof ApiError ? err.message : 'Could not complete the connection. Please try again.',
        );
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
            {state === 'success' && (
              <CheckCircle2 className="h-10 w-10 text-success-foreground" aria-hidden="true" />
            )}
            {state === 'error' && (
              <XCircle className="h-10 w-10 text-destructive-foreground" aria-hidden="true" />
            )}
            <CardTitle className="text-lg">
              {state === 'loading' && 'Connecting your account…'}
              {state === 'success' && 'Account connected'}
              {state === 'error' && 'Connection failed'}
            </CardTitle>
            <CardDescription role="status" aria-live="polite">
              {state === 'loading' && 'Hang tight while we finish linking Instagram and Facebook.'}
              {state === 'success' && 'Brands can now see your verified Instagram and Facebook metrics.'}
              {state === 'error' && errorMessage}
            </CardDescription>
          </CardHeader>
          {state !== 'loading' && (
            <CardContent className="flex justify-center">
              <Button
                onClick={() => {
                  localStorage.removeItem(META_ONBOARDING_RESUME_KEY);
                  navigate(returnPath);
                }}
              >
                {state === 'success'
                  ? resumingOnboarding
                    ? 'Back to onboarding'
                    : 'Back to Settings'
                  : resumingOnboarding
                    ? 'Back to onboarding'
                    : 'Try Again'}
              </Button>
            </CardContent>
          )}
        </Card>
      </div>
    </CreatorLayout>
  );
}
