import * as React from 'react';
import { AlertTriangle, Check, Copy, KeyRound, Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { useToast } from '@/hooks/use-toast';
import { api, ApiError } from '@/lib/api';

/**
 * Brand-facing issuance of the per-workspace conversion webhook signing secret.
 *
 * F-0377 — `POST /webhook-secret/generate` (ConversionWebhookSecretController.java:50) had
 * ZERO callers anywhere in `src/`, on any branch. Since it is the only issuer of the
 * plaintext and there is deliberately no read-back endpoint, no brand could ever obtain the
 * secret, so `POST /webhooks/conversion` and `POST /webhooks/redemption` rejected 100% of
 * well-formed traffic with a fail-closed `INVALID_WEBHOOK_SIGNATURE` — indistinguishable
 * from a wrong signature, so the cause was undiagnosable from the outside. This card is the
 * missing surface.
 *
 * Two behaviours the backend's contract forces on this UI:
 *  - ONE-TIME REVEAL. The value is held in component state only, never persisted, never
 *    re-fetched. Navigating away loses it, which is the honest reflection of the server's
 *    "there is no show-me-again endpoint" design rather than a UX oversight.
 *  - GENERATE IS ROTATE. A second call invalidates the first secret immediately. The button
 *    therefore changes label and requires a confirm once a secret already exists, so nobody
 *    silently breaks a live integration by re-reading this page.
 */
export function ConversionWebhookSecretCard({ className }: { className?: string }) {
  const { toast } = useToast();
  const [secret, setSecret] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [confirmingRotate, setConfirmingRotate] = React.useState(false);
  const [copied, setCopied] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const generate = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await api.conversionWebhookSecret.generate();
      setSecret(result.secret);
      setConfirmingRotate(false);
      toast({ title: 'Signing secret generated', description: 'Copy it now — it is not shown again.' });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not generate a signing secret.');
    } finally {
      setBusy(false);
    }
  };

  const copy = async () => {
    if (!secret) return;
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setError('Could not copy to the clipboard — select the value and copy it manually.');
    }
  };

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <KeyRound className="h-5 w-5" aria-hidden="true" />
          Conversion webhook signing secret
        </CardTitle>
        <CardDescription>
          Your store signs every conversion and redemption webhook with this secret, sent as an{' '}
          <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">X-Influora-Signature</code>{' '}
          header. Without it, Influora rejects those calls and no conversion or coupon
          redemption is ever recorded.
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-4">
        {secret && (
          <div className="space-y-2">
            <div className="flex gap-2">
              <div className="flex flex-1 items-center overflow-x-auto rounded-lg border border-primary/20 bg-primary/10 p-3">
                <code className="font-mono text-sm break-all">{secret}</code>
              </div>
              <Button
                type="button"
                size="icon"
                variant="outline"
                onClick={copy}
                aria-label={copied ? 'Copied' : 'Copy signing secret to clipboard'}
              >
                {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
              </Button>
            </div>
            <div
              role="note"
              className="flex gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-sm"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden="true" />
              <p className="text-muted-foreground">
                Copy this now. It is shown once and cannot be retrieved again — if you lose it you
                will have to generate a new one, which stops the old one working.
              </p>
            </div>
          </div>
        )}

        {confirmingRotate && (
          <div
            role="alert"
            className="flex gap-2 rounded-lg border border-destructive/30 bg-destructive/10 p-3 text-sm"
          >
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" aria-hidden="true" />
            <p className="text-destructive-foreground">
              Generating a new secret invalidates the current one immediately. Any webhook your
              store sends with the old secret will be rejected until you update it there too.
            </p>
          </div>
        )}

        {error && (
          <p role="alert" className="text-sm text-destructive-foreground">
            {error}
          </p>
        )}
      </CardContent>

      <CardFooter className="gap-2">
        {confirmingRotate ? (
          <>
            <Button type="button" variant="destructive" onClick={generate} disabled={busy}>
              {busy ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                  Rotating...
                </>
              ) : (
                'Yes, rotate the secret'
              )}
            </Button>
            <Button type="button" variant="ghost" onClick={() => setConfirmingRotate(false)}>
              Cancel
            </Button>
          </>
        ) : (
          <Button
            type="button"
            onClick={secret ? () => setConfirmingRotate(true) : generate}
            disabled={busy}
          >
            {busy ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                Generating...
              </>
            ) : secret ? (
              'Rotate secret'
            ) : (
              'Generate signing secret'
            )}
          </Button>
        )}
      </CardFooter>
    </Card>
  );
}

export default ConversionWebhookSecretCard;
