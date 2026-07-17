import * as React from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Download,
  Loader2,
  PenTool,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
import { useToast } from '@/hooks/use-toast';
import { formatINR } from '@/lib/utils';
import type { DealContractStatus } from '@/components/brand/deal-room/deal-contract-tab';
import { statusAfterCreatorSign } from '@/lib/creator-contract-store';

interface CreatorDealContractTabProps {
  contractId: string;
  brandName: string;
  campaignName: string;
  amount: number;
  status: DealContractStatus;
  onStatusChange: (status: DealContractStatus) => void;
}

export function CreatorDealContractTab({
  contractId,
  brandName,
  campaignName,
  amount,
  status,
  onStatusChange,
}: CreatorDealContractTabProps) {
  const [isSigning, setIsSigning] = React.useState(false);
  const { toast } = useToast();
  const canSign = status === 'brand_signed';
  const netEarnings = Math.round(amount * 0.79);

  const handleDownload = () => {
    downloadContractPDF(
      {
        contractId,
        brandName,
        creatorName: 'You',
        campaignName,
        amount,
        deliverables: [
          { title: 'Instagram Reel', description: 'Campaign content', quantity: 2 },
        ],
        deadline: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
        usageRights: '6 months',
        exclusivity: 'Per brief',
        revisionCap: 2,
        customClauses: [],
        createdAt: new Date(),
      },
      `${contractId}.pdf`,
    );
    toast({ title: 'PDF downloaded' });
  };

  const handleSign = async () => {
    setIsSigning(true);
    try {
      const result = await signContract(contractId, 'creator');
      if (result.success) {
        onStatusChange(statusAfterCreatorSign());
        toast({ title: 'Contract signed', description: 'Escrow is funded. You can start deliverables.' });
      }
    } catch {
      toast({ title: 'Signing failed', variant: 'destructive' });
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
                  {brandName} has signed. Review the PDF, then sign to start deliverables.
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
          <div className="flex justify-between border-t pt-2">
            <span className="font-medium">You receive (est.)</span>
            <span className="font-bold text-success">{formatINR(netEarnings)}</span>
          </div>
        </div>

        <div className="flex flex-col sm:flex-row gap-2">
          <Button variant="outline" className="flex-1 gap-2" onClick={handleDownload}>
            <Download className="h-4 w-4" />
            Download PDF
          </Button>
          {canSign && (
            <Button className="flex-1 gap-2" onClick={handleSign} disabled={isSigning}>
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
          {status === 'active' && (
            <div className="flex items-center gap-2 text-sm text-success px-3">
              <CheckCircle2 className="h-4 w-4" />
              Contract active
            </div>
          )}
        </div>
      </div>
    </ScrollArea>
  );
}
