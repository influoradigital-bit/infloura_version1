'use client';

import * as React from 'react';
import { ChevronRight, X, Plus, Minus, IndianRupee, DollarSign } from 'lucide-react';
import { cn } from '@/lib/utils';
import { uniqueId } from '@/lib/unique-id';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

export interface ProposalFormData {
  // Step 1: Deliverables
  deliverables: Array<{ id: string; type: string; count: number }>;
  // Step 2: Budget
  budget: number;
  // Step 3: Timeline
  deadline: string;
  // Step 4: Usage Rights
  usageRightsDuration: string;
  usageRightsAddOns: string[];
  // Step 5: Terms
  exclusivity: string;
  revisionCap: number;
  customClauses: string;
}

interface ProposalFormProps {
  creatorName: string;
  onSubmit: (data: ProposalFormData) => void;
  onClose: () => void;
  isSubmitting?: boolean;
}

const deliverableTypes = [
  'Instagram Reel',
  'TikTok Video',
  'Instagram Story Set',
  'Instagram Feed Post',
  'YouTube Video',
  'Blog Post',
  'Product Review',
  'Behind-the-scenes',
  'Testimonial',
  'Other',
];

const usageRightsDurations = [
  { value: '3-months', label: '3 months' },
  { value: '6-months', label: '6 months (Standard)' },
  { value: '12-months', label: '12 months' },
  { value: 'perpetual', label: 'Perpetual' },
];

const addOnOptions = [
  { id: 'whitelisting', label: 'Whitelisting/Paid Ads', price: 30 },
  { id: 'extended', label: 'Extended Duration', price: 20 },
  { id: 'perpetual', label: 'Perpetual Usage', price: 50 },
  { id: 'repurposing', label: 'Repurposing Rights', price: 25 },
  { id: 'exclusive', label: 'Exclusive Content', price: 40 },
];

export function ProposalForm({ creatorName, onSubmit, onClose, isSubmitting = false }: ProposalFormProps) {
  const [step, setStep] = React.useState(1);
  const [formData, setFormData] = React.useState<ProposalFormData>({
    deliverables: [{ id: uniqueId('del'), type: 'Instagram Reel', count: 1 }],
    budget: 50000,
    deadline: '',
    usageRightsDuration: '6-months',
    usageRightsAddOns: [],
    exclusivity: 'none',
    revisionCap: 2,
    customClauses: '',
  });

  const totalSteps = 5;
  const steps = [
    { num: 1, title: 'Deliverables', subtitle: 'What do you want?' },
    { num: 2, title: 'Budget', subtitle: 'How much can you pay?' },
    { num: 3, title: 'Timeline', subtitle: 'When do you need it?' },
    { num: 4, title: 'Usage Rights', subtitle: 'How will you use it?' },
    { num: 5, title: 'Terms', subtitle: 'Final details' },
  ];

  const handleNext = () => {
    if (step < totalSteps) {
      setStep(step + 1);
    }
  };

  const handleBack = () => {
    if (step > 1) {
      setStep(step - 1);
    }
  };

  const handleSubmit = () => {
    onSubmit(formData);
  };

  // Calculate add-on fees
  const addOnFee = formData.usageRightsAddOns.reduce((sum, addonId) => {
    const addon = addOnOptions.find((a) => a.id === addonId);
    return sum + ((addon?.price || 0) * formData.budget) / 100;
  }, 0);

  const platformFee = (formData.budget * 10) / 100;
  const gstOnFee = (platformFee * 18) / 100;
  const totalCost = formData.budget + addOnFee + platformFee + gstOnFee;

  return (
    <div className="fixed inset-0 z-50">
      {/* Overlay */}
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />

      {/* Slide-in Panel */}
      <div className="absolute right-0 top-0 h-full w-full max-w-2xl bg-background border-l border-border flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border p-6">
          <div>
            <h2 className="text-xl font-semibold">Send Proposal to {creatorName}</h2>
            <p className="text-sm text-muted-foreground mt-1">
              Step {step} of {totalSteps}: {steps[step - 1].title}
            </p>
          </div>
          <Button variant="ghost" size="icon" onClick={onClose}>
            <X className="h-5 w-5" />
          </Button>
        </div>

        {/* Progress Steps */}
        <div className="flex gap-2 px-6 pt-4 pb-2 overflow-x-auto">
          {steps.map((s, idx) => (
            <div
              key={idx}
              className={cn(
                'flex items-center gap-2 shrink-0 pb-2',
                idx < step - 1 ? 'text-success' : idx === step - 1 ? 'text-primary' : 'text-muted-foreground'
              )}
            >
              <div
                className={cn(
                  'h-8 w-8 rounded-full flex items-center justify-center text-xs font-semibold',
                  idx < step - 1 ? 'bg-success text-white' : idx === step - 1 ? 'bg-primary text-white' : 'bg-muted text-muted-foreground'
                )}
              >
                {idx < step - 1 ? '✓' : s.num}
              </div>
              <span className="text-xs font-medium whitespace-nowrap">{s.title}</span>
            </div>
          ))}
        </div>

        {/* Content - scrollable */}
        <div className="flex-1 overflow-y-auto px-6 py-6">
          {/* Step 1: Deliverables */}
          {step === 1 && (
            <div className="space-y-6">
              <div>
                <h3 className="font-semibold mb-3">Add Deliverables</h3>
                <p className="text-sm text-muted-foreground mb-4">What content do you want the creator to produce?</p>
              </div>

              <div className="space-y-3">
                {formData.deliverables.map((del, idx) => (
                  <div key={del.id} className="flex items-end gap-2 p-3 rounded-lg border border-border bg-muted/30">
                    <div className="flex-1">
                      <Label className="text-xs mb-1.5 block">Deliverable Type</Label>
                      <Select
                        value={del.type}
                        onValueChange={(val) => {
                          const newDeliverables = [...formData.deliverables];
                          newDeliverables[idx].type = val;
                          setFormData({ ...formData, deliverables: newDeliverables });
                        }}
                      >
                        <SelectTrigger className="h-9 text-sm">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {deliverableTypes.map((type) => (
                            <SelectItem key={type} value={type}>
                              {type}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="w-20">
                      <Label className="text-xs mb-1.5 block">Qty</Label>
                      <Input
                        type="number"
                        min="1"
                        max="10"
                        value={del.count}
                        onChange={(e) => {
                          const newDeliverables = [...formData.deliverables];
                          newDeliverables[idx].count = parseInt(e.target.value) || 1;
                          setFormData({ ...formData, deliverables: newDeliverables });
                        }}
                        className="h-9 text-sm text-center"
                      />
                    </div>
                    {formData.deliverables.length > 1 && (
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          setFormData({
                            ...formData,
                            deliverables: formData.deliverables.filter((_, i) => i !== idx),
                          });
                        }}
                        className="h-9 w-9"
                      >
                        <Minus className="h-4 w-4" />
                      </Button>
                    )}
                  </div>
                ))}
              </div>

              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setFormData({
                    ...formData,
                    deliverables: [...formData.deliverables, { id: uniqueId('del'), type: 'Instagram Reel', count: 1 }],
                  });
                }}
                className="w-full gap-2"
              >
                <Plus className="h-4 w-4" />
                Add Another
              </Button>
            </div>
          )}

          {/* Step 2: Budget */}
          {step === 2 && (
            <div className="space-y-6">
              <div>
                <h3 className="font-semibold mb-2">Proposed Budget</h3>
                <p className="text-sm text-muted-foreground mb-4">What's your budget for this collaboration?</p>
              </div>

              <div className="relative">
                <Label className="text-sm mb-2 block">Amount (INR)</Label>
                <div className="flex items-center gap-2">
                  <IndianRupee className="h-5 w-5 text-muted-foreground" />
                  <Input
                    type="number"
                    value={formData.budget}
                    onChange={(e) => setFormData({ ...formData, budget: parseInt(e.target.value) || 0 })}
                    className="text-lg font-semibold"
                    placeholder="0"
                  />
                </div>
              </div>

              {/* Cost Breakdown Preview */}
              <Card className="border-border/50 bg-muted/20">
                <CardContent className="pt-4 pb-4">
                  <h4 className="text-xs font-semibold text-muted-foreground mb-3 uppercase tracking-wide">Cost Breakdown</h4>
                  <div className="space-y-2 text-sm">
                    <div className="flex justify-between">
                      <span>Creator Payout:</span>
                      <span className="font-semibold">₹{formData.budget.toLocaleString('en-IN')}</span>
                    </div>
                    {addOnFee > 0 && (
                      <div className="flex justify-between text-muted-foreground">
                        <span>Add-ons ({formData.usageRightsAddOns.length}):</span>
                        <span>₹{Math.round(addOnFee).toLocaleString('en-IN')}</span>
                      </div>
                    )}
                    <div className="flex justify-between text-muted-foreground">
                      <span>Platform Fee (10%):</span>
                      <span>₹{Math.round(platformFee).toLocaleString('en-IN')}</span>
                    </div>
                    <div className="flex justify-between text-muted-foreground">
                      <span>GST on Fee (18%):</span>
                      <span>₹{Math.round(gstOnFee).toLocaleString('en-IN')}</span>
                    </div>
                    <div className="border-t border-border/50 pt-2 mt-2 flex justify-between font-semibold">
                      <span>Total You Pay:</span>
                      <span className="text-primary">₹{Math.round(totalCost).toLocaleString('en-IN')}</span>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </div>
          )}

          {/* Step 3: Timeline */}
          {step === 3 && (
            <div className="space-y-6">
              <div>
                <h3 className="font-semibold mb-2">Deadline</h3>
                <p className="text-sm text-muted-foreground mb-4">When do you need the content delivered?</p>
              </div>

              <div>
                <Label className="text-sm mb-2 block">Delivery Date</Label>
                <Input
                  type="date"
                  value={formData.deadline}
                  onChange={(e) => setFormData({ ...formData, deadline: e.target.value })}
                />
              </div>

              <div className="p-3 rounded-lg border border-border bg-muted/30 text-sm">
                <p className="text-muted-foreground">
                  The creator will be notified of the deadline. Extensions can be negotiated before delivery.
                </p>
              </div>
            </div>
          )}

          {/* Step 4: Usage Rights */}
          {step === 4 && (
            <div className="space-y-6">
              <div>
                <h3 className="font-semibold mb-2">Usage Rights</h3>
                <p className="text-sm text-muted-foreground mb-4">How long and where will you use this content?</p>
              </div>

              {/* Duration */}
              <div>
                <Label className="text-sm mb-2 block">Duration</Label>
                <Select value={formData.usageRightsDuration} onValueChange={(val) => setFormData({ ...formData, usageRightsDuration: val })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {usageRightsDurations.map((dur) => (
                      <SelectItem key={dur.value} value={dur.value}>
                        {dur.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              {/* Add-ons */}
              <div>
                <Label className="text-sm mb-3 block">Add Usage Add-ons (optional)</Label>
                <div className="space-y-2">
                  {addOnOptions.map((addon) => (
                    <div key={addon.id} className="flex items-center gap-3 p-2 rounded border border-border hover:bg-muted/50">
                      <Checkbox
                        checked={formData.usageRightsAddOns.includes(addon.id)}
                        onCheckedChange={(checked) => {
                          if (checked) {
                            setFormData({
                              ...formData,
                              usageRightsAddOns: [...formData.usageRightsAddOns, addon.id],
                            });
                          } else {
                            setFormData({
                              ...formData,
                              usageRightsAddOns: formData.usageRightsAddOns.filter((id) => id !== addon.id),
                            });
                          }
                        }}
                      />
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-medium">{addon.label}</p>
                      </div>
                      <Badge variant="outline" className="shrink-0">
                        +{addon.price}%
                      </Badge>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* Step 5: Terms */}
          {step === 5 && (
            <div className="space-y-6">
              <div>
                <h3 className="font-semibold mb-2">Final Terms</h3>
                <p className="text-sm text-muted-foreground mb-4">Set revision limits and special terms</p>
              </div>

              {/* Exclusivity */}
              <div>
                <Label className="text-sm mb-2 block">Exclusivity</Label>
                <Select value={formData.exclusivity} onValueChange={(val) => setFormData({ ...formData, exclusivity: val })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="none">No exclusivity</SelectItem>
                    <SelectItem value="3-months">3 months exclusive</SelectItem>
                    <SelectItem value="6-months">6 months exclusive</SelectItem>
                    <SelectItem value="perpetual">Perpetual exclusive</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {/* Revision Cap */}
              <div>
                <Label className="text-sm mb-2 block">Max Revisions: {formData.revisionCap}</Label>
                <Input
                  type="range"
                  min="1"
                  max="5"
                  value={formData.revisionCap}
                  onChange={(e) => setFormData({ ...formData, revisionCap: parseInt(e.target.value) })}
                  className="w-full"
                />
                <p className="text-xs text-muted-foreground mt-2">Creator gets {formData.revisionCap} revision(s) before final approval</p>
              </div>

              {/* Custom Clauses */}
              <div>
                <Label className="text-sm mb-2 block">Custom Clauses (optional)</Label>
                <Textarea
                  value={formData.customClauses}
                  onChange={(e) => setFormData({ ...formData, customClauses: e.target.value })}
                  placeholder="Add any special terms or conditions here..."
                  className="min-h-20"
                />
              </div>

              {/* Final Cost */}
              <Card className="border-primary/20 bg-primary/5">
                <CardContent className="pt-4 pb-4">
                  <div className="flex justify-between items-center">
                    <span className="font-semibold">Total You Pay:</span>
                    <span className="text-xl font-bold text-primary">₹{Math.round(totalCost).toLocaleString('en-IN')}</span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-2">Funds will be held securely in escrow until you approve the work</p>
                </CardContent>
              </Card>
            </div>
          )}
        </div>

        {/* Footer - Navigation */}
        <div className="border-t border-border p-6 flex items-center gap-3">
          {step > 1 && <Button variant="outline" onClick={handleBack}>
            Back
          </Button>}
          <div className="flex-1" />
          {step < totalSteps ? (
            <Button onClick={handleNext} className="gap-1.5">
              Next
              <ChevronRight className="h-4 w-4" />
            </Button>
          ) : (
            <Button onClick={handleSubmit} disabled={isSubmitting} className="gap-1.5">
              {isSubmitting ? 'Sending...' : 'Send Proposal'}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
