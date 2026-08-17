import * as React from 'react';
import {
  ShieldCheck,
  Upload,
  X,
  CheckCircle2,
  AlertCircle,
  Loader2,
} from 'lucide-react';

import { api, isApiLive } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Alert, AlertDescription } from '@/components/ui/alert';

/**
 * B-5: business-verification (KYC) prompt shown at first campaign creation.
 *
 * CTO ruling 2026-07-11: KYC is a soft, dismissible prompt — never forced. Since then
 * `CampaignValidator.validateStatusForWorkspace` (influora-api) has started enforcing it
 * server-side: it never blocks saving/editing a DRAFT, but a workspace must be VERIFIED
 * before a campaign can go ACTIVE (403 WORKSPACE_NOT_VERIFIED otherwise — see
 * VerificationRequiredBox.tsx / WorkspaceVerificationBanner.tsx). So "optional" only holds
 * for drafting; publishing a live campaign does require it. Collects GSTIN/PAN + documents
 * and submits via `api.onboarding.submitBrandKyc` (same `POST /onboarding/brand/kyc` the
 * settings/verification page posts to). Dismissal is remembered in localStorage so it is
 * not nagging.
 *
 * OB-1 (BrandF.md §105/§91) — the read endpoint referenced by the old "Follow-up" note above
 * now exists: `GET /onboarding/brand/status` returns `kycPromptDismissed`, and
 * `POST /onboarding/brand/kyc-prompt-dismissed` persists it server-side. Both are wired below
 * alongside (not instead of) the localStorage flag, so dismissal survives across devices while
 * still hiding instantly in the current tab without waiting on a network round-trip.
 */

const DISMISS_KEY = 'influora_brand_kyc_prompt_dismissed';

function FileField({
  id,
  label,
  file,
  onChange,
  disabled,
}: {
  id: string;
  label: string;
  file: File | null;
  onChange: (f: File | null) => void;
  disabled?: boolean;
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id} className="text-xs">
        {label}
      </Label>
      <label
        htmlFor={id}
        className="flex cursor-pointer items-center gap-2 rounded-md border border-dashed border-input px-3 py-2 text-sm text-muted-foreground hover:bg-accent/50"
      >
        <Upload className="h-4 w-4 shrink-0" aria-hidden="true" />
        <span className="truncate">{file ? file.name : 'Choose file (PDF/JPG/PNG)'}</span>
        <input
          id={id}
          type="file"
          accept=".pdf,.jpg,.jpeg,.png"
          className="sr-only"
          disabled={disabled}
          onChange={(e) => onChange(e.target.files?.[0] ?? null)}
        />
      </label>
    </div>
  );
}

export function BrandKycPrompt() {
  const [dismissed, setDismissed] = React.useState<boolean>(() => {
    try {
      return typeof window !== 'undefined' && window.localStorage.getItem(DISMISS_KEY) === '1';
    } catch {
      return false;
    }
  });

  // OB-1 — server-side mirror of the dismiss flag. Best-effort: a failed fetch just leaves the
  // localStorage-only state as-is, since this prompt is optional either way.
  React.useEffect(() => {
    if (!isApiLive()) return;
    let cancelled = false;
    api.onboarding
      .getBrandStatus()
      .then(({ kycPromptDismissed }) => {
        if (!cancelled && kycPromptDismissed) setDismissed(true);
      })
      .catch(() => {
        /* ignore — localStorage flag (if set) already covers this device */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const [expanded, setExpanded] = React.useState(false);
  const [gstin, setGstin] = React.useState('');
  const [pan, setPan] = React.useState('');
  const [gstinFile, setGstinFile] = React.useState<File | null>(null);
  const [panFile, setPanFile] = React.useState<File | null>(null);
  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [done, setDone] = React.useState(false);

  const rememberDismiss = React.useCallback(() => {
    try {
      window.localStorage.setItem(DISMISS_KEY, '1');
    } catch {
      /* ignore storage errors — the prompt is optional anyway */
    }
    // OB-1 — fire-and-forget server-side persistence alongside the localStorage flag, so the
    // dismissal survives across devices/browsers instead of living only in this one.
    if (isApiLive()) {
      void api.onboarding.dismissBrandKycPrompt().catch(() => {
        /* best-effort; the localStorage flag above already hides the prompt on this device */
      });
    }
  }, []);

  const skip = () => {
    rememberDismiss();
    setDismissed(true);
  };

  if (done) {
    return (
      <Card className="mb-6 border-emerald-500/30 bg-emerald-500/5">
        <CardContent className="flex items-center gap-3 p-4">
          <CheckCircle2 className="h-5 w-5 shrink-0 text-emerald-600" aria-hidden="true" />
          <p className="text-sm">
            Business verification submitted — we'll review it shortly. You can keep creating campaigns.
          </p>
        </CardContent>
      </Card>
    );
  }

  if (dismissed) return null;

  const canSubmit =
    gstin.trim().length > 0 &&
    pan.trim().length > 0 &&
    gstinFile !== null &&
    panFile !== null &&
    !submitting;

  const submit = async () => {
    setError(null);
    if (!gstinFile || !panFile) {
      setError('Please attach both the GSTIN and PAN documents.');
      return;
    }
    setSubmitting(true);
    try {
      const [g, p] = await Promise.all([
        api.uploads.upload(gstinFile),
        api.uploads.upload(panFile),
      ]);
      await api.onboarding.submitBrandKyc({
        gstin: gstin.trim(),
        pan: pan.trim().toUpperCase(),
        gstinDocUrl: g.url,
        panDocUrl: p.url,
      });
      rememberDismiss();
      setDone(true);
    } catch {
      setError(
        'Could not submit verification right now. You can try again, or skip and keep working on a draft — but you’ll need to verify before you can publish a live campaign.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card className="mb-6 border-primary/20 bg-primary/5">
      <CardContent className="p-4 sm:p-5">
        <div className="flex items-start gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
            <ShieldCheck className="h-5 w-5 text-primary" aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-start justify-between gap-2">
              <div>
                <h3 className="text-sm font-semibold">
                  Verify your business{' '}
                  <span className="font-normal text-muted-foreground">· required to publish</span>
                </h3>
                <p className="mt-0.5 text-sm text-muted-foreground">
                  Add your GSTIN &amp; PAN to build creator trust and speed up payouts. You can
                  skip this and keep working on a draft — but you'll need to verify before you can
                  publish a live campaign.
                </p>
              </div>
              <Button
                variant="ghost"
                size="icon"
                className="h-7 w-7 shrink-0"
                onClick={skip}
                aria-label="Dismiss verification prompt"
              >
                <X className="h-4 w-4" aria-hidden="true" />
              </Button>
            </div>

            {!expanded ? (
              <div className="mt-3 flex gap-2">
                <Button size="sm" onClick={() => setExpanded(true)}>
                  Verify now
                </Button>
                <Button size="sm" variant="ghost" onClick={skip}>
                  Skip for now
                </Button>
              </div>
            ) : (
              <div className="mt-4 space-y-3">
                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label htmlFor="kyc-gstin" className="text-xs">
                      GSTIN
                    </Label>
                    <Input
                      id="kyc-gstin"
                      value={gstin}
                      onChange={(e) => setGstin(e.target.value)}
                      placeholder="15-digit GSTIN"
                      maxLength={15}
                      disabled={submitting}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="kyc-pan" className="text-xs">
                      PAN
                    </Label>
                    <Input
                      id="kyc-pan"
                      value={pan}
                      onChange={(e) => setPan(e.target.value)}
                      placeholder="10-char PAN"
                      maxLength={10}
                      className="uppercase placeholder:normal-case"
                      disabled={submitting}
                    />
                  </div>
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <FileField
                    id="kyc-gstin-doc"
                    label="GSTIN document"
                    file={gstinFile}
                    onChange={setGstinFile}
                    disabled={submitting}
                  />
                  <FileField
                    id="kyc-pan-doc"
                    label="PAN document"
                    file={panFile}
                    onChange={setPanFile}
                    disabled={submitting}
                  />
                </div>
                {error && (
                  <Alert variant="destructive">
                    <AlertCircle className="h-4 w-4" aria-hidden="true" />
                    <AlertDescription>{error}</AlertDescription>
                  </Alert>
                )}
                <div className="flex gap-2">
                  <Button size="sm" onClick={submit} disabled={!canSubmit}>
                    {submitting ? (
                      <>
                        <Loader2 className="mr-1.5 h-4 w-4 animate-spin" aria-hidden="true" />
                        Submitting…
                      </>
                    ) : (
                      'Submit verification'
                    )}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={skip} disabled={submitting}>
                    Skip for now
                  </Button>
                </div>
                {!isApiLive() && (
                  <p className="text-xs text-muted-foreground">
                    Demo mode — submission is simulated.
                  </p>
                )}
              </div>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

export default BrandKycPrompt;
