import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LiveStatsPanel } from './LiveStatsPanel';

describe('LiveStatsPanel: 시간 눈금', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('짧은 지난 방송처럼 눈금 글자가 겹쳐도 React key가 겹치지 않는다', () => {
    // 2분짜리 방송은 시:분 눈금이 「14:00 · 14:01 · 14:02 · 14:02」가 된다(리뷰). 글자를 key로 쓰면 같은 key 경고가 난다
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(
      <LiveStatsPanel
        chatVolume={{ points: [], markers: [], timeLabels: ['14:00', '14:01', '14:02', '14:02'] }}
        viewerLine={[]}
        donations={[]}
        categorySegments={[]}
        metrics={[]}
        highlightSummary={{ total: 0, auto: 0, manual: 0 }}
        ended
      />,
    );
    expect(errors.mock.calls.some((args) => String(args[0]).includes('same key'))).toBe(false);
  });
});
