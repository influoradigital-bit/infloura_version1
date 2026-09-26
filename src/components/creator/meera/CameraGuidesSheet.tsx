import * as React from 'react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Switch } from '@/components/ui/switch';
import { adviceText, type ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import { CAMERA_GRIDS, DEFAULT_CAMERA_GRID, type CameraGrid } from '@/lib/shoot-check/guide-prefs';

/**
 * The camera's "Guides" sheet (spec v2 Phase 5a + 5b), opened from the Guides button in the
 * camera sheet's header:
 *   1. "Shot guides": the on/off switch for the shot bands and prop zone;
 *   2. "Camera grid": a radio group, Rule of thirds (Default) / Golden grid / Off;
 *   3. "Safe zone: Always on" as plain text with NO control, then SAFE_ZONE_NOTE and DEVICE_NOTE;
 *   4. "Your choice is remembered on this phone."
 *
 * A labelled Radix dialog nested in the camera's own dialog (Esc closes only this one). Focus goes
 * back to the Guides button on close, whichever way it closes. Storage is the caller's job
 * (`guide-prefs.ts`); this sheet only reports the choice.
 */
export interface CameraGuidesSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lang: ShootCheckLang;
  shotGuides: boolean;
  onShotGuidesChange: (on: boolean) => void;
  grid: CameraGrid;
  onGridChange: (grid: CameraGrid) => void;
  /** The Guides button: focus returns here when the sheet closes. */
  returnFocusRef: React.RefObject<HTMLElement | null>;
}

const GRID_LABEL_KEY: Record<CameraGrid, string> = {
  thirds: 'grid_option_thirds',
  golden: 'grid_option_golden',
  off: 'grid_option_off',
};

// Chrome that is not safe-zone or grid copy, so it stays with the sheet like the camera sheet's own.
// hi-IN pending review, like the advice-copy Hindi lines.
const COPY = {
  'en-IN': { done: 'Done' },
  'hi-IN': { done: 'ठीक है' },
} as const;

export function CameraGuidesSheet({
  open,
  onOpenChange,
  lang,
  shotGuides,
  onShotGuidesChange,
  grid,
  onGridChange,
  returnFocusRef,
}: CameraGuidesSheetProps) {
  const ids = React.useId();
  const switchId = `${ids}-shot-guides`;
  const gridLabelId = `${ids}-grid-label`;
  const t = (key: string) => adviceText(key, lang);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        showCloseButton={false}
        data-testid="camera-guides-sheet"
        className="max-h-[90dvh] gap-5 overflow-y-auto sm:max-w-sm"
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          returnFocusRef.current?.focus();
        }}
      >
        <DialogTitle>{t('guides_button')}</DialogTitle>
        <DialogDescription className="sr-only">{t('guide_remembered')}</DialogDescription>

        <div className="flex min-h-11 items-center justify-between gap-4">
          <label htmlFor={switchId} className="text-sm font-medium">
            {t('shot_guides_label')}
          </label>
          <Switch id={switchId} checked={shotGuides} onCheckedChange={onShotGuidesChange} data-testid="guides-shot-switch" />
        </div>

        <div className="flex flex-col gap-3">
          <p id={gridLabelId} className="text-sm font-medium">
            {t('grid_setting_label')}
          </p>
          <RadioGroup
            aria-labelledby={gridLabelId}
            value={grid}
            onValueChange={(value) => {
              if ((CAMERA_GRIDS as readonly string[]).includes(value)) onGridChange(value as CameraGrid);
            }}
            className="gap-1"
          >
            {CAMERA_GRIDS.map((option) => {
              const itemId = `${ids}-grid-${option}`;
              return (
                <label key={option} htmlFor={itemId} className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
                  <RadioGroupItem id={itemId} value={option} />
                  <span>{t(GRID_LABEL_KEY[option])}</span>
                  {option === DEFAULT_CAMERA_GRID ? (
                    <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">{t('grid_default_tag')}</span>
                  ) : null}
                </label>
              );
            })}
          </RadioGroup>
        </div>

        <div className="flex flex-col gap-2 text-sm" data-testid="guides-safe-zone">
          <p className="font-medium">{t('safe_zone_always_on')}</p>
          <p className="text-muted-foreground">{t('safe_zone_note')}</p>
          <p className="text-muted-foreground">{t('device_note')}</p>
        </div>

        <p className="text-xs text-muted-foreground">{t('guide_remembered')}</p>

        <DialogClose asChild>
          <Button type="button" className="min-h-11 w-full">
            {COPY[lang].done}
          </Button>
        </DialogClose>
      </DialogContent>
    </Dialog>
  );
}
