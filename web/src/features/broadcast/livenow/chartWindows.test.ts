import { describe, expect, it } from 'vitest';
import { COLLECTOR_WINDOW_MS, chartWindows } from './useLiveMockState';

// 수집기는 채팅·차트를 한 번에 1시간까지만 준다(window-max PT1H, 넘으면 400 too_wide — PR #200 codex)
describe('chartWindows', () => {
  it('2시간 반을 1시간·1시간·30분으로 자른다 — 빈틈도 겹침도 없다', () => {
    const from = 1_000_000;
    const to = from + 2.5 * COLLECTOR_WINDOW_MS;
    const windows = chartWindows(from, to);
    expect(windows).toEqual([
      [from, from + COLLECTOR_WINDOW_MS],
      [from + COLLECTOR_WINDOW_MS, from + 2 * COLLECTOR_WINDOW_MS],
      [from + 2 * COLLECTOR_WINDOW_MS, to],
    ]);
    for (const [a, b] of windows) expect(b - a).toBeLessThanOrEqual(COLLECTOR_WINDOW_MS);
  });

  it('1시간 이하면 창 하나다', () => {
    expect(chartWindows(0, 30 * 60_000)).toEqual([[0, 30 * 60_000]]);
  });
});
