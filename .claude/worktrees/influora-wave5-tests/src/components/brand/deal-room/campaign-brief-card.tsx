'use client';

import * as React from 'react';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FileText, IndianRupee, Calendar, Users } from 'lucide-react';
import { formatINR } from '@/lib/utils';

export interface CampaignBrief {
  id: string;
  name: string;
  description: string;
  deliverables: Array<{
    type: string;
    quantity: number;
  }>;
  budgetMin: number;
  budgetMax: number;
  deadline: string;
  usageRights: string;
  exclusivity: string;
}

export function CampaignBriefCard({ brief }: { brief: CampaignBrief }) {
  return (
    <Card className="bg-gradient-to-br from-blue-50 to-cyan-50 border-stage-outreach-border">
      <CardContent className="pt-6">
        <div className="flex items-start justify-between gap-4 mb-4">
          <div className="flex gap-3 flex-1">
            <div className="rounded-full p-2 bg-stage-outreach">
              <FileText className="h-5 w-5 text-stage-outreach-fg" />
            </div>
            <div className="flex-1">
              <p className="font-semibold text-sm">Campaign Brief</p>
              <p className="text-xs text-muted-foreground mt-1">{brief.name}</p>
            </div>
          </div>
          <Badge variant="outline" className="bg-white">
            Open
          </Badge>
        </div>

        {/* Campaign Details */}
        <div className="space-y-3">
          <p className="text-sm text-foreground/80 line-clamp-2">{brief.description}</p>

          {/* Budget Range */}
          <div className="flex items-center gap-2 text-sm">
            <IndianRupee className="h-4 w-4 text-stage-approved-fg" />
            <span className="font-medium">
              {formatINR(brief.budgetMin)} - {formatINR(brief.budgetMax)}
            </span>
          </div>

          {/* Deliverables */}
          <div className="text-xs text-muted-foreground">
            <p className="font-semibold mb-1">Deliverables:</p>
            {brief.deliverables.map((d, i) => (
              <p key={i}>
                • {d.type} (Qty: {d.quantity})
              </p>
            ))}
          </div>

          {/* Deadline & Rights */}
          <div className="flex gap-4 text-xs text-muted-foreground">
            <div className="flex items-center gap-1">
              <Calendar className="h-3 w-3" />
              <span>{new Date(brief.deadline).toLocaleDateString()}</span>
            </div>
            <div className="flex items-center gap-1">
              <Users className="h-3 w-3" />
              <span>{brief.exclusivity}</span>
            </div>
          </div>
        </div>

        {/* Action */}
        <Button size="sm" className="w-full mt-4" variant="default">
          Submit Bid
        </Button>
      </CardContent>
    </Card>
  );
}
