import * as React from 'react';
import { Loader2, UserPlus, Trash2, MailX, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  api,
  ApiError,
  type WorkspaceMemberRow,
  type WorkspaceInviteResponse,
} from '@/lib/api';
import { toast } from '@/hooks/use-toast';

/**
 * [F-0443, unreachable-endpoint] Workspace team management.
 *
 * WorkspaceMemberController has been backend-complete since Task 23 and NO shipped frontend
 * reached any of it — a brand could not invite a colleague through the product at all. This panel
 * is that missing surface: list members, invite by email with a role, list outstanding invites,
 * revoke one, and remove a member.
 *
 * DELIBERATELY NOT BUILT HERE, because building them would mean inventing behaviour the backend
 * does not have:
 *
 * - ROLE CHANGE. `WorkspaceMemberDtos.ChangeRoleRequest` exists and its javadoc documents
 *   `PATCH /workspace/members/{memberId}/role`, but WorkspaceMemberController has no such mapping
 *   — the DTO is orphaned. A role dropdown here would be a control that cannot possibly work,
 *   which is the F-0341 dead-control shape this project has already been bitten by.
 * - WORKSPACE SWITCHING. `POST /workspace/members/switch` exists, but nothing enumerates the
 *   workspaces a user belongs to: `/workspaces/me` returns exactly one and there is no
 *   list-my-workspaces route. A switcher would have nothing to populate, so its options could
 *   only be fabricated. Recorded as its own finding rather than faked.
 *
 * The invite endpoint carries `@NotBlank @Email` on email, so it emits per-field VALIDATION_ERROR
 * entries; those are read off `ApiError.fields` here rather than flattened into a generic toast
 * (the F-0668/F-0681 contract), including Spring's bracketed index form for list elements.
 */

type AssignableRole = 'ADMIN' | 'MANAGER' | 'MEMBER' | 'VIEWER';

const ASSIGNABLE_ROLES: readonly AssignableRole[] = ['ADMIN', 'MANAGER', 'MEMBER', 'VIEWER'];

/** Roles the SERVER lets manage seats. Mirrored for UX only — the server is still the authority. */
const MANAGING_ROLES = new Set(['OWNER', 'ADMIN']);

function baseFieldName(field: string): string {
  return field.replace(/\[\d+\]/g, '');
}

function roleLabel(role: string): string {
  return role.charAt(0) + role.slice(1).toLowerCase();
}

export function TeamMembersPanel() {
  const [members, setMembers] = React.useState<WorkspaceMemberRow[]>([]);
  const [invites, setInvites] = React.useState<WorkspaceInviteResponse[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  const [email, setEmail] = React.useState('');
  const [role, setRole] = React.useState<AssignableRole>('MEMBER');
  const [inviting, setInviting] = React.useState(false);
  const [emailError, setEmailError] = React.useState<string | undefined>();
  const [busyId, setBusyId] = React.useState<string | null>(null);

  const load = React.useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [memberRows, inviteRows] = await Promise.all([
        api.workspaceMembers.list(),
        api.workspaceMembers.listInvites(),
      ]);
      setMembers(memberRows);
      setInvites(inviteRows);
    } catch (err) {
      // An empty table with no explanation is indistinguishable from "you have no team", which is
      // the exact failure mode this whole finding is about. Say the load failed.
      setLoadError(
        err instanceof ApiError ? err.message : 'Could not load your team. Please try again.',
      );
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    void load();
  }, [load]);

  const myUserId =
    typeof localStorage !== 'undefined' ? localStorage.getItem('brand_user_id') : null;
  const myRole = members.find((m) => m.userId === myUserId)?.role ?? null;
  // An unknown role (no matching row) is treated as CAN manage: the server rejects what it must,
  // and hiding the controls on a failed lookup would recreate the very bug this panel fixes —
  // a brand with no way to invite anyone.
  const canManage = myRole === null || MANAGING_ROLES.has(myRole);

  const pendingInvites = invites.filter((i) => i.status === 'PENDING');
  const activeMembers = members.filter((m) => m.active);

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault();
    setInviting(true);
    setEmailError(undefined);
    try {
      await api.workspaceMembers.invite(email.trim(), role);
      toast({
        title: 'Invite sent',
        description: email.trim() + ' was invited as ' + roleLabel(role) + '.',
      });
      setEmail('');
      await load();
    } catch (err) {
      if (err instanceof ApiError && err.fields?.length) {
        const named = err.fields.map((f) => ({ ...f, field: baseFieldName(f.field) }));
        const emailField = named.find((f) => f.field === 'email');
        setEmailError(emailField?.message);
        const others = named.filter((f) => f.field !== 'email');
        toast({
          title: emailField ? 'Please fix the highlighted field' : 'Please fix the following',
          description: others.length
            ? others.map((f) => f.field + ': ' + f.message).join(' · ')
            : err.message,
          variant: 'destructive',
        });
      } else {
        toast({
          title: 'Could not send the invite',
          description: err instanceof ApiError ? err.message : 'Please try again.',
          variant: 'destructive',
        });
      }
    } finally {
      setInviting(false);
    }
  }

  async function handleRevoke(inviteId: string, inviteEmail: string) {
    setBusyId(inviteId);
    try {
      await api.workspaceMembers.revokeInvite(inviteId);
      toast({ title: 'Invite revoked', description: inviteEmail + ' can no longer join.' });
      await load();
    } catch (err) {
      toast({
        title: 'Could not revoke the invite',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setBusyId(null);
    }
  }

  async function handleRemove(memberId: string) {
    setBusyId(memberId);
    try {
      await api.workspaceMembers.removeMember(memberId);
      // "Removed from workspace", never "deleted": DELETE /workspace/members/{id} DEACTIVATES the
      // membership; the person's account continues to exist.
      toast({ title: 'Removed from workspace' });
      await load();
    } catch (err) {
      toast({
        title: 'Could not remove that member',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setBusyId(null);
    }
  }

  if (loading) {
    return (
      <Card className="p-6">
        <div className="flex items-center gap-2 text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          <span>Loading your team...</span>
        </div>
      </Card>
    );
  }

  return (
    <div className="space-y-6">
      <Card className="p-6">
        <h3 className="font-semibold mb-1">Team members</h3>
        <p className="text-sm text-muted-foreground mb-6">People who can work in this workspace.</p>

        {loadError && (
          <div className="mb-4 rounded-lg border border-destructive/40 bg-destructive/10 p-3">
            <p className="text-sm text-destructive-foreground">{loadError}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={() => void load()}>
              Try again
            </Button>
          </div>
        )}

        {!loadError && activeMembers.length === 0 && (
          <p className="text-sm text-muted-foreground">No active members yet.</p>
        )}

        <ul className="space-y-2">
          {activeMembers.map((member) => (
            <li
              key={member.id}
              className="flex items-center justify-between rounded-lg border p-3"
              data-testid="team-member-row"
            >
              <div className="min-w-0">
                <p className="text-sm font-medium truncate">
                  {member.userId === myUserId ? 'You' : member.userId}
                </p>
                <Badge variant="outline" className="mt-1">
                  {roleLabel(member.role)}
                </Badge>
              </div>
              {canManage && member.role !== 'OWNER' && member.userId !== myUserId && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busyId === member.id}
                  onClick={() => void handleRemove(member.id)}
                  aria-label={'Remove ' + member.userId + ' from workspace'}
                >
                  {busyId === member.id ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Trash2 className="h-4 w-4" />
                  )}
                </Button>
              )}
            </li>
          ))}
        </ul>
      </Card>

      {canManage ? (
        <Card className="p-6">
          <h3 className="font-semibold mb-1">Invite a colleague</h3>
          <p className="text-sm text-muted-foreground mb-6">
            They will need to sign in to Influora before they can accept.
          </p>
          <form onSubmit={handleInvite} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="invite-email">Work email</Label>
              <Input
                id="invite-email"
                type="email"
                required
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value);
                  if (emailError) setEmailError(undefined);
                }}
                placeholder="colleague@company.com"
                aria-invalid={!!emailError}
              />
              {emailError && <p className="text-xs text-destructive-foreground">{emailError}</p>}
            </div>
            <div className="space-y-2">
              <Label htmlFor="invite-role">Role</Label>
              <Select value={role} onValueChange={(v) => setRole(v as AssignableRole)}>
                <SelectTrigger id="invite-role">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ASSIGNABLE_ROLES.map((r) => (
                    <SelectItem key={r} value={r}>
                      {roleLabel(r)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <Button type="submit" disabled={inviting || !email.trim()}>
              {inviting ? (
                <Loader2 className="h-4 w-4 animate-spin mr-2" />
              ) : (
                <UserPlus className="h-4 w-4 mr-2" />
              )}
              Send invite
            </Button>
          </form>
        </Card>
      ) : (
        <Card className="p-6">
          <div className="flex items-start gap-3">
            <ShieldCheck className="h-4 w-4 mt-0.5 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">
              Only workspace owners and admins can invite or remove members.
            </p>
          </div>
        </Card>
      )}

      {pendingInvites.length > 0 && (
        <Card className="p-6">
          <h3 className="font-semibold mb-1">Pending invites</h3>
          <p className="text-sm text-muted-foreground mb-6">
            Invites that have not been accepted yet.
          </p>
          <ul className="space-y-2">
            {pendingInvites.map((invite) => (
              <li
                key={invite.id}
                className="flex items-center justify-between rounded-lg border p-3"
                data-testid="pending-invite-row"
              >
                <div className="min-w-0">
                  <p className="text-sm font-medium truncate">{invite.email}</p>
                  <Badge variant="outline" className="mt-1">
                    {roleLabel(invite.role)}
                  </Badge>
                </div>
                {canManage && (
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={busyId === invite.id}
                    onClick={() => void handleRevoke(invite.id, invite.email)}
                    aria-label={'Revoke the invite for ' + invite.email}
                  >
                    {busyId === invite.id ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : (
                      <MailX className="h-4 w-4" />
                    )}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}
