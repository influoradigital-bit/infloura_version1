'use client';

import * as React from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Input } from '@/components/ui/input';
import { FileText, Download, PenTool, CheckCircle2, Lock, Loader2 } from 'lucide-react';
import { TimelineEvent } from '@/lib/types';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
import { ApiError } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';

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

  const statusSteps = [
    { key: 'generated', label: 'Generated', done: true },
    { key: 'brand_signed', label: 'Brand Signed', done: ['brand_signed', 'creator_signed', 'active'].includes(status) },
    { key: 'creator_signed', label: 'Creator Signed', done: ['creator_signed', 'active'].includes(status) },
    { key: 'active', label: 'Active', done: status === 'active' },
  ];

  const handleDownloadPDF = () => {
    const contractData = {
      contractId: meta?.contractId || 'CONT-001',
      brandName: 'Influora Brand',
      creatorName: 'Priya Sharma',
      campaignName: meta?.campaignName || 'Summer Fashion',
      amount: meta?.amount || 50000,
      deliverables: [
        { title: 'Instagram Reel', description: 'High-quality reel', quantity: 2 },
        { title: 'Instagram Story', description: 'Story series', quantity: 1 },
      ],
      deadline: meta?.deadline || '2024-02-15',
      usageRights: '6 months on social media platforms',
      exclusivity: 'No exclusivity agreement',
      revisionCap: 2,
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
                <p className="font-mono text-sm">{meta?.contractId || 'CONT-001'}</p>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-muted-foreground">Amount</p>
                  <p className="text-lg font-bold">₹{(meta?.amount || 50000).toLocaleString('en-IN')}</p>
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
                    <li>Creator shall deliver {meta?.deliverables || 3} pieces of content as agreed</li>
                    <li>Content must be delivered by {meta?.deadline || '2024-02-15'}</li>
                    <li>Brand retains usage rights for 6 months from delivery</li>
                    <li>Payment will be released upon approval of final deliverables</li>
                    <li>Either party may request revisions up to 2 times</li>
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
              <h3 className="font-semibold text-sm">Escrow Status</h3>
            </div>
            <Card className="border-yellow-200/50 bg-yellow-50/30">
              <CardContent className="pt-4">
                <p className="text-sm text-muted-foreground">₹{(meta?.amount || 50000).toLocaleString('en-IN')} is locked in escrow</p>
                <p className="text-xs text-muted-foreground mt-2">
                  Funds will be released when all deliverables are approved
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
                <p className="text-xs text-destructive">
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
