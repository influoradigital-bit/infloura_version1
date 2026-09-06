/**
 * INFLUORA ADMIN PANEL — Brand Meta Pixel ID
 * Owner: Ananya (Frontend)
 * Reference: T-FESTIVALBOX-0905 phase 10 (screen 2, continuation)
 *
 * Editable `workspaces.meta_pixel_id` field on the admin Brand profile view. Live-wired to
 * `brandApi.updateMetaPixel` (`PATCH /admin/brands/{id}/meta-pixel`, real mapping —
 * `AdminBrandController#updateMetaPixel` / `AdminBrandDtos.UpdateMetaPixelRequest`, both read
 * directly off disk before writing this). This is the piece the earlier phase-10 pass set up
 * (the `BrandDetail.metaPixelId` type field and `brandApi.updateMetaPixel` client method already
 * existed, and named this component in a comment) but never built before being stopped.
 *
 * SET vs CLEAR is not the same shape as `BrandProfile`'s `handleSaveEdit` above it — that PUT is a
 * `Partial<Brand>` where omitting a field means "leave unchanged". Here `metaPixelId: null` is a
 * real, meaningful request (a sponsor withdrawing consent must be able to send it), so Clear is a
 * distinct action from Save, not "save an empty string" — an empty string would fail the server's
 * own `^[0-9]{8,20}$` pattern (blank does not match "8-20 digits"), so the client never sends one.
 *
 * VALIDATION MESSAGE — copied verbatim from `AdminBrandDtos.UpdateMetaPixelRequest`'s `@Pattern`
 * message (`"metaPixelId must be 8-20 digits, or null to clear"`). Checked client-side first so a
 * malformed id never leaves the browser, but the exact same string is also what a stale client
 * would see back from the server — never invent separate, stricter copy.
 *
 * FRAMING — this field does not switch anything on. Read `src/pages/festival-box-edition.tsx`
 * (the public page) and `src/content/festival-editions.ts` before ever "simplifying" this note:
 * the live page's pixel-firing effect reads `FestivalSponsor.metaPixelId` off a HAND-CURATED
 * content file (Edition 01 has no backend edition/sponsor model at all — see that file's own
 * "WHY THIS IS A CONTENT MODULE" doc comment), completely independent of `workspaces
 * .meta_pixel_id`. Saving a value here updates the workspace row an admin can audit and a brand
 * profile can display, but an engineer still has to hand-copy the id into that content file
 * before the public page's `usePageConsent`-gated `fbq('init', …)` call ever fires it — this
 * screen alone does not connect the two. Do not soften this into "may take effect shortly" or
 * similar — that would be false today.
 */

import { useRef, useState } from 'react';
import { Radio, Pencil, X, Trash2 } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { brandApi } from '../../services/api-contracts';

// Mirrors `AdminBrandDtos.UpdateMetaPixelRequest`'s `@Pattern(regexp = "^[0-9]{8,20}$")` exactly —
// digits only, 8-20 of them. Checked client-side before any network call is made.
const META_PIXEL_PATTERN = /^[0-9]{8,20}$/;
const META_PIXEL_VALIDATION_MESSAGE = 'metaPixelId must be 8-20 digits, or null to clear';

function orDash(value: string | null | undefined): string {
  return value && value.trim().length > 0 ? value : '—';
}

export interface MetaPixelSectionProps {
  brandId: string;
  brandName: string;
  metaPixelId: string | null;
  /** Re-pulls the brand record (`useBrandDetail.refresh`) — same post-mutation pattern every
   *  other action in `BrandProfile` already uses, rather than this component holding its own
   *  copy of server truth. */
  refresh: () => void;
}

type PendingAction = { kind: 'set'; value: string } | { kind: 'clear' };

export function MetaPixelSection({ brandId, brandName, metaPixelId, refresh }: MetaPixelSectionProps) {
  const [isEditing, setIsEditing] = useState(false);
  const [draft, setDraft] = useState(metaPixelId ?? '');
  const [formError, setFormError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [pending, setPending] = useState<PendingAction | null>(null);
  // Belt-and-suspenders double-fire guard, same discipline as
  // FestivalEnquiriesPage's `provisionInFlightRef` — a ref mutates synchronously so a second
  // click racing the first render is still caught, unlike relying on `saving` state alone.
  const inFlightRef = useRef(false);

  function startEditing() {
    setDraft(metaPixelId ?? '');
    setFormError(null);
    setSaveError(null);
    setIsEditing(true);
  }

  function cancelEditing() {
    setIsEditing(false);
    setFormError(null);
    setDraft(metaPixelId ?? '');
  }

  function requestSave() {
    const trimmed = draft.trim();
    if (!META_PIXEL_PATTERN.test(trimmed)) {
      setFormError(META_PIXEL_VALIDATION_MESSAGE);
      return;
    }
    setFormError(null);
    setSaveError(null);
    setPending({ kind: 'set', value: trimmed });
  }

  function requestClear() {
    setFormError(null);
    setSaveError(null);
    setPending({ kind: 'clear' });
  }

  async function handleConfirm() {
    if (!pending || inFlightRef.current || saving) return;
    inFlightRef.current = true;
    setSaving(true);
    setSaveError(null);
    const value = pending.kind === 'clear' ? null : pending.value;
    const res = await brandApi.updateMetaPixel(brandId, value);
    if (res.success) {
      setPending(null);
      setIsEditing(false);
      refresh();
    } else {
      setSaveError(res.error ?? 'Failed to update the Meta Pixel ID.');
    }
    setSaving(false);
    inFlightRef.current = false;
  }

  return (
    <Card className="gap-3 p-5">
      <div className="flex items-center justify-between">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Radio className="size-4" aria-hidden="true" />
          Meta Pixel
        </h3>
        {!isEditing && (
          <Button type="button" size="sm" variant="outline" onClick={startEditing}>
            <Pencil aria-hidden="true" />
            {metaPixelId ? 'Edit' : 'Set pixel ID'}
          </Button>
        )}
      </div>

      {/* Always-visible framing — never collapsed behind a tooltip, same discipline as
          FestivalMetricsPage's copy-vs-sale note. This is the single most important fact an
          admin needs before touching this field: saving here does not turn anything on. */}
      <div
        role="note"
        className="flex gap-2 rounded-lg border border-warning-foreground/30 bg-muted p-3 text-xs"
      >
        <span className="text-muted-foreground">
          Storing an id here does not switch anything on by itself. The pixel only fires on{' '}
          <code className="rounded bg-card px-1 py-0.5 font-mono">/festival-box/*</code> pages,
          only after a visitor accepts the on-page consent bar — and, today, only for a sponsor an
          engineer has also added to the Festival Box content file with this exact id. This screen
          records the sponsor's consent and the id for the record; it does not itself wire the
          public page.
        </span>
      </div>

      {saveError && !pending && (
        <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
          {saveError}
        </p>
      )}

      {!isEditing ? (
        <p className="text-sm">
          <span className="text-muted-foreground">Pixel ID: </span>
          <span className="font-mono font-medium text-foreground">{orDash(metaPixelId)}</span>
        </p>
      ) : (
        <div className="flex flex-col gap-2">
          <Label htmlFor="brand-meta-pixel">Meta Pixel ID</Label>
          <Input
            id="brand-meta-pixel"
            inputMode="numeric"
            placeholder="e.g. 1234567890123456"
            value={draft}
            onChange={(e) => {
              setDraft(e.target.value);
              setFormError(null);
            }}
            disabled={saving}
            aria-invalid={formError ? true : undefined}
            aria-describedby={formError ? 'brand-meta-pixel-error' : undefined}
          />
          {formError && (
            <p id="brand-meta-pixel-error" role="alert" className="text-xs text-destructive-foreground">
              {formError}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" size="sm" onClick={requestSave} disabled={saving || draft.trim().length === 0}>
              Save
            </Button>
            {metaPixelId && (
              <Button type="button" size="sm" variant="destructive" onClick={requestClear} disabled={saving}>
                <Trash2 aria-hidden="true" />
                Clear (withdraw consent)
              </Button>
            )}
            <Button type="button" size="sm" variant="ghost" onClick={cancelEditing} disabled={saving}>
              <X aria-hidden="true" />
              Cancel
            </Button>
          </div>
        </div>
      )}

      {/* Confirm step — required for any write action here, same "second confirmation" pattern as
          BrandProfile's suspend/reinstate/budget-override dialogs. Clear and Set get distinct
          copy since they have opposite real-world meanings (a sponsor opting IN vs OUT). */}
      <AlertDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (saving) return;
          if (!open) {
            setPending(null);
            setSaveError(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {pending?.kind === 'clear'
                ? `Clear the Meta Pixel ID for ${brandName}?`
                : `Set the Meta Pixel ID for ${brandName}?`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {pending?.kind === 'clear' ? (
                <>
                  This removes the stored pixel id — use this when the sponsor withdraws tracking
                  consent. It does not by itself stop a pixel that an engineer has already hand-wired
                  into the Festival Box content file; that also has to be removed there.
                </>
              ) : (
                <>
                  This stores <span className="font-mono font-medium text-foreground">{pending?.kind === 'set' ? pending.value : ''}</span>{' '}
                  as this brand's Meta Pixel ID. It does not make the pixel fire on the public page —
                  an engineer still has to add this id to the Festival Box content file, and even
                  then it only fires after a visitor accepts the consent bar.
                </>
              )}
            </AlertDialogDescription>
          </AlertDialogHeader>
          {saveError && (
            <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
              {saveError}
            </p>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={saving}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(e) => {
                e.preventDefault();
                void handleConfirm();
              }}
              disabled={saving}
            >
              {saving ? 'Saving…' : pending?.kind === 'clear' ? 'Confirm & clear' : 'Confirm & save'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

export default MetaPixelSection;
