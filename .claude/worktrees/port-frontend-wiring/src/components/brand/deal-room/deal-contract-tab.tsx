import * as React from 'react';
import { CheckCircle2, Download, FileText, Loader2, Lock, PenTool } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { ScrollArea } from '@/components/ui/scroll-area';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
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
  const { toast } = useToast();

  const stepDone = (key: string) => {
    const order = ['generated', 'brand_signed', 'creator_signed', 'active'];
    return order.indexOf(status) >= order.indexOf(key);
  };

  const handleDownloadPDF = () => {
    downloadContractPDF(
      {
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
      },
      `${contractId}.pdf`,
    );
    toast({ title: 'PDF downloaded', description: 'Review the contract before signing.' });
  };

  const handleSign = async () => {
    setIsSigning(true);
    try {
      const result = await signContract(contractId, 'brand');
      if (result.success) {
        onStatusChange('brand_signed');
        toast({
          title: 'Contract signed',
          description: `Sent to ${creatorName} for signature.`,
        });
      }
    } catch {
      toast({
        title: 'Signing failed',
        description: 'Please try again.',
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

        <div className="flex flex-col sm:flex-row gap-2">
          <Button variant="outline" className="flex-1 gap-2" onClick={handleDownloadPDF}>
            <Download className="h-4 w-4" />
            Download PDF
          </Button>
          {canBrandSign && (
            <Button className="flex-1 gap-2" onClick={handleSign} disabled={isSigning}>
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
