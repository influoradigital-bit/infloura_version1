/**
 * Meera's light markdown, cleaned for display and for parsing (2026-09-24).
 *
 * Run: npx vitest run src/lib/meera-text.test.ts
 */
import { render } from '@testing-library/react';
import * as React from 'react';
import { describe, expect, it } from 'vitest';

import { renderMeeraText, stripMeeraMarkdown } from './meera-text';

describe('stripMeeraMarkdown', () => {
  it('unwraps **bold** and __bold__, drops divider lines and heading markers', () => {
    const text = '## Plan\r\n**Idea:** Morning routine\n---\n***\n__Caption:__ Try it';
    expect(stripMeeraMarkdown(text)).toBe('Plan\nIdea: Morning routine\nCaption: Try it');
  });

  it('leaves plain text, single asterisks and hyphenated words alone', () => {
    const text = 'Rate: 4.5 * 2 - well-known tip\n-- not a divider';
    expect(stripMeeraMarkdown(text)).toBe(text);
  });
});

describe('renderMeeraText', () => {
  it('renders **bold** as <strong> and keeps line breaks', () => {
    const { container } = render(
      React.createElement('div', null, ...renderMeeraText('Best time: **weekend**\n---\nnext line')),
    );
    expect(container.querySelector('strong')?.textContent).toBe('weekend');
    expect(container.textContent).toBe('Best time: weekend\nnext line');
  });
});
