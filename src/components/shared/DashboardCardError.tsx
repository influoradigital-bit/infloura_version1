import * as React from 'react';
import { AlertTriangle } from 'lucide-react';

import { Button } from '@/components/ui/button';

/**
 * F-0245 (dashboard-page.tsx) — shared loading/error primitive for data-backed cards on a money
 * surface. `text-destructive-foreground` (not `text-destructive`) per this theme's pale-bg/
 * strong-fg palette — `text-destructive` renders effectively invisible here.
 *
 * F-0324 (brand-wallet.tsx) — extracted out of dashboard-page.tsx (where it was a local,
 * non-exported function) into its own file so the wallet page could reuse the identical
 * loading/error/retry shell instead of inventing a second one. Moved verbatim: same markup,
 * same classes, same props. dashboard-page.tsx now imports this instead of declaring its own
 * copy.
 */
export function DashboardCardError({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div className="flex flex-col items-center gap-3 py-8 text-center" role="alert">
      <AlertTriangle className="h-5 w-5 text-destructive-foreground" />
      <p className="text-sm text-destructive-foreground">{message}</p>
      <Button type="button" variant="outline" size="sm" onClick={onRetry}>
        Retry
      </Button>
    </div>
  );
}
