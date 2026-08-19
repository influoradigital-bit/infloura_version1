import type { ReactElement } from 'react';

import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion';
import { FadeUp } from '@/components/motion';
import { JsonLd, getFaqPageSchema, type FaqItem } from '@/lib/seo/schema';

/**
 * FAQ block that renders the visible accordion AND the FAQPage JSON-LD from the
 * SAME array.
 *
 * WHY THIS EXISTS: before this, a page that wanted rich-result-eligible FAQs had
 * to hand-write `getFaqPageSchema(FAQS)` next to a hand-written `<Accordion>`
 * loop over the same constant, as the Secure Payments page used to. That worked
 * only for as long as nobody edited one and not the other — and a structured-
 * data payload that no longer matches the rendered text is worse than none at
 * all: Google drops the rich result and flags the page for mismatch, and answer
 * engines that quoted the schema answer end up quoting copy the site no longer
 * shows. Passing one `items` array through one component makes that class of
 * drift unrepresentable.
 *
 * ANSWER SHAPE: each `answer` must be a complete, standalone sentence or two —
 * 40-60 words is the citation sweet spot. ChatGPT, Perplexity and AI Overviews
 * quote the answer *without* the question and without the surrounding page, so
 * an answer that begins "It does, because..." is unusable to them even though
 * it reads fine in the accordion.
 */
export interface FaqSectionProps {
  /** Section heading. Keep it question-shaped where possible. */
  heading: string;
  /** Optional line under the heading. */
  intro?: string;
  items: FaqItem[];
  /**
   * Set false on a page that already emits FAQPage JSON-LD elsewhere, or that
   * renders two FaqSections — two FAQPage blocks on one URL is a validation
   * error, not a doubling of coverage.
   */
  emitSchema?: boolean;
  className?: string;
}

export function FaqSection({
  heading,
  intro,
  items,
  emitSchema = true,
  className = 'border-t border-border/60 py-20',
}: FaqSectionProps): ReactElement {
  return (
    <section className={className} aria-label="Frequently asked questions">
      {emitSchema && <JsonLd data={getFaqPageSchema(items)} />}
      <div className="mx-auto max-w-3xl px-6">
        <FadeUp className="text-center">
          <h2 className="text-3xl font-semibold">{heading}</h2>
          {intro && <p className="mt-3 text-muted-foreground">{intro}</p>}
        </FadeUp>
        <FadeUp delay={0.1} className="mt-10">
          <Accordion type="single" collapsible className="w-full">
            {items.map((faq) => (
              <AccordionItem key={faq.question} value={faq.question}>
                <AccordionTrigger className="text-left text-base font-medium">
                  {faq.question}
                </AccordionTrigger>
                {/*
                  `data-speakable` marks this as a passage a voice/AI surface
                  should read back verbatim — see getWebPageSchema's speakable
                  selectors in src/lib/seo/schema.ts.
                */}
                <AccordionContent className="text-muted-foreground" data-speakable>
                  {faq.answer}
                </AccordionContent>
              </AccordionItem>
            ))}
          </Accordion>
        </FadeUp>
      </div>
    </section>
  );
}
