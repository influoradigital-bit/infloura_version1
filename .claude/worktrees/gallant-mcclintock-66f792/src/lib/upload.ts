/**
 * Cloudflare R2 Upload Service
 *
 * Architecture:
 * - Files are uploaded to Cloudflare R2 via a presigned URL flow
 * - MySQL stores the R2 object URL + metadata (filename, size, mime, uploaded_by)
 * - On fetch, the app reads the URL from MySQL and loads from R2
 *
 * Currently: Mock implementation that simulates the R2 upload
 * When backend is ready, replace mock functions with real API calls
 */

export interface UploadResult {
  success: boolean;
  /** The Cloudflare R2 public URL for the uploaded file */
  url: string;
  /** R2 object key (bucket path) */
  key: string;
  /** Original filename */
  filename: string;
  /** File size in bytes */
  size: number;
  /** MIME type */
  mimeType: string;
  /** Upload timestamp */
  uploadedAt: string;
}

export interface UploadProgress {
  loaded: number;
  total: number;
  percentage: number;
}

type ProgressCallback = (progress: UploadProgress) => void;

/**
 * Upload a file to Cloudflare R2
 *
 * Real flow (when backend is ready):
 * 1. POST /api/upload/presign  -> get { presignedUrl, key }
 * 2. PUT presignedUrl with file body
 * 3. POST /api/upload/confirm  -> save R2 URL to MySQL
 *
 * Current: mock that creates a local blob URL
 */
export async function uploadToR2(
  file: File,
  folder: string = 'uploads',
  onProgress?: ProgressCallback,
): Promise<UploadResult> {
  // Simulate upload with progress
  const totalSteps = 10;
  for (let i = 1; i <= totalSteps; i++) {
    await new Promise((r) => setTimeout(r, 150));
    onProgress?.({
      loaded: (file.size / totalSteps) * i,
      total: file.size,
      percentage: Math.round((i / totalSteps) * 100),
    });
  }

  // In production: this would be the R2 public URL
  const mockKey = `${folder}/${Date.now()}-${file.name.replace(/\s+/g, '-')}`;
  const mockUrl = `https://r2.influora.com/${mockKey}`;

  const result: UploadResult = {
    success: true,
    url: mockUrl,
    key: mockKey,
    filename: file.name,
    size: file.size,
    mimeType: file.type,
    uploadedAt: new Date().toISOString(),
  };

  // In production: POST /api/upload/confirm to save URL in MySQL
  // await apiClient.post('/upload/confirm', { key, url, filename, size, mimeType });

  return result;
}

/**
 * Delete a file from Cloudflare R2
 */
export async function deleteFromR2(key: string): Promise<{ success: boolean }> {
  await new Promise((r) => setTimeout(r, 300));
  // In production: DELETE /api/upload/:key
  return { success: true };
}

/**
 * Generate a local preview URL for an image file (before upload)
 */
export function createLocalPreview(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => resolve(reader.result as string);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

/**
 * Validate file before upload
 */
export function validateFile(
  file: File,
  options: {
    maxSizeMB?: number;
    allowedTypes?: string[];
  } = {},
): { valid: boolean; error?: string } {
  const { maxSizeMB = 10, allowedTypes } = options;
  const maxBytes = maxSizeMB * 1024 * 1024;

  if (file.size > maxBytes) {
    return { valid: false, error: `File size must be under ${maxSizeMB}MB` };
  }

  if (allowedTypes && allowedTypes.length > 0) {
    const isAllowed = allowedTypes.some((type) => {
      if (type.endsWith('/*')) {
        return file.type.startsWith(type.replace('/*', '/'));
      }
      return file.type === type;
    });
    if (!isAllowed) {
      return { valid: false, error: `File type ${file.type} is not allowed` };
    }
  }

  return { valid: true };
}

/**
 * Format file size for display
 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Build R2 path for different file types
 * 
 * Structure:
 * - contracts/collab-{id}/contract-{id}.pdf
 * - deliverables/collab-{id}/collab-{id}_reel-{n}_{version}.{ext}
 * - messages/collab-{id}/msg-{eventId}_attachment.{ext}
 */
export function buildR2Path(
  type: 'contract' | 'deliverable' | 'message',
  collaborationId: string,
  options: {
    contractId?: string;
    deliverableNumber?: number;
    version?: number;
    eventId?: string;
    filename?: string;
  } = {},
): string {
  switch (type) {
    case 'contract':
      return `contracts/collab-${collaborationId}/contract-${options.contractId}.pdf`;
    
    case 'deliverable': {
      const filename = options.filename || `reel-${options.deliverableNumber || 1}`;
      const version = options.version || 1;
      const ext = filename.includes('.') ? filename.split('.').pop() : 'mp4';
      return `deliverables/collab-${collaborationId}/collab-${collaborationId}_${filename}_v${version}.${ext}`;
    }
    
    case 'message':
      return `messages/collab-${collaborationId}/msg-${options.eventId}_attachment`;
    
    default:
      return `uploads/collab-${collaborationId}/${options.filename || 'file'}`;
  }
}

/**
 * Generate presigned URL for R2 object (mock)
 */
export function generatePresignedUrl(r2Path: string, expiresIn: number = 3600): string {
  return `https://r2.influora.com/${r2Path}?presign=${Date.now()}&exp=${expiresIn}`;
}

