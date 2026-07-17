'use client';

import * as React from 'react';
import { Truck, Package, Plus, X } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent } from '@/components/ui/card';
import type { ShippingAddressData } from '@/components/creator/deal-room/shipping-address-form';

export interface ShipmentItem {
  name: string;
  quantity: number;
}

export interface ShipmentData {
  items: ShipmentItem[];
  courier: string;
  trackingNumber: string;
  trackingUrl?: string;
  estimatedDelivery: string;
  notes?: string;
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  shippingAddress: ShippingAddressData;
  onSubmit: (data: ShipmentData) => Promise<void> | void;
  isSubmitting?: boolean;
}

const COURIERS = ['Delhivery', 'Blue Dart', 'DTDC', 'Ekart', 'India Post', 'Shiprocket', 'Other'];

export function ShipmentForm({ open, onOpenChange, shippingAddress, onSubmit, isSubmitting }: Props) {
  const [items, setItems] = React.useState<ShipmentItem[]>([{ name: '', quantity: 1 }]);
  const [courier, setCourier] = React.useState('');
  const [trackingNumber, setTrackingNumber] = React.useState('');
  const [trackingUrl, setTrackingUrl] = React.useState('');
  const [estimatedDelivery, setEstimatedDelivery] = React.useState('');
  const [notes, setNotes] = React.useState('');

  const addItem = () => setItems((p) => [...p, { name: '', quantity: 1 }]);
  const removeItem = (i: number) => setItems((p) => p.filter((_, idx) => idx !== i));
  const updateItem = (i: number, key: keyof ShipmentItem, value: string | number) =>
    setItems((p) => p.map((it, idx) => (idx === i ? { ...it, [key]: value } : it)));

  const isValid =
    items.every((it) => it.name.length > 0 && it.quantity > 0) &&
    courier.length > 0 &&
    trackingNumber.length > 3 &&
    estimatedDelivery.length > 0;

  const handleSubmit = async () => {
    if (!isValid) return;
    await onSubmit({ items, courier, trackingNumber, trackingUrl, estimatedDelivery, notes });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Truck className="h-5 w-5" />
            Create Shipment
          </DialogTitle>
          <DialogDescription>
            Add product details and tracking information so the creator can track the package.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          {/* Address summary */}
          <Card className="bg-muted/40">
            <CardContent className="p-3 text-sm">
              <p className="font-medium mb-1">Shipping to</p>
              <p className="text-muted-foreground">
                {shippingAddress.fullName}, {shippingAddress.phone}
              </p>
              <p className="text-muted-foreground">
                {shippingAddress.addressLine1}
                {shippingAddress.addressLine2 ? `, ${shippingAddress.addressLine2}` : ''}
              </p>
              <p className="text-muted-foreground">
                {shippingAddress.city}, {shippingAddress.state} - {shippingAddress.pincode}
              </p>
            </CardContent>
          </Card>

          {/* Items */}
          <div className="space-y-2">
            <Label className="flex items-center gap-1.5">
              <Package className="h-3.5 w-3.5" /> Items being shipped
            </Label>
            {items.map((item, i) => (
              <div key={i} className="flex gap-2">
                <Input
                  className="flex-1"
                  placeholder="Product name (e.g. Summer Dress, Size M)"
                  value={item.name}
                  onChange={(e) => updateItem(i, 'name', e.target.value)}
                />
                <Input
                  className="w-20"
                  type="number"
                  min={1}
                  value={item.quantity}
                  onChange={(e) => updateItem(i, 'quantity', parseInt(e.target.value || '1', 10))}
                />
                {items.length > 1 && (
                  <Button variant="ghost" size="icon" onClick={() => removeItem(i)}>
                    <X className="h-4 w-4" />
                  </Button>
                )}
              </div>
            ))}
            <Button variant="outline" size="sm" onClick={addItem} className="gap-1.5">
              <Plus className="h-3.5 w-3.5" /> Add item
            </Button>
          </div>

          {/* Courier + tracking */}
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="courier">Courier</Label>
              <Select value={courier} onValueChange={setCourier}>
                <SelectTrigger id="courier">
                  <SelectValue placeholder="Select courier" />
                </SelectTrigger>
                <SelectContent>
                  {COURIERS.map((c) => (
                    <SelectItem key={c} value={c}>{c}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="awb">Tracking / AWB Number</Label>
              <Input id="awb" value={trackingNumber} onChange={(e) => setTrackingNumber(e.target.value)} placeholder="AWB123456789" />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="trackingUrl">Tracking URL (optional)</Label>
              <Input id="trackingUrl" value={trackingUrl} onChange={(e) => setTrackingUrl(e.target.value)} placeholder="https://courier.com/track/..." />
            </div>
            <div className="space-y-2">
              <Label htmlFor="eta">Estimated Delivery</Label>
              <Input id="eta" type="date" value={estimatedDelivery} onChange={(e) => setEstimatedDelivery(e.target.value)} />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="notes">Notes for creator (optional)</Label>
            <Textarea id="notes" rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Care instructions, what's inside, etc." />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isSubmitting}>Cancel</Button>
          <Button onClick={handleSubmit} disabled={!isValid || isSubmitting}>
            {isSubmitting ? 'Sending...' : 'Send Shipment Details'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
