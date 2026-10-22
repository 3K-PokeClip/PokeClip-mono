import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveStatsPanel, type TimelineCard } from './LiveStatsPanel';
import type { StatsChart } from './statsChart';
import type { LiveStatsMockState } from './useLiveStatsMockState';

const T0 = Date.parse('2026-09-26T19:30:00+09:00');

/** 30초 칸 n개. 칸 i의 채팅은 20 + (i % 7), 후원은 50칸마다 하나 */
function chart(n: number): StatsChart {
  return {
    t0: T0,
    t1: T0 + n * 30_000,
    bucketSeconds: 30,
    buckets: Array.from({ length: n }, (_, i) => ({
      start: T0 + i * 30_000,
      chats: 20 + (i % 7),
      donations: i % 50 === 0 ? 1 : 0,
    })),
  };
}

function stats(over: Partial<LiveStatsMockState> = {}): LiveStatsMockState {
  const c = over.chart === undefined ? chart(268) : over.chart;
  return {
    chart: c,
    scope: c ? 'broadcast' : null,
    viewers: c
      ? Array.from({ length: 30 }, (_, k) => ({ t: T0 + k * 4 * 60_000, v: 1000 + k * 10 }))
      : [],
    categories: c ? [{ t: c.t0, name: '리그 오브 레전드' }] : [],
    chat: { label: '분당 평균 채팅', value: '45.0', sub: '방송 중 채팅 6,030개' },
    viewerCell: { label: '시청자', value: '1,290', sub: '최고 1,290 · 평균 1,145' },
    donation: { label: '후원', value: '6회', sub: '방송 전체 · 마지막 21:40' },
    lastViewers: 1290,
    noViewers: '수집 전',
    ...over,
  };
}

const CARDS: TimelineCard[] = [
  {
    id: 'card-12',
    atMs: T0 + 20 * 60_000,
    timestamp: '0:20:00',
    label: '#12',
    manual: false,
    reason: '채팅 ×3.2 급증 (120건)',
  },
  {
    id: 'card-15',
    atMs: T0 + 70 * 60_000,
    timestamp: '1:10:00',
    label: '수동',
    manual: true,
    reason: '수동 마킹',
  },
];

function panel(over: Partial<Parameters<typeof LiveStatsPanel>[0]> = {}) {
  const onSeekCard = vi.fn();
  const props = {
    stats: stats(),
    cards: CARDS,
    highlightSummary: { total: 2, auto: 1, manual: 1 },
    live: true,
    ended: false,
    onSeekCard,
    ...over,
  };
  return { ...render(<LiveStatsPanel {...props} />), onSeekCard, props };
}

beforeEach(() => {
  // jsdom은 폭이 0이다 — 그래프는 폭을 재고서야 그린다. 넓은 화면의 패널 안쪽 폭으로 둔다
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
    width: 1092,
    height: 300,
    top: 0,
    left: 0,
    right: 1092,
    bottom: 300,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  } as DOMRect);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('LiveStatsPanel: 숫자 줄', () => {
  it('분당 평균 채팅·시청자·하이라이트·후원을 이름과 값으로 세운다', () => {
    panel();
    const region = screen.getByRole('region', { name: '실시간 통계' });
    const value = (label: string) =>
      within(region).getByText(label, { selector: 'dt' }).nextElementSibling;
    expect(value('분당 평균 채팅')).toHaveTextContent('45.0');
    expect(value('시청자')).toHaveTextContent('1,290');
    expect(value('하이라이트')).toHaveTextContent('2');
    expect(within(region).getByText('자동 1 · 수동 1')).toBeInTheDocument();
    expect(value('후원')).toHaveTextContent('6회');
  });

  it('끝난 방송은 「방송 통계」이고 그래프 끝은 「지금」이 아니다', () => {
    panel({ live: false, ended: true });
    expect(screen.getByRole('region', { name: '방송 통계' })).toBeInTheDocument();
    expect(screen.queryByText('지금')).toBeNull();
  });

  it('차트를 받기 전에는 막대를 지어내지 않고 「그래프를 불러오고 있어요」다', () => {
    panel({ stats: stats({ chart: null }) });
    expect(screen.getByText('그래프를 불러오고 있어요')).toBeInTheDocument();
    expect(screen.queryByRole('group')).toBeNull();
  });
});

describe('LiveStatsPanel: 그래프', () => {
  it('타임라인이 하이라이트 수와 후원 수를 읽어 준다', () => {
    panel();
    expect(
      screen.getByRole('group', {
        name: /^방송 타임라인 — 채팅·시청자, 하이라이트 2곳, 후원 6회/,
      }),
    ).toBeInTheDocument();
  });

  it('하이라이트 핀을 누르면 그 카드 시점으로 간다 — 자동은 카드 번호, 수동은 「수동」', () => {
    const { onSeekCard } = panel();
    fireEvent.click(screen.getByRole('button', { name: /^하이라이트 #12 — 채팅 ×3\.2 급증/ }));
    expect(onSeekCard).toHaveBeenCalledWith('0:20:00');
    const manual = screen.getByRole('button', { name: /^하이라이트 수동 — 수동 마킹/ });
    expect(manual).toHaveTextContent('수동');
    fireEvent.click(manual);
    expect(onSeekCard).toHaveBeenLastCalledWith('1:10:00');
  });

  it('막대 아래 후원 줄은 없다: 「후원」은 숫자 줄 한 칸뿐이다', () => {
    panel();
    expect(screen.getAllByText('후원')).toHaveLength(1);
    expect(screen.getByText('카테고리')).toBeInTheDocument();
  });

  it('←/→로 칸을 옮기면 그 칸의 채팅·시청자를 읽어 주고, 카테고리는 툴팁에 넣지 않는다', () => {
    panel({
      stats: stats({
        categories: [
          { t: T0, name: '저스트 채팅' },
          { t: T0 + 38 * 60_000, name: '리그 오브 레전드' },
        ],
      }),
    });
    fireEvent.keyDown(screen.getByRole('group'), { key: 'Home' });
    const tip = screen.getByRole('status');
    expect(tip).toHaveTextContent('채팅 (2분)');
    expect(tip).toHaveTextContent('시청자');
    expect(tip).not.toHaveTextContent('카테고리');
    expect(tip).not.toHaveTextContent('저스트 채팅');
  });

  it('카테고리가 바뀌면 구간마다 띠가 나뉜다', () => {
    panel({
      stats: stats({
        categories: [
          { t: T0, name: '저스트 채팅' },
          { t: T0 + 38 * 60_000, name: '리그 오브 레전드' },
        ],
      }),
    });
    expect(screen.getByTitle('저스트 채팅 · 38분')).toBeInTheDocument();
    expect(screen.getByTitle('리그 오브 레전드 · 1시간 36분')).toBeInTheDocument();
  });

  it('긴 방송은 가로 스크롤이고, 방송 중이면 끝 단추가 「지금」, 끝난 방송이면 「끝」이다', () => {
    const long = stats({ chart: chart(504) });
    const { unmount } = panel({ stats: long });
    expect(screen.getByRole('button', { name: /처음/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /지금/ })).toBeInTheDocument();
    unmount();
    panel({ stats: long, live: false, ended: true });
    expect(screen.getByRole('button', { name: /끝/ })).toBeInTheDocument();
  });

  it('한 화면에 들어가는 방송은 스크롤 단추가 없다', () => {
    panel();
    expect(screen.queryByRole('button', { name: /처음/ })).toBeNull();
  });
});

describe('LiveStatsPanel: 열쇠', () => {
  it('짧은 지난 방송에서도 시간 눈금의 React key가 겹치지 않는다', () => {
    // 2분짜리 방송은 시:분 눈금 글자가 겹친다(리뷰). 글자를 key로 쓰면 같은 key 경고가 난다
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    panel({ stats: stats({ chart: chart(4) }), live: false, ended: true, cards: [] });
    expect(errors.mock.calls.some((args) => String(args[0]).includes('same key'))).toBe(false);
  });

  it('같은 칸에 든 카드 둘의 핀은 카드가 다시 와도 쌓이지 않는다', () => {
    // 좌표로 key를 만들면 같은 칸의 카드 둘이 겹쳐 차트를 다시 읽을 때마다 유령 점이 늘었다(리뷰 2라운드) — 카드 번호로 묶는다
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const near = (shift: number): TimelineCard[] => [
      { ...CARDS[0]!, atMs: CARDS[0]!.atMs + shift },
      { ...CARDS[0]!, id: 'card-13', label: '#13', atMs: CARDS[0]!.atMs + shift + 1000 },
      { ...CARDS[1]!, atMs: CARDS[1]!.atMs + shift },
    ];
    const { rerender, props } = panel({ cards: near(0) });
    rerender(<LiveStatsPanel {...props} cards={near(10_000)} />);
    rerender(<LiveStatsPanel {...props} cards={near(20_000)} />);
    // 붙은 둘은 핀 하나(+1)로, 떨어진 하나는 따로
    expect(screen.getAllByRole('button', { name: /^하이라이트 / })).toHaveLength(2);
    expect(screen.getByRole('button', { name: /^하이라이트 #12 — .* 외 1개/ })).toHaveTextContent(
      '#12+1',
    );
  });
});
