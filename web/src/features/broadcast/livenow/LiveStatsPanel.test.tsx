import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import styles from './LiveScreen.module.css';
import { LiveStatsPanel } from './LiveStatsPanel';
import type { Point } from './statsTimeline';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('LiveStatsPanel: 시간 눈금', () => {
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

describe('LiveStatsPanel: 하이라이트 점', () => {
  it('같은 칸에 든 카드 둘의 점은 좌표가 바뀌어도 쌓이지 않는다', () => {
    // 카드는 차트 칸 자리에 찍혀 같은 칸의 카드 둘은 좌표가 같다. 좌표로 key를 만들면 key가 겹쳐 옛 점이 화면에
    // 남는다: 차트를 다시 읽을 때마다 유령 점이 는다(리뷰 2라운드)
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const panel = (markers: Point[]) => (
      <LiveStatsPanel
        chatVolume={{ points: [], markers, timeLabels: ['', '', '', ''] }}
        viewerLine={[]}
        donations={[]}
        categorySegments={[]}
        metrics={[]}
        highlightSummary={{ total: 3, auto: 3, manual: 0 }}
      />
    );
    const { container, rerender } = render(
      panel([
        [400, 50],
        [400, 50],
        [600, 40],
      ]),
    );
    rerender(
      panel([
        [410, 52],
        [410, 52],
        [610, 42],
      ]),
    );
    rerender(
      panel([
        [420, 54],
        [420, 54],
        [620, 44],
      ]),
    );

    expect(container.querySelectorAll(`.${styles.statsHighlight}`)).toHaveLength(3);
  });
});
