import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { CreatorLayout } from '@/components/creator/creator-layout';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Progress } from '@/components/ui/progress';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { CollaborationTimeline } from '@/components/brand/timeline/collaboration-timeline';
import {
  Calendar,
  Clock,
  Upload,
  FileVideo,
  Image,
  CheckCircle2,
  AlertCircle,
  ChevronRight,
  Eye,
  MessageCircle,
  Shield,
  IndianRupee,
  Loader2,
  FileText,
  RotateCcw,
  X,
} from 'lucide-react';
import { cn } from '@/lib/utils';

// Collaboration states matching the PDF
type CollaborationState = 
  | 'CONTRACT_SIGNING'
  | 'IN_PROGRESS'
  | 'REVIEW_PENDING'
  | 'REVISION_REQUESTED'
  | 'APPROVED'
  | 'SETTLING'
  | 'COMPLETED'
  | 'DISPUTED';

const stateConfig: Record<CollaborationState, { label: string; color: string; bgColor: string }> = {
  CONTRACT_SIGNING: { label: 'Contract Signing', color: 'text-stage-contracted-fg', bgColor: 'bg-stage-contracted' },
  IN_PROGRESS: { label: 'In Progress', color: 'text-stage-progress-fg', bgColor: 'bg-stage-progress' },
  REVIEW_PENDING: { label: 'Under Review', color: 'text-stage-review-fg', bgColor: 'bg-stage-review' },
  REVISION_REQUESTED: { label: 'Revision Needed', color: 'text-stage-review-fg', bgColor: 'bg-stage-review' },
  APPROVED: { label: 'Approved', color: 'text-stage-approved-fg', bgColor: 'bg-stage-approved' },
  SETTLING: { label: 'Payment Processing', color: 'text-stage-approved-fg', bgColor: 'bg-stage-approved' },
  COMPLETED: { label: 'Completed', color: 'text-stage-approved-fg', bgColor: 'bg-stage-approved' },
  DISPUTED: { label: 'In Dispute', color: 'text-stage-disputed-fg', bgColor: 'bg-stage-disputed' },
};

const mockCollaborations = [
  {
    id: 'collab-1',
    brandName: 'Nykaa Fashion',
    brandLogo: '',
    campaignTitle: 'Summer Collection Launch',
    state: 'IN_PROGRESS' as CollaborationState,
    deliverables: [
      { id: 'd1', type: 'REEL', title: 'Outfit Transition Reel', status: 'PENDING', dueDate: '2026-06-10' },
      { id: 'd2', type: 'REEL', title: 'Styling Tips Reel', status: 'PENDING', dueDate: '2026-06-12' },
      { id: 'd3', type: 'STORY', title: 'BTS Stories', status: 'PENDING', dueDate: '2026-06-15' },
    ],
    budget: 45000,
    deadline: '2026-06-15',
    progress: 33,
    escrowAmount: 45000,
    revisionsRemaining: 2,
    startedAt: '2026-06-01',
  },
  {
    id: 'collab-2',
    brandName: 'BoAt Lifestyle',
    brandLogo: '',
    campaignTitle: 'New Earbuds Launch',
    state: 'REVIEW_PENDING' as CollaborationState,
    deliverables: [
      { id: 'd4', type: 'VIDEO', title: 'Unboxing + Review', status: 'SUBMITTED', dueDate: '2026-06-08' },
      { id: 'd5', type: 'POST', title: 'Lifestyle Post 1', status: 'APPROVED', dueDate: '2026-06-10' },
      { id: 'd6', type: 'POST', title: 'Lifestyle Post 2', status: 'PENDING', dueDate: '2026-06-12' },
    ],
    budget: 75000,
    deadline: '2026-06-20',
    progress: 66,
    escrowAmount: 75000,
    revisionsRemaining: 1,
    startedAt: '2026-05-28',
  },
  {
    id: 'collab-3',
    brandName: 'Mamaearth',
    brandLogo: '',
    campaignTitle: 'Skincare Routine Series',
    state: 'REVISION_REQUESTED' as CollaborationState,
    deliverables: [
      { id: 'd7', type: 'REEL', title: 'Morning Routine', status: 'REVISION', dueDate: '2026-06-05', revisionNotes: 'Please show the product label more clearly at 0:15' },
      { id: 'd8', type: 'REEL', title: 'Night Routine', status: 'PENDING', dueDate: '2026-06-08' },
      { id: 'd9', type: 'REEL', title: 'Weekly Treatment', status: 'PENDING', dueDate: '2026-06-10' },
    ],
    budget: 35000,
    deadline: '2026-06-10',
    progress: 25,
    escrowAmount: 35000,
    revisionsRemaining: 1,
    startedAt: '2026-05-25',
  },
];

function formatINR(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function daysUntil(dateString: string): number {
  const date = new Date(dateString);
  const now = new Date();
  const diff = date.getTime() - now.getTime();
  return Math.ceil(diff / (1000 * 60 * 60 * 24));
}

export default function CreatorActivePage() {
  const navigate = useNavigate();
  const [selectedCollab, setSelectedCollab] = React.useState<typeof mockCollaborations[0] | null>(null);
  const [showUploadDialog, setShowUploadDialog] = React.useState(false);
  const [selectedDeliverable, setSelectedDeliverable] = React.useState<typeof mockCollaborations[0]['deliverables'][0] | null>(null);
  const [uploadCaption, setUploadCaption] = React.useState('');
  const [isUploading, setIsUploading] = React.useState(false);
  const [showTimeline, setShowTimeline] = React.useState(false);
  const [timelineCollab, setTimelineCollab] = React.useState<typeof mockCollaborations[0] | null>(null);
  const [showContractSheet, setShowContractSheet] = React.useState(false);
  const [contractCollab, setContractCollab] = React.useState<typeof mockCollaborations[0] | null>(null);

  const activeCollabs = mockCollaborations.filter(
    (c) => !['COMPLETED', 'DISPUTED'].includes(c.state)
  );
  const completedCollabs = mockCollaborations.filter(
    (c) => c.state === 'COMPLETED'
  );

  const handleUpload = async () => {
    setIsUploading(true);
    await new Promise((resolve) => setTimeout(resolve, 2000));
    setIsUploading(false);
    setShowUploadDialog(false);
    setSelectedDeliverable(null);
    setUploadCaption('');
  };

  const getDeliverableIcon = (type: string) => {
    switch (type) {
      case 'REEL':
      case 'VIDEO':
        return FileVideo;
      case 'STORY':
      case 'POST':
        return Image;
      default:
        return FileText;
    }
  };

  const getDeliverableStatusBadge = (status: string) => {
    switch (status) {
      case 'APPROVED':
        return <Badge className="bg-stage-approved text-stage-approved-fg hover:bg-stage-approved">Approved</Badge>;
      case 'SUBMITTED':
        return <Badge className="bg-purple-100 text-purple-700 hover:bg-purple-100">Under Review</Badge>;
      case 'REVISION':
        return <Badge className="bg-orange-100 text-orange-700 hover:bg-orange-100">Revision</Badge>;
      default:
        return <Badge variant="outline">Pending</Badge>;
    }
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-2xl">
        {/* Header */}
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Active Collaborations</h1>
          <p className="text-muted-foreground">
            {activeCollabs.length} ongoing collaboration{activeCollabs.length !== 1 ? 's' : ''}
          </p>
        </div>

        {/* Summary Cards */}
        <div className="grid grid-cols-2 gap-3 mb-6">
          <Card>
            <CardContent className="p-4">
              <div className="flex items-center gap-3">
                <div className="h-10 w-10 rounded-full bg-stage-outreach flex items-center justify-center">
                  <FileText className="h-5 w-5 text-stage-outreach-fg" />
                </div>
                <div>
                  <p className="text-2xl font-bold">{activeCollabs.length}</p>
                  <p className="text-xs text-muted-foreground">Active</p>
                </div>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4">
              <div className="flex items-center gap-3">
                <div className="h-10 w-10 rounded-full bg-stage-approved flex items-center justify-center">
                  <Shield className="h-5 w-5 text-stage-approved-fg" />
                </div>
                <div>
                  <p className="text-2xl font-bold">
                    {formatINR(activeCollabs.reduce((sum, c) => sum + c.escrowAmount, 0))}
                  </p>
                  <p className="text-xs text-muted-foreground">In Escrow</p>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Urgent Actions */}
        {mockCollaborations.some((c) => c.state === 'REVISION_REQUESTED') && (
          <Card className="mb-6 border-orange-300 bg-orange-50">
            <CardContent className="p-4">
              <div className="flex items-start gap-3">
                <AlertCircle className="h-5 w-5 text-orange-600 flex-shrink-0 mt-0.5" />
                <div>
                  <p className="font-medium text-orange-800">Action Required</p>
                  <p className="text-sm text-orange-700">
                    1 deliverable needs revision. Complete it to continue.
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Tabs */}
        <Tabs defaultValue="active" className="space-y-4">
          <TabsList className="w-full grid grid-cols-2">
            <TabsTrigger value="active">
              Active
              <Badge className="ml-2 h-5 px-1.5">{activeCollabs.length}</Badge>
            </TabsTrigger>
            <TabsTrigger value="completed">
              Completed
            </TabsTrigger>
          </TabsList>

          <TabsContent value="active" className="space-y-4">
            {activeCollabs.map((collab) => {
              const daysLeft = daysUntil(collab.deadline);
              const isUrgent = daysLeft <= 3 && daysLeft > 0;
              const isOverdue = daysLeft < 0;
              const stateInfo = stateConfig[collab.state];

              return (
                <Card
                  key={collab.id}
                  className={cn(
                    'cursor-pointer transition-all hover:shadow-md',
                    collab.state === 'REVISION_REQUESTED' && 'border-orange-300'
                  )}
                  onClick={() => setSelectedCollab(collab)}
                >
                  <CardContent className="p-4">
                    <div className="flex items-start justify-between gap-3 mb-3">
                      <div className="flex items-start gap-3 min-w-0">
                        <Avatar className="h-10 w-10 flex-shrink-0">
                          <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold text-sm">
                            {collab.brandName.charAt(0)}
                          </AvatarFallback>
                        </Avatar>
                        <div className="min-w-0">
                          <p className="font-semibold truncate">{collab.brandName}</p>
                          <p className="text-sm text-muted-foreground truncate">
                            {collab.campaignTitle}
                          </p>
                        </div>
                      </div>
                      <Badge className={cn('flex-shrink-0', stateInfo.bgColor, stateInfo.color, 'hover:' + stateInfo.bgColor)}>
                        {stateInfo.label}
                      </Badge>
                    </div>

                    {/* Progress */}
                    <div className="mb-3">
                      <div className="flex items-center justify-between text-sm mb-1">
                        <span className="text-muted-foreground">Progress</span>
                        <span className="font-medium">{collab.progress}%</span>
                      </div>
                      <Progress value={collab.progress} className="h-2" />
                    </div>

                    {/* Footer Info */}
                    <div className="flex items-center justify-between text-sm">
                      <div className="flex items-center gap-1 text-muted-foreground">
                        <Calendar className="h-4 w-4" />
                        <span className={cn(
                          isOverdue && 'text-stage-disputed-fg font-medium',
                          isUrgent && 'text-stage-negotiating-fg font-medium'
                        )}>
                          {isOverdue ? 'Overdue' : isUrgent ? `${daysLeft}d left` : `Due ${new Date(collab.deadline).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}`}
                        </span>
                      </div>
                      <div className="flex items-center gap-1 text-stage-approved-fg font-medium">
                        <Shield className="h-4 w-4" />
                        {formatINR(collab.escrowAmount)}
                      </div>
                    </div>
                  </CardContent>
                </Card>
              );
            })}
          </TabsContent>

          <TabsContent value="completed" className="space-y-4">
            <div className="text-center py-12 text-muted-foreground">
              <CheckCircle2 className="h-12 w-12 mx-auto mb-3 text-muted-foreground/50" />
              <p>No completed collaborations yet</p>
              <p className="text-sm">Your completed work will appear here</p>
            </div>
          </TabsContent>
        </Tabs>
      </div>

      {/* Collaboration Detail Dialog */}
      <Dialog open={!!selectedCollab} onOpenChange={() => setSelectedCollab(null)}>
        <DialogContent className="max-w-md max-h-[90vh] overflow-y-auto">
          {selectedCollab && (
            <>
              <DialogHeader>
                <div className="flex items-center gap-3">
                  <Avatar className="h-12 w-12">
                    <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold">
                      {selectedCollab.brandName.charAt(0)}
                    </AvatarFallback>
                  </Avatar>
                  <div>
                    <DialogTitle>{selectedCollab.brandName}</DialogTitle>
                    <DialogDescription>{selectedCollab.campaignTitle}</DialogDescription>
                  </div>
                </div>
              </DialogHeader>

              <div className="space-y-4">
                {/* Status & Escrow */}
                <div className="grid grid-cols-2 gap-3">
                  <div className="bg-muted/50 rounded-lg p-3">
                    <p className="text-xs text-muted-foreground">Status</p>
                    <Badge className={cn(stateConfig[selectedCollab.state].bgColor, stateConfig[selectedCollab.state].color, 'mt-1')}>
                      {stateConfig[selectedCollab.state].label}
                    </Badge>
                  </div>
                  <div className="bg-stage-approved rounded-lg p-3">
                    <p className="text-xs text-muted-foreground">Escrow</p>
                    <p className="font-bold text-stage-approved-fg">{formatINR(selectedCollab.escrowAmount)}</p>
                  </div>
                </div>

                {/* Progress */}
                <div>
                  <div className="flex items-center justify-between text-sm mb-2">
                    <span className="font-medium">Overall Progress</span>
                    <span>{selectedCollab.progress}%</span>
                  </div>
                  <Progress value={selectedCollab.progress} className="h-2" />
                </div>

                {/* Deliverables */}
                <div>
                  <h4 className="font-medium mb-3">Deliverables</h4>
                  <div className="space-y-2">
                    {selectedCollab.deliverables.map((deliverable) => {
                      const Icon = getDeliverableIcon(deliverable.type);
                      const needsAction = deliverable.status === 'PENDING' || deliverable.status === 'REVISION';
                      
                      return (
                        <div
                          key={deliverable.id}
                          className={cn(
                            'flex items-center justify-between p-3 rounded-lg border',
                            deliverable.status === 'REVISION' && 'border-orange-300 bg-orange-50',
                            deliverable.status === 'APPROVED' && 'border-green-200 bg-green-50'
                          )}
                        >
                          <div className="flex items-center gap-3 min-w-0">
                            <div className={cn(
                              'h-10 w-10 rounded-lg flex items-center justify-center flex-shrink-0',
                              deliverable.status === 'APPROVED' ? 'bg-stage-approved' : 'bg-muted'
                            )}>
                              <Icon className={cn(
                                'h-5 w-5',
                                deliverable.status === 'APPROVED' ? 'text-stage-approved-fg' : 'text-muted-foreground'
                              )} />
                            </div>
                            <div className="min-w-0">
                              <p className="font-medium text-sm truncate">{deliverable.title}</p>
                              <p className="text-xs text-muted-foreground">
                                Due {new Date(deliverable.dueDate).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
                              </p>
                              {deliverable.revisionNotes && (
                                <p className="text-xs text-orange-700 mt-1 line-clamp-2">
                                  {deliverable.revisionNotes}
                                </p>
                              )}
                            </div>
                          </div>
                          <div className="flex items-center gap-2 flex-shrink-0">
                            {getDeliverableStatusBadge(deliverable.status)}
                            {needsAction && (
                              <Button
                                size="sm"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setSelectedDeliverable(deliverable);
                                  setShowUploadDialog(true);
                                }}
                              >
                                <Upload className="h-4 w-4" />
                              </Button>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* Revisions Info */}
                <div className="flex items-center gap-2 text-sm bg-muted/50 rounded-lg p-3">
                  <RotateCcw className="h-4 w-4 text-muted-foreground" />
                  <span>
                    {selectedCollab.revisionsRemaining} revision{selectedCollab.revisionsRemaining !== 1 ? 's' : ''} remaining
                  </span>
                </div>

                {/* Actions */}
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1"
                    onClick={() => {
                      setTimelineCollab(selectedCollab);
                      setShowTimeline(true);
                      setSelectedCollab(null);
                    }}
                  >
                    <MessageCircle className="h-4 w-4 mr-2" />
                    Message Brand
                  </Button>
                  <Button
                    variant="outline"
                    className="flex-1"
                    onClick={() => {
                      setContractCollab(selectedCollab);
                      setShowContractSheet(true);
                      setSelectedCollab(null);
                    }}
                  >
                    <FileText className="h-4 w-4 mr-2" />
                    View Contract
                  </Button>
                </div>
              </div>
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* Upload Dialog */}
      <Dialog open={showUploadDialog} onOpenChange={setShowUploadDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Submit Deliverable</DialogTitle>
            <DialogDescription>
              Upload your content for {selectedDeliverable?.title}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            {/* Upload Area */}
            <div className="border-2 border-dashed rounded-lg p-8 text-center cursor-pointer hover:bg-muted/50 transition-colors">
              <Upload className="h-10 w-10 mx-auto text-muted-foreground mb-3" />
              <p className="font-medium">Click to upload or drag and drop</p>
              <p className="text-sm text-muted-foreground mt-1">
                MP4, MOV up to 2GB or JPG, PNG up to 25MB
              </p>
            </div>

            {/* Caption */}
            <div className="space-y-2">
              <Label>Caption / Notes (Optional)</Label>
              <Textarea
                placeholder="Add any notes for the brand..."
                value={uploadCaption}
                onChange={(e) => setUploadCaption(e.target.value)}
                rows={3}
              />
            </div>

            {/* Auto-checks Info */}
            <div className="bg-blue-50 border border-stage-outreach-border rounded-lg p-3">
              <p className="text-sm text-blue-800">
                <CheckCircle2 className="h-4 w-4 inline mr-1" />
                We&apos;ll automatically check file integrity and format before submission
              </p>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowUploadDialog(false)}>
              Cancel
            </Button>
            <Button onClick={handleUpload} disabled={isUploading}>
              {isUploading ? (
                <>
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  Uploading...
                </>
              ) : (
                <>
                  <Upload className="h-4 w-4 mr-2" />
                  Submit
                </>
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Collaboration Timeline Sheet */}
      <Sheet open={showTimeline} onOpenChange={setShowTimeline}>
        <SheetContent side="right" className="w-full sm:w-[540px] sm:max-w-[540px] p-0 flex flex-col">
          <SheetHeader className="px-6 py-4 border-b">
            <SheetTitle className="flex items-center gap-3">
              <Avatar className="h-8 w-8">
                <AvatarFallback className="bg-gradient-to-br from-violet-100 to-purple-100 text-stage-contracted-fg font-semibold text-sm">
                  {timelineCollab?.brandName.charAt(0)}
                </AvatarFallback>
              </Avatar>
              {timelineCollab?.brandName} - Chat & Timeline
            </SheetTitle>
          </SheetHeader>
          {timelineCollab && (
            <div className="flex-1 overflow-hidden px-6 py-4">
              <CollaborationTimeline
                collaboration={{
                  id: timelineCollab.id,
                  creatorId: 'creator-1',
                  creatorName: 'Priya Sharma',
                } as import('@/lib/types').Collaboration}
                currentUserType="creator"
                peerName={timelineCollab.brandName}
              />
            </div>
          )}
        </SheetContent>
      </Sheet>

      {/* Contract Detail Sheet */}
      <Sheet open={showContractSheet} onOpenChange={setShowContractSheet}>
        <SheetContent side="right" className="w-full sm:w-[480px] sm:max-w-[480px]">
          <SheetHeader>
            <SheetTitle>Contract Details</SheetTitle>
          </SheetHeader>
          {contractCollab && (
            <div className="mt-6 space-y-6">
              {/* Parties */}
              <div>
                <h4 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Parties</h4>
                <div className="space-y-2">
                  <div className="flex items-center justify-between p-3 bg-muted/50 rounded-lg">
                    <span className="text-sm text-muted-foreground">Brand</span>
                    <span className="text-sm font-medium">{contractCollab.brandName}</span>
                  </div>
                  <div className="flex items-center justify-between p-3 bg-muted/50 rounded-lg">
                    <span className="text-sm text-muted-foreground">Creator</span>
                    <span className="text-sm font-medium">Priya Sharma</span>
                  </div>
                </div>
              </div>

              {/* Campaign Details */}
              <div>
                <h4 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Campaign</h4>
                <div className="p-3 bg-muted/50 rounded-lg space-y-2">
                  <p className="font-medium">{contractCollab.campaignTitle}</p>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Compensation</span>
                    <span className="font-semibold text-stage-approved-fg">{formatINR(contractCollab.budget)}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Deadline</span>
                    <span>{new Date(contractCollab.deadline).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Status</span>
                    <Badge className={cn(stateConfig[contractCollab.state].bgColor, stateConfig[contractCollab.state].color)}>
                      {stateConfig[contractCollab.state].label}
                    </Badge>
                  </div>
                </div>
              </div>

              {/* Deliverables */}
              <div>
                <h4 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Deliverables</h4>
                <div className="space-y-2">
                  {contractCollab.deliverables.map((d) => (
                    <div key={d.id} className="flex items-center justify-between p-3 bg-muted/50 rounded-lg">
                      <div>
                        <span className="text-sm font-medium">{d.title}</span>
                        <p className="text-xs text-muted-foreground">Due {new Date(d.dueDate).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}</p>
                      </div>
                      {d.status === 'APPROVED' ? (
                        <Badge className="bg-stage-approved text-stage-approved-fg">Approved</Badge>
                      ) : d.status === 'SUBMITTED' ? (
                        <Badge className="bg-purple-100 text-purple-700">Under Review</Badge>
                      ) : d.status === 'REVISION' ? (
                        <Badge className="bg-orange-100 text-orange-700">Revision</Badge>
                      ) : (
                        <Badge variant="outline">Pending</Badge>
                      )}
                    </div>
                  ))}
                </div>
              </div>

              {/* Payment Terms */}
              <div>
                <h4 className="text-sm font-semibold mb-3 text-muted-foreground uppercase tracking-wide">Payment</h4>
                <div className="p-3 bg-stage-approved border border-stage-approved-border rounded-lg space-y-2">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Total Amount</span>
                    <span className="font-bold text-stage-approved-fg">{formatINR(contractCollab.budget)}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Escrow Status</span>
                    <span className="flex items-center gap-1 text-stage-approved-fg font-medium">
                      <Shield className="h-3 w-3" />
                      Funded
                    </span>
                  </div>
                </div>
              </div>

              <Button variant="outline" className="w-full" onClick={() => setShowContractSheet(false)}>
                Close
              </Button>
            </div>
          )}
        </SheetContent>
      </Sheet>

    </CreatorLayout>
  );
}
