'use client';

import * as React from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Input } from '@/components/ui/input';
import { FileText, Download, PenTool, CheckCircle2, Lock, Loader2, AlertCircle } from 'lucide-react';
import { TimelineEvent } from '@/lib/types';
import type { DealContractStatus } from '@/components/brand/deal-room/deal-contract-tab';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
import { ApiError } from '@/lib/api';
import { statusAfterCreatorSign } from '@/lib/creator-contract-store';
import { useToast } from '@/hooks/use-toast';

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

  const statusSteps = [
    { key: 'generated', label: 'Generated', done: true },
    { key: 'brand_signed', label: 'Brand Signed', done: ['brand_signed', 'creator_signed', 'active'].includes(status) },
    { key: 'creator_signed', label: 'You Signed', done: ['creator_signed', 'active'].includes(status) },
    { key: 'active', label: 'Active', done: status === 'active' },
  ];

  const creatorSigningStatus = () => {
    if (status === 'generated') return 'Awaiting Brand Signature';
    if (status === 'brand_signed') return 'Brand Signed - Your Turn to Sign';
    if (status === 'creator_signed') return 'Both Signed - Active';
    if (status === 'active') return 'Contract Active & Funded';
    return 'Unknown Status';
  };

  const shouldShowSignButton = status === 'brand_signed';

  const handleDownloadPDF = () => {
    const contractData = {
      contractId: meta?.contractId || 'CONT-001',
      brandName: meta?.brandName || 'Influora Brand',
      creatorName: 'You (Creator)',
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
        toast({
          title: 'Contract signed',
          description: 'Escrow is funded. You can start on deliverables.',
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
                  The brand has signed the contract. Please review carefully and sign below to proceed.
                </p>
              )}
              {status === 'active' && (
                <p className="text-sm text-blue-700 mt-1">
                  Both parties have signed. Escrow is funded and you can start working on deliverables.
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
            <div className="space-y-3">
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Contract ID</span>
                <span className="text-sm font-medium">{meta?.contractId || 'CTR-2024-001'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Brand</span>
                <span className="text-sm font-medium">{meta?.brandName || 'StyleCo Fashion'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-sm text-gray-600">Campaign</span>
                <span className="text-sm font-medium">{meta?.campaignName || 'Summer Fashion 2024'}</span>
              </div>
              <div className="flex justify-between pt-2 border-t">
                <span className="text-sm font-medium">Contract Value</span>
                <span className="text-sm font-bold text-stage-approved-fg">₹{(meta?.amount || 50000).toLocaleString('en-IN')}</span>
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
                <span className="font-medium">₹{(meta?.amount || 50000).toLocaleString('en-IN')}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-700">Platform Fee (15%)</span>
                <span className="font-medium text-stage-disputed-fg">-₹{Math.round((meta?.amount || 50000) * 0.15).toLocaleString('en-IN')}</span>
              </div>
              <Separator className="my-2" />
              <div className="flex justify-between">
                <span className="font-semibold text-gray-900">You Receive</span>
                <span className="font-bold text-lg text-stage-approved-fg">
                  ₹{Math.round((meta?.amount || 50000) * 0.85).toLocaleString('en-IN')}
                </span>
              </div>
              <p className="text-xs text-gray-500 mt-2">
                Amount will be transferred to your wallet upon final approval and deliverable completion.
              </p>
            </div>
          </div>

          <Separator />

          {/* Key Terms */}
          <div>
            <h3 className="font-semibold text-sm mb-4">Key Terms</h3>
            <div className="space-y-3 text-sm">
              <div>
                <p className="text-gray-600">Payment Terms</p>
                <p className="font-medium">50% upfront (escrow), 50% on completion</p>
              </div>
              <div>
                <p className="text-gray-600">Deliverables</p>
                <p className="font-medium">2 Instagram Reels, 1 Instagram Story</p>
              </div>
              <div>
                <p className="text-gray-600">Deadline</p>
                <p className="font-medium">{meta?.deadline || '2024-02-15'}</p>
              </div>
              <div>
                <p className="text-gray-600">Usage Rights</p>
                <p className="font-medium">6 months on social media platforms</p>
              </div>
              <div>
                <p className="text-gray-600">Revision Cap</p>
                <p className="font-medium">2 revisions per deliverable</p>
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
