import * as React from 'react';
import { CheckCircle2, Download, FileText, Loader2, Lock, PenTool } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Input } from '@/components/ui/input';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
import { api, ApiError, isApiLive } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { formatINR } from '@/lib/utils';

export type DealContractStatus = 'generated' | 'brand_signed' | 'creator_signed' | 'active';

interface DealContractTabProps {
  dealId: string;
  creatorName: string;
  campaignName: string;
  dealValue: number;
  contractId: string;
  status: DealContractStatus;
  onStatusChange: (status: DealContractStatus) => void;
}

const statusSteps = [
  { key: 'generated', label: 'Generated' },
  { key: 'brand_signed', label: 'Brand signed' },
  { key: 'creator_signed', label: 'Creator signed' },
  { key: 'active', label: 'Active' },
] as const;

export function DealContractTab({
  creatorName,
  campaignName,
  dealValue,
  contractId,
  status,
  onStatusChange,
}: DealContractTabProps) {
  const [isSigning, setIsSigning] = React.useState(false);
  const [signerName, setSignerName] = React.useState('');
  const [isFetchingPdf, setIsFetchingPdf] = React.useState(false);
  const { toast } = useToast();
  const liveApi = isApiLive();

  const stepDone = (key: string) => {
    const order = ['generated', 'brand_signed', 'creator_signed', 'active'];
    return order.indexOf(status) >= order.indexOf(key);
  };

  // FE-6: live mode fetches the real presigned R2 URL (GET
  // /contracts/:id/pdf-download-url) instead of the client-side HTML print.
  // 404 CONTRACT_PDF_NOT_READY is legitimate until both parties have signed.
  const handleDownloadPDF = async () => {
    // Shared by the mock-mode branch below AND the live-mode fallback (F-CONTRACT-DL):
    // if the presigned URL never becomes available (e.g. R2 unconfigured, leaving
    // pdfR2Key null forever), this is what we fall back to generating locally.
    const contractData = {
      contractId,
      brandName: 'Your Brand',
      creatorName,
      campaignName,
      amount: dealValue,
      deliverables: [
        { title: 'Instagram Reel', description: 'Campaign content', quantity: 2 },
        { title: 'Instagram Story', description: 'Story series', quantity: 3 },
      ],
      deadline: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
      usageRights: '6 months on social media platforms',
      exclusivity: 'As per campaign brief',
      revisionCap: 2,
      customClauses: [],
      createdAt: new Date(),
    };

    if (liveApi) {
      setIsFetchingPdf(true);
      try {
        // `downloadUrl`, not `url` — see api.ts. Throwing on an empty value is what makes the
        // F-CONTRACT-DL fallback below actually reachable; window.open(undefined) never threw.
        const { downloadUrl } = await api.contracts.pdfDownloadUrl('brand', contractId);
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
    toast({ title: 'PDF downloaded', description: 'Review the contract before signing.' });
  };

  const handleSign = async () => {
    const trimmedName = signerName.trim();
    if (!trimmedName) return;
    setIsSigning(true);
    try {
      const result = await signContract(contractId, 'brand', trimmedName);
      if (result.success) {
        onStatusChange('brand_signed');
        toast({
          title: 'Contract signed',
          description: `Sent to ${creatorName} for signature.`,
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

  const waitingOnCreator = status === 'brand_signed';
  const canBrandSign = status === 'generated';
  const isActive = status === 'active' || status === 'creator_signed';

  return (
    <ScrollArea className="h-full">
      <div className="max-w-3xl mx-auto p-6 space-y-6">
        {waitingOnCreator && (
          <Card className="border-primary/30 bg-primary/5">
            <CardContent className="pt-4 flex items-start gap-3">
              <FileText className="h-5 w-5 text-primary shrink-0 mt-0.5" />
              <div>
                <p className="font-medium text-sm">Sent to creator for signature</p>
                <p className="text-sm text-muted-foreground mt-1">
                  {creatorName} will sign in their Deal Room. You&apos;ll be notified when it&apos;s
                  complete.
                </p>
              </div>
            </CardContent>
          </Card>
        )}

        <div>
          <h3 className="font-semibold text-sm mb-4">Signature progress</h3>
          <div className="flex gap-2">
            {statusSteps.map((step, idx) => (
              <React.Fragment key={step.key}>
                <div className="flex flex-col items-center flex-1 min-w-0">
                  <div
                    className={`w-10 h-10 rounded-full flex items-center justify-center mb-2 ${
                      stepDone(step.key)
                        ? 'bg-primary text-primary-foreground'
                        : 'bg-muted text-muted-foreground'
                    }`}
                  >
                    {stepDone(step.key) ? (
                      <CheckCircle2 className="h-5 w-5" />
                    ) : (
                      <span className="text-sm font-semibold">{idx + 1}</span>
                    )}
                  </div>
                  <p className="text-[10px] sm:text-xs text-center text-muted-foreground">
                    {step.label}
                  </p>
                </div>
                {idx < statusSteps.length - 1 && (
                  <div
                    className={`flex-1 h-0.5 mt-5 min-w-[8px] ${
                      stepDone(step.key) ? 'bg-primary' : 'bg-muted'
                    }`}
                  />
                )}
              </React.Fragment>
            ))}
          </div>
        </div>

        <Separator />

        <Card className="border-primary/20">
          <CardHeader className="pb-3">
            <CardTitle className="text-base flex items-center justify-between gap-2">
              <span>Contract summary</span>
              <Badge variant="outline">{contractId}</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-xs text-muted-foreground">Campaign</p>
                <p className="font-medium">{campaignName}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Creator</p>
                <p className="font-medium">{creatorName}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Value</p>
                <p className="font-semibold text-success">{formatINR(dealValue)}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Status</p>
                <p className="font-medium capitalize">{status.replace(/_/g, ' ')}</p>
              </div>
            </div>

            <div>
              <p className="text-xs text-muted-foreground mb-2">Terms (read-only)</p>
              <div className="bg-muted/50 p-3 rounded-md text-xs space-y-2 max-h-48 overflow-y-auto">
                <ol className="list-decimal list-inside space-y-1 text-muted-foreground">
                  <li>Creator delivers agreed content by campaign deadline.</li>
                  <li>Brand retains usage rights for 6 months from approval.</li>
                  <li>Up to 2 revision rounds per deliverable.</li>
                  <li>Payment released from escrow after deliverable approval.</li>
                  <li>Disputes resolved via Influora platform arbitration.</li>
                </ol>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="border-warning/30 bg-warning/5">
          <CardContent className="pt-4 flex gap-2">
            <Lock className="h-4 w-4 text-warning shrink-0 mt-0.5" />
            <div>
              <p className="text-sm font-medium">Escrow</p>
              <p className="text-xs text-muted-foreground mt-1">
                {isActive
                  ? `${formatINR(dealValue)} is locked and will release on approved deliverables.`
                  : `${formatINR(dealValue)} will be locked when both parties have signed.`}
              </p>
            </div>
          </CardContent>
        </Card>

        {canBrandSign && (
          <div className="space-y-2">
            <label htmlFor="brand-signer-name" className="text-xs font-medium text-muted-foreground">
              Type your full legal name to sign
            </label>
            <Input
              id="brand-signer-name"
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
            onClick={handleDownloadPDF}
            disabled={isFetchingPdf}
          >
            {isFetchingPdf ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Download className="h-4 w-4" />
            )}
            Download PDF
          </Button>
          {canBrandSign && (
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
                  Sign & send to creator
                </>
              )}
            </Button>
          )}
        </div>
      </div>
    </ScrollArea>
  );
}
