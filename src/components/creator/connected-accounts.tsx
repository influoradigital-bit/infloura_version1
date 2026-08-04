import * as React from 'react';
import { Instagram, Facebook, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { useToast } from '@/hooks/use-toast';
import { api } from '@/lib/api';

interface MetaScopeInfo {
  scope: string;
  label: string;
}

/** Human labels for the scopes MetaOAuthService.REQUIRED_SCOPES requests server-side. */
const SCOPE_LABELS: MetaScopeInfo[] = [
  { scope: 'instagram_basic', label: 'Instagram profile & media' },
  { scope: 'instagram_manage_insights', label: 'Instagram insights & demographics' },
  { scope: 'pages_show_list', label: 'Facebook Pages list' },
  { scope: 'pages_read_engagement', label: 'Facebook Page engagement' },
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
  const [connectionState] = React.useState(() => api.metaOAuth.getLocalConnectionState());
  const [isConnecting, setIsConnecting] = React.useState(false);

  const handleConnect = async () => {
    setIsConnecting(true);
    try {
      const { authorizationUrl } = await api.metaOAuth.authorize();
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

  return (
    <Card className="mb-6">
      <CardHeader>
        <div className="flex items-center gap-2">
          <Instagram className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
          <CardTitle className="text-base">Connected Accounts</CardTitle>
        </div>
        <CardDescription>
          Connect Instagram and Facebook so brands see your verified reach and engagement.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
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
                {isConnected ? 'Profile, media & insights connected' : 'Not connected'}
              </p>
            </div>
          </div>
          {isConnected ? (
            <span className="flex items-center gap-1.5 text-sm font-medium text-success-foreground">
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
              Connected
            </span>
          ) : (
            <Button size="sm" onClick={handleConnect} disabled={isConnecting}>
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
                {isConnected ? 'Page list & engagement connected' : 'Not connected'}
              </p>
            </div>
          </div>
          {isConnected ? (
            <span className="flex items-center gap-1.5 text-sm font-medium text-success-foreground">
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
              Connected
            </span>
          ) : (
            <Button size="sm" variant="outline" onClick={handleConnect} disabled={isConnecting}>
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

        {isConnected && connectionState.scopes.length > 0 && (
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
      </CardContent>
    </Card>
  );
}
