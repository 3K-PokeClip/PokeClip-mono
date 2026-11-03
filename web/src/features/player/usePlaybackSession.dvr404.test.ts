import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { assertTransitionInvariants } from './dvr404TestKit';
import type * as rewindSwitchModule from './rewindSwitch';
import {
  SESSION_NOTICES,
  transition,
  type SessionEffect,
  type Transition,
  type XAnswer,
} from './rewindSwitch';
import {
  usePlaybackSession,
  type PlaybackSession,
  type SessionOptions,
  type SessionPlayer,
} from './usePlaybackSession';

// 세션에 들어간 사건 · 나온 효과를 보려고 상태기계를 감싼다 — 동작은 원본 그대로다
vi.mock('./rewindSwitch', async (importOriginal) => {
  const actual = await importOriginal<typeof rewindSwitchModule>();
  return { ...actual, transition: vi.fn(actual.transition) };
});

// 방송 중 되감기 조각 404 의 되풀이 속도 · 응답 지연(POK-528 c3 — 계획 3-3 「되풀이 속도
// 상한」 · 8-3b · P2). 엣지 하한이 늘 제어면 F 보다 앞선 꼴이다: 세션이 재생기를 새로
// 세울 때마다(세대 상승) 시험이 곧바로 그 세대의 404 를 넣는다(가짜 재생기 없이
// dispatch 직접 — 계획 8-3b 투입 경로). 스트림 경로는 물을 때마다 첫 조각 F 를 한
// 조각(4초)씩 민 같은 회차 readyLive 로 답한다. 옛 표는 404 사건을 모르는 사건으로
// 받아 상태를 그대로 두므로, 음성 대조 트리에서는 「교체 ≥ 2」 단언으로 FAIL 한다.

const BASE = 'https://media.pokeclip.com';
const NOW = Date.parse('2026-10-25T06:00:00.000Z');
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const FIRST_F = NOW - 3_600_000;
const SEGMENT_MS = 4_000;
/** 시험 창(계획 8-3b — 60초) */
const WINDOW_MS = 60_000;
/** 시계를 미는 간격 — 세대 상승을 보고 404 를 넣는 주기 */
const STEP_MS = 100;
/** 정기 물음 주기(useRewindStatus REWIND_POLL_MS) · 요청 시한(REWIND_REQUEST_TIMEOUT_MS) */
const POLL_MS = 10_000;
const REQUEST_TIMEOUT_MS = 8_000;

interface StreamAnswer {
  /** n 번째(0 부터) 스트림 물음의 답이 실을 첫 조각 */
  floorMs: (n: number) => number;
  /** n 번째 스트림 물음의 응답 지연 */
  delayMs: (n: number) => number;
}

let streamStarts: number[];

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
  vi.mocked(transition).mockClear();
  streamStarts = [];
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function serve(answer: StreamAnswer): void {
  stubFetch((url) => {
    if (url !== STREAM) return jsonResponse(404, { error: 'not_found' });
    const n = streamStarts.length;
    streamStarts.push(Date.now());
    const body = {
      ready: true,
      mode: 'live',
      url: A1,
      firstPdt: new Date(answer.floorMs(n)).toISOString(),
      headPdt: new Date(Date.now()).toISOString(),
    };
    const delay = answer.delayMs(n);
    if (delay === 0) return jsonResponse(200, body);
    return new Promise((resolve) => {
      window.setTimeout(() => resolve(jsonResponse(200, body)), delay);
    });
  });
}

async function flush() {
  await act(async () => {
    for (let i = 0; i < 10; i += 1) await Promise.resolve();
  });
}

function options(onNotice: (text: string) => void): SessionOptions {
  return {
    canOpen: true,
    roundId: 'r-A',
    accessKey: 'r-A|key-1',
    liveUrl: `${BASE}/live/key-1/index.m3u8`,
    controlKey: 'key-1',
    controlBase: BASE,
    resolveX: () => Promise.resolve<XAnswer>({ kind: 'known', id: 'r-A' }),
    onClose: vi.fn(),
    onNotice,
  };
}

/** 감싼 transition 의 호출마다 [앞 상태, 사건, 시각, 결과] */
function transitions() {
  const { calls, results } = vi.mocked(transition).mock;
  return calls.map(([before, event, now], i) => {
    const result = results[i];
    if (result?.type !== 'return') throw new Error(`transition ${i} 이 돌려주지 않았다`);
    return { before, event, now, next: result.value as Transition };
  });
}

interface Run {
  /** 교체(세대 상승) 시각 */
  swaps: number[];
  /** 옮긴 자리(movedFloorMs 있음)에 넣은 404 의 시각 */
  movedGones: number[];
  /** 세션이 받은 스트림 답의 시각 */
  answers: number[];
  effects: SessionEffect[];
  notices: string[];
  /** 404 를 처음 넣은 시각 */
  goneAt: number;
}

/**
 * 세션을 열어 라이브 답을 받고 되감은 뒤 404 하나를 넣고, 시계를 60초 밀며 세대가 오를
 * 때마다 그 세대의 404 를 넣는다. 모든 전이에 감싸개 불변식을 단언한다(계획 8-0)
 */
async function runRepeat(answer: StreamAnswer): Promise<Run> {
  serve(answer);
  const onNotice = vi.fn();
  const { result } = renderHook(() => usePlaybackSession(options(onNotice)));
  await flush();
  const session = (): PlaybackSession => {
    const current = result.current;
    if (current === null) throw new Error('세션이 없다');
    return current;
  };
  act(() =>
    session().dispatch({
      type: 'seek',
      targetMs: NOW - 600_000,
      view: { lowerMs: NOW - 16_000, endMs: NOW },
    }),
  );
  expect(session().state.phase.kind).toBe('rewind');
  const goneAt = Date.now();
  const movedGones: number[] = [];
  let seen = session().state.source.generation;
  act(() => session().dispatch({ type: 'dvr-segment-gone', generation: seen }));
  await flush();
  for (let t = 0; t < WINDOW_MS; t += STEP_MS) {
    await act(async () => vi.advanceTimersByTime(STEP_MS));
    await flush();
    const { state } = session();
    if (state.source.generation === seen) continue;
    seen = state.source.generation;
    if (state.phase.kind === 'rewind' && state.phase.movedFloorMs !== undefined) {
      movedGones.push(Date.now());
    }
    act(() => session().dispatch({ type: 'dvr-segment-gone', generation: seen }));
  }
  const all = transitions();
  for (const { before, next } of all) assertTransitionInvariants(before, next.state);
  const swaps = all
    .filter(({ before, next }) => next.state.source.generation > before.source.generation)
    .filter(({ event }) => event.type === 'stream-poll')
    .map(({ now }) => now);
  const answers = all.filter(({ event }) => event.type === 'stream-poll').map(({ now }) => now);
  return {
    swaps,
    movedGones,
    answers,
    effects: all.flatMap(({ next }) => next.effects),
    notices: onNotice.mock.calls.map(([text]) => String(text)),
    goneAt,
  };
}

/** 이웃한 두 시각의 간격 */
function gapsOf(times: readonly number[]): number[] {
  return times.slice(1).map((at, i) => at - (times[i] ?? at));
}

function refreshes(run: Run): number {
  return run.effects.filter((e) => e.kind === 'refresh').length;
}

/** n 번째 물음의 첫 조각 — 물을 때마다 한 조각씩 나아간다 */
function advancing(n: number): number {
  return FIRST_F + n * SEGMENT_MS;
}

describe('되풀이 속도 상한 — 엣지 하한이 늘 F 보다 앞섬(계획 3-3 · P2)', () => {
  it('60초에 스트림 물음 ≤ 8(열 때 1 + refresh 1 + 정기 6) · 교체 2–7 · refresh 1 · 알림 합 1', async () => {
    const run = await runRepeat({ floorMs: advancing, delayMs: () => 0 });
    expect(streamStarts.length).toBeLessThanOrEqual(8);
    expect(run.swaps.length).toBeGreaterThanOrEqual(2);
    expect(run.swaps.length).toBeLessThanOrEqual(7);
    expect(refreshes(run)).toBe(1);
    expect(run.notices.filter((text) => text === SESSION_NOTICES.oldest)).toHaveLength(1);
  });

  it('같은 F 변형: 답의 F 가 고정이면 60초 동안 교체 1(첫 (가) 만)', async () => {
    const run = await runRepeat({ floorMs: () => FIRST_F, delayMs: () => 0 });
    expect(run.swaps).toHaveLength(1);
    expect(refreshes(run)).toBe(1);
  });
});

describe('응답 지연 변형 — 요청 주기와 응답 · 교체 간격을 나눠 본다(계획 3-3 · cx M2)', () => {
  // 스트림 물음의 응답 지연을 열 때 0 · 그 뒤 7.9초 · 0초로 번갈아 준다(요청 시한 8초 안)
  const alternating = (n: number) => (n % 2 === 1 ? 7_900 : 0);

  it('교체 ≤ 받은 응답 · 정기 물음 시작 간격 10초 · 옮긴 자리 404 → 다음 답 ≤ 10 + 8초', async () => {
    const run = await runRepeat({ floorMs: advancing, delayMs: alternating });
    expect(run.swaps.length).toBeLessThanOrEqual(run.answers.length);
    // 0 = 열 때 · 1 = 404 의 refresh(곧바로) · 그 뒤는 앞 물음 시작 + 10초
    expect(streamStarts[1]).toBe(run.goneAt);
    const gaps = gapsOf(streamStarts.slice(1));
    expect(gaps.length).toBeGreaterThan(0);
    expect(new Set(gaps)).toEqual(new Set([POLL_MS]));
    // 창에 10 + 8초 이상 남은 404 는 다음 답이 반드시 그 안에 온다 — 답이 빠지면 FAIL 한다.
    // 창 끝이 가까운 404 는 다음 답이 창 밖일 수 있어 시한만 본다
    const answerDeadlineMs = POLL_MS + REQUEST_TIMEOUT_MS;
    const windowEnd = run.goneAt + WINDOW_MS;
    const roomy = run.movedGones.filter((at) => windowEnd - at >= answerDeadlineMs);
    expect(roomy.length).toBeGreaterThan(0);
    for (const at of run.movedGones) {
      const next = run.answers.find((answeredAt) => answeredAt > at);
      if (roomy.includes(at)) expect(next, `${at - run.goneAt}ms 의 404`).toBeDefined();
      if (next !== undefined) expect(next - at).toBeLessThanOrEqual(answerDeadlineMs);
    }
  });

  it('교체 간격에는 하한이 없다 — 지연이 흔들리면 10초보다 짧은 간격이 난다(한계 고정)', async () => {
    const run = await runRepeat({ floorMs: advancing, delayMs: alternating });
    expect(Math.min(...gapsOf(run.swaps))).toBeLessThan(POLL_MS);
  });
});

describe('재생기 명령 배선 — hold-frame · drop-pause-mark(POK-528 c5 · 계획 4-1 · 3-3 · 8-3)', () => {
  function fakePlayer(): SessionPlayer {
    return {
      seekToMs: vi.fn(),
      seekToLiveEdge: vi.fn(),
      keepPausedOnSwap: vi.fn(),
      holdFrame: vi.fn(),
      dropPauseMark: vi.fn(),
    };
  }

  /** 세션을 열고 재생기를 묶은 뒤 되감는다 */
  async function rewindWithPlayer(answer: StreamAnswer) {
    serve(answer);
    const { result } = renderHook(() => usePlaybackSession(options(vi.fn())));
    await flush();
    const session = (): PlaybackSession => {
      const current = result.current;
      if (current === null) throw new Error('세션이 없다');
      return current;
    };
    const player = fakePlayer();
    act(() => session().bindPlayer(player));
    act(() =>
      session().dispatch({
        type: 'seek',
        targetMs: NOW - 600_000,
        view: { lowerMs: NOW - 16_000, endMs: NOW },
      }),
    );
    expect(session().state.phase.kind).toBe('rewind');
    const gone = () =>
      act(() =>
        session().dispatch({
          type: 'dvr-segment-gone',
          generation: session().state.source.generation,
        }),
      );
    return { session, player, gone };
  }

  it('막힌 끝남 대기의 hold-frame 은 재생기 holdFrame 을 한 번 부르고 멈춤 유지는 걸지 않는다', async () => {
    const { session, player, gone } = await rewindWithPlayer({
      floorMs: () => FIRST_F,
      delayMs: () => 0,
    });
    act(() => session().dispatch({ type: 'endlist' }));
    await flush();
    gone();
    await flush();
    expect(session().state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(player.holdFrame).toHaveBeenCalledTimes(1);
    expect(player.keepPausedOnSwap).not.toHaveBeenCalled();
    expect(player.dropPauseMark).not.toHaveBeenCalled();
  });

  it('(가) 의 drop-pause-mark 는 새 소스를 커밋하기 전에 재생기 dropPauseMark 를 한 번 부른다', async () => {
    const { session, player, gone } = await rewindWithPlayer({
      floorMs: advancing,
      delayMs: () => 0,
    });
    const before = session().state.source.generation;
    // 커밋 앞인지 — 부른 순간 세션 칸(stateRef 즉시 갱신 · positionEpoch)이 아직 옛 소스인가
    const epochs: number[] = [];
    vi.mocked(player.dropPauseMark).mockImplementation(() => {
      epochs.push(session().positionEpoch());
    });
    const epochBefore = session().positionEpoch();
    gone();
    await flush();
    expect(session().state.source.generation).toBeGreaterThan(before);
    expect(session().state.phase).toMatchObject({ kind: 'rewind' });
    expect(player.dropPauseMark).toHaveBeenCalledTimes(1);
    expect(epochs).toEqual([epochBefore]);
    expect(player.holdFrame).not.toHaveBeenCalled();
  });
});
