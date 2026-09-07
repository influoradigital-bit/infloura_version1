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
import { MetaConnectPathDialog } from '@/components/creator/meta-connect-path-dialog';
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
        {/*
          T-IGTRUST-0907 — the same three promises onboarding's Step 1 makes, kept here for the
          creator who skipped onboarding and arrived at this card cold; they'd otherwise be asked
          to grant Meta access with no statement of what it does and doesn't allow. Checked
          against MetaOAuthService.REQUIRED_SCOPES / INSTAGRAM_LOGIN_SCOPES: neither list carries
          a publishing (instagram_content_publish, pages_manage_posts) or messaging
          (instagram_manage_messages) scope. Only shown while disconnected — once connected, the
          granted-permissions block below reports the REAL grant and this would be noise.
        */}
        {!isConnected && !verifying && (
          <ul className="space-y-1.5 rounded-lg bg-muted/50 p-3 text-xs text-muted-foreground">
            <li>· We read your profile, media and insight numbers — nothing else.</li>
            <li>· We never post, comment or DM. Those permissions are never requested.</li>
            <li>· We never see your password — you sign in on Instagram’s own screen.</li>
            <li>· You can disconnect from this card at any time.</li>
          </ul>
        )}
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

      {/* T-IGLOGIN-0820, extracted to a shared component by T-IGTRUST-0907 — this card was the
          only connect surface that asked the question, and the other two silently defaulted to
          the Page-required path. See MetaConnectPathDialog's header. */}
      <MetaConnectPathDialog
        open={showPathChoice}
        onOpenChange={setShowPathChoice}
        onChoose={(authPath) => void handleConnect(authPath)}
        busy={isConnecting}
        changeLaterLabel="from this page"
      />

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
                  through getValidCreatorToken, which the revoke genuinely empties.
                  T-IGTRUST-0907 (Tejas, fresh-context review) — the third wording was still an
                  overstatement: it described the frozen figures as current as of the moment of
                  disconnect. MetaTokenStorage.revokeCreatorToken (MetaTokenStorage.java:394)
                  marks the token row revoked and writes an audit entry, and touches
                  platform_stats not at all. What survives is therefore whatever the LAST SYNC
                  wrote, which may be months stale. Gate: connect-copy-claims-verified.sh —
                  which is why the old phrasing is described here rather than quoted. */}
              Your Instagram metrics will stop syncing, so the reach and engagement brands see
              will stay frozen at your last synced numbers until you reconnect.
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
