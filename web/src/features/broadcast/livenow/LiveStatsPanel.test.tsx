import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveStatsPanel, type TimelineCard } from './LiveStatsPanel';
import styles from './LiveStatsPanel.module.css';
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
    chartLoading: c === null,
    noChart: '볼 수 없는 방송이에요',
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
  vi.unstubAllGlobals();
});

/** jsdom은 스크롤 폭을 재지 않는다 — 넘기는 상자의 보이는 폭·내용 폭을 박아 둔다 */
function scrollBox(client: () => number, scrollWidth: number) {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(client);
  vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(scrollWidth);
}
const scrollerOf = (container: HTMLElement) =>
  container.querySelector<HTMLElement>(`.${styles.scroll}`)!;

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

  it('차트를 받기 전에는 막대를 지어내지 않고 그래프 자리에 그래프 모양 스켈레톤을 세운다(POK-445)', async () => {
    const { container } = panel({ stats: stats({ chart: null }) });
    // 「불러오고 있어요」 글자 한 줄이 아니라 스켈레톤이다. 낭독은 숨은 status 한 줄이다
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('그래프를 불러오는 중');
    expect(screen.queryByText('그래프를 불러오고 있어요')).toBeNull();
    expect(screen.queryByText('볼 수 없는 방송이에요')).toBeNull();
    expect(screen.queryByRole('group')).toBeNull();
    // 받은 그래프·빈 자리(.empty)와 같은 높이: 핀 줄 30 + 시청자 112 + 사이 14 + 채팅 78
    const skeleton = container.querySelector<HTMLElement>(`.${styles.chartSkeleton}`);
    expect(skeleton?.style.height).toBe('calc(234 * var(--pc-u))');
    expect(await axe(container)).toHaveNoViolations();
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

  it('덜 찬 마지막 칸이 있어도 핀은 그 카드가 든 막대 위에 선다', () => {
    // 30초 칸 269개 = 2분 칸 67개 + 30초. 막대만 같은 폭 칸으로 나누면 끝으로 갈수록 핀이 이웃 막대 위에 섰다(PR #316 리뷰)
    const atMs = T0 + (264 * 30 + 60) * 1000; // 2분 칸 66번의 가운데
    const { container } = panel({
      stats: stats({ chart: chart(269) }),
      cards: [{ ...CARDS[0]!, atMs }],
    });
    const pinX = parseFloat(screen.getByRole('button', { name: /^하이라이트 #12/ }).style.left);
    const hot = container.querySelector(`.${styles.barHot}`)!;
    const barX = Number(/^M([\d.e-]+),/.exec(hot.getAttribute('d')!)![1]);
    const slot = (1040 * 120) / (269 * 30);
    expect(Math.abs(pinX - (barX + (slot * 0.72) / 2))).toBeLessThan(slot / 2);
  });

  it('덜 찬 마지막 칸의 툴팁은 실제 길이와 끝 시각을 쓴다', () => {
    panel({ stats: stats({ chart: chart(269) }), cards: [] });
    fireEvent.keyDown(screen.getByRole('group'), { key: 'End' });
    const tip = screen.getByRole('status');
    // 19:30 + 2시간 14분 = 21:44, 마지막 칸은 30초어치다(「2분」·21:46:00이 아니다)
    expect(tip).toHaveTextContent('21:44:00 – 21:44:30');
    expect(tip).toHaveTextContent('채팅 (30초)');
  });

  it('키로 고른 칸이 화면 밖이면 그 칸까지 넘긴다', () => {
    scrollBox(() => 1040, 1764);
    const { container } = panel({
      stats: stats({ chart: chart(504) }),
      live: false,
      ended: true,
    });
    const scroller = scrollerOf(container);
    expect(scroller.scrollLeft).toBe(0);
    fireEvent.keyDown(screen.getByRole('group'), { key: 'End' });
    // 마지막 칸(1750–1764px)과 뒤 두 칸 여유가 보이게 — 키의 기본 스크롤은 막았으니 안 넘기면 툴팁이 화면 밖이었다
    expect(scroller.scrollLeft).toBeGreaterThan(1764 - 1040 - 1);
    fireEvent.keyDown(screen.getByRole('group'), { key: 'Home' });
    expect(scroller.scrollLeft).toBe(0);
  });

  it('보이는 폭만 바뀌어도 끝에 붙어 있으면 다시 끝으로 붙는다', () => {
    const observers: ((entries: { contentRect: { width: number } }[]) => void)[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(cb: (entries: { contentRect: { width: number } }[]) => void) {
          observers.push(cb);
        }
        observe() {}
        disconnect() {}
      },
    );
    let client = 1040;
    scrollBox(() => client, 1764);
    const { container } = panel({ stats: stats({ chart: chart(504) }) });
    const scroller = scrollerOf(container);
    expect(scroller.scrollLeft).toBe(1764);
    // 브라우저는 폭이 줄어도 scrollLeft를 그대로 둔다(끝이 화면 밖으로 밀린다). 폭이 바뀌면 다시 끝으로 붙어야 한다(PR #316 리뷰)
    scroller.scrollLeft = 724;
    client = 840;
    act(() => {
      for (const cb of observers) cb([{ contentRect: { width: 892 } }]);
    });
    expect(scroller.scrollLeft).toBe(1764);
  });

  it('좁은 화면에서도 툴팁은 보이는 구간 안에 선다', () => {
    // 그래프 폭 240px·30초 칸 16개에서 넷째 칸을 고르면 「오른쪽 끝 200u」 기준으로 뒤집혀 왼쪽 끝이 −127.5px였다(리뷰 2라운드)
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
      width: 292,
      height: 300,
      top: 0,
      left: 0,
      right: 292,
      bottom: 300,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    } as DOMRect);
    panel({ stats: stats({ chart: chart(16) }), cards: [] });
    const group = screen.getByRole('group');
    fireEvent.keyDown(group, { key: 'Home' });
    for (let k = 0; k < 3; k++) fireEvent.keyDown(group, { key: 'ArrowRight' });
    const tip = screen.getByRole('status');
    const left = parseFloat(tip.style.left);
    expect(tip.style.transform).toBe('');
    expect(left).toBeGreaterThanOrEqual(0);
    expect(left + 168).toBeLessThanOrEqual(240);
  });

  it('핀 위에서는 마우스 좌표가 아니라 그 카드의 칸을 고른다', () => {
    // 핀은 칸보다 넓어, 좌표로 고르면 핀 오른쪽에서 옆 칸이 잡혀 툴팁에 그 카드가 없었다(리뷰 2라운드)
    const { container } = panel();
    fireEvent.pointerMove(screen.getByRole('button', { name: /^하이라이트 수동/ }), { clientX: 0 });
    const tip = container.querySelector(`.${styles.tip}`)!;
    expect(tip).toHaveTextContent('수동 · 수동 마킹');
  });

  it('묶인 핀(+N)에 호버하면 칸이 달라도 묶인 카드를 전부 말한다', () => {
    // 스크롤 모드(2분 칸 14px)에서 2분 30초 떨어진 두 카드는 「#12+1」 핀 하나다. 첫 카드의 칸만 말하면 #13은 그래프에서
    // 닿을 길이 없었다(PR #316 리뷰 3라운드)
    const twelve = CARDS[0]!;
    const { container } = panel({
      stats: stats({ chart: chart(504) }),
      cards: [
        twelve,
        {
          ...twelve,
          id: 'card-13',
          label: '#13',
          atMs: twelve.atMs + 150_000,
          timestamp: '0:22:30',
        },
      ],
    });
    const pin = screen.getByRole('button', { name: /^하이라이트 2개 묶음/ });
    fireEvent.pointerMove(pin, { clientX: 0 });
    const tip = container.querySelector(`.${styles.tip}`)!;
    expect(tip).toHaveTextContent('#12');
    // 다른 칸의 카드는 시점을 붙인다
    expect(tip).toHaveTextContent('#13');
    expect(tip).toHaveTextContent('0:22:30');
    expect(tip).toHaveTextContent('누르면 목록에서 골라요');
  });

  it('Alt·Ctrl·Cmd 조합 키는 그래프가 가로채지 않는다(브라우저 뒤로 가기·문서 처음)', () => {
    panel();
    const group = screen.getByRole('group');
    // fireEvent는 preventDefault되면 false를 돌려준다
    expect(fireEvent.keyDown(group, { key: 'ArrowLeft', altKey: true })).toBe(true);
    expect(fireEvent.keyDown(group, { key: 'Home', ctrlKey: true })).toBe(true);
    expect(fireEvent.keyDown(group, { key: 'End', metaKey: true })).toBe(true);
    expect(screen.queryByRole('status')).toBeNull();
    // 수정자 없는 키는 그대로 칸을 옮긴다
    expect(fireEvent.keyDown(group, { key: 'ArrowLeft' })).toBe(false);
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('마우스로 고른 칸은 스크린 리더에 낭독하지 않고, 키로 옮긴 칸만 알린다', () => {
    const { container } = panel();
    const group = screen.getByRole('group');
    fireEvent.pointerMove(group, { clientX: 500 });
    expect(container.querySelector(`.${styles.tip}`)).not.toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
    fireEvent.keyDown(group, { key: 'ArrowRight' });
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('볼 수 없게 된 방송은 그래프를 불러온다고 하지 않는다', () => {
    panel({
      stats: stats({ chart: null, chartLoading: false, noChart: '볼 수 없는 방송이에요' }),
      live: false,
      ended: true,
    });
    expect(screen.getByText('볼 수 없는 방송이에요')).toBeInTheDocument();
    expect(screen.queryByText('그래프를 불러오고 있어요')).toBeNull();
    expect(screen.queryByText('그래프 확인 중')).toBeNull();
    // 다시 읽지 않을 그래프라 스켈레톤도 세우지 않는다(POK-445)
    expect(screen.queryByText('그래프를 불러오는 중')).toBeNull();
  });

  it('시청자 관측이 하나뿐이면 「기록 없음」이 아니라 점 하나다', () => {
    const { container } = panel({
      stats: stats({ viewers: [{ t: T0 + 60_000, v: 1200 }], noViewers: '기록 없음' }),
      live: false,
      ended: true,
    });
    expect(screen.queryByText('시청자 수 기록 없음')).toBeNull();
    expect(container.querySelector('circle[data-single]')).not.toBeNull();
  });
});

describe('LiveStatsPanel: 열쇠', () => {
  it('짧은 지난 방송에서도 시간 눈금의 React key가 겹치지 않는다', () => {
    // 2분짜리 방송은 시:분 눈금 글자가 겹친다(리뷰). 글자를 key로 쓰면 같은 key 경고가 난다
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    panel({ stats: stats({ chart: chart(4) }), live: false, ended: true, cards: [] });
    expect(errors.mock.calls.some((args) => String(args[0]).includes('same key'))).toBe(false);
  });

  it('같은 칸의 수동 카드 둘은 툴팁에서 key가 겹치지 않고 두 줄로 선다', () => {
    // 수동 카드는 글자(「수동」·「수동 마킹」)가 같다 — 글자로 key를 만들면 겹쳤다(PR #316 리뷰)
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    panel({
      cards: [
        { ...CARDS[1]!, id: 'card-20', atMs: T0 + 10_000 },
        { ...CARDS[1]!, id: 'card-21', atMs: T0 + 40_000 },
      ],
    });
    fireEvent.keyDown(screen.getByRole('group'), { key: 'Home' });
    expect(within(screen.getByRole('status')).getAllByText('수동')).toHaveLength(2);
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
    // 붙은 둘은 묶음 하나(개수 2)로, 떨어진 하나는 따로
    expect(screen.getAllByRole('button', { name: /^하이라이트 / })).toHaveLength(2);
    expect(screen.getByRole('button', { name: /^하이라이트 2개 묶음/ })).toHaveTextContent(/^2$/);
  });
});

describe('LiveStatsPanel: 겹치는 핀 묶음(POK-500)', () => {
  const twelve = CARDS[0]!;
  /** #12와 #13이 붙어 있고 수동은 멀리 떨어진 카드 셋 */
  const near: TimelineCard[] = [
    twelve,
    { ...twelve, id: 'card-13', label: '#13', atMs: twelve.atMs + 60_000, timestamp: '0:21:00' },
    CARDS[1]!,
  ];

  it('겹치는 핀은 개수 하나로 묶고, 떨어진 핀은 하나짜리 그대로다', () => {
    panel({ cards: near });
    const group = screen.getByRole('button', { name: /^하이라이트 2개 묶음/ });
    expect(group).toHaveTextContent(/^2$/);
    expect(group).toHaveAttribute('aria-expanded', 'false');
    // 묶인 카드는 따로 핀이 없다
    expect(screen.queryByRole('button', { name: /^하이라이트 #12/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^하이라이트 #13/ })).toBeNull();
    // 떨어진 수동 핀은 하나짜리 그대로다(글자 · 누르면 바로 이동)
    const manual = screen.getByRole('button', { name: /^하이라이트 수동 — 수동 마킹/ });
    expect(manual).toHaveTextContent('수동');
    expect(manual).not.toHaveAttribute('aria-expanded');
  });

  it('묶음을 누르면 바로 옮기지 않고 그 안의 카드 목록을 띄운다', () => {
    const { onSeekCard } = panel({ cards: near });
    const group = screen.getByRole('button', { name: /^하이라이트 2개 묶음/ });
    fireEvent.click(group);
    expect(onSeekCard).not.toHaveBeenCalled();
    expect(group).toHaveAttribute('aria-expanded', 'true');
    const list = screen.getByRole('group', { name: '묶인 하이라이트 2개' });
    const items = within(list).getAllByRole('button');
    expect(items.map((b) => b.textContent)).toEqual([
      expect.stringContaining('#12'),
      expect.stringContaining('#13'),
    ]);
    expect(items[1]).toHaveTextContent('0:21:00');
    // 열면 첫 카드에 초점이 간다(키보드로도 고를 수 있다)
    expect(items[0]).toHaveFocus();
    // 목록이 떠 있는 동안 툴팁은 목록을 가리지 않는다
    fireEvent.pointerMove(group, { clientX: 0 });
    expect(document.querySelector(`.${styles.tip}`)).toBeNull();
  });

  it('목록에서 고르면 하나짜리 핀을 누른 것처럼 그 시점으로 가고 목록을 닫는다', () => {
    const { onSeekCard } = panel({ cards: near });
    const group = screen.getByRole('button', { name: /^하이라이트 2개 묶음/ });
    fireEvent.click(group);
    const list = screen.getByRole('group', { name: '묶인 하이라이트 2개' });
    fireEvent.click(within(list).getByRole('button', { name: /^하이라이트 #13 — / }));
    expect(onSeekCard).toHaveBeenCalledTimes(1);
    expect(onSeekCard).toHaveBeenCalledWith('0:21:00');
    expect(screen.queryByRole('group', { name: '묶인 하이라이트 2개' })).toBeNull();
    expect(group).toHaveAttribute('aria-expanded', 'false');
  });

  it('Esc · 바깥 누르기 · 묶음 다시 누르기로 목록을 닫는다', () => {
    panel({ cards: near });
    const group = screen.getByRole('button', { name: /^하이라이트 2개 묶음/ });
    const name = '묶인 하이라이트 2개';

    fireEvent.click(group);
    fireEvent.keyDown(screen.getByRole('group', { name }), { key: 'Escape' });
    expect(screen.queryByRole('group', { name })).toBeNull();
    expect(group).toHaveFocus();

    fireEvent.click(group);
    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole('group', { name })).toBeNull();

    fireEvent.click(group);
    fireEvent.pointerDown(group);
    fireEvent.click(group);
    expect(screen.queryByRole('group', { name })).toBeNull();
  });

  it('목록을 보는 중에 카드가 다시 와도 초점이 첫 카드로 튀지 않는다', () => {
    // 초점을 묶음 객체에 걸면 갱신마다 새 객체라 다시 돌아 초점이 첫 카드로 갔다(로컬 리뷰)
    const { rerender, props } = panel({ cards: near });
    fireEvent.click(screen.getByRole('button', { name: /^하이라이트 2개 묶음/ }));
    const second = () =>
      within(screen.getByRole('group', { name: '묶인 하이라이트 2개' })).getAllByRole('button')[1]!;
    act(() => second().focus());
    rerender(<LiveStatsPanel {...props} cards={near.map((c) => ({ ...c }))} />);
    expect(second()).toHaveFocus();
  });

  it('목록 안에서 ←/→는 그래프 칸을 옮기지 않는다', () => {
    panel({ cards: near });
    fireEvent.click(screen.getByRole('button', { name: /^하이라이트 2개 묶음/ }));
    const list = screen.getByRole('group', { name: '묶인 하이라이트 2개' });
    expect(fireEvent.keyDown(within(list).getAllByRole('button')[0]!, { key: 'ArrowRight' })).toBe(
      true,
    );
    expect(document.querySelector(`.${styles.tip}`)).toBeNull();
  });

  it('그래프 폭이 바뀌면 화면 위 거리로 다시 묶는다', () => {
    // 268칸(2시간 14분) 그래프에서 6분 떨어진 두 카드: 폭 1092면 약 47px(따로), 1000이면 약 42px(묶음)
    let resize: ResizeObserverCallback = () => undefined;
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(cb: ResizeObserverCallback) {
          resize = cb;
        }
        observe() {}
        disconnect() {}
      },
    );
    panel({
      cards: [twelve, { ...twelve, id: 'card-13', label: '#13', atMs: twelve.atMs + 360_000 }],
    });
    expect(screen.getByRole('button', { name: /^하이라이트 #12 — / })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^하이라이트 #13 — / })).toBeInTheDocument();
    act(() =>
      resize([{ contentRect: { width: 1000 } } as ResizeObserverEntry], {} as ResizeObserver),
    );
    expect(screen.getByRole('button', { name: /^하이라이트 2개 묶음/ })).toHaveTextContent(/^2$/);
    act(() =>
      resize([{ contentRect: { width: 1092 } } as ResizeObserverEntry], {} as ResizeObserver),
    );
    expect(screen.queryByRole('button', { name: /^하이라이트 2개 묶음/ })).toBeNull();
  });
});
