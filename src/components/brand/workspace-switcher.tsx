import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { Check, Building2 } from 'lucide-react';
import {
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import { api, ApiError, isApiLive, type WorkspaceSummary } from '@/lib/api';
import { toast } from '@/hooks/use-toast';

/**
 * Moves this session into `workspaceId` and drops everything cached for the workspace being left.
 *
 * `api.workspaceMembers.switchWorkspace` swaps the access token; every react-query entry still in
 * memory (workspace identity, role, verification, onboarding status, campaigns, deals, wallet...)
 * was fetched with the OLD token and describes the OLD workspace, so the cache is cleared rather
 * than selectively invalidated — a stale `['workspace','my-role']` would otherwise keep gating the
 * new workspace's controls by the previous workspace's role.
 */
export async function enterWorkspace(queryClient: QueryClient, workspaceId: string): Promise<WorkspaceSummary> {
  const workspace = await api.workspaceMembers.switchWorkspace(workspaceId);
  queryClient.clear();
  return workspace;
}

/**
 * "Switch workspace" rows for the brand account menu. Renders nothing for the common case of a
 * brand that belongs to exactly one workspace, so the menu is unchanged for them.
 *
 * Why this exists: every brand user is created with their own workspace, so anyone who accepts an
 * invite belongs to two. `POST /workspace/members/switch` has existed for a while, but nothing
 * listed the workspaces a user could switch to — an invitee had no way into the workspace they
 * had joined, and (once they were in it) no way back to their own.
 */
export function WorkspaceSwitcherMenuItems({ currentWorkspaceId }: { currentWorkspaceId?: string }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [switchingTo, setSwitchingTo] = React.useState<string | null>(null);

  const mine = useQuery({
    queryKey: ['workspace', 'mine'],
    queryFn: () => api.workspaces.listMine(),
    staleTime: 5 * 60 * 1000,
    enabled: isApiLive(),
  });

  const workspaces = mine.data ?? [];
  if (workspaces.length < 2) return null;

  const handleSwitch = async (workspace: WorkspaceSummary) => {
    if (workspace.id === currentWorkspaceId || switchingTo) return;
    setSwitchingTo(workspace.id);
    try {
      await enterWorkspace(queryClient, workspace.id);
      navigate('/brand/dashboard');
    } catch (err) {
      toast({
        variant: 'destructive',
        title: 'Could not switch workspace',
        description: err instanceof ApiError ? err.message : 'Please try again.',
      });
    } finally {
      setSwitchingTo(null);
    }
  };

  return (
    <>
      <DropdownMenuLabel className="font-medium text-xs text-muted-foreground">
        Switch workspace
      </DropdownMenuLabel>
      {workspaces.map((workspace) => {
        const isCurrent = workspace.id === currentWorkspaceId;
        return (
          <DropdownMenuItem
            key={workspace.id}
            disabled={switchingTo !== null}
            aria-current={isCurrent ? 'true' : undefined}
            onClick={() => void handleSwitch(workspace)}
          >
            <Building2 className="mr-2 h-4 w-4 shrink-0" />
            <span className="min-w-0 flex-1 truncate">{workspace.name}</span>
            {isCurrent && <Check className="ml-2 h-4 w-4 shrink-0" aria-label="Current workspace" />}
          </DropdownMenuItem>
        );
      })}
      <DropdownMenuSeparator />
    </>
  );
}
