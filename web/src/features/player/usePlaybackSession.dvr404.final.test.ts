import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { assertTransitionInvariants } from './dvr404TestKit';
import type * as rewindSwitchModule from './rewindSwitch';
import { transition, type XAnswer } from './rewindSwitch';
import {
  usePlaybackSession,
  type PlaybackSession,
  type SessionClosed,
  type SessionOptions,
} from './usePlaybackSession';

// 세션에 들어간 사건 · 나온 효과를 보려고 상태기계를 감싼다 — 동작은 원본 그대로다
vi.mock('./rewindSwitch', async (importOriginal) => {
  const actual = await importOriginal<typeof rewindSwitchModule>();
  return { ...actual, transition: vi.fn(actual.transition) };
});

// 되감기 조각 404 의 세션 훅 배선(POK-528 c4 — 계획 3-6 · 8-1 「8-3 웹 행」 · 8-3).
// ① 마지막 확인(finalCheck)은 회차별 경로만 켜고, 켜는 렌더에서 곧바로 묻는다
//    (useRewindStatus 기동의 첫 물음). 옛 표는 녹화로 정한 끝남 대기의 404 를 무시해
//    회차별 물음이 나가지 않으므로 음성 대조 트리에서는 단언으로 FAIL 한다.
// ② 마지막 확인의 회차별 물음은 한 번이다(정본 r7 1151 「두 번째 확인은 없다」).
//    답을 기다리는 동안 숨김 복귀가 되풀이돼도 요청을 끊고 다시 묻지 않고, 처음
//    물음의 시한(8초)에 녹화로 닫는다.
// ③ 백그라운드 탭 10분(W13 증거 · c7 몫): 숨은 탭은 브라우저가 타이머를 묶어 정기
//    물음이 나가지 않는다. 목록 다시 받기는 제어면 답을 기다리지 않으므로, 숨은
//    동안이든 보이게 된 뒤든 되감기 중의 404 는 곧바로 같은 목록을 다시 받는다(물음 0). 숨은
//    동안은 시계만 밀고(vi.setSystemTime) 타이머는 돌리지 않는다 — 묶인 타이머의
//    꼴이다. usePlaybackSession.dvr404.test.ts 가 아니라 여기 두는 까닭: 숨김 전환
//    setVisibility 와 물음 기록 serve · openRewind 를 ② 와 같이 쓴다.

const BASE = 'https://media.pokeclip.com';
const NOW = Date.parse('2026-10-25T06:00:00.000Z');
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const ROUND = `${BASE}/api/streams/key-1/rounds/r-A/rewind`;
const FIRST_F = NOW - 3_600_000;
const MIN = 60_000;
/** 요청 하나의 시한(useRewindStatus REWIND_REQUEST_TIMEOUT_MS) */
const REQUEST_TIMEOUT_MS = 8_000;
/** 숨은 동안 흐른 시간(계획 8-1 「8-3 웹 행」 — 백그라운드 탭 10분) */
const HIDDEN_MS = 10 * MIN;

/** 답하지 않는 물음 — 끊길 때까지 걸려 있다(요청 시한 · 끊기를 보려고) */
const NO_ANSWER = Symbol('no-answer');

interface Answers {
  /** n 번째(0 부터) 스트림 물음의 본문 */
  stream: (n: number) => unknown;
  /** n 번째 회차별 물음의 본문. NO_ANSWER 면 끊길 때까지 답하지 않는다 */
  round: (n: number) => unknown;
}

let streamStarts: number[];
let roundStarts: number[];
/** 회차별 물음마다 받은 끊기 신호 */
let roundSignals: Array<AbortSignal | undefined>;

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
  vi.mocked(transition).mockClear();
  streamStarts = [];
  roundStarts = [];
  roundSignals = [];
});
afterEach(() => {
  setVisibility('visible');
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function setVisibility(state: DocumentVisibilityState): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: state });
  document.dispatchEvent(new Event('visibilitychange'));
}

function body(patch: Record<string, unknown>): unknown {
  return { ready: false, mode: null, url: null, firstPdt: null, headPdt: null, ...patch };
}

/** 같은 회차의 방송 중 목록 — 첫 조각이 floorMs */
function liveBody(floorMs: number): unknown {
  return body({
    ready: true,
    mode: 'live',
    url: A1,
    firstPdt: new Date(floorMs).toISOString(),
    headPdt: new Date(Date.now()).toISOString(),
  });
}

const ENDED_BODY = body({ mode: 'ended' });

/** 끊기 신호가 올 때까지 답하지 않는 응답 — 끊기면 fetch 처럼 AbortError 로 거절한다 */
function untilAborted(signal: AbortSignal | undefined): Promise<Response> {
  return new Promise((_resolve, reject) => {
    signal?.addEventListener('abort', () => {
      reject(new DOMException('aborted', 'AbortError'));
    });
  });
}

function serve(answers: Answers): void {
  stubFetch((url, init) => {
    if (url === STREAM) {
      streamStarts.push(Date.now());
      return jsonResponse(200, answers.stream(streamStarts.length - 1));
    }
    if (url === ROUND) {
      roundStarts.push(Date.now());
      const signal = init?.signal ?? undefined;
      roundSignals.push(signal);
      const answer = answers.round(roundStarts.length - 1);
      return answer === NO_ANSWER ? untilAborted(signal) : jsonResponse(200, answer);
    }
    return jsonResponse(404, { error: 'not_found' });
  });
}

async function flush() {
  await act(async () => {
    for (let i = 0; i < 10; i += 1) {
      await Promise.resolve();
    }
  });
}

function options(canOpen: boolean, onClose: (closed: SessionClosed) => void): SessionOptions {
  return {
    canOpen,
    roundId: 'r-A',
    accessKey: 'r-A|key-1',
    liveUrl: `${BASE}/live/key-1/index.m3u8`,
    controlKey: 'key-1',
    controlBase: BASE,
    resolveX: () => Promise.resolve<XAnswer>({ kind: 'known', id: 'r-A' }),
    onClose,
  };
}

/** 감싼 transition 의 모든 호출에 감싸개 불변식을 단언한다(계획 8-0) */
function assertAllTransitions(): void {
  const { calls, results } = vi.mocked(transition).mock;
  calls.forEach(([before, event], i) => {
    const result = results[i];
    if (result?.type !== 'return') throw new Error(`transition ${i} 이 돌려주지 않았다`);
    assertTransitionInvariants(before, event, result.value);
  });
}

/** 세션을 열어 라이브 답을 받고 되감은 세션 */
async function openRewind(answers: Answers) {
  serve(answers);
  // 녹화로 닫힌 방송은 다시 열지 않는다 — 화면(LiveScreen)의 여는 조건 꼴
  let canOpen = true;
  const onClose = vi.fn((closed: SessionClosed) => {
    if (closed.reason === 'recorded') canOpen = false;
  });
  const { result } = renderHook(() => usePlaybackSession(options(canOpen, onClose)));
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
  const gone = () =>
    act(() =>
      session().dispatch({
        type: 'dvr-segment-gone',
        generation: session().state.source.generation,
      }),
    );
  return { session, gone, onClose };
}

describe('마지막 확인의 폴링 배선(계획 3-6 · 8-3)', () => {
  it('녹화로 정한 끝남 대기의 404 → 회차별만 곧바로 한 번 묻고 · ended 면 녹화로 닫는다', async () => {
    const { session, gone, onClose } = await openRewind({
      stream: () => liveBody(FIRST_F),
      round: () => ENDED_BODY,
    });
    act(() => session().dispatch({ type: 'endlist' }));
    await flush();
    expect(session().state.phase).toMatchObject({ kind: 'ending-wait', recordOnEnd: true });
    const streamBefore = streamStarts.length;
    expect(roundStarts).toHaveLength(1);
    gone();
    await flush();
    expect(roundStarts).toHaveLength(2);
    expect(roundStarts[1]).toBe(Date.now());
    expect(streamStarts).toHaveLength(streamBefore);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onClose.mock.calls[0]?.[0]).toMatchObject({ reason: 'recorded' });
    assertAllTransitions();
  });

  it('답을 기다리는 동안 숨김 복귀가 되풀이돼도 다시 묻지 않고 · 처음 시한에 녹화로 닫는다', async () => {
    const { session, gone, onClose } = await openRewind({
      stream: () => liveBody(FIRST_F),
      round: (n) => (n === 0 ? ENDED_BODY : NO_ANSWER),
    });
    act(() => session().dispatch({ type: 'endlist' }));
    await flush();
    expect(session().state.phase).toMatchObject({ kind: 'ending-wait', recordOnEnd: true });
    gone();
    await flush();
    expect(session().state.phase).toMatchObject({ kind: 'ending-wait', finalCheck: true });
    expect(roundStarts).toHaveLength(2);
    const askedAt = roundStarts[1] ?? Number.NaN;
    expect(askedAt).toBe(Date.now());
    // 2초 숨김 · 3초 복귀 · 5초 숨김 · 6초 복귀 — 복귀마다 새로 물으면 시한이 밀린다
    for (const [afterMs, state] of [
      [2_000, 'hidden'],
      [3_000, 'visible'],
      [5_000, 'hidden'],
      [6_000, 'visible'],
    ] as const) {
      await act(async () => vi.advanceTimersByTimeAsync(askedAt + afterMs - Date.now()));
      act(() => setVisibility(state));
      await flush();
    }
    expect(roundStarts).toHaveLength(2);
    expect(roundSignals[1]?.aborted).toBe(false);
    expect(onClose).not.toHaveBeenCalled();
    // 처음 물음의 시한 직전까지는 기다리고, 시한에 끊어 녹화로 닫는다
    await act(async () =>
      vi.advanceTimersByTimeAsync(askedAt + REQUEST_TIMEOUT_MS - 1 - Date.now()),
    );
    expect(onClose).not.toHaveBeenCalled();
    await act(async () => vi.advanceTimersByTimeAsync(1));
    await flush();
    expect(roundSignals[1]?.aborted).toBe(true);
    expect(roundStarts).toHaveLength(2);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onClose.mock.calls[0]?.[0]).toMatchObject({ reason: 'recorded' });
    assertAllTransitions();
  });
});

describe('백그라운드 탭 10분 — 404 는 숨김과 무관하게 곧바로 같은 목록을 다시 받는다(8-3b · W13)', () => {
  it('숨은 지 10분 뒤 404 → 타이머를 돌리지 않고 곧바로 세대 + 1 · 같은 url · 물음 0 → 보이게 되어도 다시 받기 유지', async () => {
    const { session, gone } = await openRewind({
      stream: () => liveBody(FIRST_F),
      round: () => ENDED_BODY,
    });
    const { source } = session().state;
    setVisibility('hidden');
    vi.setSystemTime(Date.now() + HIDDEN_MS);
    await flush();
    const asked = streamStarts.length;
    gone();
    await flush();
    expect(session().state.source).toMatchObject({
      url: source.url,
      generation: source.generation + 1,
    });
    expect(session().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'list' } });
    expect(streamStarts).toHaveLength(asked);
    act(() => setVisibility('visible'));
    await flush();
    expect(session().state.source.generation).toBe(source.generation + 1);
    expect(session().state.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'list' } });
    assertAllTransitions();
  });
});
