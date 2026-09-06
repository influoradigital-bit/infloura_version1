/**
 * INFLUORA ADMIN PANEL — Festival Box sponsor coupon codes
 * Owner: Ananya (Frontend)
 * Reference: T-FESTIVALBOX-0905 phase 12 (screen 1, live)
 *
 * Rendered inside `FestivalEnquiriesPage`'s "Sponsor account" drawer section, once a BRAND
 * enquiry has been provisioned (`provisionedCampaignId` known) — this is the "reachable by
 * navigation from a provisioned sponsor" surface the phase-10 brief asked for.
 *
 * ---------------------------------------------------------------------------------------------
 * LIVE as of phase 12 — the phase-10/11 blocker is gone
 * ---------------------------------------------------------------------------------------------
 * Every control here used to be `disabled` (see this file's history) because the only coupon
 * endpoints reachable from this client resolved the caller's workspace via
 * `BrandContextService.requireBrandWorkspace`, which 403s any non-BRAND principal — an admin
 * session could never register a coupon. `AdminCampaignCouponController` (Vikram) closes that
 * gap: it is a dedicated admin-scoped twin, mounted at `/admin/campaigns/{campaignId}/coupons`,
 * that resolves the workspace from the campaign row itself
 * (`AdminCampaignCouponService#requireCampaign`), never through `BrandContextService`. See
 * `festivalCouponApi` in the sibling `api-contracts.ts` for the wire contract, verified against
 * `AdminCampaignCouponController.java`/`AdminCampaignCouponService.java` directly.
 *
 * ---------------------------------------------------------------------------------------------
 * THE OPERATIONALLY IMPORTANT FACT — Influora generates the code, the admin must place it
 * ---------------------------------------------------------------------------------------------
 * `CouponCodeService.generateCreatorCoupon`/`generateBrandCoupon` mint the `code` string
 * server-side (slugified campaign/creator name + a collision suffix) — this form only ever
 * submits discount terms, never a code string, and there is nowhere here to type one. Once a
 * code exists in this table, someone must create a discount with that EXACT code in the
 * sponsor's own Shopify/WooCommerce (or manual) store. A code that exists in our table but not
 * in their store is dead at their checkout — it will sit on a public Festival Box page and fail
 * for every shopper who tries it. That is the step most likely to be skipped, so it is stated
 * plainly below and repeated on every successful registration, not just in this comment.
 *
 * ---------------------------------------------------------------------------------------------
 * A SEPARATE, STILL-OPEN GAP — the public Festival Box page doesn't read this data model at all
 * ---------------------------------------------------------------------------------------------
 * `src/content/festival-editions.ts` (the public `/festival-box/:edition` page's ENTIRE data
 * source for Edition 01) is a hand-curated content file — "coupons are minted by hand" per its
 * own doc comment, and its `FestivalSponsor.coupon` is a plain string with no relationship to a
 * `CouponCode` row. A coupon registered through this panel will not appear on the live public
 * page or be counted by `FestivalCouponCopyController` (which tracks copies keyed by sponsor
 * slug against that same content file) until the edition model is rebuilt to read from the
 * database — see that file's "WHY THIS IS A CONTENT MODULE" comment. This panel is still real,
 * general-purpose value for the platform's ordinary (non-Festival-Box) campaign coupons — just
 * not a Festival-Box-page dependency yet.
 */

import { useId, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Tag, Ticket } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Spinner } from '@/components/ui/spinner';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
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
import { useToast } from '@/hooks/use-toast';
import { festivalCouponApi, type FestivalSponsorCoupon } from '../../services/api-contracts';

function orDash(value: string | null | undefined): string {
  return value && value.trim().length > 0 ? value : '—';
}

function formatDiscount(discountType: string, discountValue: number): string {
  return discountType === 'percentage' ? `${discountValue}%` : `₹${discountValue}`;
}

function formatExpiry(iso: string | null): string {
  if (!iso) return orDash(null);
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(iso));
}

export interface FestivalCouponsPanelProps {
  /** The provisioned sponsor's campaign id (`provisionedCampaignId`) — coupons are always
   *  registered against a campaign, never a bare workspace/brand. */
  campaignId: string;
  /** Company/brand name, for the panel's own copy and the post-registration reminder. */
  sponsorName: string;
}

type CouponKind = 'BRAND_LEVEL' | 'PER_CREATOR';

export function FestivalCouponsPanel({ campaignId, sponsorName }: FestivalCouponsPanelProps) {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const couponsQueryKey = ['admin', 'festival-campaign-coupons', campaignId] as const;

  const [kind, setKind] = useState<CouponKind>('BRAND_LEVEL');
  const [creatorProfileId, setCreatorProfileId] = useState('');
  const [discountType, setDiscountType] = useState<'percentage' | 'fixed'>('percentage');
  const [discountValue, setDiscountValue] = useState('');
  const [usageLimit, setUsageLimit] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [showConfirm, setShowConfirm] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [lastCreatedCode, setLastCreatedCode] = useState<string | null>(null);
  const kindGroupId = useId();

  // Belt-and-suspenders double-fire guard: a ref mutates synchronously, so even a second click
  // event dispatched before React re-renders `createCouponMutation.isPending` is still caught —
  // relying on `isPending` alone leaves a window between the click and the re-render. Same
  // discipline as `FestivalEnquiriesPage`'s "Provision sponsor" guard.
  const createInFlightRef = useRef(false);

  const couponsQuery = useQuery({
    queryKey: couponsQueryKey,
    queryFn: async () => {
      const res = await festivalCouponApi.list(campaignId);
      if (res.success && res.data) return res.data.coupons;
      throw new Error(res.error ?? 'Could not load coupons.');
    },
  });

  const parsedDiscountValue = Number(discountValue);
  const parsedUsageLimit = usageLimit ? Number(usageLimit) : undefined;
  const trimmedCreatorProfileId = creatorProfileId.trim();

  const canSubmit =
    (kind === 'BRAND_LEVEL' || trimmedCreatorProfileId.length > 0) &&
    Number.isFinite(parsedDiscountValue) &&
    parsedDiscountValue > 0;

  const createCouponMutation = useMutation({
    mutationFn: () =>
      festivalCouponApi.create(campaignId, {
        creatorProfileId: kind === 'PER_CREATOR' ? trimmedCreatorProfileId : undefined,
        discountType,
        discountValue: parsedDiscountValue,
        usageLimit: parsedUsageLimit,
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : undefined,
      }),
    onSuccess: (res) => {
      if (!res.success || !res.data) {
        setCreateError(res.error ?? 'Could not register the coupon. Please try again.');
        return;
      }
      setCreateError(null);
      setLastCreatedCode(res.data.code);
      setCreatorProfileId('');
      setDiscountValue('');
      setUsageLimit('');
      setExpiresAt('');
      queryClient.invalidateQueries({ queryKey: couponsQueryKey });
      toast({
        title: 'Coupon registered',
        description: `Code ${res.data.code} now exists in Influora. Create this exact code in ${sponsorName}'s Shopify/WooCommerce store now, or it will fail at checkout.`,
      });
    },
    onError: (err: Error) => {
      setCreateError(err.message || 'Could not register the coupon. Please try again.');
    },
    onSettled: () => {
      createInFlightRef.current = false;
    },
  });

  const handleConfirmCreate = () => {
    if (createInFlightRef.current || createCouponMutation.isPending) return;
    createInFlightRef.current = true;
    setShowConfirm(false);
    createCouponMutation.mutate();
  };

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border bg-card p-3">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <Tag className="size-4" aria-hidden="true" />
        Sponsor coupon codes
      </h3>

      {/* Always-visible operational note — the fact most likely to be skipped. */}
      <div role="note" className="flex gap-2 rounded-lg border border-warning-foreground/30 bg-muted p-3 text-xs">
        <AlertTriangle className="mt-0.5 size-3.5 shrink-0 text-warning-foreground" aria-hidden="true" />
        <div className="space-y-1.5 text-muted-foreground">
          <p className="font-medium text-foreground">Influora generates the code — you place it</p>
          <p>
            You set the discount terms below; Influora <em>generates</em> the code string, never
            the other way around. After registering, give that <em>exact</em> code to the sponsor
            so they create a matching discount in their own Shopify/WooCommerce (or manual)
            store. A code that exists in our table but not in their store is dead at their
            checkout — it will sit on a public page and fail for every shopper who tries it.
          </p>
        </div>
      </div>

      <p className="text-xs text-muted-foreground">
        Campaign <span className="font-mono text-foreground">{orDash(campaignId)}</span> ·{' '}
        {sponsorName}
      </p>

      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <Label id={`${kindGroupId}-label`}>Code kind</Label>
          <div className="flex flex-col gap-2 sm:flex-row" role="radiogroup" aria-labelledby={`${kindGroupId}-label`}>
            <label className="flex flex-1 cursor-pointer items-start gap-2 rounded-lg border border-border p-3 text-sm">
              <input
                type="radio"
                name={`${kindGroupId}-kind`}
                checked={kind === 'BRAND_LEVEL'}
                onChange={() => setKind('BRAND_LEVEL')}
                className="mt-0.5"
              />
              <span>
                <span className="block font-medium text-foreground">Brand-level (page-exclusive)</span>
                <span className="block text-xs text-muted-foreground">
                  Exactly ONE per campaign. Attributes the sale to the brand only — earns no
                  creator commission. A second attempt is refused ("this campaign already has a
                  brand-level coupon"), never a generic failure.
                </span>
              </span>
            </label>
            <label className="flex flex-1 cursor-pointer items-start gap-2 rounded-lg border border-border p-3 text-sm">
              <input
                type="radio"
                name={`${kindGroupId}-kind`}
                checked={kind === 'PER_CREATOR'}
                onChange={() => setKind('PER_CREATOR')}
                className="mt-0.5"
              />
              <span>
                <span className="block font-medium text-foreground">Per-creator</span>
                <span className="block text-xs text-muted-foreground">
                  Attributes the sale to one named creator and pays their commission. One per
                  (campaign, creator) pair.
                </span>
              </span>
            </label>
          </div>
        </div>

        {kind === 'PER_CREATOR' && (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="coupon-creator-id">Creator profile ID</Label>
            <Input
              id="coupon-creator-id"
              placeholder="cr_xxxxxxxx"
              value={creatorProfileId}
              onChange={(e) => setCreatorProfileId(e.target.value)}
            />
          </div>
        )}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="coupon-discount-type">Discount type</Label>
            <Select value={discountType} onValueChange={(v) => setDiscountType(v as 'percentage' | 'fixed')}>
              <SelectTrigger id="coupon-discount-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="percentage">Percentage (%)</SelectItem>
                <SelectItem value="fixed">Fixed amount (₹)</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="coupon-discount-value">
              {discountType === 'percentage' ? 'Percentage' : 'Amount (₹)'}
            </Label>
            <Input
              id="coupon-discount-value"
              type="number"
              min={1}
              max={discountType === 'percentage' ? 100 : undefined}
              value={discountValue}
              onChange={(e) => setDiscountValue(e.target.value)}
              placeholder="15"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="coupon-usage-limit">Usage limit (optional)</Label>
            <Input
              id="coupon-usage-limit"
              type="number"
              min={1}
              value={usageLimit}
              onChange={(e) => setUsageLimit(e.target.value)}
              placeholder="Unlimited"
            />
          </div>
        </div>

        <div className="flex flex-col gap-1.5 sm:w-1/3">
          <Label htmlFor="coupon-expires-at">Expires (optional)</Label>
          <Input
            id="coupon-expires-at"
            type="date"
            value={expiresAt}
            onChange={(e) => setExpiresAt(e.target.value)}
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Button
            type="button"
            size="sm"
            className="w-fit"
            disabled={!canSubmit || createCouponMutation.isPending}
            onClick={() => setShowConfirm(true)}
          >
            {createCouponMutation.isPending ? (
              <Spinner className="mr-1.5" aria-hidden="true" />
            ) : (
              <Ticket aria-hidden="true" />
            )}
            Register coupon
          </Button>

          {lastCreatedCode && !createError && (
            <p className="text-xs text-success-foreground">
              Code <span className="font-mono">{lastCreatedCode}</span> registered — create this
              exact code in {sponsorName}'s store now.
            </p>
          )}

          {createError && (
            <p role="alert" className="text-xs text-destructive-foreground">
              {createError}
            </p>
          )}
        </div>
      </div>

      <AlertDialog
        open={showConfirm}
        onOpenChange={(nextOpen) => {
          // Ignore attempts to close (Escape/overlay click) once the request is in flight — the
          // confirm step must not be dismissible mid-request.
          if (createCouponMutation.isPending) return;
          setShowConfirm(nextOpen);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Register this coupon?</AlertDialogTitle>
            <AlertDialogDescription>
              Influora will generate a{' '}
              {kind === 'BRAND_LEVEL' ? 'brand-level (page-exclusive)' : 'per-creator'} code for{' '}
              {formatDiscount(discountType, parsedDiscountValue || 0)} off
              {kind === 'PER_CREATOR' && trimmedCreatorProfileId
                ? ` for creator ${trimmedCreatorProfileId}`
                : ''}
              . You will still need to create that exact code in {sponsorName}'s own
              Shopify/WooCommerce store afterward — it does nothing at their checkout until you
              do.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={createCouponMutation.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={createCouponMutation.isPending}
              onClick={(e) => {
                // AlertDialogAction closes on click by default — handleConfirmCreate already
                // closes it explicitly and (critically) sets the in-flight ref synchronously
                // before mutate(), so a second click racing this one is a no-op regardless of
                // render timing.
                e.preventDefault();
                handleConfirmCreate();
              }}
            >
              {createCouponMutation.isPending ? (
                <Spinner className="mr-1.5" aria-hidden="true" />
              ) : null}
              Confirm &amp; register
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Existing coupons for this campaign. */}
      <div className="flex flex-col gap-2">
        <h4 className="text-xs font-semibold text-foreground">Registered coupons for this campaign</h4>
        <div className="overflow-hidden rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Code</TableHead>
                <TableHead>Kind</TableHead>
                <TableHead className="text-right">Discount</TableHead>
                <TableHead className="text-right">Uses</TableHead>
                <TableHead className="text-right">Expires</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {couponsQuery.isLoading && (
                <TableRow>
                  <TableCell colSpan={5} className="py-6 text-center text-xs text-muted-foreground">
                    <Spinner className="mr-1.5 inline-block" aria-hidden="true" />
                    Loading coupons…
                  </TableCell>
                </TableRow>
              )}
              {couponsQuery.isError && (
                <TableRow>
                  <TableCell colSpan={5} className="py-6 text-center text-xs text-destructive-foreground">
                    {couponsQuery.error instanceof Error
                      ? couponsQuery.error.message
                      : 'Could not load coupons.'}
                  </TableCell>
                </TableRow>
              )}
              {couponsQuery.isSuccess && couponsQuery.data.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} className="py-6 text-center text-xs text-muted-foreground">
                    No coupons registered yet for this campaign.
                  </TableCell>
                </TableRow>
              )}
              {couponsQuery.isSuccess &&
                couponsQuery.data.map((coupon: FestivalSponsorCoupon) => (
                  <TableRow key={coupon.id}>
                    <TableCell className="font-mono text-xs">{coupon.code}</TableCell>
                    <TableCell className="text-xs">
                      {coupon.creatorProfileId ? (
                        <span>Per-creator · {orDash(coupon.creatorProfileId)}</span>
                      ) : (
                        <span>Brand-level</span>
                      )}
                    </TableCell>
                    <TableCell className="text-right text-xs">
                      {formatDiscount(coupon.discountType, coupon.discountValue)}
                    </TableCell>
                    <TableCell className="text-right text-xs">
                      {coupon.usageCount} / {orDash(coupon.usageLimit != null ? String(coupon.usageLimit) : null)}
                    </TableCell>
                    <TableCell className="text-right text-xs">{formatExpiry(coupon.expiresAt)}</TableCell>
                  </TableRow>
                ))}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  );
}

export default FestivalCouponsPanel;
