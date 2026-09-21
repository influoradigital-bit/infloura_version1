'use client';

import * as React from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Input } from '@/components/ui/input';
import { Download, PenTool, CheckCircle2, Lock, Loader2 } from 'lucide-react';
import { TimelineEvent } from '@/lib/types';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
import { ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { formatINR } from '@/lib/utils';
import {
  deliverableCountLabel,
  deliverableSlotsOf,
  deliverableTypeLabel,
} from '@/lib/deliverable-slots';

export function ContractPanel({
  open,
  onOpenChange,
  event,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  event: TimelineEvent;
}) {
  const meta = event.metadata;
  const status = meta?.contractStatus || 'generated';
  const [isSigning, setIsSigning] = React.useState(false);
  const [signerName, setSignerName] = React.useState('');
  const { toast } = useToast();
  const contractId = meta?.contractId;
  const amount = meta?.amount;
  // Distinguishes "amount not loaded/set" from a real number so we never fabricate a figure
  // (see BrandF.md P-3 — the old `meta?.amount || 50000` showed a fake ₹50,000 for deals
  // whose real amount was 0/missing).
  const hasAmount = amount != null && Number.isFinite(amount);

  const statusSteps = [
    { key: 'generated', label: 'Generated', done: true },
    { key: 'brand_signed', label: 'Brand Signed', done: ['brand_signed', 'creator_signed', 'active'].includes(status) },
    { key: 'creator_signed', label: 'Creator Signed', done: ['creator_signed', 'active'].includes(status) },
    { key: 'active', label: 'Active', done: status === 'active' },
  ];

  const handleDownloadPDF = () => {
    if (!hasAmount) {
      toast({
        title: 'Amount not available',
        description: 'This contract has no confirmed amount yet — PDF download is unavailable.',
        variant: 'destructive',
      })
      return
    }
    const contractData = {
      contractId: meta?.contractId || 'Not available',
      // F-0669 round 3: use the real brand name from event metadata when the
      // app has it; 'Brand' is an honest generic role label — never an
      // invented company name like 'Influora Brand'.
      brandName: meta?.brandName || 'Brand',
      // F-0669: no fabricated human name on a legal document. Use the real
      // creator name when the app actually has it (event metadata); otherwise
      // an honest role label — never an invented person like 'Priya Sharma'.
      creatorName: meta?.creatorName || 'Creator',
      // F-0669 round 5 (brand twin of the creator-side fix): 'Summer Fashion' was an
      // invented campaign name written into a contract DOCUMENT. Nothing this panel
      // receives carries a campaign title — DealService writes `deliverables` and
      // `deliverableCount` onto the deal-message metadata (DealService.java:2052-2053)
      // and never a campaign name — so the honest value is the same 'Not specified'
      // contract-generator.ts already renders for the terms it was not given.
      campaignName: meta?.campaignName || 'Not specified',
      amount,
      /*
       * F-0669 round 5: this was a fixed two-row table — 2x 'Instagram Reel', 1x
       * 'Instagram Story' — written into the downloadable contract PDF of EVERY brand
       * deal, whatever the brand had actually ordered. Build it from the message's real
       * deliverable slots (`metadata.deliverables`, DealService.java:2052) through the
       * shared helpers, exactly as the already-fixed creator-side panel does. An empty
       * table makes no claim; an invented one asserts a deliverable schedule nobody
       * agreed to, on the document the brand signs.
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
    }
    downloadContractPDF(contractData, `${meta?.contractId || 'contract'}.pdf`)
    toast({
      title: 'PDF Downloaded',
      description: 'Contract PDF is ready to print or save.',
    })
  }

  const handleSign = async () => {
    const trimmedName = signerName.trim()
    if (!contractId || !trimmedName) return
    setIsSigning(true)
    try {
      const result = await signContract(contractId, 'brand', trimmedName)
      if (result.success) {
        toast({
          title: 'Contract Signed',
          description: 'Your signature has been recorded. Awaiting creator signature.',
        })
        setSignerName('')
        // In production: update contract status to brand_signed
      }
    } catch (error) {
      toast({
        title: 'Error',
        description: error instanceof ApiError ? error.message : 'Failed to sign contract. Please try again.',
        variant: 'destructive',
      })
    } finally {
      setIsSigning(false)
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:w-[700px] overflow-y-auto">
        <SheetHeader className="mb-6">
          <SheetTitle>Contract Details</SheetTitle>
        </SheetHeader>

        <div className="space-y-6">
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
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted text-muted-foreground'
                      }`}
                    >
                      {step.done ? <CheckCircle2 className="h-5 w-5" /> : idx + 1}
                    </div>
                    <p className="text-xs text-center text-muted-foreground">{step.label}</p>
                  </div>
                  {idx < statusSteps.length - 1 && (
                    <div
                      className={`flex-1 h-0.5 mt-5 ${
                        step.done ? 'bg-primary' : 'bg-muted'
                      }`}
                    />
                  )}
                </React.Fragment>
              ))}
            </div>
          </div>

          <Separator />

          {/* Contract Info Card */}
          <Card className="border-primary/20">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Contract Summary</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <p className="text-xs text-muted-foreground">Contract ID</p>
                <p className="font-mono text-sm">{meta?.contractId || 'Not available'}</p>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-muted-foreground">Amount</p>
                  <p className="text-lg font-bold">{formatINR(amount)}</p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">Status</p>
                  <Badge className="mt-1">{status.replace(/_/g, ' ').toUpperCase()}</Badge>
                </div>
              </div>

              <div>
                <p className="text-xs text-muted-foreground mb-2">Contract Terms (Read-only)</p>
                <div className="bg-muted/50 p-3 rounded text-xs space-y-2 max-h-40 overflow-y-auto">
                  <p className="font-semibold">Terms & Conditions</p>
                  <ol className="list-decimal list-inside space-y-1 text-muted-foreground">
                    {/* Same trap as proposal-card: `meta.deliverables` is a DeliverableSlot[], so
                        rendering it directly threw React #31. The old `|| 3` also invented a term
                        the parties never agreed to (TECH-STACK.md rule 7). */}
                    <li>
                      Creator shall deliver {deliverableCountLabel(meta) ?? 'the agreed pieces'} of
                      content as agreed
                    </li>
                    {/* F-0669 round 3: this on-screen clause list is what the brand actually
                        reads as the agreement. Render the real deadline when the app has it;
                        never a fabricated date, usage-rights term, or revision cap. 'Not
                        specified' matches the honest fallback contract-generator.ts already
                        renders into the PDF for these same optional legal terms — same
                        wording whether the brand reads the panel or the downloaded PDF. */}
                    <li>Content must be delivered by {meta?.deadline || 'Not specified'}</li>
                    <li>Usage rights: Not specified</li>
                    <li>Payment is released once the post is live and its link has been submitted</li>
                    <li>Revisions: Not specified</li>
                    <li>Disputes will be resolved through platform arbitration</li>
                  </ol>
                </div>
              </div>
            </CardContent>
          </Card>

          <Separator />

          {/* Escrow Information */}
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <Lock className="h-4 w-4 text-yellow-500" />
              <h3 className="font-semibold text-sm">Secured Funds</h3>
            </div>
            <Card className="border-yellow-200/50 bg-yellow-50/30">
              <CardContent className="pt-4">
                <p className="text-sm text-muted-foreground">
                  {hasAmount ? `${formatINR(amount)} secured` : 'Secured amount not yet available'}
                </p>
                <p className="text-xs text-muted-foreground mt-2">
                  Funds stay secured until the approved post is live and its link has been submitted
                </p>
              </CardContent>
            </Card>
          </div>

          {status === 'generated' && (
            <div className="space-y-2">
              <label htmlFor="brand-panel-signer-name" className="text-xs font-medium text-muted-foreground">
                Type your full legal name to sign
              </label>
              <Input
                id="brand-panel-signer-name"
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
            {status === 'generated' && (
              <Button
                className="flex-1 gap-2"
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
          </div>
        </div>
      </SheetContent>
    </Sheet>
  )
}
