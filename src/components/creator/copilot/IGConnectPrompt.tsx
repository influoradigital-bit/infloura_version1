import * as React from 'react';
import { Instagram, Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useToast } from '@/hooks/use-toast';
import { api } from '@/lib/api';

interface IGConnectPromptProps {
  className?: string;
}

/**
 * Dashboard nudge to link Instagram so Co-pilot can generate a daily
 * suggestion. A slim standalone prompt (fe-components-plan.md §1.2 option b)
 * — not a re-render of the full `ConnectedAccounts` settings card — that
 * mirrors `ConnectedAccounts.handleConnect`'s body exactly (same
 * try/catch -> toast pattern). Reuses the existing OAuth flow verbatim, no
 * forked logic (API-CONTRACT.md §4 — no `connectIg()` method exists).
 */
export function IGConnectPrompt({ className }: IGConnectPromptProps) {
  const { toast } = useToast();
  const [isConnecting, setIsConnecting] = React.useState(false);

  const handleConnect = async () => {
    setIsConnecting(true);
    try {
      // CR-65 — without this, the callback page has no way to know this connect started from
      // Co-pilot and always sends the creator to Settings instead.
      api.metaOAuth.setConnectReturnTo('/creator/copilot');
      const { authorizationUrl } = await api.metaOAuth.authorize();
      // Full-page navigation, not a fetch — same as connected-accounts.tsx's
      // handleConnect: Meta's OAuth dialog must load in the top-level
      // browsing context.
      window.location.href = authorizationUrl;
    } catch (err) {
      setIsConnecting(false);
      toast({
        variant: 'destructive',
        title: 'Could not start Instagram connect',
        description: err instanceof Error ? err.message : 'Please try again in a moment.',
      });
    }
  };

  return (
    <Card className={className}>
      <CardContent className="flex items-center justify-between gap-3 py-4">
        <div className="flex items-center gap-3">
          <div
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-purple-500 via-pink-500 to-orange-400"
            aria-hidden="true"
          >
            <Instagram className="h-5 w-5 text-white" />
          </div>
          <div>
            <p className="text-sm font-medium">Get your first daily idea</p>
            <p className="text-xs text-muted-foreground">Link Instagram to unlock Co-pilot.</p>
          </div>
        </div>
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
      </CardContent>
    </Card>
  );
}
