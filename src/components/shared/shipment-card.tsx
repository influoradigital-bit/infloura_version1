'use client';

import * as React from 'react';
import { Truck, Package, ExternalLink, CheckCircle2, Clock, AlertCircle, MapPin } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { isSafeHttpUrl } from '@/lib/utils';

export type ShipmentStatus = 'created' | 'in_transit' | 'out_for_delivery' | 'delivered' | 'received' | 'damaged';

interface ShipmentItem { name: string; quantity: number }

interface Props {
  status: ShipmentStatus;
  items: ShipmentItem[];
  courier: string;
  trackingNumber: string;
  trackingUrl?: string;
  /** ISO date `YYYY-MM-DD`; null/undefined when the brand hasn't provided an ETA yet. */
  estimatedDelivery?: string | null;
  notes?: string;
  perspective: 'brand' | 'creator';
  onMarkReceived?: () => void;
  onUpdateTracking?: () => void;
}

const STATUS_CONFIG: Record<ShipmentStatus, { label: string; color: string; icon: React.ComponentType<{ className?: string }> }> = {
  created:           { label: 'Shipment Created',  color: 'bg-stage-outreach text-blue-800 border-stage-outreach-border',     icon: Package },
  in_transit:        { label: 'In Transit',         color: 'bg-amber-100 text-amber-800 border-stage-negotiating-border', icon: Truck },
  out_for_delivery:  { label: 'Out for Delivery',   color: 'bg-amber-100 text-amber-800 border-stage-negotiating-border', icon: Truck },
  delivered:         { label: 'Delivered',          color: 'bg-stage-approved text-green-800 border-green-200', icon: CheckCircle2 },
  received:          { label: 'Received by Creator',color: 'bg-stage-approved text-green-800 border-green-200', icon: CheckCircle2 },
  damaged:           { label: 'Reported Damaged',   color: 'bg-red-100 text-red-800 border-red-200',       icon: AlertCircle },
};

const STEPS: ShipmentStatus[] = ['created', 'in_transit', 'delivered', 'received'];

export function ShipmentCard({
  status, items, courier, trackingNumber, trackingUrl, estimatedDelivery, notes,
  perspective, onMarkReceived, onUpdateTracking,
}: Props) {
  const cfg = STATUS_CONFIG[status];
  const StatusIcon = cfg.icon;
  const currentStep = Math.max(0, STEPS.indexOf(status));
  const totalUnits = items.reduce((s, it) => s + it.quantity, 0);

  return (
    <div className="flex justify-center">
      <Card className="w-full max-w-md border-2 border-blue-100 bg-blue-50/30">
        <CardContent className="p-4">
          {/* Header */}
          <div className="flex items-start justify-between mb-3">
            <div className="flex items-center gap-2">
              <div className="h-9 w-9 rounded-full bg-stage-outreach flex items-center justify-center">
                <Truck className="h-4 w-4 text-blue-700" />
              </div>
              <div>
                <p className="font-semibold text-sm">Product Shipment</p>
                <p className="text-xs text-muted-foreground">
                  {totalUnits} {totalUnits === 1 ? 'item' : 'items'} via {courier}
                </p>
              </div>
            </div>
            <Badge className={cfg.color}>
              <StatusIcon className="h-3 w-3 mr-1" />
              {cfg.label}
            </Badge>
          </div>

          {/* Progress steps */}
          <div className="mb-4">
            <div className="flex items-center justify-between mb-1.5">
              {STEPS.map((s, i) => (
                <div key={s} className="flex flex-col items-center flex-1">
                  <div className={`h-2 w-2 rounded-full ${i <= currentStep ? 'bg-green-600' : 'bg-muted-foreground/30'}`} />
                </div>
              ))}
            </div>
            <div className="flex items-center justify-between text-[10px] text-muted-foreground">
              <span>Created</span>
              <span>In Transit</span>
              <span>Delivered</span>
              <span>Received</span>
            </div>
          </div>

          {/* Tracking info */}
          <div className="space-y-2 text-sm border-t pt-3 mb-3">
            <div className="flex justify-between">
              <span className="text-muted-foreground">AWB / Tracking</span>
              <span className="font-mono text-xs">{trackingNumber}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted-foreground">Est. Delivery</span>
              <span className="font-medium">
                {estimatedDelivery ? new Date(estimatedDelivery).toLocaleDateString() : '—'}
              </span>
            </div>
          </div>

          {/* Items list */}
          <div className="bg-white rounded-md border p-2.5 mb-3 space-y-1">
            {items.map((it, i) => (
              <div key={i} className="flex items-center justify-between text-xs">
                <span className="flex items-center gap-1.5">
                  <Package className="h-3 w-3 text-muted-foreground" />
                  {it.name}
                </span>
                <span className="text-muted-foreground">Qty {it.quantity}</span>
              </div>
            ))}
          </div>

          {notes && (
            <div className="bg-amber-50 border border-stage-negotiating-border rounded p-2 mb-3">
              <p className="text-xs text-amber-900"><span className="font-medium">Note: </span>{notes}</p>
            </div>
          )}

          {/* Actions */}
          <div className="flex gap-2">
            {isSafeHttpUrl(trackingUrl) && (
              <Button asChild variant="outline" size="sm" className="flex-1 gap-1.5">
                <a href={trackingUrl} target="_blank" rel="noopener noreferrer">
                  <ExternalLink className="h-3.5 w-3.5" /> Track Live
                </a>
              </Button>
            )}
            {perspective === 'creator' && status === 'delivered' && onMarkReceived && (
              <Button size="sm" className="flex-1 gap-1.5" onClick={onMarkReceived}>
                <CheckCircle2 className="h-3.5 w-3.5" /> Mark as Received
              </Button>
            )}
            {perspective === 'brand' && (status === 'created' || status === 'in_transit') && onUpdateTracking && (
              <Button variant="outline" size="sm" className="flex-1 gap-1.5" onClick={onUpdateTracking}>
                Update Status
              </Button>
            )}
          </div>

          {status === 'received' && (
            <div className="mt-3 flex items-center gap-2 p-2 bg-stage-approved rounded">
              <MapPin className="h-3.5 w-3.5 text-stage-approved-fg" />
              <p className="text-xs text-green-800">Shipment confirmed. Creator can now begin production.</p>
            </div>
          )}

          {status === 'created' && perspective === 'creator' && (
            <div className="mt-3 flex items-center gap-2 p-2 bg-stage-outreach rounded">
              <Clock className="h-3.5 w-3.5 text-blue-700" />
              <p className="text-xs text-blue-800">Brand has dispatched your package. Tracking updates will appear here.</p>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
