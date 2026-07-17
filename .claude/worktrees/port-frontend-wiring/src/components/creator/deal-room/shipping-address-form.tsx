'use client';

import * as React from 'react';
import { MapPin, Phone, User } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Alert, AlertDescription } from '@/components/ui/alert';

export interface ShippingAddressData {
  fullName: string;
  phone: string;
  addressLine1: string;
  addressLine2?: string;
  city: string;
  state: string;
  pincode: string;
  landmark?: string;
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  defaultValues?: Partial<ShippingAddressData>;
  onSubmit: (data: ShippingAddressData) => Promise<void> | void;
  isSubmitting?: boolean;
}

export function ShippingAddressForm({ open, onOpenChange, defaultValues, onSubmit, isSubmitting }: Props) {
  const [form, setForm] = React.useState<ShippingAddressData>({
    fullName: defaultValues?.fullName ?? '',
    phone: defaultValues?.phone ?? '',
    addressLine1: defaultValues?.addressLine1 ?? '',
    addressLine2: defaultValues?.addressLine2 ?? '',
    city: defaultValues?.city ?? '',
    state: defaultValues?.state ?? '',
    pincode: defaultValues?.pincode ?? '',
    landmark: defaultValues?.landmark ?? '',
  });

  const update = (k: keyof ShippingAddressData) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setForm((p) => ({ ...p, [k]: e.target.value }));

  const isValid =
    form.fullName.length > 1 &&
    /^\d{10}$/.test(form.phone) &&
    form.addressLine1.length > 4 &&
    form.city.length > 1 &&
    form.state.length > 1 &&
    /^\d{6}$/.test(form.pincode);

  const handleSubmit = async () => {
    if (!isValid) return;
    await onSubmit(form);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <MapPin className="h-5 w-5" />
            Shipping Address
          </DialogTitle>
          <DialogDescription>
            Provide the address where the brand should ship the product or sample.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <Alert>
            <AlertDescription className="text-xs">
              Your address is shared only with this brand for this deal. It is not displayed publicly.
            </AlertDescription>
          </Alert>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="fullName" className="flex items-center gap-1.5">
                <User className="h-3.5 w-3.5" /> Full Name
              </Label>
              <Input id="fullName" value={form.fullName} onChange={update('fullName')} placeholder="Priya Sharma" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="phone" className="flex items-center gap-1.5">
                <Phone className="h-3.5 w-3.5" /> Phone
              </Label>
              <Input id="phone" value={form.phone} onChange={update('phone')} placeholder="9876543210" maxLength={10} />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="addressLine1">Address Line 1</Label>
            <Input id="addressLine1" value={form.addressLine1} onChange={update('addressLine1')} placeholder="House/Flat no., Building, Street" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="addressLine2">Address Line 2 (optional)</Label>
            <Input id="addressLine2" value={form.addressLine2} onChange={update('addressLine2')} placeholder="Area, Sector" />
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-2">
              <Label htmlFor="city">City</Label>
              <Input id="city" value={form.city} onChange={update('city')} placeholder="Mumbai" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="state">State</Label>
              <Input id="state" value={form.state} onChange={update('state')} placeholder="Maharashtra" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="pincode">Pincode</Label>
              <Input id="pincode" value={form.pincode} onChange={update('pincode')} placeholder="400001" maxLength={6} />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="landmark">Landmark (optional)</Label>
            <Textarea id="landmark" value={form.landmark} onChange={update('landmark')} rows={2} placeholder="Near metro station, opposite ABC mall..." />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isSubmitting}>Cancel</Button>
          <Button onClick={handleSubmit} disabled={!isValid || isSubmitting}>
            {isSubmitting ? 'Saving...' : 'Confirm Address'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
