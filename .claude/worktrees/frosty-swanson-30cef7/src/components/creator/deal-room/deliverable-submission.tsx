'use client';

import * as React from 'react';
import { Upload, X, AlertCircle, CheckCircle2, File, Image as ImageIcon, Video } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter, DialogDescription } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Progress } from '@/components/ui/progress';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

interface Deliverable {
  id: string;
  title: string;
  description: string;
  completed: boolean;
  currentRevision?: number;
  maxRevisions?: number;
}

interface DeliverableSubmissionProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  deliverables: Deliverable[];
  onSubmit: (data: DeliverableSubmissionData) => Promise<void>;
  isSubmitting?: boolean;
}

export interface DeliverableSubmissionData {
  deliverableId: string;
  file: File;
  caption: string;
}

export function DeliverableSubmission({
  open,
  onOpenChange,
  deliverables,
  onSubmit,
  isSubmitting = false,
}: DeliverableSubmissionProps) {
  const [selectedDeliverable, setSelectedDeliverable] = React.useState<string>('');
  const [file, setFile] = React.useState<File | null>(null);
  const [caption, setCaption] = React.useState('');
  const [dragActive, setDragActive] = React.useState(false);
  const [preview, setPreview] = React.useState<string>('');
  const fileInputRef = React.useRef<HTMLInputElement>(null);

  const selectedDeliverableObj = deliverables.find(d => d.id === selectedDeliverable);
  const isRevision = selectedDeliverableObj?.currentRevision ? selectedDeliverableObj.currentRevision > 0 : false;

  React.useEffect(() => {
    if (deliverables.length > 0 && !selectedDeliverable) {
      setSelectedDeliverable(deliverables[0].id);
    }
  }, [deliverables, selectedDeliverable]);

  const handleDrag = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActive(true);
    } else if (e.type === 'dragleave') {
      setDragActive(false);
    }
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDragActive(false);

    const files = e.dataTransfer.files;
    if (files && files[0]) {
      handleFileSelect(files[0]);
    }
  };

  const handleFileSelect = (selectedFile: File) => {
    const validTypes = ['video/mp4', 'video/quicktime', 'image/jpeg', 'image/png', 'image/webp', 'application/pdf'];
    
    if (!validTypes.includes(selectedFile.type)) {
      console.log('[v0] Invalid file type:', selectedFile.type);
      return;
    }

    setFile(selectedFile);

    // Create preview
    if (selectedFile.type.startsWith('image/')) {
      const reader = new FileReader();
      reader.onloadend = () => setPreview(reader.result as string);
      reader.readAsDataURL(selectedFile);
    } else if (selectedFile.type.startsWith('video/')) {
      setPreview(`video-${selectedFile.name}`);
    } else {
      setPreview(`file-${selectedFile.name}`);
    }
  };

  const handleSubmit = async () => {
    if (!file || !selectedDeliverable || !caption) {
      console.log('[v0] Missing required fields');
      return;
    }

    try {
      await onSubmit({
        deliverableId: selectedDeliverable,
        file,
        caption,
      });

      // Reset form
      setFile(null);
      setCaption('');
      setPreview('');
      setSelectedDeliverable(deliverables[0]?.id || '');
      onOpenChange(false);
    } catch (error) {
      console.log('[v0] Error submitting deliverable:', error);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Submit Deliverable</DialogTitle>
          <DialogDescription>
            {isRevision ? 'Submit a revised version of your deliverable' : 'Upload and submit your deliverable for review'}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-6 py-4">
          {/* Deliverable Selection */}
          <div className="space-y-2">
            <Label htmlFor="deliverable">Select Deliverable</Label>
            <Select value={selectedDeliverable} onValueChange={setSelectedDeliverable}>
              <SelectTrigger id="deliverable">
                <SelectValue placeholder="Choose a deliverable..." />
              </SelectTrigger>
              <SelectContent>
                {deliverables.map(d => (
                  <SelectItem key={d.id} value={d.id}>
                    <div className="flex items-center gap-2">
                      <span>{d.title}</span>
                      {d.currentRevision && d.maxRevisions && (
                        <Badge variant="outline" className="text-xs">
                          Rev {d.currentRevision}/{d.maxRevisions}
                        </Badge>
                      )}
                    </div>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {selectedDeliverableObj && (
              <p className="text-xs text-muted-foreground">{selectedDeliverableObj.description}</p>
            )}
          </div>

          {/* File Upload */}
          <div className="space-y-2">
            <Label>Upload File</Label>
            <div
              className={cn(
                'border-2 border-dashed rounded-lg p-8 text-center transition-colors',
                dragActive ? 'border-primary bg-primary/5' : 'border-muted-foreground/25',
                file && 'border-green-500 bg-green-50/50'
              )}
              onDragEnter={handleDrag}
              onDragLeave={handleDrag}
              onDragOver={handleDrag}
              onDrop={handleDrop}
            >
              <input
                ref={fileInputRef}
                type="file"
                className="hidden"
                onChange={(e) => e.target.files?.[0] && handleFileSelect(e.target.files[0])}
                accept=".mp4,.mov,.jpg,.jpeg,.png,.webp,.pdf"
              />

              {file ? (
                <div className="space-y-2">
                  <CheckCircle2 className="h-8 w-8 mx-auto text-stage-approved-fg" />
                  <p className="font-medium text-sm">{file.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {(file.size / 1024 / 1024).toFixed(2)} MB
                  </p>
                </div>
              ) : (
                <div className="space-y-2">
                  <Upload className="h-8 w-8 mx-auto text-muted-foreground" />
                  <p className="text-sm text-muted-foreground">
                    Drag and drop your file here, or click to browse
                  </p>
                  <p className="text-xs text-muted-foreground">
                    Supported: MP4, MOV, JPG, PNG, WebP, PDF (Max 100MB)
                  </p>
                </div>
              )}

              <Button
                variant="outline"
                size="sm"
                className="mt-3"
                onClick={() => fileInputRef.current?.click()}
              >
                {file ? 'Change File' : 'Choose File'}
              </Button>
            </div>
          </div>

          {/* Preview */}
          {preview && (
            <div className="space-y-2">
              <Label>Preview</Label>
              <div className="bg-muted rounded-lg p-4 flex items-center justify-center min-h-32">
                {preview.startsWith('video-') ? (
                  <div className="flex flex-col items-center gap-2 text-muted-foreground">
                    <Video className="h-8 w-8" />
                    <span className="text-sm">{file?.name}</span>
                  </div>
                ) : preview.startsWith('file-') ? (
                  <div className="flex flex-col items-center gap-2 text-muted-foreground">
                    <File className="h-8 w-8" />
                    <span className="text-sm">{file?.name}</span>
                  </div>
                ) : (
                  <img src={preview} alt="Preview" className="max-h-32 max-w-full rounded" />
                )}
              </div>
            </div>
          )}

          {/* Caption */}
          <div className="space-y-2">
            <Label htmlFor="caption">Caption / Notes</Label>
            <Textarea
              id="caption"
              placeholder="Add caption, hashtags, or notes about this deliverable..."
              rows={3}
              value={caption}
              onChange={(e) => setCaption(e.target.value)}
              disabled={isSubmitting}
            />
            <p className="text-xs text-muted-foreground">{caption.length}/500 characters</p>
          </div>

          {/* Revision Alert */}
          {isRevision && (
            <Alert>
              <AlertCircle className="h-4 w-4" />
              <AlertDescription>
                This is revision {selectedDeliverableObj?.currentRevision} of {selectedDeliverableObj?.maxRevisions}. After this, the brand must approve or you&apos;ll need new terms.
              </AlertDescription>
            </Alert>
          )}
        </div>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={isSubmitting}
          >
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={!file || !selectedDeliverable || !caption || isSubmitting}
          >
            {isSubmitting ? 'Submitting...' : 'Submit Deliverable'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
