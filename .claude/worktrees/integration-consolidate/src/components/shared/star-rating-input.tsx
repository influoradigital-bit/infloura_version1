import * as React from 'react';
import { Star } from 'lucide-react';
import { useReducedMotion } from 'framer-motion';

import { cn } from '@/lib/utils';

interface StarRatingInputProps {
  value: number;
  onChange: (stars: number) => void;
  disabled?: boolean;
  label?: string;
  id?: string;
}

export function StarRatingInput({
  value,
  onChange,
  disabled = false,
  label = 'Rating',
  id = 'star-rating',
}: StarRatingInputProps) {
  const reduceMotion = useReducedMotion();
  const [hover, setHover] = React.useState(0);

  return (
    <div>
      <span id={id} className="sr-only">
        {label}
      </span>
      <div
        role="radiogroup"
        aria-labelledby={id}
        className="flex items-center gap-1"
        onMouseLeave={() => setHover(0)}
      >
        {[1, 2, 3, 4, 5].map((star) => {
          const active = star <= (hover || value);
          return (
            <button
              key={star}
              type="button"
              role="radio"
              aria-checked={value === star}
              aria-label={`${star} star${star === 1 ? '' : 's'}`}
              disabled={disabled}
              className={cn(
                'rounded p-0.5 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                disabled && 'cursor-not-allowed opacity-50',
                !reduceMotion && 'hover:scale-105 active:scale-95',
              )}
              onMouseEnter={() => !disabled && setHover(star)}
              onClick={() => !disabled && onChange(star)}
            >
              <Star
                className={cn(
                  'h-6 w-6',
                  active ? 'fill-amber-400 text-amber-400' : 'text-muted-foreground/40',
                )}
              />
            </button>
          );
        })}
      </div>
    </div>
  );
}
