'use client';

import * as React from 'react';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FileText, IndianRupee, Star } from 'lucide-react';
import { formatINR } from '@/lib/utils';

export interface CreatorBid {
  id: string;
  creatorName: string;
  bidAmount: number;
  pitch: string;
  previousWork?: number;
  rating?: number;
  deliveryTimeframe?: string;
}

export function CreatorBidCard({
  bid,
  onAccept,
  onCounter,
}: {
  bid: CreatorBid;
  onAccept?: () => void;
  onCounter?: () => void;
}) {
  return (
    <Card className="bg-gradient-to-br from-amber-50 to-orange-50 border-stage-negotiating-border">
      <CardContent className="pt-6">
        <div className="flex items-start justify-between gap-4 mb-4">
          <div className="flex gap-3 flex-1">
            <div className="rounded-full p-2 bg-amber-100">
              <FileText className="h-5 w-5 text-stage-negotiating-fg" />
            </div>
            <div className="flex-1">
              <p className="font-semibold text-sm">Bid from {bid.creatorName}</p>
              {bid.rating && (
                <div className="flex items-center gap-1 mt-1">
                  {[...Array(5)].map((_, i) => (
                    <Star
                      key={i}
                      className={`h-3 w-3 ${
                        i < (bid.rating ?? 0) ? 'fill-yellow-400 text-yellow-400' : 'text-gray-300'
                      }`}
                    />
                  ))}
                  <span className="text-xs text-muted-foreground ml-1">({bid.rating}.0)</span>
                </div>
              )}
            </div>
          </div>
          <Badge variant="outline" className="bg-white">
            Pending
          </Badge>
        </div>

        {/* Bid Amount */}
        <div className="flex items-center gap-2 text-lg font-bold mb-3">
          <IndianRupee className="h-5 w-5 text-stage-approved-fg" />
          <span>{formatINR(bid.bidAmount)}</span>
        </div>

        {/* Pitch */}
        <p className="text-sm text-foreground/80 mb-3 line-clamp-2">{bid.pitch}</p>

        {/* Stats */}
        <div className="flex gap-4 text-xs text-muted-foreground mb-4">
          {bid.previousWork && (
            <div>
              <p className="font-semibold">{bid.previousWork}+</p>
              <p>Previous Projects</p>
            </div>
          )}
          {bid.deliveryTimeframe && (
            <div>
              <p className="font-semibold">{bid.deliveryTimeframe}</p>
              <p>Delivery Time</p>
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="flex gap-2">
          {onAccept && (
            <Button size="sm" className="flex-1" onClick={onAccept}>
              Accept Bid
            </Button>
          )}
          {onCounter && (
            <Button size="sm" variant="outline" className="flex-1" onClick={onCounter}>
              Counter
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
