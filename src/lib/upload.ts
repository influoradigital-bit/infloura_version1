/**
 * Cloudflare R2 Upload Service
 *
 * Architecture:
 * - Files are uploaded to Cloudflare R2 via a presigned URL flow
 * - MySQL stores the R2 object URL + metadata (filename, size, mime, uploaded_by)
 * - On fetch, the app reads the URL from MySQL and loads from R2
 *
 * F-0461 — `uploadToR2` used to be a mock that fabricated a `https://r2.influora.com/...`
 * URL pointing at nothing, which then got persisted into `workspaces.logo_url`. It now goes
 * through `api.uploads.upload()` — the same `POST /uploads` public-upload path the creator
 * avatar upload already uses (see creator-profile.tsx's `handleAvatarFileSelected`) — with no
 * `purpose`, so the server returns a real, persistable R2 URL rather than a private preview URL.
 */

import { api } from './api';

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
 * Upload a file to Cloudflare R2, via `POST /uploads` (the same public-upload endpoint and
 * `api.uploads.upload()` client call the creator avatar upload already uses — see
 * creator-profile.tsx). Returns the real R2 URL the server stores the file at, so callers can
 * persist it (e.g. `workspaces.logo_url`) instead of a fabricated one.
 *
 * `folder` has no server-side equivalent on this endpoint (the server key-namespaces uploads
 * itself) and is accepted only so existing callers keep compiling; it is not sent.
 *
 * The endpoint has no progress-streaming equivalent (`api.uploads.upload` is a single
 * fetch/FormData POST, not XHR), so `onProgress` is reported at start (0%) and completion
 * (100%) rather than fabricating intermediate steps.
 */
export async function uploadToR2(
  file: File,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- kept for call-site compatibility, see doc comment above
  folder: string = 'uploads',
  onProgress?: ProgressCallback,
): Promise<UploadResult> {
  onProgress?.({ loaded: 0, total: file.size, percentage: 0 });

  const { url, key } = await api.uploads.upload(file, 'brand');

  onProgress?.({ loaded: file.size, total: file.size, percentage: 100 });

  return {
    success: true,
    url,
    key,
    filename: file.name,
    size: file.size,
    mimeType: file.type,
    uploadedAt: new Date().toISOString(),
  };
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

