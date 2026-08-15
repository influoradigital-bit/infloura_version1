'use client';

import * as React from 'react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { FileText, CheckCircle2, AlertCircle, ChevronRight } from 'lucide-react';
import { TimelineEvent } from '@/lib/types';
import { formatINR } from '@/lib/utils';
import { deliverableCountLabel } from '@/lib/deliverable-slots';

interface CreatorContractCardProps {
  event: TimelineEvent;
  onViewClick: () => void;
  onSignClick?: () => void;
}

export function CreatorContractCard({
  event,
  onViewClick,
  onSignClick,
}: CreatorContractCardProps) {
  const meta = event.metadata;
  const status = meta?.contractStatus || 'generated';

  const getStatusBadgeColor = () => {
    switch (status) {
      case 'generated':
        return 'bg-stage-outreach text-blue-800';
      case 'brand_signed':
        return 'bg-amber-100 text-amber-800';
      case 'creator_signed':
        return 'bg-purple-100 text-purple-800';
      case 'active':
        return 'bg-stage-approved text-green-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  const getStatusLabel = () => {
    switch (status) {
      case 'generated':
        return 'Awaiting Brand Signature';
      case 'brand_signed':
        return 'Your Turn to Sign';
      case 'creator_signed':
        return 'Both Signed';
      case 'active':
        return 'Active & Funded';
      default:
        return 'Unknown';
    }
  };

  const getStatusIcon = () => {
    switch (status) {
      case 'generated':
        return <AlertCircle className="h-4 w-4" />;
      case 'brand_signed':
        return <AlertCircle className="h-4 w-4" />;
      case 'creator_signed':
      case 'active':
        return <CheckCircle2 className="h-4 w-4" />;
      default:
        return null;
    }
  };

  return (
    <Card className="bg-white border border-gray-200 overflow-hidden">
      <div className="p-4 space-y-4">
        {/* Header with Status */}
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3 flex-1">
            <div className="p-2 bg-gradient-to-br from-emerald-50 to-teal-50 rounded-lg">
              <FileText className="h-5 w-5 text-emerald-600" />
            </div>
            <div className="flex-1 min-w-0">
              <h4 className="font-semibold text-gray-900 truncate">
                Contract {meta?.contractId || 'CTR-2024-001'}
              </h4>
              <p className="text-sm text-gray-500">{meta?.brandName || 'Brand Name'}</p>
            </div>
          </div>
          <Badge className={`whitespace-nowrap ${getStatusBadgeColor()}`}>
            <span className="flex items-center gap-1">
              {getStatusIcon()}
              {getStatusLabel()}
            </span>
          </Badge>
        </div>

        {/* Contract Value */}
        <div className="flex items-center justify-between p-3 bg-gray-50 rounded-lg">
          <span className="text-sm text-gray-600">Contract Value</span>
          <span className="font-bold text-lg text-emerald-600">
            {formatINR(meta?.amount)}
          </span>
        </div>

        {/* Signature Progress */}
        <div className="space-y-2">
          <p className="text-xs font-medium text-gray-600 uppercase">Signature Progress</p>
          <div className="flex gap-2">
            {/* Generated */}
            <div className="flex-1 h-2 bg-emerald-200 rounded-full" />
            {/* Brand Signed */}
            <div className={`flex-1 h-2 rounded-full ${
              ['brand_signed', 'creator_signed', 'active'].includes(status) 
                ? 'bg-emerald-200' 
                : 'bg-gray-200'
            }`} />
            {/* Creator Signed */}
            <div className={`flex-1 h-2 rounded-full ${
              ['creator_signed', 'active'].includes(status) 
                ? 'bg-emerald-200' 
                : 'bg-gray-200'
            }`} />
            {/* Active */}
            <div className={`flex-1 h-2 rounded-full ${
              status === 'active' 
                ? 'bg-emerald-200' 
                : 'bg-gray-200'
            }`} />
          </div>
          <div className="flex justify-between text-xs text-gray-500 mt-1">
            <span>Generated</span>
            <span>Brand</span>
            <span>You</span>
            <span>Active</span>
          </div>
        </div>

        {/* Key Terms */}
        <div className="space-y-2">
          <p className="text-xs font-medium text-gray-600 uppercase">Key Terms</p>
          <div className="grid grid-cols-2 gap-3">
            <div className="text-sm">
              <p className="text-gray-500 text-xs">Deliverables</p>
              {/* Same React #31 trap as the brand-side proposal card — `meta.deliverables` is a
                  DeliverableSlot[]. The old `?? 2` also invented a term (TECH-STACK.md rule 7). */}
              <p className="font-medium text-gray-900">
                {deliverableCountLabel(meta, 'item') ?? 'Not specified'}
              </p>
            </div>
            <div className="text-sm">
              <p className="text-gray-500 text-xs">Deadline</p>
              <p className="font-medium text-gray-900">{meta?.deadline || '2024-02-15'}</p>
            </div>
            <div className="text-sm">
              <p className="text-gray-500 text-xs">Usage Rights</p>
              <p className="font-medium text-gray-900">6 months</p>
            </div>
            <div className="text-sm">
              <p className="text-gray-500 text-xs">Revisions</p>
              <p className="font-medium text-gray-900">Up to 2</p>
            </div>
          </div>
        </div>

        {/* Escrow Status */}
        {status === 'active' && (
          <div className="p-3 bg-stage-approved border border-stage-approved-border rounded-lg flex items-center gap-2">
            <CheckCircle2 className="h-5 w-5 text-stage-approved-fg flex-shrink-0" />
            <div>
              <p className="font-semibold text-sm text-green-900">
                Escrow Funded
              </p>
              <p className="text-xs text-stage-approved-fg">
                {meta?.amount != null && Number.isFinite(meta.amount)
                  ? `${formatINR(meta.amount)} secured in escrow`
                  : 'Escrow amount not yet available'}
              </p>
            </div>
          </div>
        )}

        {/* Status-specific Message */}
        {status === 'brand_signed' && (
          <div className="p-3 bg-amber-50 border border-stage-negotiating-border rounded-lg">
            <p className="text-sm text-amber-900">
              The brand has signed the contract. Please review the terms carefully and sign to proceed.
            </p>
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-2 pt-2">
          <Button 
            variant="outline" 
            className="flex-1"
            onClick={onViewClick}
          >
            <span className="flex items-center gap-2">
              View Details
              <ChevronRight className="h-4 w-4" />
            </span>
          </Button>
          {status === 'brand_signed' && onSignClick && (
            <Button 
              className="flex-1 bg-emerald-600 hover:bg-emerald-700"
              onClick={onSignClick}
            >
              <span className="flex items-center gap-2">
                Sign Now
                <ChevronRight className="h-4 w-4" />
              </span>
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
}
