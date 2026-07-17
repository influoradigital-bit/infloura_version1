import { CheckCircle2, Clock, Image as ImageIcon, Pen, Video } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { ScrollArea } from '@/components/ui/scroll-area';
import { cn, formatINR } from '@/lib/utils';

export interface DealDeliverableItem {
  id: string;
  title: string;
  type: 'video' | 'image';
  status: 'pending' | 'pending_review' | 'approved' | 'revision';
  submittedAt?: Date;
}

interface DealDeliverablesTabProps {
  done: number;
  total: number;
  dealValue: number;
  items: DealDeliverableItem[];
  onApprove?: (id: string) => void;
  onRequestRevision?: (id: string) => void;
}

export function DealDeliverablesTab({
  done,
  total,
  dealValue,
  items,
  onApprove,
  onRequestRevision,
}: DealDeliverablesTabProps) {
  const progress = total > 0 ? Math.round((done / total) * 100) : 0;

  return (
    <ScrollArea className="h-full">
      <div className="max-w-3xl mx-auto p-6 space-y-6">
        <div>
          <div className="flex items-center justify-between mb-2">
            <h3 className="font-semibold text-sm">Deliverables</h3>
            <span className="text-sm text-muted-foreground">
              {done}/{total} approved
            </span>
          </div>
          <Progress value={progress} className="h-2" />
          <p className="text-xs text-muted-foreground mt-2">
            {formatINR(dealValue)} releases from escrow as each deliverable is approved.
          </p>
        </div>

        <div className="space-y-3">
          {items.length === 0 ? (
            <Card>
              <CardContent className="py-8 text-center text-sm text-muted-foreground">
                No deliverables yet. They appear here once the creator submits content.
              </CardContent>
            </Card>
          ) : (
            items.map((item) => {
              const isApproved = item.status === 'approved';
              const isPending = item.status === 'pending_review';
              return (
                <Card
                  key={item.id}
                  className={cn(
                    isApproved && 'border-success/30 bg-success/5',
                    isPending && 'border-warning/30',
                  )}
                >
                  <CardContent className="p-4">
                    <div className="flex items-start gap-3">
                      <div
                        className={cn(
                          'h-12 w-12 rounded-lg flex items-center justify-center shrink-0',
                          isApproved ? 'bg-success/15' : 'bg-primary/10',
                        )}
                      >
                        {item.type === 'video' ? (
                          <Video className="h-6 w-6 text-primary" />
                        ) : (
                          <ImageIcon className="h-6 w-6 text-primary" />
                        )}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <p className="font-medium text-sm">{item.title}</p>
                          {isApproved && (
                            <Badge className="bg-success/15 text-success text-xs">Approved</Badge>
                          )}
                          {isPending && (
                            <Badge className="bg-warning/15 text-warning text-xs">Pending review</Badge>
                          )}
                          {item.status === 'revision' && (
                            <Badge variant="outline" className="text-xs">
                              Revision requested
                            </Badge>
                          )}
                          {item.status === 'pending' && (
                            <Badge variant="secondary" className="text-xs gap-1">
                              <Clock className="h-3 w-3" />
                              Awaiting submission
                            </Badge>
                          )}
                        </div>
                        {item.submittedAt && (
                          <p className="text-xs text-muted-foreground mt-1">
                            Submitted{' '}
                            {item.submittedAt.toLocaleDateString('en-IN', {
                              day: 'numeric',
                              month: 'short',
                            })}
                          </p>
                        )}
                      </div>
                    </div>
                    {isPending && (
                      <div className="flex gap-2 mt-3">
                        <Button
                          size="sm"
                          className="h-8 text-xs"
                          onClick={() => onApprove?.(item.id)}
                        >
                          <CheckCircle2 className="h-3.5 w-3.5 mr-1" />
                          Approve
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-8 text-xs"
                          onClick={() => onRequestRevision?.(item.id)}
                        >
                          <Pen className="h-3.5 w-3.5 mr-1" />
                          Request changes
                        </Button>
                      </div>
                    )}
                  </CardContent>
                </Card>
              );
            })
          )}
        </div>
      </div>
    </ScrollArea>
  );
}
