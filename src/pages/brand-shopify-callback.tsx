import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, CheckCircle2, XCircle } from 'lucide-react';

import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { api, ApiError } from '@/lib/api';

type CallbackState = 'loading' | 'success' | 'error';

/**
 * [F-0731] Landing route Shopify redirects the merchant's browser to after they approve the app
 * install (`influora.shopify.redirect-uri`).
 *
 * <p>Before this route existed, `ShopifyConnectController`'s own javadoc described "the frontend
 * route at redirect-uri" that reads the result and forwards the user — and no such route was ever
 * built. `GET /shopify/oauth/callback` answers with a JSON `ApiResponse`, not a 302, so the browser
 * landed on the API host looking at raw JSON with no link back into the app. The merchant's only
 * way home was to retype the URL.
 *
 * <p>Shape mirrors `creator-meta-callback.tsx`, the one working precedent in this codebase: read
 * `code`/`state`/`shop` off our own query string, complete the exchange as a normal authenticated
 * API call, then route back to settings.
 *
 * <p><b>Error text is never reflected.</b> Shopify appends its own `error`/`error_description`
 * params, and they are attacker-controllable — anyone can link a brand here with arbitrary text.
 * Only known codes get a specific message; everything else falls through to one generic line. This
 * is the same rule CR-118 established for the Meta callback after that page reflected
 * `error_description` verbatim.
 */
const KNOWN_OAUTH_ERROR_MESSAGES: Record<string, string> = {
  access_denied: 'You declined the connection request.',
};
const GENERIC_OAUTH_ERROR_MESSAGE = 'Could not connect your Shopify store. Please try again.';

const SETTINGS_PATH = '/brand/settings';

export default function BrandShopifyCallbackPage() {
  const navigate = useNavigate();
  const [state, setState] = React.useState<CallbackState>('loading');
  const [errorMessage, setErrorMessage] = React.useState('');
  const [shopDomain, setShopDomain] = React.useState('');

  React.useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const oauthError = params.get('error');
    if (oauthError) {
      setErrorMessage(KNOWN_OAUTH_ERROR_MESSAGES[oauthError] ?? GENERIC_OAUTH_ERROR_MESSAGE);
      setState('error');
      return;
    }

    const code = params.get('code');
    const oauthState = params.get('state');
    const shop = params.get('shop');
    if (!code || !oauthState || !shop) {
      // Reached directly, or Shopify sent an incomplete redirect. Say so plainly rather than
      // firing a request that would fail with a less useful message.
      setErrorMessage('This page is only reachable from Shopify after approving the connection.');
      setState('error');
      return;
    }

    let cancelled = false;
    api.storeIntegrations
      .completeShopifyConnect({ code, state: oauthState, shop })
      .then((result) => {
        if (cancelled) return;
        // The server decides whether this counts as connected — the page does not assume success
        // from a 200. Same lesson as CR-103 on the Meta callback, where any 200 was rendered as
        // success including the response that explicitly reported connected: false.
        if (!result.connected) {
          setErrorMessage(GENERIC_OAUTH_ERROR_MESSAGE);
          setState('error');
          return;
        }
        setShopDomain(result.shopDomain);
        setState('success');
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setErrorMessage(err instanceof ApiError ? err.message : GENERIC_OAUTH_ERROR_MESSAGE);
        setState('error');
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="mx-auto flex min-h-[60vh] max-w-md items-center px-4">
      <Card className="w-full p-8 text-center">
        {state === 'loading' && (
          <>
            <Loader2 className="mx-auto h-8 w-8 animate-spin text-muted-foreground" />
            <h1 className="mt-4 text-lg font-semibold">Connecting your Shopify store…</h1>
            <p className="mt-2 text-sm text-muted-foreground">
              Finishing the setup and subscribing to your order updates.
            </p>
          </>
        )}

        {state === 'success' && (
          <>
            <CheckCircle2 className="mx-auto h-8 w-8 text-primary" />
            <h1 className="mt-4 text-lg font-semibold">Shopify store connected</h1>
            <p className="mt-2 text-sm text-muted-foreground">
              {shopDomain
                ? `Orders from ${shopDomain} will now be attributed to your campaigns.`
                : 'Orders from your store will now be attributed to your campaigns.'}
            </p>
            <Button className="mt-6 w-full" onClick={() => navigate(SETTINGS_PATH)}>
              Back to settings
            </Button>
          </>
        )}

        {state === 'error' && (
          <>
            <XCircle className="mx-auto h-8 w-8 text-destructive-foreground" />
            <h1 className="mt-4 text-lg font-semibold">Could not connect</h1>
            <p className="mt-2 text-sm text-muted-foreground">{errorMessage}</p>
            <Button className="mt-6 w-full" onClick={() => navigate(SETTINGS_PATH)}>
              Back to settings
            </Button>
          </>
        )}
      </Card>
    </div>
  );
}
