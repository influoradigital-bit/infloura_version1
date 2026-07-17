import * as React from 'react';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import { X, MessagesSquare, FileSignature, Package } from 'lucide-react';

import { Button } from '@/components/ui/button';

const STORAGE_KEY = 'brand_deal_room_explainer_seen';

/**
 * First-visit explainer for the Deal Room surface (/brand/chat). Answers the
 * audit's "terminology confusion" gap: brands didn't know chat/contract/
 * deliverables live in one place per collaboration. Dismissible, gated on
 * localStorage so it only fires once per browser (matches the
 * brand_tour_seen / brand_onboarding_complete convention used elsewhere).
 */
export function DealRoomExplainer() {
  const shouldReduceMotion = useReducedMotion();
  const [dismissed, setDismissed] = React.useState(true);

  React.useEffect(() => {
    const seen = localStorage.getItem(STORAGE_KEY) === 'true';
    setDismissed(seen);
  }, []);

  const handleDismiss = () => {
    localStorage.setItem(STORAGE_KEY, 'true');
    setDismissed(true);
  };

  return (
    <AnimatePresence>
      {!dismissed && (
        <motion.div
          initial={shouldReduceMotion ? {} : { opacity: 0, y: -8 }}
          animate={shouldReduceMotion ? {} : { opacity: 1, y: 0 }}
          exit={shouldReduceMotion ? {} : { opacity: 0, y: -8 }}
          transition={{ duration: 0.2, ease: 'easeOut' }}
          className="relative m-3 mb-0 rounded-xl border border-primary/20 bg-primary/5 p-4"
          role="note"
          aria-label="What is a Deal Room"
        >
          <button
            type="button"
            onClick={handleDismiss}
            aria-label="Dismiss explainer"
            className="absolute right-2.5 top-2.5 rounded-md p-1 text-muted-foreground hover:bg-primary/10 hover:text-foreground transition-colors"
          >
            <X className="h-4 w-4" />
          </button>

          <p className="pr-6 text-sm font-semibold text-foreground">
            This is your Deal Room
          </p>
          <p className="mt-1 pr-6 text-sm text-muted-foreground">
            Everything for a collaboration lives here — chat, contract, and
            deliverables. Each accepted campaign or direct invite gets its own
            room.
          </p>

          <div className="mt-3 flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
            <span className="flex items-center gap-1.5">
              <MessagesSquare className="h-3.5 w-3.5 text-primary" />
              Chat
            </span>
            <span className="flex items-center gap-1.5">
              <FileSignature className="h-3.5 w-3.5 text-primary" />
              Contract
            </span>
            <span className="flex items-center gap-1.5">
              <Package className="h-3.5 w-3.5 text-primary" />
              Deliverables
            </span>
          </div>

          <Button
            variant="ghost"
            size="sm"
            onClick={handleDismiss}
            className="mt-2 h-7 px-2 text-xs text-primary hover:text-primary"
          >
            Got it
          </Button>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
