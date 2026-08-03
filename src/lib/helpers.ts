/**
 * Common utility functions for the Brand application
 */

import { statusBadgeClass } from '@/lib/stage-colors';

/**
 * Format currency values
 */
export const formatCurrency = (amount: number, currency = 'USD'): string => {
  const formatter = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  });
  return formatter.format(amount);
};

/**
 * Format date to readable string
 */
export const formatDate = (date: Date | string): string => {
  const d = typeof date === 'string' ? new Date(date) : date;
  return d.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
};

/**
 * Format date to time ago (e.g., "2 hours ago")
 */
export const formatTimeAgo = (date: Date | string): string => {
  const d = typeof date === 'string' ? new Date(date) : date;
  const now = new Date();
  const seconds = Math.floor((now.getTime() - d.getTime()) / 1000);

  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`;

  return formatDate(d);
};

/**
 * Get initials from name
 */
export const getInitials = (name: string | null | undefined): string => {
  if (!name) return '';
  return name
    .split(' ')
    .filter(Boolean)
    .map((word) => word[0])
    .join('')
    .toUpperCase()
    .slice(0, 2);
};

/**
 * Validate email format
 */
export const isValidEmail = (email: string): boolean => {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return emailRegex.test(email);
};

/**
 * Validate strong password
 */
export const isStrongPassword = (password: string): boolean => {
  // At least 8 chars, 1 uppercase, 1 lowercase, 1 number
  const passwordRegex = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[a-zA-Z\d\w\W]{8,}$/;
  return passwordRegex.test(password);
};

/**
 * Generate random color for avatar
 */
export const getAvatarColor = (seed: string): string => {
  const colors = [
    'bg-stage-contracted text-stage-contracted-fg',
    'bg-stage-outreach text-stage-outreach-fg',
    'bg-stage-review text-stage-review-fg',
    'bg-stage-progress text-stage-progress-fg',
    'bg-stage-approved text-stage-approved-fg',
    'bg-stage-negotiating text-stage-negotiating-fg',
    'bg-stage-expired text-stage-expired-fg',
    'bg-primary/20 text-primary',
  ];

  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = seed.charCodeAt(i) + ((hash << 5) - hash);
  }

  return colors[Math.abs(hash) % colors.length];
};

/**
 * Truncate string with ellipsis
 */
export const truncate = (str: string, maxLength: number): string => {
  if (str.length <= maxLength) return str;
  return str.slice(0, maxLength) + '...';
};

/**
 * Debounce function
 */
export const debounce = <T extends (...args: never[]) => unknown>(
  func: T,
  wait: number,
): ((...args: Parameters<T>) => void) => {
  let timeout: NodeJS.Timeout;
  return (...args: Parameters<T>) => {
    clearTimeout(timeout);
    timeout = setTimeout(() => func(...args), wait);
  };
};

/**
 * Group array by property
 */
export const groupBy = <T>(array: T[], key: keyof T): Record<string, T[]> => {
  return array.reduce(
    (result, item) => {
      const groupKey = String(item[key]);
      if (!result[groupKey]) {
        result[groupKey] = [];
      }
      result[groupKey].push(item);
      return result;
    },
    {} as Record<string, T[]>,
  );
};

/**
 * Safely parse JSON
 */
export const safeJsonParse = <T>(json: string, fallback: T): T => {
  try {
    return JSON.parse(json);
  } catch {
    return fallback;
  }
};

/**
 * Get status badge color
 */
export const getStatusColor = (
  status: string,
): {
  bg: string;
  text: string;
  border: string;
} => {
  const badge = statusBadgeClass(status);
  const parts = badge.split(' ');
  return {
    bg: parts.find((p) => p.startsWith('bg-')) ?? 'bg-stage-draft',
    text: parts.find((p) => p.startsWith('text-')) ?? 'text-stage-draft-fg',
    border: parts.find((p) => p.startsWith('border-')) ?? 'border-stage-draft-border',
  };
};

/**
 * Calculate read time (in minutes)
 */
export const calculateReadTime = (text: string): number => {
  const wordsPerMinute = 200;
  const wordCount = text.split(/\s+/).length;
  return Math.ceil(wordCount / wordsPerMinute);
};

/**
 * Format phone number
 */
export const formatPhoneNumber = (phone: string): string => {
  const cleaned = phone.replace(/\D/g, '');
  if (cleaned.length === 10) {
    return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(6)}`;
  }
  return phone;
};

/**
 * Check if URL is valid
 */
export const isValidUrl = (url: string): boolean => {
  try {
    new URL(url);
    return true;
  } catch {
    return false;
  }
};

/**
 * Get platform from URL
 */
export const getPlatformFromUrl = (url: string): string => {
  if (url.includes('instagram')) return 'instagram';
  if (url.includes('tiktok')) return 'tiktok';
  if (url.includes('youtube')) return 'youtube';
  if (url.includes('twitter') || url.includes('x.com')) return 'twitter';
  if (url.includes('linkedin')) return 'linkedin';
  if (url.includes('twitch')) return 'twitch';
  return 'other';
};

export default {
  formatCurrency,
  formatDate,
  formatTimeAgo,
  getInitials,
  isValidEmail,
  isStrongPassword,
  getAvatarColor,
  truncate,
  debounce,
  groupBy,
  safeJsonParse,
  getStatusColor,
  calculateReadTime,
  formatPhoneNumber,
  isValidUrl,
  getPlatformFromUrl,
};
