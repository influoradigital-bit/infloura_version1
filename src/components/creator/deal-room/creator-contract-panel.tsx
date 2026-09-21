'use client';

import * as React from 'react';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Input } from '@/components/ui/input';
import { Download, PenTool, CheckCircle2, Loader2, AlertCircle } from 'lucide-react';
import { TimelineEvent } from '@/lib/types';
import type { DealContractStatus } from '@/components/brand/deal-room/deal-contract-tab';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
import { api, ApiError } from '@/lib/api';
import { statusAfterCreatorSign } from '@/lib/creator-contract-store';
import { useToast } from '@/hooks/use-toast';
import { formatINR } from '@/lib/utils';
import {
  deliverableCountLabel,
  deliverableSlotsLabel,
  deliverableSlotsOf,
  deliverableTypeLabel,
} from '@/lib/deliverable-slots';

export function CreatorContractPanel({
  open,
  onOpenChange,
  event,
  status,
  onStatusChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  event: TimelineEvent;
  status: DealContractStatus;
  onStatusChange: (status: DealContractStatus) => void;
}) {
  const meta = event.metadata;
  const [isSigning, setIsSigning] = React.useState(false);
  const [signerName, setSignerName] = React.useState('');
  const { toast } = useToast();
  const contractId = meta?.contractId;
  const amount = meta?.amount;
  // Distinguishes "amount not loaded/set" from a real number so we never fabricate a figure
  // (see BrandF.md P-3 — the old `meta?.amount || 50000` showed a fake ₹50,000 for deals
  // whose real amount was 0/missing).
  const hasAmount = amount != null && Number.isFinite(amount);

  /*
   * The real platform fee, in basis points, from GET /creator/platform-fee
   * (`api.wallet.platformFee`, api.ts:3755 — the same endpoint `counter-proposal-form.tsx` and
   * `creator-wallet.tsx` already read).
   *
   * `null` means "we do not know it yet, or the call failed", and a null fee HIDES the fee and
   * net-earnings rows rather than falling back to 15. The previous code rendered a literal
   * "Platform Fee (15%)" label beside `amount * 0.15`, and computed "You Receive" as
   * `amount * 0.85`, on a screen the creator reads as their own signed economics. The rate is
   * configurable — the admin Fee Control panel writes `creatorFeePercent` and the bundle already
   * ships fee-CHANGE copy keyed on it — so 15 is a guess about a number the server will tell us,
   * and a wrong take-home figure is worse than an absent one.
   *
   * Deliberately NOT the `counter-proposal-form.tsx` pattern of seeding the state at 1500: that
   * form is a calculator the creator is driving, this panel is a statement of what they are about
   * to sign.
   */
  const [feeBps, setFeeBps] = React.useState<number | null>(null);
  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const fee = await api.wallet.platformFee();
        if (!cancelled && fee && Number.isFinite(fee.feeBps)) setFeeBps(fee.feeBps);
      } catch (err) {
        // Non-blocking, and deliberately no fallback rate — the rows stay hidden.
        console.error('Failed to load platform fee', err);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const feePercentLabel = feeBps == null ? null : (feeBps / 100).toFixed(feeBps % 100 === 0 ? 0 : 2);
  const platformFeeAmount =
    feeBps != null && hasAmount && amount != null ? Math.round((amount * feeBps) / 10000) : null;
  const netAmount =
    platformFeeAmount != null && amount != null ? amount - platformFeeAmount : null;
  const showFeeBreakdown = platformFeeAmount != null && netAmount != null;

  /*
   * What the brand actually ordered, read off the message metadata through the shared helpers
   * (`deliverableSlotsOf`/`deliverableSlotsLabel`, src/lib/deliverable-slots.ts) — never a
   * constant. This row used to read "2 Instagram Reels, 1 Instagram Story" for every contract on
   * the platform.
   *
   * Three honest outcomes, in order of how much the message carries:
   *   slots  -> "2x Instagram Reel · 1x Instagram Story" (the real per-type breakdown)
   *   count  -> "3 pieces" (messages written before 2026-07-26 stored only the number)
   *   neither-> null, and the row is not rendered at all.
   */
  const deliverablesLabel = deliverableSlotsLabel(meta) ?? deliverableCountLabel(meta);

  const statusSteps = [
    { key: 'generated', label: 'Generated', done: true },
    { key: 'brand_signed', label: 'Brand Signed', done: ['brand_signed', 'creator_signed', 'active'].includes(status) },
    { key: 'creator_signed', label: 'You Signed', done: ['creator_signed', 'active'].includes(status) },
    { key: 'active', label: 'Active', done: status === 'active' },
  ];

  const creatorSigningStatus = () => {
    if (status === 'generated') return 'Awaiting Brand Signature';
    // 'pending_signature' (F-0250 follow-up): one party has signed, which one is unknown — do
    // not claim it was the brand.
    if (status === 'pending_signature') return 'Signature Pending - Your Turn to Sign';
    if (status === 'brand_signed') return 'Brand Signed - Your Turn to Sign';
    if (status === 'creator_signed') return 'Both Signed - Active';
    // F-0226: contract ACTIVE means both signed, not that escrow is funded (separate step).
    if (status === 'active') return 'Both Signed - Awaiting Funding';
    return 'Unknown Status';
  };

  // 'pending_signature': signer unknown, so the creator's Sign control must stay reachable —
  // otherwise a brand-first PENDING_SIGNATURES deadlocks the creator (F-0250 follow-up).
  const shouldShowSignButton = status === 'brand_signed' || status === 'pending_signature';

  const handleDownloadPDF = () => {
    if (!hasAmount) {
      toast({
        title: 'Amount not available',
        description: 'This contract has no confirmed amount yet — PDF download is unavailable.',
        variant: 'destructive',
      });
      return;
    }
    const contractData = {
      contractId: meta?.contractId || 'Not available',
      // F-0669 round 3: 'Influora Brand' was an invented company name on a contract
      // document — the same fabrication the sibling brand panel was fixed for, missed here
      // because only the sibling was named. 'Brand' is an honest generic role label.
      brandName: meta?.brandName || 'Brand',
      // F-0669: prefer the real creator name from event metadata when the app
      // has it. 'You (Creator)' is an honest role label for this self-view
      // (the viewer IS the creator) — never a fabricated person's name.
      creatorName: meta?.creatorName || 'You (Creator)',
      // F-0669 round 4: 'Summer Fashion' was an invented campaign name on a contract document.
      campaignName: meta?.campaignName || 'Not specified',
      amount,
      /*
       * F-0669 round 4: this was a fixed two-row list — 2x 'Instagram Reel', 1x 'Instagram
       * Story' — written into the downloadable contract PDF of every deal, whatever the brand
       * had actually ordered. Build it from the message's real deliverable slots; an empty
       * table is honest, an invented one is not (the same call `creator-deal-contract-tab.tsx`
       * already makes with `deliverables: []`).
       */
      deliverables: deliverableSlotsOf(meta).map((slot) => ({
        title: deliverableTypeLabel(slot.type),
        description: '',
        quantity: typeof slot.qty === 'number' ? slot.qty : 1,
      })),
      // F-0669: this component never receives the real deadline, usage rights,
      // exclusivity, or revision cap from the server — leave them undefined
      // (ContractData makes them optional) rather than invent a past date or a
      // fake policy. generateContractHTML renders an honest "Not specified".
      deadline: meta?.deadline,
      customClauses: [],
      createdAt: new Date(),
    };
    downloadContractPDF(contractData, `${meta?.contractId || 'contract'}.pdf`);
    toast({
      title: 'PDF Downloaded',
      description: 'Contract PDF is ready to review and sign.',
    });
  };

  const handleSign = async () => {
    const trimmedName = signerName.trim();
    if (!contractId || !trimmedName) return;
    setIsSigning(true);
    try {
      const result = await signContract(contractId, 'creator', trimmedName);
      if (result.success) {
        const next = statusAfterCreatorSign();
        onStatusChange(next);
        // F-0226: signing reaches CONTRACTED only; IN_PROGRESS comes from CollaborationLifecycleService.onEscrowFunded
        // when the brand funds a milestone — a separate action. Submit control is gated on in_progress.
        toast({
          title: 'Contract signed',
          description: 'Waiting for the brand to secure the funds. You’ll be notified when you can start work.',
        });
        setSignerName('');
        onOpenChange(false);
      }
    } catch (error) {
      toast({
        title: 'Error',
        description: error instanceof ApiError ? error.message : 'Failed to sign contract. Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsSigning(false);
    }
  };

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:w-[700px] overflow-y-auto">
        <SheetHeader className="mb-6">
          <SheetTitle>Contract Review & Signing</SheetTitle>
        </SheetHeader>

        <div className="space-y-6">
          {/* Status Alert */}
          <div className={`p-4 rounded-lg border flex gap-3 ${
            shouldShowSignButton 
              ? 'bg-amber-50 border-stage-negotiating-border' 
              : 'bg-blue-50 border-stage-outreach-border'
          }`}>
            {shouldShowSignButton ? (
              <AlertCircle className="h-5 w-5 text-stage-negotiating-fg flex-shrink-0 mt-0.5" />
            ) : (
              <CheckCircle2 className="h-5 w-5 text-stage-outreach-fg flex-shrink-0 mt-0.5" />
            )}
            <div>
              <p className={`font-medium ${shouldShowSignButton ? 'text-amber-900' : 'text-blue-900'}`}>
                {creatorSigningStatus()}
              </p>
              {shouldShowSignButton && (
                <p className="text-sm text-amber-700 mt-1">
                  {status === 'brand_signed'
                    ? 'The brand has signed the contract. Please review carefully and sign below to proceed.'
                    : 'Please review the contract carefully and sign below to proceed.'}
                </p>
              )}
              {status === 'active' && (
                <p className="text-sm text-blue-700 mt-1">
                  Both parties have signed. Waiting for the brand to secure the funds before you can start work.
                </p>
              )}
            </div>
          </div>

          {/* Status Timeline */}
          <div>
            <h3 className="font-semibold text-sm mb-4">Signature Progress</h3>
            <div className="flex gap-2">
              {statusSteps.map((step, idx) => (
                <React.Fragment key={step.key}>
                  <div className="flex flex-col items-center flex-1">
                    <div
                      className={`w-10 h-10 rounded-full flex items-center justify-center font-semibold text-sm mb-2 ${
                        step.done
                          ? 'bg-stage-approved text-stage-approved-fg'
                          : 'bg-gray-100 text-gray-400'
                      }`}
                    >
                      {step.done ? <CheckCircle2 className="h-5 w-5" /> : idx + 1}
                    </div>
                    <p className={`text-xs text-center ${step.done ? 'text-gray-900 font-medium' : 'text-gray-500'}`}>
                      {step.label}
                    </p>
                  </div>
                  {idx < statusSteps.length - 1 && (
                    <div className={`flex-1 h-1 mt-5 ${step.done ? 'bg-green-200' : 'bg-gray-200'}`} />
                  )}
                </React.Fragment>
              ))}
            </div>
          </div>

          <Separator />

          {/* Contract Details */}
          <div>
            <h3 className="font-semibold text-sm mb-4">Contract Details</h3>
            {/* F-0669 round 4: the three fallbacks here were 'CTR-2024-001', 'StyleCo Fashion'
                and 'Summer Fashion 2024' — a fake contract number, a fake company and a fake
                campaign, shown to a creator on the screen where they sign. Same ruling as the
                Key Terms rows below: render the real value or an honest 'Not specified'. */}
            <div className="space-y-3">
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Contract ID</span>
                <span className="text-sm font-medium">{meta?.contractId || 'Not specified'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Brand</span>
                <span className="text-sm font-medium">{meta?.brandName || 'Not specified'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Campaign</span>
                <span className="text-sm font-medium">{meta?.campaignName || 'Not specified'}</span>
              </div>
              <div className="flex justify-between pt-2 border-t">
                <span className="text-sm font-medium">Contract Value</span>
                <span className="text-sm font-bold text-stage-approved-fg">{formatINR(amount)}</span>
              </div>
            </div>
          </div>

          <Separator />

          {/* Your Earnings */}
          <div>
            <h3 className="font-semibold text-sm mb-4">Your Earnings</h3>
            <div className="space-y-2 bg-gray-50 p-4 rounded-lg">
              <div className="flex justify-between text-sm">
                <span className="text-gray-700">Contract Value</span>
                <span className="font-medium">{formatINR(amount)}</span>
              </div>
              {showFeeBreakdown && (
                <>
                  <div className="flex justify-between text-sm">
                    <span className="text-gray-700">Platform Fee ({feePercentLabel}%)</span>
                    <span className="font-medium text-stage-disputed-fg">
                      -{formatINR(platformFeeAmount)}
                    </span>
                  </div>
                  <Separator className="my-2" />
                  <div className="flex justify-between">
                    <span className="font-semibold text-gray-900">You Receive</span>
                    <span className="font-bold text-lg text-stage-approved-fg">
                      {formatINR(netAmount)}
                    </span>
                  </div>
                </>
              )}
              {!showFeeBreakdown && (
                <p className="text-xs text-gray-500">
                  The platform fee for this contract isn&apos;t available right now, so your
                  take-home figure isn&apos;t shown.
                </p>
              )}
              {/* F-0669 round 4 / paytrigger: this line used to read "Amount will be transferred
                  to your wallet upon final approval and deliverable completion" — wrong trigger
                  (approving a draft pays nobody) and wrong destination (there is no wallet
                  payout and no self-serve withdrawal). */}
              <p className="text-xs text-gray-500 mt-2">
                Post your content, then submit the live link. Once a workspace Owner or Admin
                releases the payment, Influora sends it by bank transfer to your saved payout
                details within 2 working days. You never have to request it.
              </p>
            </div>
          </div>

          <Separator />

          {/* Key Terms */}
          <div>
            <h3 className="font-semibold text-sm mb-4">Key Terms</h3>
            <div className="space-y-3 text-sm">
              {/* F-0669 round 4: "50% upfront (secured), 50% on completion" was presented here
                  as this creator's signed payment schedule. Nothing in the contract event, the
                  collaboration or any API this page calls carries a payment split — the figure
                  was invented, and the comment two fields down already forbade exactly this.
                  There is no per-deal payment-terms field to read, so the row is honest-blank. */}
              <div>
                <p className="text-gray-600">Payment Terms</p>
                <p className="font-medium">Not specified</p>
              </div>
              {/* F-0669 round 4: was the constant "2 Instagram Reels, 1 Instagram Story". Now the
                  message's real slots; the row is dropped entirely when it carries none. */}
              {deliverablesLabel && (
                <div>
                  <p className="text-gray-600">Deliverables</p>
                  <p className="font-medium">{deliverablesLabel}</p>
                </div>
              )}
              {/* F-0669 round 3: the creator reads this as the actual signed terms. Render the
                  real deadline when the app has it; never a fabricated date, usage-rights term,
                  or revision cap — 'Not specified' matches the honest fallback
                  contract-generator.ts already renders into the PDF for these same terms. */}
              <div>
                <p className="text-gray-600">Deadline</p>
                <p className="font-medium">{meta?.deadline || 'Not specified'}</p>
              </div>
              <div>
                <p className="text-gray-600">Usage Rights</p>
                <p className="font-medium">Not specified</p>
              </div>
              <div>
                <p className="text-gray-600">Revision Cap</p>
                <p className="font-medium">Not specified</p>
              </div>
            </div>
          </div>

          <Separator />

          {shouldShowSignButton && (
            <div className="space-y-2">
              <label htmlFor="creator-panel-signer-name" className="text-xs font-medium text-gray-600">
                Type your full legal name to sign
              </label>
              <Input
                id="creator-panel-signer-name"
                placeholder="Your legal name"
                value={signerName}
                onChange={(e) => setSignerName(e.target.value)}
                disabled={isSigning || !contractId}
              />
              {!contractId && (
                <p className="text-xs text-destructive-foreground">
                  No contract ID on this event — signing is unavailable.
                </p>
              )}
            </div>
          )}

          {/* Actions */}
          <div className="flex gap-2 pt-4">
            <Button variant="outline" className="flex-1 gap-2" onClick={handleDownloadPDF}>
              <Download className="h-4 w-4" />
              Download PDF
            </Button>
            {shouldShowSignButton && (
              <Button
                className="flex-1 gap-2 bg-stage-approved-fg hover:opacity-90 text-white"
                onClick={handleSign}
                disabled={isSigning || !contractId || !signerName.trim()}
              >
                {isSigning ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" />
                    Signing...
                  </>
                ) : (
                  <>
                    <PenTool className="h-4 w-4" />
                    Sign Now
                  </>
                )}
              </Button>
            )}
            {status === 'active' && (
              <Button className="flex-1 gap-2" disabled>
                <CheckCircle2 className="h-4 w-4" />
                Signed & Active
              </Button>
            )}
          </div>

          {status === 'generated' && (
            <p className="text-xs text-gray-500 text-center pt-4">
              Waiting for brand to sign the contract first.
            </p>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}
