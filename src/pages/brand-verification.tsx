/**
 * BrandVerificationPage — the destination for the "Start verification" CTA on the
 * workspace-verification banner and the publish-blocked box. Renders the brand KYC
 * capture form (GSTIN/PAN + docs) for an OWNER/ADMIN of a non-verified workspace, and
 * status-appropriate messaging otherwise (already verified, in review, or not permitted).
 *
 * On a successful submit it optimistically flips the cached `['workspace','me']` status to
 * PENDING and invalidates it, so the banner (and this page) reflect "in review" immediately —
 * closing the loop the interim /brand/settings link only partially served.
 */

import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, ArrowLeft, CheckCircle2, Clock, Loader2, ShieldCheck } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useWorkspaceVerification } from '@/hooks/brand/useWorkspaceVerification';
import { BrandKycForm } from '@/components/brand/BrandKycForm';
import type { WorkspaceMeResponse } from '@/lib/api';

export default function BrandVerificationPage() {
  const queryClient = useQueryClient();
  const { status, isLoading, isVerified, canVerify, roleError, retryRole } =
    useWorkspaceVerification();

  const handleSubmitted = () => {
    // Reflect PENDING everywhere at once, then refetch to confirm from the server.
    queryClient.setQueryData<WorkspaceMeResponse | undefined>(['workspace', 'me'], (prev) =>
      prev ? { ...prev, verificationStatus: 'PENDING' } : prev,
    );
    void queryClient.invalidateQueries({ queryKey: ['workspace', 'me'] });
  };

  return (
    <div className="mx-auto max-w-2xl px-4 py-8 sm:px-6">
      <Button variant="ghost" size="sm" asChild className="mb-4 -ml-2 gap-1">
        <Link to="/brand/campaigns">
          <ArrowLeft className="h-4 w-4" aria-hidden="true" /> Campaigns
        </Link>
      </Button>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <ShieldCheck className="h-5 w-5 text-primary" aria-hidden="true" />
            Verify your workspace
          </CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              Loading verification status…
            </div>
          ) : isVerified ? (
            <StatusMessage
              icon={<CheckCircle2 className="h-6 w-6 text-stage-approved-fg" aria-hidden="true" />}
              title="Your workspace is verified"
              body="You’re all set — you can publish live campaigns."
            />
          ) : status === 'PENDING' ? (
            <StatusMessage
              icon={<Clock className="h-6 w-6 text-primary" aria-hidden="true" />}
              title="Verification in review"
              body="We’ll email you when it’s approved. Once it clears, you can publish your drafts live — approval doesn’t publish them for you."
            />
          ) : !canVerify ? (
            <StatusMessage
              icon={<ShieldCheck className="h-6 w-6 text-muted-foreground" aria-hidden="true" />}
              title="Ask a workspace admin to verify"
              body="Only an Owner or Admin can submit workspace verification. Ask one of them to complete it."
            />
          ) : (
            <>
              {/* F-0279: canVerify fails OPEN whenever roleResolved is false (F-0244), so a
                  role-query rejection or missing brand_user_id lands here — the form still
                  renders (the server enforces the real gate), but silently rendering it with
                  no signal that the client couldn't confirm the role wastes the caller's time
                  if the server then 403s. roleError/retryRole existed on the hook since F-0244
                  but no consumer ever read either. */}
              {roleError && (
                <div
                  role="status"
                  className="mb-4 flex items-center justify-between gap-3 rounded-lg border border-amber-400/50 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-400/30 dark:bg-amber-950/40 dark:text-amber-100"
                >
                  <span className="flex items-center gap-2">
                    <AlertTriangle className="h-4 w-4 flex-none" aria-hidden="true" />
                    We couldn’t confirm your workspace role. You can still submit — the server
                    checks permissions independently — but retrying may resolve this first.
                  </span>
                  <Button size="sm" variant="outline" onClick={retryRole} className="flex-none">
                    Retry
                  </Button>
                </div>
              )}
              <BrandKycForm onSubmitted={handleSubmitted} />
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function StatusMessage({
  icon,
  title,
  body,
}: {
  icon: ReactNode;
  title: string;
  body: string;
}) {
  return (
    <div className="flex flex-col items-center gap-2 py-6 text-center">
      {icon}
      <p className="text-base font-semibold text-foreground">{title}</p>
      <p className="max-w-sm text-sm text-muted-foreground">{body}</p>
    </div>
  );
}
