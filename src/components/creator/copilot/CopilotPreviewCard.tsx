import * as React from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Sparkles } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { DURATION_NORMAL, EASE_OUT } from '@/lib/motion-config';

interface CopilotPreviewCardProps {
  className?: string;
}

/**
 * Pre-connect Co-pilot preview (creator-copilot.tsx, Ananya A2). The real
 * `DailySuggestionCard` is IG-gated end to end — `useDailySuggestion` never
 * fetches until `isConnected` (see its `enabled: isConnected` query option) —
 * but the model only actually needs a niche theme + a current trend, both
 * available pre-connect (AI review, wiki/ai-review/creator-copilot-content-
 * idea-library.md). Rather than wire a live no-auth preview call (out of
 * scope here), this renders one representative example idea so the value is
 * visible before a creator connects Instagram. Explicitly labelled "Preview"
 * throughout — this is illustrative copy, never presented as a real
 * suggestion, and it never replaces the Connect Instagram CTA
 * (`IGConnectPrompt`, rendered by `DailySuggestionSection` right below this).
 */
export function CopilotPreviewCard({ className }: CopilotPreviewCardProps) {
  const shouldReduceMotion = useReducedMotion();

  return (
    <motion.div
      initial={shouldReduceMotion ? {} : { opacity: 0, y: 12 }}
      animate={shouldReduceMotion ? {} : { opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, ease: EASE_OUT }}
      className={className}
    >
      <Card className="border-dashed">
        <CardContent className="space-y-3">
          <div className="flex items-center justify-between gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-md bg-muted px-2 py-1 text-xs font-medium text-muted-foreground">
              <Sparkles className="h-3.5 w-3.5" aria-hidden="true" />
              Preview
            </span>
            <Badge variant="secondary" className="text-[10px] font-normal">
              Skincare Routine
            </Badge>
          </div>

          <div>
            <p className="font-medium">Turn your morning routine into a 30-second reel</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Trending audio + quick cuts between each product step is performing well this
              week — pair it with a swipe-up to your favourite product.
            </p>
          </div>

          <p className="border-t border-border pt-3 text-xs text-muted-foreground">
            Preview — connect Instagram for ideas personalised to your audience.
          </p>
        </CardContent>
      </Card>
    </motion.div>
  );
}
