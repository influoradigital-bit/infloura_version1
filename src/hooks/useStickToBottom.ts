import * as React from 'react';

/**
 * Photo check in Meera's chat, SPEC section 3b "Scroll that respects the reader".
 *
 * The chat used to scroll to the bottom on EVERY change to its messages, smoothly, on every
 * streamed token. A creator who had scrolled up to re-read a script was yanked back down, and an
 * in-place edit on an old card ("Show as text") jumped to the bottom as well.
 *
 * This hook follows new content only when the reader was already near the bottom (within
 * `threshold` px, 120 by default), or when the caller asked for it with `forceScroll()` (the
 * creator sent a message, a photo check started, the first history load). Otherwise it leaves the
 * reader where they are and raises `showPill`, for a "New messages" button that jumps down.
 *
 * `contentKey` is what counts as "new content": the caller builds it from the NEWEST row (its id,
 * its text length, its tool cards) plus anything else that grows the bottom of the list. An edit
 * to an older row, a prepended page of older messages or a card toggle does not change it, so it
 * never scrolls. Following uses `behavior: 'auto'` (a smooth scroll per token lags and fights the
 * reader); only a forced jump is smooth, and never under reduced motion.
 */
export interface StickToBottom {
  /** True while the reader is within the threshold of the bottom (tracked on scroll). */
  atBottom: boolean;
  /** True when new content arrived while the reader was scrolled up. */
  showPill: boolean;
  /** Ask for the next content change to scroll to the bottom whatever the reader's position. */
  forceScroll: () => void;
  /** Jump to the bottom now (the pill's click), smooth unless reduced motion. */
  scrollToBottom: () => void;
  /** Wire to the scroll container's `onScroll`. */
  onScroll: () => void;
}

export interface StickToBottomOptions {
  contentKey: string;
  reduceMotion?: boolean | null;
  threshold?: number;
}

export const STICK_TO_BOTTOM_THRESHOLD_PX = 120;

function distanceFromBottom(el: HTMLElement): number {
  return el.scrollHeight - el.scrollTop - el.clientHeight;
}

const SMOOTH_JUMP_MS = 700;

function scrollElToBottom(el: HTMLElement, behavior: ScrollBehavior): void {
  // jsdom (the test DOM) has no scrollTo; an unguarded call would throw inside the effect.
  if (typeof el.scrollTo === 'function') {
    el.scrollTo({ top: el.scrollHeight, behavior });
  } else {
    el.scrollTop = el.scrollHeight;
  }
}

export function useStickToBottom(
  scrollRef: React.RefObject<HTMLElement | null>,
  { contentKey, reduceMotion = false, threshold = STICK_TO_BOTTOM_THRESHOLD_PX }: StickToBottomOptions,
): StickToBottom {
  const atBottomRef = React.useRef(true);
  const forceRef = React.useRef(false);
  const [atBottom, setAtBottom] = React.useState(true);
  const [showPill, setShowPill] = React.useState(false);
  const lastKeyRef = React.useRef<string | null>(null);
  // While our own smooth jump is animating DOWN, its intermediate scroll events are not the
  // reader leaving the bottom; without this a token landing mid-animation would raise the pill
  // instead of following. A move UP is always the reader, and ends the grace period at once.
  const smoothUntilRef = React.useRef(0);
  const lastTopRef = React.useRef(0);

  const onScroll = React.useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const top = el.scrollTop;
    const movingDown = top >= lastTopRef.current;
    lastTopRef.current = top;
    const near = distanceFromBottom(el) <= threshold;
    if (!near && movingDown && Date.now() < smoothUntilRef.current) return;
    if (!movingDown) smoothUntilRef.current = 0;
    atBottomRef.current = near;
    setAtBottom(near);
    if (near) setShowPill(false);
  }, [scrollRef, threshold]);

  const forceScroll = React.useCallback(() => {
    forceRef.current = true;
  }, []);

  const scrollToBottom = React.useCallback(() => {
    const el = scrollRef.current;
    setShowPill(false);
    atBottomRef.current = true;
    setAtBottom(true);
    if (!el) return;
    if (!reduceMotion) smoothUntilRef.current = Date.now() + SMOOTH_JUMP_MS;
    scrollElToBottom(el, reduceMotion ? 'auto' : 'smooth');
    lastTopRef.current = el.scrollTop;
  }, [scrollRef, reduceMotion]);

  // A layout effect, so a followed token is scrolled into view before the browser paints it
  // below the fold (no one-frame flicker at the bottom edge).
  React.useLayoutEffect(() => {
    if (lastKeyRef.current === contentKey) return;
    const first = lastKeyRef.current === null;
    lastKeyRef.current = contentKey;
    const el = scrollRef.current;
    if (!el) return;
    if (forceRef.current) {
      forceRef.current = false;
      atBottomRef.current = true;
      setAtBottom(true);
      setShowPill(false);
      if (!reduceMotion) smoothUntilRef.current = Date.now() + SMOOTH_JUMP_MS;
      scrollElToBottom(el, reduceMotion ? 'auto' : 'smooth');
      lastTopRef.current = el.scrollTop;
      return;
    }
    if (first) return;
    if (atBottomRef.current) {
      scrollElToBottom(el, 'auto');
      lastTopRef.current = el.scrollTop;
      return;
    }
    setShowPill(true);
  }, [contentKey, scrollRef, reduceMotion]);

  return { atBottom, showPill, forceScroll, scrollToBottom, onScroll };
}
