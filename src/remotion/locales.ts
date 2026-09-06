import { HI_LOCALE, type LangCode, type Locale } from './script';
import { EN_LOCALE } from './script.en';
import { MR_LOCALE } from './script.mr';

/** Every language the demo ships in. Order = order of the page's language switch. */
export const LOCALES: Record<LangCode, Locale> = {
  hi: HI_LOCALE,
  en: EN_LOCALE,
  mr: MR_LOCALE,
};

export const LANG_ORDER: LangCode[] = ['hi', 'en', 'mr'];
