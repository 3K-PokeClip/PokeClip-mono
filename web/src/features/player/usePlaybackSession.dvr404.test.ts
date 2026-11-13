import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import {
  assertTransitionInvariants,
  holeGuard,
  relistGuard,
  resumeBySpec,
  revisitGuard,
} from './dvr404TestKit';
import type * as rewindSwitchModule from './rewindSwitch';
import {
  SESSION_NOTICES,
  transition,
  type DvrSegment,
  type SessionEffect,
  type SessionEvent,
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

// 방송 중 되감기 조각 404 의 목록 다시 받기 — 세션 훅 층(POK-528 c7 — 계획 3-3 (5) · 8-3b).
// 가짜 재생기 없이 dispatch 로 사건을 넣는다(계획 8-3b 투입 경로): 세션이 재생기를 새로
// 세울 때마다(세대 상승) 시험이 정한 대로 그 세대의 첫 목록 · 화면 · 404 를 넣는다. 스트림
// 경로는 물을 때마다 시험이 정한 답을 준다. 모든 전이에 감싸개 불변식과 다시 받기 ·
// 재방문 계수기를 건다(계획 8-0). 옛 표는 404 에서 제어면을 다시 읽으므로(c3), 음성 대조
// 트리에서는 「다시 받기 · 막힘」 단언으로 FAIL 한다.

const BASE = 'https://media.pokeclip.com';
const NOW = Date.parse('2026-10-25T06:00:00.000Z');
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const FIRST_F = NOW - 3_600_000;
const MIN = 60_000;
/** 시험 창(계획 8-3b — 60초) */
const WINDOW_MS = 60_000;
/** 시계를 미는 간격 — 세대 상승을 보고 사건을 넣는 주기 */
const STEP_MS = 100;
/** 정기 물음 주기(useRewindStatus REWIND_POLL_MS) */
const POLL_MS = 10_000;
/**
 * 세대마다 목록 · 화면 · 404 를 넣는 행의 걸음 — 그 행은 걸음마다 세대가 올라 STEP_MS 로는 60초에
 * 다시 받기 600 · 전이 2261 을 돌려, 부하 높은 기계에서 5초 시한을 넘겼다. 다시 받기 · 화면
 * 고리는 시계가 아니라 사건으로만 넘어가므로 걸음을 넓혀도 단언의 뜻은 같고, 정기 물음보다
 * 짧아 물음이 고리 사이에 거듭 든다
 */
const PAINT_CYCLE_STEP_MS = 1_000;
/** 막힘의 refresh 가 답 url 이 그대로인 동안 다시 묻는 수(useRewindStatus REWIND_RETRY_COUNT · R16) */
const BLOCKED_RETRIES = 3;
/** 라이브에서 본 자리 · 라이브에서 되감아 청한 자리(58분 전) */
const LIVE_AT = NOW - 5_000;
const ASKED = NOW - 58 * MIN;
/**
 * 같은 회차 새 url 이 처음 실리는 스트림 답의 순번(0 부터 — 다섯째 답). 넷째 답부터 버리는 결함이
 * 그 답을 놓치게 셋째 답보다 뒤로 둔다(정식 교정 ① 변이 MB2)
 */
const LATE_ANSWER = 4;

interface StreamAnswer {
  url: string;
  floorMs: number;
}

/** n 번째(0 부터) 스트림 물음의 답 */
type Answers = (n: number) => StreamAnswer;

/** 재생기가 404 로 올리는 조각의 sn 바탕 — 정상 경우는 세대마다 다른 조각이다(segmentOf) */
const BASE_SN = 100;

/** 그 세대 재생기의 404 조각 — 세대마다 다르다(서로 다른 조각의 404) */
function segmentOf(generation: number): DvrSegment {
  return { type: 'main', sn: BASE_SN + generation };
}

/**
 * 새 재생기가 차례로 내는 것 — 첫 목록(list) · 화면 붙음(paint) · 그 세대 조각의 404(own) · 적은
 * 조각의 404
 */
type PlayerStep = 'list' | 'paint' | 'own' | DvrSegment;

/** 각본 — offset 번째로 선 새 재생기(1 = 첫 404 뒤 다시 받은 재생기)가 낼 것 */
type Script = (offset: number) => PlayerStep[];

/**
 * 새 세대(재생기)가 설 때마다 시험이 넣는 것 — 화면 전 404 · 첫 목록 뒤 404 · 화면 붙음 → 404 · 첫
 * 목록 뒤 화면 붙음(404 없음) · 아무것도 · 각본
 */
type OnSwap = 'gone' | 'list-then-gone' | 'paint-then-gone' | 'paint' | 'none' | Script;

const NAMED_SWAPS: Record<Exclude<OnSwap, Script>, PlayerStep[]> = {
  gone: ['own'],
  'list-then-gone': ['list', 'own'],
  'paint-then-gone': ['list', 'paint', 'own'],
  paint: ['list', 'paint'],
  none: [],
};

let streamStarts: number[];
/**
 * 지금 시험이 끝났는가 — vitest 는 시한을 넘긴 시험 함수를 멈추지 않으므로, 그 시계 고리가 다음
 * 시험까지 돌며 옛 세션에 사건을 넣어 같은 transition 모의에 섞인다(다음 행 relists 2 ≠ 1).
 * afterEach 가 끝을 적고 runRelist 가 매 걸음 확인해 멈춘다
 */
let testState: { ended: boolean };

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
  vi.mocked(transition).mockClear();
  streamStarts = [];
  testState = { ended: false };
});
afterEach(() => {
  testState.ended = true;
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function serve(answers: Answers): void {
  stubFetch((url) => {
    if (url !== STREAM) return jsonResponse(404, { error: 'not_found' });
    const answer = answers(streamStarts.length);
    streamStarts.push(Date.now());
    return jsonResponse(200, {
      ready: true,
      mode: 'live',
      url: answer.url,
      firstPdt: new Date(answer.floorMs).toISOString(),
      headPdt: new Date(Date.now()).toISOString(),
    });
  });
}

/** 늘 같은 url · 같은 첫 조각의 답 */
const steady: Answers = () => ({ url: A1, floorMs: FIRST_F });

async function flush() {
  await act(async () => {
    for (let i = 0; i < 10; i += 1) {
      await Promise.resolve();
    }
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

interface Checked {
  event: SessionEvent;
  now: number;
  /** 이 전이가 세대를 올렸다 */
  raised: boolean;
  next: Transition;
}

/**
 * 감싼 transition 의 모든 호출에 감싸개 불변식 · 다시 받기 · 재방문 계수기를 걸고(계획 8-0)
 * 호출마다 [사건, 시각, 세대 상승, 결과] 를 돌려준다
 */
function checkedTransitions(): Checked[] {
  const { calls, results } = vi.mocked(transition).mock;
  const guards = [relistGuard(), revisitGuard(), holeGuard()];
  return calls.map(([before, event, now], i) => {
    const result = results[i];
    if (result?.type !== 'return') throw new Error(`transition ${i} 이 돌려주지 않았다`);
    const next = result.value as Transition;
    assertTransitionInvariants(before, event, next);
    for (const guard of guards) {
      guard.observe(before, event, next.state);
    }
    const raised = next.state.source.generation > before.source.generation;
    return { event, now, raised, next };
  });
}

interface Run {
  /** 세대를 올린 전이 — 404 로(다시 받기) · 스트림 폴링으로(교체) · 리마운트로 */
  relists: number;
  pollSwaps: number;
  remounts: number;
  /** 막힌 끝남 대기로 든 전이 수 */
  blocked: number;
  /** 세션에 들어간 404 사건 수 */
  gones: number;
  effects: SessionEffect[];
  notices: string[];
  session: () => PlaybackSession;
}

/** 세션을 열고 라이브 답을 받는다. liveAt 이 있으면 라이브 재생기가 그 자리를 냈다 */
async function openLive(answers: Answers, liveAt: number | null) {
  serve(answers);
  const onNotice = vi.fn();
  const { result } = renderHook(() => usePlaybackSession(options(onNotice)));
  await flush();
  const session = (): PlaybackSession => {
    const current = result.current;
    if (current === null) throw new Error('세션이 없다');
    return current;
  };
  const dispatch = (event: SessionEvent) => act(() => session().dispatch(event));
  if (liveAt !== null) {
    dispatch({
      type: 'position',
      atMs: liveAt,
      playing: true,
      runMs: 0,
      epoch: session().positionEpoch(),
    });
  }
  return { session, dispatch, onNotice };
}

/**
 * 지금 세대 재생기가 낼 것 — 첫 목록(잘림 cut) · 화면은 사건으로, 404 는 조각으로(올릴지는 재생기가
 * 정한다 — runRelist). offset = 몇 번째로 선 새 재생기인가
 */
function playerSteps(
  session: () => PlaybackSession,
  onSwap: OnSwap,
  offset: number,
): Array<SessionEvent | DvrSegment> {
  const { state } = session();
  const listLoaded: SessionEvent = {
    type: 'list-loaded',
    url: state.source.url,
    firstMs: FIRST_F,
    lastEndMs: NOW,
    targetDurationS: 4,
    headSpanMs: 12_000,
    startFloorCut: true,
  };
  const painted: SessionEvent = {
    type: 'position',
    atMs: (state.source.startAtMs ?? FIRST_F) + 4_000,
    playing: true,
    runMs: 0,
    epoch: session().positionEpoch(),
  };
  const steps = typeof onSwap === 'function' ? onSwap(offset) : NAMED_SWAPS[onSwap];
  return steps.map((step) => {
    if (step === 'list') return listLoaded;
    if (step === 'paint') return painted;
    return step === 'own' ? segmentOf(state.source.generation) : step;
  });
}

/**
 * 세션을 열어 라이브 답을 받고(liveAt 이 있으면 라이브 자리도) targetMs 로 되감은 뒤 404 하나(조각
 * first — 없으면 그 세대 조각)를 넣고, 시계를 60초 stepMs 씩 밀며 세대가 오를 때마다 onSwap 대로
 * 사건을 넣는다. asks = 재생기가 404 를 올리기 전에 세션에 아는 구멍인지 묻고, 그러면 올리지
 * 않는다(hls.js 가 그 조각을 틈으로 넘긴 것처럼)
 */
async function runRelist(
  answers: Answers,
  onSwap: OnSwap,
  {
    liveAt = null,
    targetMs = NOW - 10 * MIN,
    stepMs = STEP_MS,
    first,
    asks = false,
  }: {
    liveAt?: number | null;
    targetMs?: number;
    stepMs?: number;
    first?: DvrSegment;
    asks?: boolean;
  } = {},
): Promise<Run> {
  const mine = testState;
  const stopIfEnded = () => {
    if (mine.ended) throw new Error('시험이 이미 끝났다 — 시한을 넘긴 시계 고리를 멈춘다');
  };
  const { session, dispatch, onNotice } = await openLive(answers, liveAt);
  dispatch({ type: 'seek', targetMs, view: { lowerMs: NOW - 16_000, endMs: NOW } });
  expect(session().state.phase.kind).toBe('rewind');
  const raise = (generation: number, segment: DvrSegment) => {
    // 옛 판 세션에는 질의가 없다 — 모르는 구멍으로 친다(음성 대조)
    if (asks && (session().isKnownDvrHole?.(generation, segment) ?? false)) return;
    dispatch({ type: 'dvr-segment-gone', generation, segment });
  };
  let seen = session().state.source.generation;
  raise(seen, first ?? segmentOf(seen));
  await flush();
  let offset = 0;
  for (let t = 0; t < WINDOW_MS; t += stepMs) {
    stopIfEnded();
    await act(async () => vi.advanceTimersByTime(stepMs));
    await flush();
    stopIfEnded();
    const { generation } = session().state.source;
    if (generation === seen) continue;
    seen = generation;
    offset += 1;
    for (const step of playerSteps(session, onSwap, offset)) {
      if ('sn' in step) raise(generation, step);
      else dispatch(step);
    }
  }
  // 마지막 반복에 넣은 사건이 띄운 비동기 일(라이브 창 탐침 등)을 act 안에서 끝낸 뒤에 센다
  await flush();
  const all = checkedTransitions();
  const raisedBy = (type: SessionEvent['type']) =>
    all.filter(({ raised, event }) => raised && event.type === type).length;
  const blocked = all.filter(({ event, next }) => {
    const { phase } = next.state;
    return (
      event.type === 'dvr-segment-gone' && phase.kind === 'ending-wait' && phase.blocked === true
    );
  }).length;
  return {
    relists: raisedBy('dvr-segment-gone'),
    pollSwaps: raisedBy('stream-poll'),
    remounts: raisedBy('remount'),
    blocked,
    gones: all.filter(({ event }) => event.type === 'dvr-segment-gone').length,
    effects: all.flatMap(({ next }) => next.effects),
    notices: onNotice.mock.calls.map(([text]) => String(text)),
    session,
  };
}

function refreshes(run: Run): number {
  return run.effects.filter((e) => e.kind === 'refresh').length;
}

describe('목록 다시 받기의 되풀이 상한 — 세션 훅 층(계획 3-3 (5) · 8-3b)', () => {
  it('불변식 깨짐: 새 세대마다 곧바로 404 → 60초 동안 다시 받기 1 · 막힘 1 · refresh 는 막힘의 하나 · 정기 물음만', async () => {
    const run = await runRelist(steady, 'gone');
    expect(run).toMatchObject({ relists: 1, pollSwaps: 0, remounts: 0, blocked: 1 });
    expect(refreshes(run)).toBe(1);
    expect(run.session().state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    // 열 때 1 · 막힘의 refresh 1 과 그 url 이 그대로인 동안의 다시 묻기 3(R16) · 그 뒤 정기 물음(10초
    // 주기) — 다시 받기(404)는 묻지 않는다
    expect(streamStarts.length).toBeLessThanOrEqual(1 + 1 + BLOCKED_RETRIES + WINDOW_MS / POLL_MS);
  });

  it('R2 교차: 폴링마다 자리를 품지 않는 같은 회차 새 url · 화면 전 404 → 화면 없이 세대 증가 1 · 막힘 1 · 판정 알림 1', async () => {
    const fresh: Answers = (n) =>
      n === 0
        ? { url: A1, floorMs: FIRST_F }
        : { url: `${BASE}/dvr/key-1/b${n}/index.m3u8`, floorMs: NOW - MIN };
    const run = await runRelist(fresh, 'list-then-gone');
    expect(run).toMatchObject({ relists: 1, pollSwaps: 0, blocked: 1 });
    expect(run.notices.filter((text) => text === SESSION_NOTICES.oldest)).toHaveLength(1);
  });

  it('정상(서로 다른 조각): 404(sn k) → 다시 받기 → 화면 → 404(sn k+1) … — 404 마다 다시 받기 · 막힘 0 · refresh 0 · 매번 판정 알림', async () => {
    const run = await runRelist(steady, 'paint-then-gone', { stepMs: PAINT_CYCLE_STEP_MS });
    expect(run.relists).toBeGreaterThanOrEqual(2);
    expect(run.blocked).toBe(0);
    expect(refreshes(run)).toBe(0);
    // 마지막 다시 받기는 창이 끝나 첫 목록을 받지 못했다
    expect(run.notices.filter((text) => text === SESSION_NOTICES.oldest)).toHaveLength(
      run.relists - 1,
    );
    expect(run.session().state.phase).toMatchObject({ kind: 'rewind' });
  });
});

describe('이어 볼 자리 — 세션 훅 층(R1 · Q1 · 계획 8-3b)', () => {
  it('R1: 라이브 재생(위치 있음) → 58분 되감기 → 첫 위치 전 404 → 새 재생기의 시작 = 청한 자리', async () => {
    const run = await runRelist(steady, 'none', { liveAt: LIVE_AT, targetMs: ASKED });
    expect(run.relists).toBe(1);
    expect(run.session().state.source).toMatchObject({ kind: 'rewind', url: A1, startAtMs: ASKED });
  });

  it('Q1: 같은 차림 · 스트림 답이 라이브 위치만 품은 같은 회차 새 url → url 그대로 · 시작 = 청한 자리', async () => {
    const liveOnly: Answers = (n) =>
      n === 0
        ? { url: A1, floorMs: FIRST_F }
        : { url: `${BASE}/dvr/key-1/a2/index.m3u8`, floorMs: NOW - 30 * MIN };
    const run = await runRelist(liveOnly, 'none', { liveAt: LIVE_AT, targetMs: ASKED });
    expect(streamStarts.length).toBeGreaterThan(1);
    expect(run.pollSwaps).toBe(0);
    expect(run.session().state.source).toMatchObject({ url: A1, startAtMs: ASKED });
    expect(run.session().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'list' } });
  });
});

describe('긴 되감기 세션의 정기 스트림 답 — 세션 훅 층(계획 3-4 · 8-3b)', () => {
  it('다시 받아 화면을 붙인 뒤 다섯째 답에 처음 실린 같은 회차 새 url → 그 답이 상태에 닿아 이어 볼 자리에서 갈아 끼운다', async () => {
    const targetMs = NOW - 10 * MIN;
    const later: Answers = (n) =>
      n < LATE_ANSWER
        ? { url: A1, floorMs: FIRST_F }
        : { url: `${BASE}/dvr/key-1/a2/index.m3u8`, floorMs: NOW - 30 * MIN };
    const run = await runRelist(later, 'paint', { targetMs });
    expect(streamStarts.length).toBeGreaterThan(LATE_ANSWER);
    expect(run).toMatchObject({ relists: 1, pollSwaps: 1, remounts: 0, blocked: 0 });
    // 다시 받은 재생기가 청한 자리 + 4초에 화면을 붙였다 — 그 자리가 이어 볼 자리다
    expect(run.session().state.source).toMatchObject({
      kind: 'rewind',
      url: `${BASE}/dvr/key-1/a2/index.m3u8`,
      startAtMs: targetMs + 4_000,
    });
    expect(run.session().state.entry).toMatchObject({ firstPdtMs: NOW - 30 * MIN });
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
      readSound: () => null,
    };
  }

  /** 세션을 열고 재생기를 묶은 뒤 되감는다 */
  async function rewindWithPlayer() {
    const { session, dispatch } = await openLive(steady, null);
    const player = fakePlayer();
    act(() => session().bindPlayer(player));
    dispatch({
      type: 'seek',
      targetMs: NOW - 600_000,
      view: { lowerMs: NOW - 16_000, endMs: NOW },
    });
    expect(session().state.phase.kind).toBe('rewind');
    const gone = () => {
      const { generation } = session().state.source;
      dispatch({ type: 'dvr-segment-gone', generation, segment: segmentOf(generation) });
    };
    return { session, player, gone };
  }

  it('막힌 끝남 대기의 hold-frame 은 재생기 holdFrame 을 한 번 부르고 멈춤 유지는 걸지 않는다', async () => {
    const { session, player, gone } = await rewindWithPlayer();
    act(() => session().dispatch({ type: 'endlist' }));
    await flush();
    gone();
    await flush();
    expect(session().state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(player.holdFrame).toHaveBeenCalledTimes(1);
    expect(player.keepPausedOnSwap).not.toHaveBeenCalled();
    expect(player.dropPauseMark).not.toHaveBeenCalled();
  });

  it('목록 다시 받기의 drop-pause-mark 는 새 소스를 커밋하기 전에 재생기 dropPauseMark 를 한 번 부른다', async () => {
    const { session, player, gone } = await rewindWithPlayer();
    const before = session().state.source.generation;
    // 커밋 앞인지 — 부른 순간 세션 칸(stateRef 즉시 갱신 · positionEpoch)이 아직 옛 소스인가
    const epochs: number[] = [];
    vi.mocked(player.dropPauseMark).mockImplementation(() => {
      epochs.push(session().positionEpoch());
    });
    const epochBefore = session().positionEpoch();
    gone();
    await flush();
    expect(session().state.source.generation).toBe(before + 1);
    expect(session().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'list' } });
    expect(player.dropPauseMark).toHaveBeenCalledTimes(1);
    expect(epochs).toEqual([epochBefore]);
    expect(player.holdFrame).not.toHaveBeenCalled();
  });
});

// 목록 안 구멍(POK-528 후속 계획 r3 5절) — 재생기는 404 를 올리기 전에 세션에 아는 구멍인지 묻고, 아는 구멍이고
// hls.js 가 틈으로 넘기기로 했으면 올리지 않는다(실제 판정은 useHlsPlayback · hlsErrors 몫 — 여기서는 시험의 흉내
// 재생기가 아는 구멍이면 늘 넘긴다). 옛 판 세션은
// 질의가 없어 늘 올리므로 음성 대조 트리에서는 다시 받기 · 막힘 수 단언으로 FAIL 한다.
describe('세션 층 계약 — 재생기가 아는 구멍을 넘기면', () => {
  const S: DvrSegment = { type: 'main', sn: 41 };
  const T: DvrSegment = { type: 'main', sn: 42 };
  /** 정기 물음만 — 열 때 1 · 10초 주기 */
  const POLLS_ONLY = 1 + WINDOW_MS / POLL_MS;

  it('같은 조각이 화면 전 · 뒤로 거듭 404 → 60초 동안 다시 받기 1 · 세션 404 사건 1 · 막힘 0 · refresh 0 · 끝은 화면 자리의 되감기', async () => {
    const targetMs = NOW - 10 * MIN;
    const script: Script = (offset) => (offset === 1 ? [S, 'list', S, 'paint', S] : []);
    const run = await runRelist(steady, script, { first: S, asks: true, targetMs });
    expect(run).toMatchObject({ relists: 1, pollSwaps: 0, remounts: 0, blocked: 0, gones: 1 });
    expect(refreshes(run)).toBe(0);
    const { state } = run.session();
    expect(state.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(resumeBySpec(state)).toBe(targetMs + 4_000);
    expect(streamStarts.length).toBeLessThanOrEqual(POLLS_ONLY);
  });

  it('s · s · t · t → 다시 받기 2(조각마다 1) · 막힘 0', async () => {
    const script: Script = (offset) =>
      offset === 1 ? ['list', 'paint', S, T] : offset === 2 ? ['list', 'paint', T] : [];
    const run = await runRelist(steady, script, { first: S, asks: true });
    expect(run).toMatchObject({ relists: 2, pollSwaps: 0, remounts: 0, blocked: 0, gones: 2 });
    expect(run.session().state.phase).toEqual({ kind: 'rewind', pendingNext: null });
  });

  it('묻지 않는 재생기(질의 배선 누락 · gap 거짓과 같은 결과)가 화면 뒤 같은 조각을 다시 올리면 → 다시 받기 1 · 막힘 1', async () => {
    const script: Script = (offset) => (offset === 1 ? ['list', 'paint', S] : []);
    const run = await runRelist(steady, script, { first: S });
    expect(run).toMatchObject({ relists: 1, pollSwaps: 0, remounts: 0, blocked: 1, gones: 2 });
    expect(run.session().state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
  });
});
