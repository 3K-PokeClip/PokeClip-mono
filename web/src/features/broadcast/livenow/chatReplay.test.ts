import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ChatReplayLoader,
  linesUpTo,
  replayReadStart,
  replayStartIsGuess,
  replayView,
  type ReplayFetch,
  type ReplayLine,
  type ReplaySnapshot,
} from './chatReplay';
import type { LiveChart } from './liveDataStore';
import type { WireItem } from './chatCollector';

// 지난 방송 채팅 다시 보기(POK-324). 채팅 창은 영상 시각까지의 줄만 보이고, 수집기에서는 그 시각 둘레만 읽는다.
// 수집기는 영상 축으로 묻고(서버가 보정을 더한다) 표 축 시각으로 답한다: 이 시험의 가짜 수집기도 그렇게 답한다.

const T0 = Date.parse('2026-10-12T03:00:00Z');
/** 표 축 시각 = 영상 축 시각 + 보정(POK-92 실측 3,900ms) */
const OFFSET = 3_900;
const HOUR = 3_600_000;

function chatAt(screenMs: number, id: number, kind: 'chat' | 'donation' = 'chat'): WireItem {
  return {
    kind,
    id,
    time: new Date(screenMs + OFFSET).toISOString(),
    nickname: `n${id}`,
    senderChannelId: `c${id}`,
    text: `t${id}`,
    amount: kind === 'donation' ? 1_000 : null,
  };
}

/** perSecond건씩 [0, seconds)초 동안의 채팅(영상 축) */
function steady(seconds: number, perSecond = 1): WireItem[] {
  const items: WireItem[] = [];
  for (let i = 0; i < seconds * perSecond; i += 1)
    items.push(chatAt(T0 + Math.floor((i * 1000) / perSecond), i + 1));
  return items;
}

/** 가짜 수집기: 영상 축 [from, to)를 물으면 그 안의 줄을 표 축 시각 오름차순으로 limit건씩 나눠 준다 */
function collector(items: WireItem[], limit = 500) {
  const calls: { from: number; to: number; cursor: string | null }[] = [];
  /** 요청마다 받은 끊기 손잡이 */
  const signals: AbortSignal[] = [];
  const sorted = [...items].sort((a, b) => Date.parse(a.time) - Date.parse(b.time));
  const state = {
    fail: false,
    gate: null as Promise<void> | null,
    /** 빈 쪽에 커서를 단다(계약 위반) */
    emptyWithCursor: false,
    /** 200인데 items가 없다(계약 위반) */
    noItems: false,
  };
  const fetchPage: ReplayFetch = async (from, to, cursor, signal) => {
    calls.push({ from, to, cursor });
    signals.push(signal);
    const gate = state.gate;
    // 끊으면 브라우저 fetch처럼 바로 실패한다
    if (gate)
      await Promise.race([
        gate,
        new Promise<never>((_, reject) => {
          if (signal.aborted) reject(new Error('aborted'));
          signal.addEventListener('abort', () => reject(new Error('aborted')));
        }),
      ]);
    if (state.fail) throw new Error('503');
    if (state.emptyWithCursor) return { items: [], nextCursor: 'more', appliedOffsetMs: OFFSET };
    if (state.noItems)
      return {
        items: undefined as unknown as WireItem[],
        nextCursor: null,
        appliedOffsetMs: OFFSET,
      };
    const inWindow = sorted.filter((it) => {
      const at = Date.parse(it.time) - OFFSET;
      return at >= from && at < to;
    });
    const start = cursor === null ? 0 : Number(cursor);
    const more = start + limit < inWindow.length;
    return {
      items: inWindow.slice(start, start + limit),
      nextCursor: more ? String(start + limit) : null,
      appliedOffsetMs: OFFSET,
    };
  };
  return { fetchPage, calls, signals, state };
}

/** 읽기 사슬(쪽마다 몇 번의 약속)을 끝까지 푼다 */
async function settle() {
  for (let i = 0; i < 2_000; i += 1) await Promise.resolve();
}

function chart(
  counts: number[],
  { bucketSeconds = 10, scope = 'broadcast' as LiveChart['scope'], firstMs = T0 } = {},
): LiveChart {
  return {
    bucketSeconds,
    appliedOffsetMs: OFFSET,
    scope,
    // 칸 시작도 표 축이다
    buckets: counts.map((chats, i) => ({
      start: new Date(firstMs + i * bucketSeconds * 1000 + OFFSET).toISOString(),
      chats,
      donations: 0,
    })),
  };
}

describe('linesUpTo: 영상 시각까지의 줄', () => {
  const lines: ReplayLine[] = Array.from({ length: 400 }, (_, i) => ({
    seq: i,
    item: chatAt(T0 + i * 1000, i),
    at: T0 + i * 1000,
  }));

  it('커서까지의 줄만, 그중 마지막 300줄이다: 라이브 채팅과 같은 양', () => {
    const got = linesUpTo(lines, T0 + 350_000);
    expect(got).toHaveLength(300);
    expect(got[0]!.at).toBe(T0 + 51_000);
    // 커서와 같은 시각의 줄은 보인다
    expect(got.at(-1)!.at).toBe(T0 + 350_000);
  });

  it('커서 뒤 줄은 하나도 없다: 5분으로 되돌리면 5~10분 채팅은 사라진다', () => {
    expect(linesUpTo(lines, T0 + 9_500).map((l) => l.at)).toEqual(
      Array.from({ length: 10 }, (_, i) => T0 + i * 1000),
    );
    expect(linesUpTo(lines, T0 - 1)).toEqual([]);
  });
});

describe('replayReadStart: 어디부터 읽나', () => {
  it('커서 앞 다 찬 칸을 거꾸로 세어 300건이 차는 칸부터다: 커서가 걸친 칸은 세지 않는다', () => {
    // 10초 칸마다 100건. 커서 55초는 5번 칸 안이다 → 4·3·2번 칸이 300건 → 2번 칸 시작 20초.
    // 칸 시작은 표 축이라 보정을 빼야 한다: 빼지 않으면 23.9초가 나온다
    expect(replayReadStart(chart([100, 100, 100, 100, 100, 100, 100]), T0 + 55_000, T0)).toBe(
      T0 + 20_000,
    );
  });

  it('후원도 채팅 창에 서므로 같이 센다', () => {
    const c = chart([0, 0, 0, 0, 0, 0]);
    c.buckets = c.buckets.map((b) => ({ ...b, chats: 100, donations: 50 }));
    // 칸마다 150건 → 4·3번 칸이 300건
    expect(replayReadStart(c, T0 + 55_000, T0)).toBe(T0 + 30_000);
  });

  it('방송 전체 차트를 첫 칸까지 세도 모자라면 하한부터다: 채팅이 적은 방송은 처음부터 읽고, 시작 앞 보정 여유도 읽는다', () => {
    // 첫 칸은 방송 시작에 선다. 하한은 그보다 보정 여유만큼 앞이다: 첫 칸부터 읽으면 시작 직후 채팅(영상 축으로 시작 앞)을 못
    // 읽었다(리뷰 2라운드)
    expect(replayReadStart(chart([5, 5, 5, 5]), T0 + 35_000, T0 - 60_000)).toBe(T0 - 60_000);
    // 끝 12시간만 그린 차트는 첫 칸이 방송 시작이 아니다: 그 칸부터다
    expect(replayReadStart(chart([5, 5, 5, 5], { scope: 'tail' }), T0 + 35_000, T0 - 60_000)).toBe(
      T0,
    );
  });

  it('방송 전체 차트에서 영상 시각이 첫 칸 안이라 한 칸도 못 세어도 하한부터다: 첫 칸을 넘는 순간 시작점이 바뀌지 않는다', () => {
    // 첫 칸 안일 때만 60초 앞이면 첫 칸 끝을 넘는 순간 하한으로 바뀌어 빈 구간을 덧붙여 읽고 그동안 「불러오고 있어요」가
    // 깜박였다(녹화 기준점이 방송 시작보다 늦을 때, 리뷰 3라운드)
    const c = chart([5, 5, 5, 5]);
    expect(replayReadStart(c, T0 + 7_000, T0 - 60_000)).toBe(T0 - 60_000);
    expect(replayReadStart(c, T0 + 11_000, T0 - 60_000)).toBe(T0 - 60_000);
    // 60초 칸이면 0:00:58도 하한이다(60초 앞이면 시작 앞 보정 여유를 못 읽었다)
    expect(replayReadStart(chart([5, 5], { bucketSeconds: 60 }), T0 + 58_000, T0 - 60_000)).toBe(
      T0 - 60_000,
    );
    // 영상 시각이 차트 첫 칸보다 앞이면 그 차트로는 모른다: 60초 앞이다
    expect(replayReadStart(chart([5, 5], { firstMs: T0 + HOUR }), T0 + 600_000, T0)).toBe(
      T0 + 540_000,
    );
  });

  it('하한 아래로 가지 않는다', () => {
    expect(replayReadStart(chart([5, 5, 5, 5]), T0 + 35_000, T0 + 10_000)).toBe(T0 + 10_000);
  });

  it('차트가 없거나 라이브 때의 최근 창이면 커서 60초 앞이다', () => {
    expect(replayReadStart(null, T0 + 600_000, T0)).toBe(T0 + 540_000);
    expect(replayReadStart(chart([500], { scope: 'recent' }), T0 + 600_000, T0)).toBe(T0 + 540_000);
  });

  it('커서가 차트보다 앞이면 커서 60초 앞이다: 끝 12시간만 그린 긴 방송의 앞쪽 카드', () => {
    const tail = chart([100, 100, 100, 100], { scope: 'tail', firstMs: T0 + 3 * HOUR });
    expect(replayReadStart(tail, T0 + 600_000, T0)).toBe(T0 + 540_000);
  });
});

describe('replayStartIsGuess: 시작점이 짐작인가', () => {
  it('끝 12시간만 그린 차트가 영상 시각 앞 300건을 못 세면 짐작이다: 차트 밖(더 이른 쪽) 채팅은 모른다', () => {
    const tail = chart([10, 10, 10], { scope: 'tail', firstMs: T0 + HOUR });
    expect(replayStartIsGuess(tail, T0 + HOUR + 30_000)).toBe(true);
    // 영상 시각이 차트보다 앞이어도 짐작이다
    expect(replayStartIsGuess(tail, T0 + 30_000)).toBe(true);
    // 300건을 세면 짐작이 아니다
    expect(replayStartIsGuess(chart([200, 200], { scope: 'tail' }), T0 + 20_000)).toBe(false);
  });

  it('방송 전체 차트·차트 없음·라이브 최근 창은 짐작이 아니다', () => {
    expect(replayStartIsGuess(chart([10, 10]), T0 + 20_000)).toBe(false);
    expect(replayStartIsGuess(null, T0 + 20_000)).toBe(false);
    expect(replayStartIsGuess(chart([10, 10], { scope: 'recent' }), T0 + 20_000)).toBe(false);
  });
});

describe('replayView: 무엇을 보이나', () => {
  const lines: ReplayLine[] = [10, 20, 30].map((s, i) => ({
    seq: i,
    item: chatAt(T0 + s * 1000, i),
    at: T0 + s * 1000,
  }));
  const snap = (over: Partial<ReplaySnapshot>): ReplaySnapshot => ({
    lines,
    from: T0,
    to: T0 + 40_000,
    failed: false,
    ...over,
  });

  it('커서까지 다 읽었으면 그 줄들이다', () => {
    const view = replayView(snap({}), T0 + 25_000, T0 + HOUR, T0 + 25_000);
    expect(view.status).toBe('ready');
    expect(view.lines.map((l) => l.at)).toEqual([T0 + 10_000, T0 + 20_000]);
  });

  it('커서까지 못 읽었으면 읽는 중이다: 빈 창을 「채팅 없음」처럼 보이지 않는다', () => {
    expect(replayView(snap({}), T0 + 50_000, T0 + HOUR, T0 + 50_000).status).toBe('loading');
    // 다시 읽기 시작해 아직 아무것도 없다
    expect(
      replayView(
        snap({ lines: [], from: T0 + 60_000, to: T0 + 60_000 }),
        T0 + 60_000,
        T0 + HOUR,
        T0 + 60_000,
      ).status,
    ).toBe('loading');
  });

  it('커서까지 못 읽었는데 마지막 읽기가 실패했으면 실패다', () => {
    expect(replayView(snap({ failed: true }), T0 + 50_000, T0 + HOUR, T0 + 50_000).status).toBe(
      'failed',
    );
    // 커서까지는 다 읽었으면 앞서 읽기가 실패해도 보이는 데는 문제가 없다
    expect(replayView(snap({ failed: true }), T0 + 25_000, T0 + HOUR, T0 + 25_000).status).toBe(
      'ready',
    );
  });

  it('방송 끝까지 읽었으면 커서가 끝이어도 다 읽은 것이다', () => {
    expect(
      replayView(snap({ to: T0 + 40_000 }), T0 + 40_000, T0 + 40_000, T0 + 40_000).status,
    ).toBe('ready');
  });

  it('영상 시각 쪽은 다 있는데 다 읽은 구간이 하한보다 늦게 서서 앞이 모자라면 이른 쪽을 읽는 중·실패다: 보이는 줄은 그대로다', () => {
    // 로더가 지금 덧붙이는 중인지가 아니라 스냅숏과 하한으로 가른다: 덧붙이기는 앞 요청이 끝나야 서서 그 사이 몇 줄만 보이는 창을
    // 다 읽은 것처럼 그렸다(리뷰 3라운드). 읽기 시작점이 아니라 하한이다: 시작점은 차트로 짐작한 것이라 거기서 멈추면 300줄을 못
    // 채웠다(PR #230 codex 3판)
    const view = replayView(snap({}), T0 + 25_000, T0 + HOUR, T0 - 60_000);
    expect(view.status).toBe('earlierLoading');
    expect(view.lines).toHaveLength(2);
    expect(replayView(snap({ failed: true }), T0 + 25_000, T0 + HOUR, T0 - 60_000).status).toBe(
      'earlierFailed',
    );
    // 다 읽은 구간이 하한부터 서면 몇 줄이어도 다 읽은 것이다(그 앞에 줄이 없다)
    expect(replayView(snap({}), T0 + 25_000, T0 + HOUR, T0).status).toBe('ready');
    // 영상 시각 쪽을 아직 못 읽었으면 그쪽이 먼저다
    expect(replayView(snap({}), T0 + 50_000, T0 + HOUR, T0 - 60_000).status).toBe('loading');
  });
});

describe('ChatReplayLoader: 그 시각 둘레만 읽는다', () => {
  beforeEach(() => vi.useFakeTimers({ now: T0 + 10 * HOUR }));
  afterEach(() => vi.useRealTimers());

  it('읽기 시작점부터 커서 30초 뒤까지 읽고, 줄 시각은 영상 축(표 축 − 보정)이다', async () => {
    const srv = collector(steady(600));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();

    expect(srv.calls).toEqual([{ from: T0 + 40_000, to: T0 + HOUR, cursor: null }]);
    const snap = loader.getSnapshot();
    expect(snap.lines[0]!.at).toBe(T0 + 40_000);
    const view = replayView(snap, T0 + 100_000, T0 + HOUR, T0 + 100_000);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(61);
    expect(view.lines.at(-1)!.at).toBe(T0 + 100_000);
  });

  it('한 쪽이 넘치면 같은 창을 커서로 이어 읽는다', async () => {
    // 초당 10건: 500건 한 쪽이 50초다
    const srv = collector(steady(600, 10));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();

    expect(srv.calls).toEqual([
      { from: T0 + 40_000, to: T0 + HOUR, cursor: null },
      { from: T0 + 40_000, to: T0 + HOUR, cursor: '500' },
    ]);
    expect(
      replayView(loader.getSnapshot(), T0 + 100_000, T0 + HOUR, T0 + 100_000).lines.at(-1)!.at,
    ).toBe(T0 + 100_000);
  });

  it('재생하며 영상 시각이 흐르면 30초 뒤까지 늘 미리 읽어 둔다: 모자랄 때 한 쪽씩만 묻는다', async () => {
    const srv = collector(steady(600, 10));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();
    for (let s = 101; s <= 300; s += 1) {
      loader.want(T0 + s * 1000, T0 + (s - 60) * 1000);
      await settle();
      expect(loader.getSnapshot().to).toBeGreaterThanOrEqual(T0 + (s + 30) * 1000);
    }
    // 초당 10줄 × 200초 = 2,000줄 = 넉 쪽이다. 처음 두 쪽과 합쳐 여섯이다
    expect(srv.calls).toHaveLength(6);
    expect(srv.calls.at(-1)).toEqual({ from: T0 + 40_000, to: T0 + HOUR, cursor: '2500' });
  });

  it('다 읽은 구간보다 멀리 이른 쪽으로 되감으면 그 자리부터 다시 읽고 옛 줄은 버린다', async () => {
    const srv = collector(steady(1_200));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 300_000);
    await settle();

    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();

    expect(srv.calls.at(-1)).toEqual({ from: T0 + 40_000, to: T0 + HOUR, cursor: null });
    const snap = loader.getSnapshot();
    expect(snap.from).toBe(T0 + 40_000);
    expect(snap.lines[0]!.at).toBe(T0 + 40_000);
    expect(replayView(snap, T0 + 100_000, T0 + HOUR, T0 + 100_000).lines.at(-1)!.at).toBe(
      T0 + 100_000,
    );
  });

  it('다 읽은 구간 안으로 되감으면 다시 읽지 않는다: 그 앞 줄이 300줄 넘게 있다', async () => {
    const srv = collector(steady(1_200));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 1_000_000, T0);
    await settle();
    const asked = srv.calls.length;

    loader.want(T0 + 700_000, T0 + 400_000);
    await settle();

    expect(srv.calls).toHaveLength(asked);
    const view = replayView(loader.getSnapshot(), T0 + 700_000, T0 + HOUR, T0 + 700_000);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(300);
    expect(view.lines.at(-1)!.at).toBe(T0 + 700_000);
  });

  it('멀리 늦은 쪽으로 건너뛰면 사이를 읽지 않고 그 자리부터 다시 읽는다', async () => {
    const srv = collector(steady(3_600));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();

    loader.want(T0 + 3_000_000, T0 + 2_940_000);
    await settle();

    expect(srv.calls.at(-1)).toEqual({ from: T0 + 2_940_000, to: T0 + HOUR, cursor: null });
    expect(loader.getSnapshot().from).toBe(T0 + 2_940_000);
  });

  it('조금(30초 안) 늦은 쪽으로 옮기면 다시 시작하지 않고 이어 읽는다: 화살표로 건너뛸 때', async () => {
    const srv = collector(steady(600, 10));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();
    // 140초 앞까지 읽었다. 180초로 옮기면 읽기 시작점 150초가 그보다 10초 뒤다
    loader.want(T0 + 180_000, T0 + 150_000);
    await settle();

    expect(srv.calls.slice(2).map((c) => c.cursor)).toEqual(['1000', '1500']);
    const view = replayView(loader.getSnapshot(), T0 + 180_000, T0 + HOUR, T0 + 180_000);
    expect(view.status).toBe('ready');
    expect(view.lines.at(-1)!.at).toBe(T0 + 180_000);
  });

  it('느린 답을 기다리는 동안 재생이 흘러도 처음부터 다시 묻지 않는다: 매초 다시 시작하면 끝내 아무것도 못 읽는다', async () => {
    const srv = collector(steady(600));
    let release: () => void = () => undefined;
    srv.state.gate = new Promise((resolve) => {
      release = resolve;
    });
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    for (let s = 101; s <= 110; s += 1) loader.want(T0 + s * 1000, T0 + (s - 60) * 1000);
    await settle();
    expect(srv.calls).toHaveLength(1);

    srv.state.gate = null;
    release();
    await settle();
    expect(srv.calls).toHaveLength(1);
    expect(replayView(loader.getSnapshot(), T0 + 110_000, T0 + HOUR, T0 + 110_000).status).toBe(
      'ready',
    );
  });

  it('구간 안이어도 영상 시각 앞 줄이 300 미만이면 그 앞만 읽어 붙인다: 쥔 줄을 다시 받지 않는다', async () => {
    const srv = collector(steady(1_200));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 300_000, T0 + 240_000);
    await settle();

    loader.want(T0 + 260_000, T0);
    await settle();

    expect(srv.calls.at(-1)).toEqual({ from: T0, to: T0 + 240_000, cursor: null });
    expect(srv.calls).toHaveLength(2);
    expect(
      replayView(loader.getSnapshot(), T0 + 260_000, T0 + HOUR, T0 + 260_000).lines,
    ).toHaveLength(261);
  });

  it('쥔 줄이 2,000을 넘으면 영상 시각 앞 600줄(보이는 양의 두 배)만 남긴다: 오래 틀어도 줄이 쌓이지 않는다', async () => {
    const srv = collector(steady(900, 10));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 70_000);
    await settle();
    for (let s = 101; s <= 600; s += 1) {
      loader.want(T0 + s * 1000, T0 + (s - 30) * 1000);
      await settle();
    }

    const snap = loader.getSnapshot();
    expect(snap.lines.length).toBeLessThanOrEqual(2_500);
    expect(snap.from).toBeGreaterThan(T0 + 70_000);
    // 조금 되감아도 다시 읽지 않게 보이는 양보다 넉넉히 남긴다
    const kept = snap.lines.filter((l) => l.at <= T0 + 600_000).length;
    expect(kept).toBeGreaterThanOrEqual(600);
    expect(kept).toBeLessThanOrEqual(2_000);
    const view = replayView(snap, T0 + 600_000, T0 + HOUR, T0 + 600_000);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(300);
  });

  it('읽기 시작점이 이른 쪽으로 가면(차트가 늦게 옴) 그 앞만 읽어 붙인다: 보이던 줄은 그대로 두고, 다 붙기 전에는 읽는 중이다', async () => {
    const srv = collector(steady(1_200));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    // 차트가 없어 60초 앞부터 읽었다
    loader.want(T0 + 600_000, T0 + 540_000);
    await settle();
    expect(
      replayView(loader.getSnapshot(), T0 + 600_000, T0 + HOUR, T0 + 600_000).lines,
    ).toHaveLength(61);
    const views: { n: number; status: string }[] = [];
    loader.subscribe(() => {
      const v = replayView(loader.getSnapshot(), T0 + 600_000, T0 + HOUR, T0 + 301_000);
      views.push({ n: v.lines.length, status: v.status });
    });

    // 차트가 와서 300줄이 차는 자리(301초)부터 읽어야 한다
    loader.want(T0 + 600_000, T0 + 301_000);
    // 그 앞을 읽는 동안: 보이던 61줄은 그대로고 맨 위(이른 쪽)가 읽는 중이다. 61줄을 다 읽은 것처럼 그리지 않는다(리뷰 2·3라운드)
    const during = replayView(loader.getSnapshot(), T0 + 600_000, T0 + HOUR, T0 + 301_000);
    expect(during).toMatchObject({ status: 'earlierLoading' });
    expect(during.lines).toHaveLength(61);
    await settle();

    expect(srv.calls.at(-1)).toEqual({ from: T0 + 301_000, to: T0 + 540_000, cursor: null });
    // 보이던 61줄은 한 번도 지우지 않는다
    expect(views.every((v) => v.n >= 61)).toBe(true);
    expect(views.at(-1)).toEqual({ n: 300, status: 'ready' });
  });

  it('앞 요청을 기다리는 동안 되감아 그 앞이 모자라도 다 읽은 것처럼 그리지 않는다: 덧붙이기가 서기 전에도 이른 쪽을 읽는 중이다', async () => {
    // 덧붙이는 중인지로 가르면 늦은 쪽 답을 기다리는 사이(덧붙이기를 아직 못 세움) 1줄만 보이는 창을 다 읽은 것처럼 그렸다
    // (리뷰 3라운드: 초당 50줄, 310초 늦은 쪽 요청 중 ← 두 번)
    const srv = collector(steady(600, 50));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    // 다 읽은 구간은 290초부터다
    loader.want(T0 + 300_000, T0 + 290_000);
    await settle();
    let release: () => void = () => undefined;
    srv.state.gate = new Promise((resolve) => {
      release = resolve;
    });
    // 늦은 쪽을 더 읽어야 하는 자리로 가서 그 답이 늦다
    loader.want(T0 + 310_000, T0 + 304_000);
    await settle();
    const calls = srv.calls.length;

    // 다 읽은 구간 첫머리(290초)로 되감는다: 영상 시각까지는 1줄뿐이고 그 앞(284초부터)이 모자란다
    loader.want(T0 + 290_000, T0 + 284_000);
    await settle();
    expect(srv.calls).toHaveLength(calls);
    const view = replayView(loader.getSnapshot(), T0 + 290_000, T0 + HOUR, T0 + 284_000);
    expect(view.lines).toHaveLength(1);
    expect(view.status).toBe('earlierLoading');

    srv.state.gate = null;
    release();
    await settle();
    const done = replayView(loader.getSnapshot(), T0 + 290_000, T0 + HOUR, T0 + 284_000);
    expect(done.status).toBe('ready');
    expect(done.lines).toHaveLength(300);
  });

  /** 처음 1,000초는 1초에 한 줄, 그 뒤는 100초에 한 줄(1,000초~3,500초) */
  function denseThenSparse(): WireItem[] {
    const items = steady(1_000);
    for (let k = 0; k <= 25; k += 1) items.push(chatAt(T0 + (1_000 + k * 100) * 1000, 10_000 + k));
    return items;
  }

  it('읽기 시작점까지 읽어도 영상 시각 앞 줄이 300 미만이고 하한보다 늦으면 그 앞을 더 읽는다: 끝 12시간 차트로 고른 시작점에서 멈추지 않는다', async () => {
    // 12시간 넘는 방송은 차트가 끝 12시간뿐이라, 그 안에 300건이 없으면 차트 첫 칸을 시작점으로 확정해 그 앞 채팅을 안 읽었다(PR
    // #230 codex 3판). 시작점은 짐작이다: 300줄이 찰 때까지(하한까지) 60초·2분·4분… 늘려 가며 앞을 더 읽는다
    const srv = collector(denseThenSparse());
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 3_000_000, T0 + 2_000_000, T0);
    await settle();

    const view = replayView(loader.getSnapshot(), T0 + 3_000_000, T0 + HOUR, T0);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(300);
    expect(view.lines[0]!.at).toBe(T0 + 721_000);
    expect(view.lines.at(-1)!.at).toBe(T0 + 3_000_000);
    // 영상 시각 쪽을 먼저 읽고, 앞은 차례로 늘렸다: 60초 → 2분 → 4분 → 8분 → 16분(빽빽해 두 쪽)
    expect(srv.calls.map((c) => [(c.from - T0) / 1000, (c.to - T0) / 1000])).toEqual([
      [2_000, 3_600],
      [1_940, 2_000],
      [1_820, 1_940],
      [1_580, 1_820],
      [1_100, 1_580],
      [140, 1_100],
      [140, 1_100],
    ]);
  });

  it('하한까지 읽어도 300줄이 안 되면 거기서 멈추고 다 읽은 것이다', async () => {
    const items: WireItem[] = [];
    for (let s = 0; s < 3_600; s += 100) items.push(chatAt(T0 + s * 1000, s + 1));
    const srv = collector(items);
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 3_000_000, T0 + 2_940_000, T0);
    await settle();

    expect(loader.getSnapshot().from).toBe(T0);
    const view = replayView(loader.getSnapshot(), T0 + 3_000_000, T0 + HOUR, T0);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(31);
    const asked = srv.calls.length;
    loader.want(T0 + 3_001_000, T0 + 2_941_000, T0);
    await settle();
    expect(srv.calls).toHaveLength(asked);
  });

  it('앞을 늘리는 길이는 모자랄 때마다 60초부터 다시 센다: 멀리 옮긴 뒤 첫 늘림이 앞서 늘린 길이(1시간)로 크게 읽지 않는다', async () => {
    const items: WireItem[] = [];
    for (let s = 0; s < 7_200; s += 100) items.push(chatAt(T0 + s * 1000, s + 1));
    const srv = collector(items);
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + 2 * HOUR);
    loader.want(T0 + 3_000_000, T0 + 2_940_000, T0);
    await settle();
    expect(loader.getSnapshot().from).toBe(T0);

    const asked = srv.calls.length;
    loader.want(T0 + 7_000_000, T0 + 6_940_000, T0);
    await settle();
    expect(srv.calls[asked]).toEqual({ from: T0 + 6_940_000, to: T0 + 2 * HOUR, cursor: null });
    expect(srv.calls[asked + 1]).toEqual({
      from: T0 + 6_880_000,
      to: T0 + 6_940_000,
      cursor: null,
    });
  });

  it('앞을 더 읽는 동안은 이른 쪽을 읽는 중이다: 보이는 줄은 그대로 두고, 다 채우면 다 읽은 것이다', async () => {
    const srv = collector(denseThenSparse());
    let release: () => void = () => undefined;
    // 첫 쪽(시작점부터)은 바로 답하고, 앞을 더 읽는 요청부터 붙든다
    const first = srv.fetchPage;
    let asked = 0;
    const held: ReplayFetch = (from, to, cursor, signal) => {
      asked += 1;
      if (asked === 1) return first(from, to, cursor, signal);
      return new Promise((resolve) => {
        release = () => resolve(first(from, to, cursor, signal));
      });
    };
    const gated = new ChatReplayLoader(held, T0, T0 + HOUR);
    gated.want(T0 + 3_000_000, T0 + 2_000_000, T0);
    await settle();
    const during = replayView(gated.getSnapshot(), T0 + 3_000_000, T0 + HOUR, T0);
    expect(during.status).toBe('earlierLoading');
    expect(during.lines).toHaveLength(11);
    expect(during.lines.at(-1)!.at).toBe(T0 + 3_000_000);

    // 60초·2분·4분·8분·16분(두 쪽)
    for (let i = 0; i < 6; i += 1) {
      release();
      await settle();
    }
    const after = replayView(gated.getSnapshot(), T0 + 3_000_000, T0 + HOUR, T0);
    expect(after.status).toBe('ready');
    expect(after.lines).toHaveLength(300);
  });

  it('앞을 덧붙이는 동안은 버리지 않는다: 답이 오기 전에 늦은 쪽으로 갔다 돌아와도 빈 곳 없이 그 시각의 줄이다', async () => {
    // 덧붙이기는 세울 때의 앞 경계까지 읽어 붙인다. 그 사이 늦은 쪽으로 옮겨 앞 줄을 버리면(앞 경계가 오름) 붙일 때 그 사이가 빈 채
    // 「다 읽음」이 되어, 되돌아온 시각에 10초쯤 이른 줄을 보이고 다시 읽지도 않았다(리뷰 2라운드: 급증 둘레의 ← → → ←)
    const srv = collector(steady(600, 50));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 300_000, T0 + 294_000);
    await settle();
    for (let s = 301; s <= 320; s += 1) {
      loader.want(T0 + s * 1000, T0 + (s - 6) * 1000);
      await settle();
    }
    expect(loader.getSnapshot().from).toBe(T0 + 294_000);

    // ←로 다 읽은 구간 바로 앞(285초)에 간다: [279, 294)를 덧붙이는데 그 답이 늦다
    let release: () => void = () => undefined;
    srv.state.gate = new Promise((resolve) => {
      release = resolve;
    });
    loader.want(T0 + 285_000, T0 + 279_000);
    await settle();
    expect(srv.calls.at(-1)).toEqual({ from: T0 + 279_000, to: T0 + 294_000, cursor: null });
    // 답이 오기 전에 → 일곱 번(355초)으로 앞 줄이 2,900줄을 넘게 쌓였다가 ← 다섯 번으로 300초에 돌아온다
    for (let s = 295; s <= 355; s += 10) loader.want(T0 + s * 1000, T0 + (s - 6) * 1000);
    loader.want(T0 + 300_000, T0 + 294_000);
    srv.state.gate = null;
    release();
    await settle();

    const view = replayView(loader.getSnapshot(), T0 + 300_000, T0 + HOUR, T0 + 300_000);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(300);
    // 초당 50줄(20ms마다)이라 300줄은 영상 시각 앞 6초다
    expect(view.lines[0]!.at).toBe(T0 + 294_020);
    expect(view.lines.at(-1)!.at).toBe(T0 + 300_000);
  });

  it('채팅이 빽빽하면 영상 시각 앞 30초를 남긴다: ← 한 번(10초)에 방금 버린 줄을 다시 받지 않는다', async () => {
    // 600줄만 남기면 초당 50줄은 12초라 ← 한 번이 쥔 기록을 넘어 다시 받는 동안 채팅 창이 비었다(리뷰 2라운드)
    const srv = collector(steady(600, 50));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 300_000, T0 + 294_000);
    await settle();
    for (let s = 301; s <= 420; s += 1) {
      loader.want(T0 + s * 1000, T0 + (s - 6) * 1000);
      await settle();
    }
    const snap = loader.getSnapshot();
    // 오래 틀어도 쌓이지 않는다
    expect(snap.from).toBeGreaterThan(T0 + 300_000);
    expect(snap.from).toBeLessThanOrEqual(T0 + 390_000);
    const calls = srv.calls.length;

    loader.want(T0 + 410_000, T0 + 404_000);
    await settle();

    expect(srv.calls).toHaveLength(calls);
    const view = replayView(loader.getSnapshot(), T0 + 410_000, T0 + HOUR, T0 + 410_000);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(300);
  });

  it('앞을 덧붙이는 읽기가 실패하면 이른 쪽을 못 읽었다고 한다: 몇 줄만 보이는 창을 다 읽은 것처럼 두지 않고 10초 뒤 다시 읽는다', async () => {
    // 덧붙이는 동안을 늘 ready로 보아 실패해도 61줄을 다 읽은 것처럼 두었다(리뷰 2라운드)
    const srv = collector(steady(1_200));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 540_000);
    await settle();
    srv.state.fail = true;

    loader.want(T0 + 600_000, T0 + 301_000);
    await settle();
    let view = replayView(loader.getSnapshot(), T0 + 600_000, T0 + HOUR, T0 + 301_000);
    expect(view.status).toBe('earlierFailed');
    expect(view.lines).toHaveLength(61);

    srv.state.fail = false;
    await vi.advanceTimersByTimeAsync(10_000);
    await settle();
    view = replayView(loader.getSnapshot(), T0 + 600_000, T0 + HOUR, T0 + 301_000);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(300);
  });

  it('실패 뒤 다시 읽기에 읽을 것이 없으면 실패 표시를 내린다: 나중의 정상 읽기 동안 「불러오지 못했어요」가 서지 않는다', async () => {
    // 성공해야만 내리면 다시 읽기가 할 일이 없을 때 실패가 남아, 몇 분 뒤 다른 카드를 읽는 동안에도 실패라 했다(리뷰 3라운드)
    const srv = collector(steady(1_200));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 540_000);
    await settle();
    // 첫 쪽(500줄)이 1,039초까지 덮었다. 그 뒤를 더 읽어야 하는 자리에서 실패한다
    expect(loader.getSnapshot().to).toBe(T0 + 1_039_000);
    srv.state.fail = true;
    loader.want(T0 + 1_100_000, T0 + 1_040_000);
    await settle();
    expect(loader.getSnapshot().failed).toBe(true);
    // 다 읽은 구간 안으로 돌아온다: 10초 뒤 다시 읽기에 더 읽을 것이 없다
    loader.want(T0 + 600_000, T0 + 540_000);
    await vi.advanceTimersByTimeAsync(10_000);
    await settle();
    expect(loader.getSnapshot().failed).toBe(false);

    // 몇 분 뒤 정상인 서버로 늦은 쪽을 읽는 동안은 읽는 중이다
    srv.state.fail = false;
    srv.state.gate = new Promise(() => undefined);
    loader.want(T0 + 1_100_000, T0 + 1_040_000);
    await settle();
    expect(replayView(loader.getSnapshot(), T0 + 1_100_000, T0 + HOUR, T0 + 1_040_000).status).toBe(
      'loading',
    );
  });

  it('실패가 이어지면 다시 읽는 동안에도 실패로 둔다: 알림이 10초마다 「불러오고 있어요」와 번갈아 바뀌지 않는다', async () => {
    const srv = collector(steady(1_200));
    srv.state.fail = true;
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    const statuses: string[] = [];
    loader.subscribe(() =>
      statuses.push(replayView(loader.getSnapshot(), T0 + 600_000, T0 + HOUR, T0 + 600_000).status),
    );
    loader.want(T0 + 600_000, T0 + 540_000);
    await settle();
    await vi.advanceTimersByTimeAsync(30_000);
    await settle();
    expect(srv.calls.length).toBeGreaterThanOrEqual(4);
    // 처음 읽는 중이었다가 실패한 뒤로는 실패 그대로다
    expect(statuses).toEqual(['loading', 'failed']);

    srv.state.fail = false;
    await vi.advanceTimersByTimeAsync(10_000);
    await settle();
    expect(statuses.at(-1)).toBe('ready');
  });

  it.each([
    ['끊으면 바로 실패하는 요청', true],
    ['끊어도 늦게 답이 오는 요청', false],
  ])(
    '처음부터 다시 읽으면 쪽 수를 새로 센다(%s): 연달아 옮기는 동안 받은 정상 답까지 세어 실패로 서지 않는다',
    async (_, rejectOnAbort) => {
      // 한 번 채우기 상한(50쪽)을 다시 읽기 사이로 이어 세어, 실패한 요청 없이 「불러오지 못했어요」가 섰다(리뷰 2라운드). 끊긴
      // 요청은 실패로도(브라우저 fetch) 답으로도(끊기 전에 받은 본문) 돌아온다: 두 길 다 새로 센다
      const srv = collector(steady(3_600, 10), 20);
      let held = 0;
      let release: () => void = () => undefined;
      const fetchPage: ReplayFetch = (from, to, cursor, signal) => {
        held += 1;
        if (held !== 41) return srv.fetchPage(from, to, cursor, signal);
        // 41번째 요청은 그 사이 멀리 옮길 때까지 답하지 않는다
        return new Promise((resolve, reject) => {
          if (rejectOnAbort) signal.addEventListener('abort', () => reject(new Error('aborted')));
          else release = () => resolve(srv.fetchPage(from, to, cursor, signal));
        });
      };
      const loader = new ChatReplayLoader(fetchPage, T0, T0 + HOUR);
      // 20줄 쪽(2초)이라 [540, 630)초를 다 읽으려면 45쪽이다
      loader.want(T0 + 600_000, T0 + 540_000);
      await settle();
      expect(held).toBe(41);

      loader.want(T0 + 1_800_000, T0 + 1_740_000);
      release();
      await settle();

      expect(loader.getSnapshot().failed).toBe(false);
      expect(
        replayView(loader.getSnapshot(), T0 + 1_800_000, T0 + HOUR, T0 + 1_800_000).status,
      ).toBe('ready');
    },
  );

  it('덧붙이다 영상 시각이 늦은 쪽으로 가 더는 모자라지 않으면 덧붙이기를 거두고 늦은 쪽을 읽는다', async () => {
    // 필요 없어진 덧붙이기를 끝까지 읽느라 옮긴 자리의 늦은 쪽 읽기가 그만큼 늦었다(리뷰 2라운드)
    const srv = collector(steady(1_200, 10), 100);
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 570_000);
    await settle();
    let release: () => void = () => undefined;
    srv.state.gate = new Promise((resolve) => {
      release = resolve;
    });
    // [545, 570)초 250줄을 100줄 쪽 셋으로 덧붙인다. 첫 쪽의 답이 늦다
    loader.want(T0 + 575_000, T0 + 545_000);
    await settle();
    const frontCalls = srv.calls.length;
    expect(srv.calls.at(-1)).toEqual({ from: T0 + 545_000, to: T0 + 570_000, cursor: null });

    loader.want(T0 + 650_000, T0 + 620_000);
    srv.state.gate = null;
    release();
    await settle();

    // 덧붙이기의 다음 쪽(545초 창의 커서 '100')을 묻지 않고, 늦은 쪽(570초 창)을 이어 읽는다
    expect(srv.calls[frontCalls]).toEqual({ from: T0 + 570_000, to: T0 + HOUR, cursor: '700' });
    expect(replayView(loader.getSnapshot(), T0 + 650_000, T0 + HOUR, T0 + 650_000).status).toBe(
      'ready',
    );
  });

  it('앞을 덧붙이는 읽기도 쪽 사이 순서를 지킨다: 보정이 쪽마다 달라도 시각 순서다', async () => {
    const srv = collector(steady(1_200), 100);
    let page = 0;
    // 홀수 번째 쪽만 보정이 2초 더 크다고 답한다(드문 상태: 보정이 쪽마다 달라짐)
    const fetchPage: ReplayFetch = async (from, to, cursor, signal) => {
      const answer = await srv.fetchPage(from, to, cursor, signal);
      page += 1;
      return page % 2 === 1 ? { ...answer, appliedOffsetMs: OFFSET + 2_000 } : answer;
    };
    const loader = new ChatReplayLoader(fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 540_000);
    await settle();
    loader.want(T0 + 600_000, T0 + 200_000);
    await settle();

    const ats = loader.getSnapshot().lines.map((l) => l.at);
    expect(ats).toEqual([...ats].sort((a, b) => a - b));
  });

  it('앞을 덧붙이는 읽기의 중간 쪽은 알리지 않는다: 보이는 것이 바뀌지 않았다', async () => {
    // 쪽마다 알려 바뀐 것 없이 대시보드를 다시 그렸다(리뷰 2라운드)
    const srv = collector(steady(1_200), 100);
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 540_000);
    await settle();
    const calls = srv.calls.length;
    let notified = 0;
    loader.subscribe(() => {
      notified += 1;
    });

    // [240, 540)초 300줄을 100줄 쪽 셋으로 덧붙인다
    loader.want(T0 + 600_000, T0 + 240_000);
    await settle();

    expect(srv.calls.length - calls).toBe(3);
    // 다 붙일 때 한 번뿐이다(덧붙이는 중인지는 화면이 읽기 시작점으로 안다)
    expect(notified).toBe(1);
  });

  it('다 읽은 구간 바로 앞(30초 안)으로 되감으면 그 앞만 읽어 붙인다: 쥔 뒤쪽 줄은 다시 받지 않는다', async () => {
    const srv = collector(steady(1_200));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 300_000);
    await settle();

    loader.want(T0 + 290_000, T0);
    await settle();

    expect(srv.calls.at(-1)).toEqual({ from: T0, to: T0 + 300_000, cursor: null });
    expect(srv.calls).toHaveLength(2);
    const view = replayView(loader.getSnapshot(), T0 + 290_000, T0 + HOUR, T0 + 290_000);
    expect(view.status).toBe('ready');
    expect(view.lines).toHaveLength(291);
    expect(view.lines.at(-1)!.at).toBe(T0 + 290_000);
  });

  it('앞을 덧붙이는 읽기도 1시간 창으로 나눠 묻는다', async () => {
    const items = Array.from({ length: 18 }, (_, i) => chatAt(T0 + i * 600_000, i + 1));
    const srv = collector(items);
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + 3 * HOUR);
    // 끝 둘레만 읽었다가 처음부터 읽어야 하는 것을 알았다(채팅이 적은 방송)
    loader.want(T0 + 3 * HOUR, T0 + 3 * HOUR - 60_000);
    await settle();
    loader.want(T0 + 3 * HOUR, T0);
    await settle();

    for (const c of srv.calls) expect(c.to - c.from).toBeLessThanOrEqual(HOUR);
    expect(
      replayView(loader.getSnapshot(), T0 + 3 * HOUR, T0 + 3 * HOUR, T0 + 3 * HOUR).lines,
    ).toHaveLength(18);
  });

  it('처음부터 다시 읽으면 앞서 보낸 요청을 끊는다: 답하지 않는 요청을 기다리다 갇히지 않는다', async () => {
    const srv = collector(steady(1_200));
    // 첫 요청은 영영 답하지 않는다
    srv.state.gate = new Promise(() => undefined);
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 540_000);
    await settle();
    srv.state.gate = null;

    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();

    expect(srv.signals[0]!.aborted).toBe(true);
    expect(srv.calls).toHaveLength(2);
    expect(replayView(loader.getSnapshot(), T0 + 100_000, T0 + HOUR, T0 + 100_000).status).toBe(
      'ready',
    );
  });

  it.each([
    ['끊으면 실패하는 받기', true],
    ['끊어도 영영 답하지 않는 받기', false],
  ])(
    '답이 끝나지 않는 요청은 10초 뒤 끊고 실패로 둔 뒤 10초 뒤 다시 읽는다(%s): 멈춘 화면도 「불러오고 있어요」에 갇히지 않는다',
    async (_name, honorsAbort) => {
      // 서버가 연결만 받고 답을 끝내지 않으면 읽기가 영영 멈췄다. 쪽 수 상한과 10초 뒤 다시 읽기는 답이 오거나 실패해야 돌고, 끊기는
      // 멀리 옮길 때만이라 멈춘 화면은 「채팅을 불러오고 있어요」에 머물렀다(PR #230 codex)
      const srv = collector(steady(600));
      let hang = true;
      const signals: AbortSignal[] = [];
      const fetchPage: ReplayFetch = (from, to, cursor, signal) => {
        signals.push(signal);
        if (!hang) return srv.fetchPage(from, to, cursor, signal);
        return new Promise((_, reject) => {
          if (honorsAbort) signal.addEventListener('abort', () => reject(new Error('aborted')));
        });
      };
      const loader = new ChatReplayLoader(fetchPage, T0, T0 + HOUR);
      const view = () => replayView(loader.getSnapshot(), T0 + 100_000, T0 + HOUR, T0 + 100_000);
      loader.want(T0 + 100_000, T0 + 40_000);
      await settle();
      await vi.advanceTimersByTimeAsync(9_999);
      await settle();
      expect(view().status).toBe('loading');

      await vi.advanceTimersByTimeAsync(1);
      await settle();
      expect(signals[0]!.aborted).toBe(true);
      expect(view().status).toBe('failed');

      hang = false;
      await vi.advanceTimersByTimeAsync(10_000);
      await settle();
      expect(signals).toHaveLength(2);
      expect(view().status).toBe('ready');
    },
  );

  it('200인데 items가 없는 쪽은 실패로 둔다: 재생이 흘러도 10초 동안 다시 묻지 않는다', async () => {
    const srv = collector(steady(600));
    srv.state.noItems = true;
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();
    for (let s = 101; s <= 105; s += 1) {
      loader.want(T0 + s * 1000, T0 + (s - 60) * 1000);
      await settle();
    }

    expect(srv.calls).toHaveLength(1);
    expect(replayView(loader.getSnapshot(), T0 + 105_000, T0 + HOUR, T0 + 105_000).status).toBe(
      'failed',
    );
  });

  it('한 번에 50쪽을 다 써도 영상 시각에 못 닿으면 1초 쉬고 이어 읽는다: 실패로 두지 않고, 영상이 없어도 「불러오고 있어요」에 갇히지 않는다', async () => {
    // 1초에 3만 줄: 50쪽(2만 5천 줄)으로는 1초도 못 채운다
    const srv = collector(steady(2, 30_000));
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    const view = () => replayView(loader.getSnapshot(), T0 + 1_000, T0 + HOUR, T0 + 1_000);
    loader.want(T0 + 1_000, T0);
    await settle();
    expect(srv.calls).toHaveLength(50);
    expect(view().status).toBe('loading');

    await vi.advanceTimersByTimeAsync(999);
    await settle();
    expect(srv.calls).toHaveLength(50);
    await vi.advanceTimersByTimeAsync(1);
    await settle();
    expect(srv.calls.length).toBeGreaterThan(50);

    await vi.advanceTimersByTimeAsync(10_000);
    await settle();
    expect(view().status).toBe('ready');
  });

  it('정상 답만으로 한 번에 50쪽을 다 써도 실패로 두지 않는다: 다시 시작 없이 영상 시각이 계속 늦은 쪽으로 가도(→ 연타) 「불러오지 못했어요」가 서지 않는다', async () => {
    // 쪽 수 상한은 쏟아지는 요청을 막는 것이다. 다 쓰면 실패로 두어, 받는 동안 →를 연타하면 실패한 요청 하나 없이 「채팅을
    // 불러오지 못했어요」가 10초 섰다(리뷰 3라운드 P6)
    const srv = collector(steady(3_600, 10), 20);
    let cursor = T0 + 600_000;
    const loader: ChatReplayLoader = new ChatReplayLoader(
      async (from, to, after, signal) => {
        const page = await srv.fetchPage(from, to, after, signal);
        // 받는 동안 영상 시각이 늦은 쪽으로 간다: 다시 시작할 만큼 멀지는 않다
        cursor += 2_000;
        loader.want(cursor, cursor - 60_000);
        return page;
      },
      T0,
      T0 + HOUR,
    );
    loader.want(cursor, cursor - 60_000);
    await settle();
    expect(srv.calls).toHaveLength(50);
    expect(loader.getSnapshot().failed).toBe(false);

    await vi.advanceTimersByTimeAsync(1_000);
    await settle();
    expect(srv.calls.length).toBeGreaterThan(50);
    expect(loader.getSnapshot().failed).toBe(false);
  });

  it('빈 쪽에 커서가 달려 와도 멈춘다: 끝없이 묻지 않는다', async () => {
    const srv = collector([]);
    srv.state.emptyWithCursor = true;
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();

    expect(srv.calls).toHaveLength(1);
  });

  it('읽지 못하면 실패로 두고 10초 뒤 다시 읽는다', async () => {
    const srv = collector(steady(600));
    srv.state.fail = true;
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 100_000, T0 + 40_000);
    await settle();
    expect(replayView(loader.getSnapshot(), T0 + 100_000, T0 + HOUR, T0 + 100_000).status).toBe(
      'failed',
    );

    // 재생이 흘러도 10초 전에는 다시 묻지 않는다
    loader.want(T0 + 101_000, T0 + 41_000);
    await settle();
    expect(srv.calls).toHaveLength(1);

    srv.state.fail = false;
    await vi.advanceTimersByTimeAsync(10_000);
    await settle();
    expect(srv.calls).toHaveLength(2);
    expect(replayView(loader.getSnapshot(), T0 + 101_000, T0 + HOUR, T0 + 101_000).status).toBe(
      'ready',
    );
  });

  it('다시 읽기 시작하면 앞서 보낸 요청의 답은 버린다', async () => {
    const srv = collector(steady(1_200));
    let release: () => void = () => undefined;
    srv.state.gate = new Promise((resolve) => {
      release = resolve;
    });
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 600_000, T0 + 540_000);
    loader.want(T0 + 100_000, T0 + 40_000);
    srv.state.gate = null;
    release();
    await settle();

    const snap = loader.getSnapshot();
    expect(snap.from).toBe(T0 + 40_000);
    // 540초부터 읽은 첫 답이 섞였다면 줄 시각이 거꾸로 가거나 겹친다
    const ats = snap.lines.map((l) => l.at);
    expect(ats[0]).toBe(T0 + 40_000);
    expect(ats).toEqual([...ats].sort((a, b) => a - b));
    expect(new Set(ats).size).toBe(ats.length);
  });

  it('한 번에 묻는 창은 1시간을 넘지 않는다: 수집기는 넘으면 400 too_wide다', async () => {
    const items = Array.from({ length: 18 }, (_, i) => chatAt(T0 + i * 600_000, i + 1));
    const srv = collector(items);
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + 3 * HOUR);
    loader.want(T0 + 2.5 * HOUR, T0 + 600_000);
    await settle();

    expect(srv.calls.length).toBeGreaterThanOrEqual(3);
    for (const c of srv.calls) expect(c.to - c.from).toBeLessThanOrEqual(HOUR);
    expect(
      replayView(loader.getSnapshot(), T0 + 2.5 * HOUR, T0 + 3 * HOUR, T0 + 2.5 * HOUR).status,
    ).toBe('ready');
  });

  it('방송 끝 뒤로는 읽지 않는다: 끝까지 읽었으면 끝 시각이 커서여도 다 읽은 것이다', async () => {
    const srv = collector(steady(600));
    const end = T0 + 600_000;
    const loader = new ChatReplayLoader(srv.fetchPage, T0, end);
    loader.want(end, end - 60_000);
    await settle();

    expect(srv.calls).toEqual([{ from: end - 60_000, to: end, cursor: null }]);
    const view = replayView(loader.getSnapshot(), end, end, end);
    expect(view.status).toBe('ready');
    expect(view.lines.at(-1)!.at).toBe(end - 1_000);
  });

  it('채팅과 후원의 번호가 같아도 줄 번호는 겹치지 않는다: 두 표의 id는 따로 센다', async () => {
    const srv = collector([chatAt(T0 + 1_000, 7), chatAt(T0 + 2_000, 7, 'donation')]);
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    loader.want(T0 + 10_000, T0);
    await settle();

    const seqs = loader.getSnapshot().lines.map((l) => l.seq);
    expect(seqs).toHaveLength(2);
    expect(new Set(seqs).size).toBe(2);
  });

  it('치운 뒤에는 묻지도 알리지도 않는다', async () => {
    const srv = collector(steady(600));
    let release: () => void = () => undefined;
    srv.state.gate = new Promise((resolve) => {
      release = resolve;
    });
    const loader = new ChatReplayLoader(srv.fetchPage, T0, T0 + HOUR);
    const heard = vi.fn();
    loader.subscribe(heard);
    loader.want(T0 + 100_000, T0 + 40_000);
    heard.mockClear();
    loader.dispose();
    release();
    await settle();
    loader.want(T0 + 300_000, T0 + 240_000);
    await settle();

    expect(heard).not.toHaveBeenCalled();
    expect(srv.calls).toHaveLength(1);
  });
});
