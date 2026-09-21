import { describe, expect, it } from 'vitest';
import { recentHistory, MEERA_HISTORY_MAX_TURNS, type HistoryTurn } from './meera-history';

const turn = (i: number, size = 10): HistoryTurn => ({
  role: i % 2 === 0 ? 'user' : 'assistant',
  content: String(i).padEnd(size, '.'),
});

describe('recentHistory (EV-044: never trips the server history limit)', () => {
  it('keeps a short conversation whole', () => {
    const h = [turn(0), turn(1), turn(2)];
    expect(recentHistory(h)).toEqual(h);
  });

  it('a 100-message resumed chat is cut to the newest turns, under the server limit of 40', () => {
    const h = Array.from({ length: 101 }, (_, i) => turn(i));
    const out = recentHistory(h);
    expect(out.length).toBeLessThanOrEqual(MEERA_HISTORY_MAX_TURNS);
    expect(out.length).toBeLessThan(40);
    expect(out[out.length - 1]).toEqual(h[100]); // the message being sent
    expect(out[0].role).toBe('user');
  });

  it('respects the character budget, newest first, under the server limit of 60,000', () => {
    const h = Array.from({ length: 20 }, (_, i) => turn(i, 5_000));
    const out = recentHistory(h);
    const chars = out.reduce((n, t) => n + t.content.length, 0);
    expect(chars).toBeLessThan(60_000);
    expect(out[out.length - 1]).toEqual(h[19]);
  });

  it('always keeps the latest message even if it alone is huge', () => {
    const big: HistoryTurn = { role: 'user', content: 'x'.repeat(70_000) };
    expect(recentHistory([turn(0), turn(1), big])).toEqual([big]);
  });
});
