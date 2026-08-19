import * as React from 'react';
import { Instagram, Facebook, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { useToast } from '@/hooks/use-toast';
import { useMetaConnection } from '@/hooks/creator/useMetaConnection';
import { api, type MetaAuthPath } from '@/lib/api';

interface MetaScopeInfo {
  scope: string;
  label: string;
}

/**
 * Human labels for the scopes MetaOAuthService.REQUIRED_SCOPES requests server-side.
 * CR-115 — pages_read_engagement removed from REQUIRED_SCOPES (unused; see that service's
 * javadoc), so it's dropped here too rather than advertising a permission no longer requested.
 */
const SCOPE_LABELS: MetaScopeInfo[] = [
  { scope: 'instagram_basic', label: 'Instagram profile & media' },
  { scope: 'instagram_manage_insights', label: 'Instagram insights & demographics' },
  { scope: 'pages_show_list', label: 'Facebook Pages list' },
];

/**
 * Connected accounts card for creator settings — Instagram + Facebook Page
 * connect via Meta OAuth (influora-api MetaOAuthController).
 *
 * Both platforms share a single Meta OAuth app/scope grant (Instagram Basic
 * Display + Page permissions come from the same Facebook Login dialog), so
 * there is one "Connect Meta" action rather than two separate buttons — the
 * backend's REQUIRED_SCOPES already requests both instagram_* and pages_*
 * scopes in one authorize call. We surface Instagram and Facebook Page as two
 * rows sharing that single connection state, since MetaOAuthController does
 * not expose a way to request a subset of scopes.
 */
export function ConnectedAccounts() {
  const { toast } = useToast();
  // CR-107 — re-verified against GET /meta/oauth/status on mount and on tab
  // visibility-regain, not just read once from the localStorage mirror.
  const { data: connectionState, loading: verifying, error: verifyError, refresh } = useMetaConnection();
  const [isConnecting, setIsConnecting] = React.useState(false);
  // CR-102/F-0115 — there was no way for a creator to disconnect their Meta/Instagram
  // account anywhere in the product, even though the backend route and the correctly
  // creator-scoped revoke already existed (MetaOAuthController.java:129).
  const [isDisconnecting, setIsDisconnecting] = React.useState(false);
  const [showDisconnectConfirm, setShowDisconnectConfirm] = React.useState(false);
  // T-IGLOGIN-0820 — Meta offers two configurations and only one demands a Facebook Page, so the
  // creator has to be asked BEFORE the redirect. Sending a creator with no Page into the
  // Facebook dialog dead-ends them inside Meta's UI with nothing explaining why.
  const [showPathChoice, setShowPathChoice] = React.useState(false);

  const handleDisconnect = async () => {
    setIsDisconnecting(true);
    try {
      await api.metaOAuth.disconnect();
      toast({ title: 'Instagram and Facebook disconnected' });
      setShowDisconnectConfirm(false);
      await refresh();
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Could not disconnect',
        description: err instanceof Error ? err.message : 'Please try again in a moment.',
      });
    } finally {
      setIsDisconnecting(false);
    }
  };

  const handleConnect = async (authPath: MetaAuthPath) => {
    setShowPathChoice(false);
    setIsConnecting(true);
    try {
      // F-0168 — this is a plain "just send me back here" initiator with no return-path of its
      // own; clear any leftover marker from an abandoned Deal Room/Co-pilot connect first, or
      // it would misroute this Settings-initiated connect into wherever that other attempt was.
      api.metaOAuth.clearConnectReturnTo();
      const { authorizationUrl } = await api.metaOAuth.authorize(authPath);
      // Full-page navigation, not a fetch — Meta's OAuth dialog itself must load
      // in the top-level browsing context so the user can log in and approve.
      window.location.href = authorizationUrl;
    } catch (err) {
      setIsConnecting(false);
      toast({
        variant: 'destructive',
        title: 'Could not start Instagram/Facebook connect',
        description: err instanceof Error ? err.message : 'Please try again in a moment.',
      });
    }
  };

  const isConnected = connectionState.connected;
  // Don't flash "Not connected" off a stale-but-connected localStorage seed while the real
  // status check is still in flight — show a neutral verifying state instead and only render
  // the confident Connected/Not-connected UI once the backend call resolves.
  const isVerifyingConnected = verifying && isConnected;

  return (
    <Card className="mb-6">
      <CardHeader>
        <div className="flex items-center gap-2">
          <Instagram className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
          <CardTitle className="text-base">Connected Accounts</CardTitle>
        </div>
        <CardDescription>
          Connect Instagram so brands see your verified reach and engagement. A Facebook Page is
          optional — you'll be asked which applies to you.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {verifyError && !verifying && (
          <p className="flex items-center gap-1.5 text-xs text-destructive-foreground">
            <AlertCircle className="h-3.5 w-3.5" aria-hidden="true" />
            Couldn't verify connection status — showing last known state.
          </p>
        )}
        <div className="flex items-center justify-between gap-3 rounded-lg border p-3">
          <div className="flex items-center gap-3">
            <div
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-purple-500 via-pink-500 to-orange-400"
              aria-hidden="true"
            >
              <Instagram className="h-5 w-5 text-white" />
            </div>
            <div>
              <p className="text-sm font-medium">Instagram</p>
              <p className="text-xs text-muted-foreground">
                {isVerifyingConnected
                  ? 'Verifying connection…'
                  : isConnected
                    ? 'Profile, media & insights connected'
                    : 'Not connected'}
              </p>
            </div>
          </div>
          {isVerifyingConnected ? (
            <span className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              Verifying…
            </span>
          ) : isConnected ? (
            <span className="flex items-center gap-1.5 text-sm font-medium text-success-foreground">
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
              Connected
            </span>
          ) : (
            <Button size="sm" onClick={() => setShowPathChoice(true)} disabled={isConnecting}>
              {isConnecting ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                  Connecting…
                </>
              ) : (
                'Connect Instagram'
              )}
            </Button>
          )}
        </div>

        <div className="flex items-center justify-between gap-3 rounded-lg border p-3">
          <div className="flex items-center gap-3">
            <div
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[#1877F2]"
              aria-hidden="true"
            >
              <Facebook className="h-5 w-5 text-white" />
            </div>
            <div>
              <p className="text-sm font-medium">Facebook Page</p>
              <p className="text-xs text-muted-foreground">
                {/* CR-115 follow-up (Priya) — was "Page list & engagement connected", advertising
                    pages_read_engagement after that scope was removed from REQUIRED_SCOPES. */}
                {isVerifyingConnected
                  ? 'Verifying connection…'
                  : isConnected
                    ? 'Page list connected'
                    : 'Not connected'}
              </p>
            </div>
          </div>
          {isVerifyingConnected ? (
            <span className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              Verifying…
            </span>
          ) : isConnected ? (
            <span className="flex items-center gap-1.5 text-sm font-medium text-success-foreground">
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
              Connected
            </span>
          ) : (
            <Button
              size="sm"
              variant="outline"
              onClick={() => setShowPathChoice(true)}
              disabled={isConnecting}
            >
              {isConnecting ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                  Connecting…
                </>
              ) : (
                'Connect Facebook Page'
              )}
            </Button>
          )}
        </div>

        {/*
          CR-104 — this list now renders the REAL scopes Meta's /me/permissions reported as
          granted (CreatorMetaOAuthService.connect), never the requested-scope constant. A
          creator who declined a permission no longer sees it listed here.
          `connectionState.scopes === null` is a distinct "could not verify" state (the backend's
          permissions check itself failed) — it must render its own honest message, not silently
          fall through to the empty-list branch (which would read as "verified, zero granted").
        */}
        {isConnected && !isVerifyingConnected && connectionState.scopes === null && (
          <div className="rounded-lg bg-muted/50 p-3">
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <AlertCircle className="h-3.5 w-3.5" aria-hidden="true" />
              Couldn't verify which permissions were granted. Reconnect to refresh this.
            </p>
          </div>
        )}
        {isConnected && !isVerifyingConnected && connectionState.scopes !== null && connectionState.scopes.length > 0 && (
          <div className="rounded-lg bg-muted/50 p-3">
            <p className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
              <AlertCircle className="h-3.5 w-3.5" aria-hidden="true" />
              Granted permissions
            </p>
            <ul className="space-y-1">
              {connectionState.scopes.map((scope) => {
                const info = SCOPE_LABELS.find((s) => s.scope === scope);
                return (
                  <li key={scope} className="text-xs text-muted-foreground">
                    · {info?.label ?? scope}
                  </li>
                );
              })}
            </ul>
          </div>
        )}

        {isConnected && !isVerifyingConnected && (
          <Button
            variant="ghost"
            size="sm"
            className="text-destructive-foreground hover:text-destructive-foreground"
            onClick={() => setShowDisconnectConfirm(true)}
          >
            Disconnect Instagram &amp; Facebook
          </Button>
        )}
      </CardContent>

      {/*
        T-IGLOGIN-0820 — asked BEFORE the redirect, because the two Meta configurations differ in
        whether a Facebook Page is required and the choice cannot be changed mid-dialog. The
        answer is a hint, not a fact: creators routinely do not know whether their Instagram is
        linked to a Page, so a wrong "yes" is recovered on the callback screen rather than
        dead-ending here.
      */}
      <AlertDialog open={showPathChoice} onOpenChange={setShowPathChoice}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Is your Instagram linked to a Facebook Page?</AlertDialogTitle>
            <AlertDialogDescription>
              Instagram offers two ways to connect. Pick the one that matches your setup — you can
              change it later from this page.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="space-y-3">
            <button
              type="button"
              onClick={() => handleConnect('FACEBOOK_LOGIN')}
              disabled={isConnecting}
              className="w-full rounded-lg border p-3 text-left transition-colors hover:bg-muted disabled:opacity-60"
            >
              <span className="flex items-center gap-2 text-sm font-medium">
                <Facebook className="h-4 w-4 text-[#1877F2]" aria-hidden="true" />
                Yes — I have a Facebook Page
              </span>
              <span className="mt-1 block text-xs text-muted-foreground">
                Connect with Facebook. Needed later for paid partnership ads run from your handle.
                You must be able to manage the Page.
              </span>
            </button>
            <button
              type="button"
              onClick={() => handleConnect('INSTAGRAM_LOGIN')}
              disabled={isConnecting}
              className="w-full rounded-lg border p-3 text-left transition-colors hover:bg-muted disabled:opacity-60"
            >
              <span className="flex items-center gap-2 text-sm font-medium">
                <Instagram className="h-4 w-4" aria-hidden="true" />
                No — Instagram only
              </span>
              <span className="mt-1 block text-xs text-muted-foreground">
                Connect with your Instagram login. No Facebook Page needed. Profile, media and
                insights all work; paid partnership ads do not.
              </span>
            </button>
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isConnecting}>Cancel</AlertDialogCancel>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog
        open={showDisconnectConfirm}
        onOpenChange={(open) => {
          // Matches the disabled Cancel button below — an in-flight request must not be
          // dismissable via Escape/overlay-click while it's still running.
          if (!isDisconnecting) setShowDisconnectConfirm(open);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Disconnect Instagram &amp; Facebook?</AlertDialogTitle>
            <AlertDialogDescription>
              {/* Priya review (2 rounds) — both prior wordings claimed a user-visible
                  consequence no code path actually produces:
                  (1) "deliverable verification stops" — that path can't reach a creator's
                      token today anyway (separate, pre-existing bug, not this ticket's fix).
                  (2) "brands will no longer see your reach/engagement" — disconnect only
                      revokes the token (MetaConnectionService.java:114-118,
                      MetaTokenStorage.revokeCreatorToken); it never touches the persisted
                      CreatorProfile/PlatformStat rows CreatorDiscoveryService reads for the
                      brand-facing card, so brands keep seeing the same numbers, frozen.
                  What genuinely stops, verified end-to-end: PortfolioService.syncPlatforms
                  (throws NOT_CONNECTED), CreatorCaptionSyncJob (skips the creator), and
                  MetaConnectionService.getStatus (reports disconnected) — all three go
                  through getValidCreatorToken, which the revoke genuinely empties. */}
              Your Instagram metrics will stop syncing, so the reach and engagement brands see
              will stay frozen at today's numbers until you reconnect.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isDisconnecting}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(e) => {
                e.preventDefault();
                void handleDisconnect();
              }}
              disabled={isDisconnecting}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {isDisconnecting ? (
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              ) : (
                'Disconnect'
              )}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}
