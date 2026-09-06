import * as React from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2, CheckCircle2, AlertTriangle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { api, ApiError } from '@/lib/api';

/**
 * [F-0443, unreachable-endpoint] Redeem a workspace invite.
 *
 * `POST /workspace/members/accept` was one of the five WorkspaceMemberController routes no shipped
 * frontend reached. Without this page the invite flow is only half a feature: a brand could send
 * an invite that the recipient had no way to accept, so "a brand cannot invite a colleague through
 * the product" would still be true even with the Team tab shipped.
 *
 * AUTHENTICATION IS REQUIRED, and that is not an oversight to route around. The endpoint takes an
 * `@AuthenticationPrincipal` — WorkspaceMemberService's class javadoc flags explicitly that this
 * differs from the original task brief, which described accept as public. So an invitee must sign
 * in (or register) FIRST and then redeem. This page therefore does not attempt the call when there
 * is no session; it sends the visitor to login carrying a `next` parameter so the token survives
 * the round trip, rather than firing a request that can only 401.
 */
export default function BrandAcceptInvitePage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const token = params.get('token');

  const [state, setState] = React.useState<'idle' | 'accepting' | 'accepted' | 'failed'>('idle');
  const [error, setError] = React.useState<string | null>(null);

  const hasSession =
    typeof localStorage !== 'undefined' && !!localStorage.getItem('brand_token');

  const accept = React.useCallback(async () => {
    if (!token) return;
    setState('accepting');
    setError(null);
    try {
      await api.workspaceMembers.acceptInvite(token);
      setState('accepted');
    } catch (err) {
      // A revoked, expired or already-redeemed invite all land here. The server's own message is
      // shown verbatim because it is the only thing that distinguishes them.
      setError(err instanceof ApiError ? err.message : 'This invite could not be accepted.');
      setState('failed');
    }
  }, [token]);

  if (!token) {
    return (
      <Shell>
        <div className="flex items-start gap-3">
          <AlertTriangle className="h-5 w-5 mt-0.5 text-muted-foreground" />
          <div>
            <h1 className="font-semibold">This invite link is incomplete</h1>
            <p className="text-sm text-muted-foreground mt-1">
              It is missing its token. Ask whoever invited you to send the link again.
            </p>
          </div>
        </div>
      </Shell>
    );
  }

  if (!hasSession) {
    return (
      <Shell>
        <h1 className="font-semibold">Sign in to join this workspace</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Invites are tied to your Influora account, so you need to sign in before accepting.
        </p>
        <Button
          className="mt-4"
          onClick={() =>
            navigate('/brand/login?next=' + encodeURIComponent('/brand/invite?token=' + token))
          }
        >
          Sign in to continue
        </Button>
      </Shell>
    );
  }

  if (state === 'accepted') {
    return (
      <Shell>
        <div className="flex items-start gap-3">
          <CheckCircle2 className="h-5 w-5 mt-0.5" />
          <div>
            <h1 className="font-semibold">You have joined the workspace</h1>
            <p className="text-sm text-muted-foreground mt-1">
              You now have access to this brand&apos;s campaigns and deals.
            </p>
            <Button className="mt-4" onClick={() => navigate('/brand/dashboard')}>
              Go to dashboard
            </Button>
          </div>
        </div>
      </Shell>
    );
  }

  return (
    <Shell>
      <h1 className="font-semibold">Join this workspace</h1>
      <p className="text-sm text-muted-foreground mt-1">
        Accepting adds you to the brand workspace that invited you.
      </p>
      {error && (
        <div className="mt-4 rounded-lg border border-destructive/40 bg-destructive/10 p-3">
          <p className="text-sm text-destructive-foreground">{error}</p>
        </div>
      )}
      <Button className="mt-4" disabled={state === 'accepting'} onClick={() => void accept()}>
        {state === 'accepting' && <Loader2 className="h-4 w-4 animate-spin mr-2" />}
        Accept invite
      </Button>
    </Shell>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen flex items-center justify-center p-6">
      <Card className="p-6 w-full max-w-md">{children}</Card>
    </div>
  );
}
