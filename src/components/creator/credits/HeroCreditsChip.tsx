import { useState } from 'react';
import { BuyCreditsSheet } from '@/components/creator/credits/BuyCreditsSheet';
import { CreditBalancePill } from '@/components/creator/credits/CreditBalancePill';
import { useCreatorCredits } from '@/hooks/useCreatorCredits';

/**
 * The credit balance on the Meera hero (Co-pilot page), so a creator sees it BEFORE opening the
 * chat. Tapping it opens the same "Top up your credits" sheet as the chat header pill. Renders
 * nothing while CREATOR_CREDITS_ENABLED is off (the server answers `{enabled:false}`).
 */
export function HeroCreditsChip({ language }: { language?: string }) {
  const credits = useCreatorCredits();
  const [open, setOpen] = useState(false);
  if (!credits.enabled || !credits.balance) return null;
  return (
    <>
      <CreditBalancePill balance={credits.balance} language={language} onClick={() => setOpen(true)} />
      <BuyCreditsSheet
        open={open}
        onOpenChange={setOpen}
        language={language}
        balance={credits.balance}
        onCredited={() => void credits.refresh()}
      />
    </>
  );
}

export default HeroCreditsChip;
