import * as React from 'react';
import { AlertTriangle, Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { IconBadge } from '@/components/shared/icon-badge';
import { useToast } from '@/hooks/use-toast';
import { api } from '@/lib/api';

interface BusinessAccountRequiredProps {
  /** "Skip for now" — never blocks the rest of the dashboard (spec §3.3). */
  onSkip: () => void;
  className?: string;
}

const STEPS = [
  'Open Instagram → Settings → Account type',
  'Switch to Professional Account → choose Business or Creator',
  'Connect it to a Facebook Page (Instagram prompts for this automatically)',
];

/**
 * Business/Creator-account drop-off explainer (fe-components-plan.md §4;
 * API-CONTRACT.md §4.2 — `accountType: 'personal'` on the OAuth callback).
 * Triggered only as an `idle` sub-branch via `requiresBusinessAccount`, never
 * on initial mount. Only ever occupies the same mount slot as
 * IGConnectPrompt/DailySuggestionCard — "Skip for now" just collapses back to
 * nothing special, not a navigation away from anything.
 */
export function BusinessAccountRequired({ onSkip, className }: BusinessAccountRequiredProps) {
  const { toast } = useToast();
  const [isConnecting, setIsConnecting] = React.useState(false);

  const handleReconnect = async () => {
    setIsConnecting(true);
    try {
      const { authorizationUrl } = await api.metaOAuth.authorize();
      window.location.href = authorizationUrl;
    } catch (err) {
      setIsConnecting(false);
      toast({
        variant: 'destructive',
        title: 'Could not restart Instagram connect',
        description: err instanceof Error ? err.message : 'Please try again in a moment.',
      });
    }
  };

  return (
    <Card className={className}>
      <CardHeader>
        <div className="flex items-center gap-3">
          <IconBadge icon={AlertTriangle} variant="warning" size="sm" />
          <p className="font-medium">Your Instagram needs a quick switch</p>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">
          Co-pilot needs a Business or Creator account linked to a Facebook Page — this is
          free and takes under a minute in the Instagram app.
        </p>
        <ol className="space-y-1.5 text-sm text-muted-foreground">
          {STEPS.map((step, i) => (
            <li key={step} className="flex gap-2">
              <span className="font-medium text-foreground">{i + 1}.</span>
              <span>{step}</span>
            </li>
          ))}
        </ol>
        <div className="flex items-center justify-end gap-2 pt-1">
          <Button size="sm" variant="outline" onClick={onSkip}>
            Skip for now
          </Button>
          <Button size="sm" onClick={handleReconnect} disabled={isConnecting}>
            {isConnecting ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
            ) : (
              "I've switched — reconnect"
            )}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
