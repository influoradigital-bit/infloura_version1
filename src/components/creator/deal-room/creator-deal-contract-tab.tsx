import * as React from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Clock,
  Download,
  Loader2,
  PenTool,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Input } from '@/components/ui/input';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
import { api, ApiError, isApiLive } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { formatINR } from '@/lib/utils';
import type { DealContractStatus } from '@/components/brand/deal-room/deal-contract-tab';
import { statusAfterCreatorSign } from '@/lib/creator-contract-store';

interface CreatorDealContractTabProps {
  contractId: string;
  brandName: string;
  campaignName: string;
  /** Deal.dealValue — can be null (deal not yet priced). Only a fallback; see contractAmount. */
  amount: number | null;
  /**
   * C16: the real signed contract's server-summed totalAmount (GET
   * /contracts/:id, ContractApiRecord.totalAmount) — the authoritative figure
   * once a contract exists, since deal.dealValue can be null/stale even for a
   * fully-signed contract. Undefined/null (no contract fetched yet, or mock
   * mode) falls back to `amount`.
   */
  contractAmount?: number | null;
  status: DealContractStatus;
  onStatusChange: (status: DealContractStatus) => void;
}

export function CreatorDealContractTab({
  contractId,
  brandName,
  campaignName,
  amount,
  contractAmount,
  status,
  onStatusChange,
}: CreatorDealContractTabProps) {
  const [isSigning, setIsSigning] = React.useState(false);
  const [signerName, setSignerName] = React.useState('');
  const [isFetchingPdf, setIsFetchingPdf] = React.useState(false);
  const { toast } = useToast();
  const canSign = status === 'brand_signed';
  // FE-3 honest states, derived from the real brandSignedAt/creatorSignedAt
  // timestamps (via mapApiContractToDealStatus): 'generated' = neither party
  // has signed yet (DRAFT) — the creator's turn hasn't come, so this reads
  // as "awaiting brand" too. 'creator_signed' means BOTH parties have
  // already signed (escrow just isn't funded yet) — that's "fully signed",
  // not "awaiting brand".
  const awaitingBrandSignature = status === 'generated';
  const fullySigned = status === 'creator_signed' || status === 'active';
  // C16: prefer the real contract total; fall back to the deal's dealValue,
  // and to an honest "—" only when neither is available (rather than the
  // previous silent "₹0" from a null amount).
  const effectiveAmount = contractAmount ?? amount ?? null;
  // 15% platform fee (feeBps 1500) — creator nets 85% of gross, consistent with
  // api.wallet.platformFee, the counter-proposal form, and the actual payout.
  const netEarnings = effectiveAmount != null ? Math.round(effectiveAmount * 0.85) : null;
  const liveApi = isApiLive();

  // FE-6: live mode uses the real presigned R2 URL (GET /contracts/:id/pdf-download-url,
  // ContractController.java:114) instead of the client-side HTML print. The
  // endpoint legitimately 404s (CONTRACT_PDF_NOT_READY) until both parties
  // have signed and the PDF has been generated — surface that honestly.
  const handleDownload = async () => {
    // Shared by the mock-mode branch below AND the live-mode fallback (F-CONTRACT-DL):
    // if the presigned URL never becomes available (e.g. R2 unconfigured, leaving
    // pdfR2Key null forever), this is what we fall back to generating locally.
    const contractData = {
      contractId,
      brandName,
      creatorName: 'You',
      campaignName,
      amount: effectiveAmount ?? 0,
      // F-CONTRACT-VIEW: this component doesn't receive the real deliverable
      // list as a prop, so an honest empty array beats a fabricated one.
      deliverables: [],
      deadline: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
      usageRights: '6 months',
      exclusivity: 'Per brief',
      revisionCap: 2,
      customClauses: [],
      createdAt: new Date(),
    };

    if (liveApi) {
      setIsFetchingPdf(true);
      try {
        // `downloadUrl`, not `url` — see api.ts. Throwing on an empty value is what makes the
        // F-CONTRACT-DL fallback below actually reachable; window.open(undefined) never threw.
        const { downloadUrl } = await api.contracts.pdfDownloadUrl('creator', contractId);
        if (!downloadUrl) throw new Error('No download URL returned');
        window.open(downloadUrl, '_blank', 'noopener,noreferrer');
      } catch {
        // F-CONTRACT-DL: don't strand the user if the server-side PDF never
        // becomes available — fall back to the client-side printable copy
        // instead of a dead-end "not ready" toast.
        downloadContractPDF(contractData, `${contractId}.pdf`);
        toast({
          title: 'Opened a local copy',
          description: 'Opened a local copy to review/print.',
        });
      } finally {
        setIsFetchingPdf(false);
      }
      return;
    }

    downloadContractPDF(contractData, `${contractId}.pdf`);
    toast({ title: 'PDF downloaded' });
  };

  const handleSign = async () => {
    const trimmedName = signerName.trim();
    if (!trimmedName) return;
    setIsSigning(true);
    try {
      const result = await signContract(contractId, 'creator', trimmedName);
      if (result.success) {
        onStatusChange(statusAfterCreatorSign());
        // F-0226: signing reaches CONTRACTED only; IN_PROGRESS comes from CollaborationLifecycleService.onEscrowFunded
        // when the brand funds a milestone — a separate action. Submit control is gated on in_progress.
        toast({
          title: 'Contract signed',
          description: 'Waiting for the brand to fund escrow. You’ll be notified when you can start work.',
        });
        setSignerName('');
      }
    } catch (err) {
      toast({
        title: 'Signing failed',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsSigning(false);
    }
  };

  return (
    <ScrollArea className="h-full">
      <div className="max-w-3xl mx-auto p-6 space-y-6">
        {canSign && (
          <Card className="border-warning/30 bg-warning/5">
            <CardContent className="pt-4 flex gap-3">
              <AlertCircle className="h-5 w-5 text-warning shrink-0" />
              <div>
                <p className="font-medium text-sm">Your turn to sign</p>
                <p className="text-sm text-muted-foreground mt-1">
                  {brandName} has signed. Review the PDF, then sign to proceed.
                </p>
              </div>
            </CardContent>
          </Card>
        )}

        {/* FE-3 honest state: contract exists but the brand hasn't signed yet
            (DRAFT with neither signature, or the rare case the creator signed
            first — either way the real gate is `brandSignedAt`, not an
            assumed order). */}
        {awaitingBrandSignature && (
          <Card className="border-border bg-muted/30">
            <CardContent className="pt-4 flex gap-3">
              <Clock className="h-5 w-5 text-muted-foreground shrink-0" />
              <div>
                <p className="font-medium text-sm">Awaiting brand signature</p>
                <p className="text-sm text-muted-foreground mt-1">
                  {brandName} hasn&apos;t signed this contract yet. You&apos;ll be able to sign once they do.
                </p>
              </div>
            </CardContent>
          </Card>
        )}

        <div className="space-y-3 text-sm">
          <div className="flex justify-between">
            <span className="text-muted-foreground">Contract ID</span>
            <span className="font-mono">{contractId}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Brand</span>
            <span className="font-medium">{brandName}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Campaign</span>
            <span className="font-medium">{campaignName}</span>
          </div>
          {/* netEarnings is null-safe via formatINR (shared helper, returns "—"). */}
          <div className="flex justify-between border-t pt-2">
            <span className="font-medium">You receive (est.)</span>
            <span className="font-bold text-success">{formatINR(netEarnings)}</span>
          </div>
        </div>

        {canSign && (
          <div className="space-y-2">
            <label htmlFor="creator-signer-name" className="text-xs font-medium text-muted-foreground">
              Type your full legal name to sign
            </label>
            <Input
              id="creator-signer-name"
              placeholder="Your legal name"
              value={signerName}
              onChange={(e) => setSignerName(e.target.value)}
              disabled={isSigning}
            />
          </div>
        )}

        <div className="flex flex-col sm:flex-row gap-2">
          <Button
            variant="outline"
            className="flex-1 gap-2"
            onClick={handleDownload}
            disabled={isFetchingPdf}
          >
            {isFetchingPdf ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Download className="h-4 w-4" />
            )}
            Download PDF
          </Button>
          {canSign && (
            <Button
              className="flex-1 gap-2"
              onClick={handleSign}
              disabled={isSigning || !signerName.trim()}
            >
              {isSigning ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Signing...
                </>
              ) : (
                <>
                  <PenTool className="h-4 w-4" />
                  Sign contract
                </>
              )}
            </Button>
          )}
          {fullySigned && (
            <div className="flex items-center gap-2 text-sm text-success px-3">
              <CheckCircle2 className="h-4 w-4" />
              Fully signed
            </div>
          )}
        </div>
      </div>
    </ScrollArea>
  );
}
