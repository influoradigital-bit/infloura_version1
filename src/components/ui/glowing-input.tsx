import { useEffect, useId, useRef, useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { ArrowRight, Sparkles } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * Glowing prompt bar ("Ask Meera …"). Adapted from a community glowing-input component.
 *
 * Changes from the original, all deliberate:
 * - Layout-agnostic: no full-screen black wrapper; it fills its parent's width (max 760px).
 * - The side light trails sit inside an overflow-clipped frame and only show from md up, so the
 *   bar never causes sideways scrolling on a phone.
 * - Unique input id per instance (useId), a visible-to-screen-readers label prop, and Enter does
 *   NOT submit mid-composition (Hindi / other IME input), only when the word is committed.
 * - No console.log; `onSubmit` is the only output. Clears after submit unless `clearOnSubmit` is
 *   false. Respects prefers-reduced-motion.
 */
export interface GlowingInputProps {
  placeholder?: string;
  onSubmit?: (value: string) => void;
  /** Screen-reader label for the input. */
  label?: string;
  /** Accessible name of the send button. */
  submitLabel?: string;
  disabled?: boolean;
  clearOnSubmit?: boolean;
  maxLength?: number;
  className?: string;
}

// 60-30-10: the bar sits on the 30% stage; all glow is the 10% brand accent (#6d5ae6 / #b0a3f5).
const TRAIL = 'rgba(124,106,240,0.70), rgba(124,106,240,0.18), rgba(0,0,0,0)';

export function GlowingInput({
  placeholder = 'Ask Meera anything…',
  onSubmit,
  label = 'Ask Meera',
  submitLabel = 'Send',
  disabled = false,
  clearOnSubmit = true,
  maxLength = 2000,
  className,
}: GlowingInputProps) {
  const id = useId();
  const reduced = useReducedMotion();
  const [value, setValue] = useState('');
  const [isFocused, setIsFocused] = useState(false);
  const [isTyping, setIsTyping] = useState(false);
  const typingTimer = useRef<number | null>(null);

  const canSubmit = !disabled && value.trim().length > 0;

  const handleSubmit = () => {
    if (!canSubmit) return;
    onSubmit?.(value.trim());
    if (clearOnSubmit) setValue('');
  };

  useEffect(() => {
    return () => {
      if (typingTimer.current) window.clearTimeout(typingTimer.current);
    };
  }, []);

  return (
    <div className={cn('relative w-full max-w-[760px] overflow-x-clip py-6 md:overflow-visible', className)}>
      {/* Side light trails: md and up only */}
      <motion.div
        aria-hidden
        className="pointer-events-none absolute -left-80 top-1/2 hidden h-16 w-80 -translate-y-1/2 blur-2xl md:block"
        style={{ background: `linear-gradient(90deg, ${TRAIL})` }}
        animate={{ opacity: isFocused ? 0.85 : 0.45 }}
        transition={{ type: 'spring', stiffness: 80, damping: 20 }}
      />
      <motion.div
        aria-hidden
        className="pointer-events-none absolute -right-80 top-1/2 hidden h-16 w-80 -translate-y-1/2 blur-2xl md:block"
        style={{ background: `linear-gradient(270deg, ${TRAIL})` }}
        animate={{ opacity: isFocused ? 0.85 : 0.45 }}
        transition={{ type: 'spring', stiffness: 80, damping: 20, delay: 0.05 }}
      />

      <motion.div
        className="group relative flex w-full items-center rounded-full bg-gradient-to-r from-[#1a1629] to-[#241e3b] px-3 py-2 ring-1 ring-white/10 sm:px-5 sm:py-3 md:px-6 md:py-4"
        initial={{ boxShadow: '0 0 80px -30px rgba(124,106,240,0.55)' }}
        animate={{
          boxShadow: isFocused ? '0 0 140px -25px rgba(124,106,240,0.85)' : '0 0 90px -30px rgba(124,106,240,0.6)',
        }}
        whileHover={reduced ? undefined : { scale: 1.01 }}
      >
        <div aria-hidden className="pointer-events-none absolute inset-0 rounded-full ring-1 ring-white/5" />
        <div
          aria-hidden
          className="pointer-events-none absolute left-4 right-4 top-1 h-px bg-gradient-to-r from-transparent via-violet-300/60 to-transparent opacity-80"
        />

        <motion.div
          aria-hidden
          className="mr-2 grid h-9 w-9 shrink-0 place-items-center rounded-full bg-white/5 ring-1 ring-white/10 sm:mr-3 sm:h-10 sm:w-10"
          animate={{
            scale: isFocused && !reduced ? 1.05 : 1,
            filter: isFocused ? 'drop-shadow(0 0 10px rgba(176,163,245,0.7))' : 'none',
          }}
        >
          <Sparkles className="h-5 w-5 text-violet-300" />
        </motion.div>

        <label htmlFor={id} className="sr-only">
          {label}
        </label>
        <input
          id={id}
          type="text"
          value={value}
          maxLength={maxLength}
          disabled={disabled}
          onChange={(e) => {
            setValue(e.target.value);
            setIsTyping(true);
            if (typingTimer.current) window.clearTimeout(typingTimer.current);
            typingTimer.current = window.setTimeout(() => setIsTyping(false), 700);
          }}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          onKeyDown={(e) => {
            // Never submit mid-composition (Hindi / Devanagari IME): Enter there commits the word.
            if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
              e.preventDefault();
              handleSubmit();
            }
          }}
          placeholder={placeholder}
          className="min-w-0 flex-1 bg-transparent text-base text-slate-100 caret-violet-300 outline-none placeholder:text-slate-300/70 disabled:cursor-not-allowed sm:text-lg md:text-xl"
          autoComplete="off"
        />

        <motion.button
          type="button"
          aria-label={submitLabel}
          onClick={handleSubmit}
          disabled={!canSubmit}
          className="relative ml-2 grid h-10 w-10 shrink-0 cursor-pointer place-items-center rounded-full bg-primary text-primary-foreground shadow-lg ring-4 ring-primary/25 focus:outline-none focus-visible:ring-violet-200 disabled:cursor-not-allowed disabled:opacity-60 sm:h-12 sm:w-12"
          whileHover={canSubmit && !reduced ? { scale: 1.06 } : undefined}
          whileTap={canSubmit && !reduced ? { scale: 0.96 } : undefined}
          animate={{ boxShadow: canSubmit ? '0 0 40px rgba(124,106,240,0.5)' : '0 0 12px rgba(124,106,240,0.15)' }}
          transition={{ type: 'spring', stiffness: 260, damping: 16 }}
        >
          <motion.span
            className="grid"
            animate={isTyping && !reduced ? { x: [0, 6, 12] } : { x: 0 }}
            transition={isTyping && !reduced ? { duration: 0.8, repeat: Infinity, ease: 'easeIn' } : { duration: 0.2 }}
          >
            <ArrowRight className="h-5 w-5 sm:h-6 sm:w-6" aria-hidden />
          </motion.span>
          <span aria-hidden className="pointer-events-none absolute inset-0 rounded-full ring-1 ring-white/30" />
        </motion.button>

        <motion.div
          aria-hidden
          className="pointer-events-none absolute -left-2 top-1/2 h-12 w-12 -translate-y-1/2 rounded-full bg-primary/80 blur-xl"
          animate={{ opacity: isFocused ? 1 : 0.7 }}
        />
        <motion.div
          aria-hidden
          className="pointer-events-none absolute -right-2 top-1/2 h-12 w-12 -translate-y-1/2 rounded-full bg-primary/80 blur-xl"
          animate={{ opacity: isFocused ? 1 : 0.7 }}
        />
      </motion.div>
    </div>
  );
}

export default GlowingInput;
