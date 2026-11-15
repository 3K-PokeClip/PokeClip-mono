import { act, renderHook } from '@testing-library/react';
import { Suspense, createElement, startTransition, useLayoutEffect } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PastVodState } from '@/features/player/pastVod';
import { RESUME_WAIT_MS } from '@/features/player/sessionShared';
import { REWIND_POLL_MS } from '@/features/player/useRewindStatus';
import { jsonResponse } from '@/test/mockFetch';
import { usePastVod, type PastVodInput } from './usePastVod';

// 끝난 뒤 들어온 화면(A)의 회차 경로 폴링(POK-546 설계 2-2). 폴링 자체는 useRewindStatus 가 맡으므로, 여기서는 경로를 켜고
// 끄는 조건 · 404 유예의 원점 · 방송이나 키가 바뀔 때의 되돌림 · 늦은 답을 본다. fetch 는 요청마다 시험이 답을 쥔다.

const BASE = 'https://media.pokeclip.com';
const ROUND_1 = `${BASE}/api/streams/key-1/rounds/r1/rewind`;
const ROUND_2 = `${BASE}/api/streams/key-1/rounds/r2/rewind`;
const VOD_URL = `${BASE}/vod/key-1/r1/index.m3u8`;
const START_MS = Date.parse('2026-11-04T03:00:00.000Z');

const READY_BODY = {
  ready: true,
  mode: 'vod',
  url: VOD_URL,
  firstPdt: '2026-11-04T01:00:00.000Z',
  headPdt: '2026-11-04T02:00:00.000Z',
};

interface Pending {
  url: string;
  answer: (status: number, body?: unknown) => void;
}
let pending: Pending[];

/**
 * 요청마다 답을 시험이 쥔다. honorAbort 거짓이면 끊어도 답이 온다 — 끊기 직전에 도착한 답을 흉내 낸다
 * @param honorAbort 끊으면 AbortError 로 접는가
 */
function stubControllableFetch(honorAbort = true) {
  pending = [];
  const spy = vi.fn(
    (input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((resolve, reject) => {
        if (honorAbort) {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }
        pending.push({
          url: String(input),
          answer: (status, body) => resolve(jsonResponse(status, body)),
        });
      }),
  );
  vi.stubGlobal('fetch', spy);
  return spy;
}

/** 모든 요청에 같은 상태 코드로 곧바로 답한다(주기 폴링을 여러 걸음 돌릴 때) */
function stubAnsweringFetch(status: number, body?: unknown) {
  const spy = vi.fn(async () => jsonResponse(status, body));
  vi.stubGlobal('fetch', spy);
  return spy;
}

/** 요청 경로마다 정한 상태 코드로 곧바로 답한다(본문 없음) */
function stubFetchByUrl(statusOf: (url: string) => number) {
  const spy = vi.fn(async (url: RequestInfo | URL) => jsonResponse(statusOf(String(url))));
  vi.stubGlobal('fetch', spy);
  return spy;
}

function input(patch: Partial<PastVodInput> = {}): PastVodInput {
  return {
    enabled: true,
    controlBase: BASE,
    controlKey: 'key-1',
    roundId: 'r1',
    endedAtMs: null,
    ...patch,
  };
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

/** 가짜 시계를 폴링 주기 한 걸음 앞으로 — 그 사이의 답(마이크로태스크)까지 처리한다 */
async function stepPoll() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(REWIND_POLL_MS);
  });
}

beforeEach(() => {
  vi.useFakeTimers({ now: START_MS });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('usePastVod: 경로를 켜고 끄는 조건(T3-1)', () => {
  it('켜져 있고 경로를 만들 수 있으면 곧바로 회차 경로를 묻고, 답 전에는 checking(답 없음)이다', () => {
    const spy = stubControllableFetch();
    const { result } = renderHook(() => usePastVod(input()));
    expect(result.current).toEqual({ kind: 'checking', answered: false });
    expect(spy).toHaveBeenCalledTimes(1);
    expect(pending[0]!.url).toBe(ROUND_1);
  });

  it('꺼져 있으면(A 아님 · 보관 만료) 묻지 않는다', async () => {
    const spy = stubControllableFetch();
    const { result } = renderHook(() => usePastVod(input({ enabled: false })));
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS * 3));
    expect(spy).not.toHaveBeenCalled();
    expect(result.current).toEqual({ kind: 'checking', answered: false });
  });

  it.each([
    ['제어면 주소 없음', { controlBase: null }],
    ['영상 키 없음', { controlKey: null }],
    ['방송 번호 없음', { roundId: null }],
    ['방송 번호가 경로 조각 꼴이 아님', { roundId: 'r1/../x' }],
    ['영상 키가 경로 조각 꼴이 아님', { controlKey: 'key 1' }],
  ] as const)(
    '경로를 만들 수 없으면(%s) unconfigured 이고 제어면 요청은 0이다',
    async (_, patch) => {
      const spy = stubControllableFetch();
      const { result } = renderHook(() => usePastVod(input(patch)));
      expect(result.current).toEqual({ kind: 'unconfigured' });
      await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS * 3));
      expect(spy).not.toHaveBeenCalled();
    },
  );

  it('경로를 만들 수 없으면 꺼져 있어도 unconfigured 다(물을 수 없다는 사실이 먼저)', () => {
    stubControllableFetch();
    const { result } = renderHook(() => usePastVod(input({ enabled: false, controlBase: null })));
    expect(result.current).toEqual({ kind: 'unconfigured' });
  });
});

describe('usePastVod: 방송 · 키가 바뀌면 처음부터(T3-2)', () => {
  it('방송 번호가 바뀌면 checking(답 없음)으로 돌아가고 새 회차 경로를 곧바로 묻는다', async () => {
    stubControllableFetch();
    const { result, rerender } = renderHook((props: PastVodInput) => usePastVod(props), {
      initialProps: input(),
    });
    pending[0]!.answer(200, {
      ...READY_BODY,
      ready: false,
      url: null,
      firstPdt: null,
      headPdt: null,
    });
    await flush();
    expect(result.current).toEqual({ kind: 'preparing' });

    rerender(input({ roundId: 'r2' }));
    expect(result.current).toEqual({ kind: 'checking', answered: false });
    expect(pending.map((p) => p.url)).toEqual([ROUND_1, ROUND_2]);
  });

  it('영상 키가 바뀌어도 처음부터다 — 앞 키의 none 을 들고 가지 않는다', async () => {
    stubControllableFetch();
    const { result, rerender } = renderHook((props: PastVodInput) => usePastVod(props), {
      initialProps: input(),
    });
    pending[0]!.answer(200, {
      ...READY_BODY,
      ready: false,
      mode: 'ended',
      url: null,
      firstPdt: null,
      headPdt: null,
    });
    await flush();
    expect(result.current).toEqual({ kind: 'none' });

    rerender(input({ controlKey: 'key-2' }));
    expect(result.current).toEqual({ kind: 'checking', answered: false });
    expect(pending).toHaveLength(2);
    expect(pending[1]!.url).toBe(`${BASE}/api/streams/key-2/rounds/r1/rewind`);
  });

  it('방송이 바뀌면 첫 404 원점도 지운다 — 새 방송의 유예는 그 방송의 첫 404 부터 센다', async () => {
    stubAnsweringFetch(404);
    const { result, rerender } = renderHook((props: PastVodInput) => usePastVod(props), {
      initialProps: input(),
    });
    await flush();
    expect(result.current).toEqual({ kind: 'checking', answered: true });
    // 앞 방송에서 300초(30걸음)를 404 로 보낸다
    for (let step = 0; step < 30; step += 1) {
      await stepPoll();
    }
    expect(result.current).toEqual({ kind: 'checking', answered: true });

    rerender(input({ roundId: 'r2' }));
    await flush();
    expect(result.current).toEqual({ kind: 'checking', answered: true });
    // 앞 방송 원점 + 360초를 지나도(6걸음 · 60초) 새 방송은 유예 안이다. 원점을 지우지 않으면 여기서 none 이 된다
    for (let step = 0; step < 6; step += 1) {
      await stepPoll();
    }
    expect(result.current).toEqual({ kind: 'checking', answered: true });
  });
});

describe('usePastVod: 경로가 바뀌었다 돌아와도 처음부터(T3-2 왕복)', () => {
  it('r1 ready → r2(답 전) → r1 이면 옛 ready 를 되살리지 않고 checking(답 없음)에서 다시 묻는다', async () => {
    stubControllableFetch();
    const { result, rerender } = renderHook((props: PastVodInput) => usePastVod(props), {
      initialProps: input(),
    });
    pending[0]!.answer(200, READY_BODY);
    await flush();
    expect(result.current.kind).toBe('ready');

    rerender(input({ roundId: 'r2' }));
    expect(result.current).toEqual({ kind: 'checking', answered: false });
    rerender(input({ roundId: 'r1' }));
    expect(result.current).toEqual({ kind: 'checking', answered: false });
    expect(pending.map((p) => p.url)).toEqual([ROUND_1, ROUND_2, ROUND_1]);
  });

  it('r1 첫 404 → r2(404 없음) 360초 넘게 → r1 이면 r1 의 첫 404 는 새 유예의 시작이다(옛 원점으로 곧바로 none 이 아님)', async () => {
    stubFetchByUrl((url) => (url === ROUND_1 ? 404 : 503));
    const { result, rerender } = renderHook((props: PastVodInput) => usePastVod(props), {
      initialProps: input(),
    });
    await flush();
    expect(result.current).toEqual({ kind: 'checking', answered: true });

    rerender(input({ roundId: 'r2' }));
    await flush();
    expect(result.current).toEqual({ kind: 'unavailable' });
    // r2 에서 370초(37걸음) — r1 의 옛 원점에서 보면 유예가 지났다
    for (let step = 0; step < 37; step += 1) {
      await stepPoll();
    }

    rerender(input({ roundId: 'r1' }));
    await flush();
    expect(result.current).toEqual({ kind: 'checking', answered: true });
  });
});

describe('usePastVod: 늦은 답 버림(T3-3)', () => {
  it('방송이 바뀐 뒤에 온 앞 방송의 답은 새 방송 상태를 바꾸지 않는다', async () => {
    stubControllableFetch(false);
    const { result, rerender } = renderHook((props: PastVodInput) => usePastVod(props), {
      initialProps: input(),
    });
    rerender(input({ roundId: 'r2' }));
    expect(pending).toHaveLength(2);

    pending[0]!.answer(200, READY_BODY);
    await flush();
    expect(result.current).toEqual({ kind: 'checking', answered: false });

    pending[1]!.answer(404);
    await flush();
    expect(result.current).toEqual({ kind: 'checking', answered: true });
  });
});

describe('usePastVod: 다 정해지면 폴링을 멈춘다(T3-4)', () => {
  it('vod ∧ ready 면 ready 이고 더 묻지 않는다', async () => {
    const spy = stubControllableFetch();
    const { result } = renderHook(() => usePastVod(input()));
    pending[0]!.answer(200, READY_BODY);
    await flush();
    expect(result.current).toEqual({
      kind: 'ready',
      vod: {
        url: VOD_URL,
        firstPdtMs: Date.parse(READY_BODY.firstPdt),
        headPdtMs: Date.parse(READY_BODY.headPdt),
      },
    });
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS * 3));
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('회차 답 ended 면 none 이고 더 묻지 않는다', async () => {
    const spy = stubControllableFetch();
    const { result } = renderHook(() => usePastVod(input()));
    pending[0]!.answer(200, {
      ...READY_BODY,
      ready: false,
      mode: 'ended',
      url: null,
      firstPdt: null,
      headPdt: null,
    });
    await flush();
    expect(result.current).toEqual({ kind: 'none' });
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS * 3));
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('잠시 불가(아직 아무 답 없을 때의 503)는 10초마다 다시 묻는다', async () => {
    const spy = stubAnsweringFetch(503);
    const { result } = renderHook(() => usePastVod(input()));
    await flush();
    expect(result.current).toEqual({ kind: 'unavailable' });
    await stepPoll();
    await stepPoll();
    expect(spy).toHaveBeenCalledTimes(3);
  });

  it('준비 중(회차 답 vod 이지만 아직 준비 안 됨)은 10초마다 다시 묻는다', async () => {
    const spy = stubAnsweringFetch(200, {
      ...READY_BODY,
      ready: false,
      url: null,
      firstPdt: null,
      headPdt: null,
    });
    const { result } = renderHook(() => usePastVod(input()));
    await flush();
    expect(result.current).toEqual({ kind: 'preparing' });
    await stepPoll();
    await stepPoll();
    expect(spy).toHaveBeenCalledTimes(3);
  });
});

describe('usePastVod: 404 유예의 원점(T3-5)', () => {
  it('명부 endedAt 이 없으면 이 화면의 첫 404 부터 360초 동안 checking 이고, 그 뒤 404 에서 none · 멈춤', async () => {
    const spy = stubAnsweringFetch(404);
    const { result } = renderHook(() => usePastVod(input({ endedAtMs: null })));
    await flush();
    expect(result.current).toEqual({ kind: 'checking', answered: true });
    // 걸음 수: 유예 360초 ÷ 주기 10초 = 36걸음. 35걸음(350초)까지는 유예 안이다
    const graceSteps = RESUME_WAIT_MS / REWIND_POLL_MS;
    expect(graceSteps).toBe(36);
    for (let step = 1; step < graceSteps; step += 1) {
      await stepPoll();
    }
    expect(result.current).toEqual({ kind: 'checking', answered: true });
    expect(spy).toHaveBeenCalledTimes(graceSteps);

    // 36걸음째(첫 404 + 360초)의 404 — 원점을 매 404 로 옮기면 여기서도 checking 에 남는다
    await stepPoll();
    expect(spy).toHaveBeenCalledTimes(graceSteps + 1);
    expect(result.current).toEqual({ kind: 'none' });
    for (let step = 0; step < 3; step += 1) {
      await stepPoll();
    }
    expect(spy).toHaveBeenCalledTimes(graceSteps + 1);
  });

  it('명부 endedAt 이 유예보다 오래전이면 첫 404 에서 곧바로 none 이다', async () => {
    const spy = stubAnsweringFetch(404);
    const { result } = renderHook(() =>
      usePastVod(input({ endedAtMs: START_MS - RESUME_WAIT_MS - 40_000 })),
    );
    await flush();
    expect(result.current).toEqual({ kind: 'none' });
    await stepPoll();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('명부 endedAt 이 유예 안이면 그 시각이 원점이다 — 첫 404 시각이 아니다', async () => {
    stubAnsweringFetch(404);
    const { result } = renderHook(() => usePastVod(input({ endedAtMs: START_MS - 100_000 })));
    await flush();
    expect(result.current).toEqual({ kind: 'checking', answered: true });
    // endedAt + 360초 = 지금 + 260초(26걸음). 25걸음까지는 checking, 26걸음째 none
    for (let step = 0; step < 25; step += 1) {
      await stepPoll();
    }
    expect(result.current).toEqual({ kind: 'checking', answered: true });
    await stepPoll();
    expect(result.current).toEqual({ kind: 'none' });
  });
});

// 경로를 바꾼 렌더가 커밋된 직후 · passive 효과 전에 옛 경로의 요청이 답하는 틈(r1 cc M1 · cx H1). useRewindStatus 는 답 처리
// 함수를 렌더마다 갈아 끼우고 순번은 passive 정리에서만 무효로 하므로, 이 틈의 옛 답은 새 경로 렌더의 처리 함수로 들어간다.
// act 는 passive 효과까지 한 번에 비워 이 틈을 지우므로 createRoot 를 act 없이 쓰고, 옛 답은 새 경로 렌더의 layout 효과에서
// 낸다. 답은 마이크로태스크만으로 끝나는 응답 흉내로 준다 — 실제 Response 의 본문 읽기가 이 틈을 넘기지 않게.
describe('usePastVod: 경로를 바꾼 커밋 직후 · passive 전에 온 옛 경로 답(T3-3 경쟁)', () => {
  const ROUND_REAL = `${BASE}/api/streams/key-real/rounds/r1/rewind`;
  let previousActEnvironment: boolean | undefined;
  const actScope = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };

  /**
   * 이 묶음이 세운 뿌리. createRoot 는 Testing Library 의 자동 정리 밖이라 afterEach 가 내린다 — 중간 단언이 깨져도 실제
   * 시계로 도는 폴러가 뒤 시험에 남지 않게
   */
  let roots: Root[] = [];

  /** 뿌리를 세우고 정리 대상으로 적는다 */
  function mountRoot(): Root {
    const root = createRoot(document.createElement('div'));
    roots = [...roots, root];
    return root;
  }

  beforeEach(() => {
    vi.useRealTimers();
    previousActEnvironment = actScope.IS_REACT_ACT_ENVIRONMENT;
    actScope.IS_REACT_ACT_ENVIRONMENT = false;
  });
  afterEach(() => {
    for (const root of roots) {
      root.unmount();
    }
    roots = [];
    actScope.IS_REACT_ACT_ENVIRONMENT = previousActEnvironment;
  });

  /** 본문 읽기까지 마이크로태스크로 끝나는 응답(fetchRewindStatus 는 status · json 만 읽는다) */
  function quickResponse(status: number, body?: unknown): Response {
    return { status, json: async () => body } as unknown as Response;
  }

  /** 요청마다 답을 시험이 쥔다. 끊으면 AbortError 로 접는다 */
  function stubQuickFetch() {
    const requests: { url: string; answer: (status: number, body?: unknown) => void }[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (url: RequestInfo | URL, init?: RequestInit) =>
          new Promise<Response>((resolve, reject) => {
            init?.signal?.addEventListener('abort', () =>
              reject(new DOMException('aborted', 'AbortError')),
            );
            requests.push({
              url: String(url),
              answer: (status, body) => resolve(quickResponse(status, body)),
            });
          }),
      ),
    );
    return requests;
  }

  interface ProbeProps {
    vod: PastVodInput;
    /** 커밋된 상태를 받는다 */
    record: (state: PastVodState) => void;
    /** 이 속성으로 세운 렌더가 커밋되면(layout) 한 번 부른다 */
    onCommit?: () => void;
  }

  function Probe({ vod, record, onCommit }: ProbeProps) {
    const state = usePastVod(vod);
    useLayoutEffect(() => record(state));
    useLayoutEffect(() => onCommit?.(), [onCommit]);
    return null;
  }

  /**
   * 조건이 설 때까지(최대 2초) 실제 시계로 기다린다. 서지 않아도 그대로 돌아온다 — 판정은 뒤의 단언이 한다. 고정 대기는 기계
   * 부하에서 스케줄러 작업(passive 효과)이 늦으면 깨진다
   * @param ready 기다릴 조건
   */
  async function waitUntil(ready: () => boolean) {
    const deadline = Date.now() + 2_000;
    while (!ready() && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    // 조건을 세운 렌더 뒤의 효과 · 답까지 한 번 더 비운다
    await new Promise((resolve) => setTimeout(resolve, 20));
  }

  /**
   * 첫 경로로 한 번 묻게 한 뒤, 경로(영상 키)를 바꾼 렌더가 커밋된 layout 에서 옛 요청이 답하게 한다
   * @param oldStatus 옛 요청에 줄 상태 코드
   * @param oldBody 옛 요청에 줄 본문
   */
  async function runRace(oldStatus: number, oldBody?: unknown) {
    const requests = stubQuickFetch();
    let committed: PastVodState | null = null;
    const record = (state: PastVodState) => {
      committed = state;
    };
    // 명부 ended 는 정산 뒤라 유예 밖이다 — 옛 404 가 새 경로로 접히면 곧바로 none 으로 굳는다
    const vod = input({ endedAtMs: Date.now() - 20 * 60_000 });
    const root = mountRoot();
    root.render(createElement(Probe, { vod, record }));
    await waitUntil(() => requests.length >= 1);
    expect(requests.map((r) => r.url)).toEqual([ROUND_1]);

    root.render(
      createElement(Probe, {
        vod: { ...vod, controlKey: 'key-real' },
        record,
        onCommit: () => requests[0]!.answer(oldStatus, oldBody),
      }),
    );
    // 고친 코드는 새 경로를 묻는다(두 번째 요청). 옛 답이 새 경로로 접히면 폴링이 멈춰 두 번째 요청이 끝내 나가지 않는다
    await waitUntil(() => requests.length >= 2);
    return { requests, committed: () => committed };
  }

  it('옛 경로의 404 는 새 경로를 none 으로 굳히지 않고, 새 경로 요청이 나간다', async () => {
    const { requests, committed } = await runRace(404);
    expect(committed()).toEqual({ kind: 'checking', answered: false });
    expect(requests.map((r) => r.url)).toEqual([ROUND_1, ROUND_REAL]);

    // 새 경로의 답은 그대로 받는다
    requests[1]!.answer(503);
    await waitUntil(() => committed()?.kind === 'unavailable');
    expect(committed()).toEqual({ kind: 'unavailable' });
  });

  it('옛 경로의 ready 는 옛 방송 목록을 새 자리에 세우지 않고, 새 경로 요청이 나간다', async () => {
    const { requests, committed } = await runRace(200, READY_BODY);
    expect(committed()).toEqual({ kind: 'checking', answered: false });
    expect(requests.map((r) => r.url)).toEqual([ROUND_1, ROUND_REAL]);
  });

  // 전이(startTransition)로 B 경로를 렌더하다 형제가 멈춰(Suspense) 그 렌더가 버려지고, 처음 A 요소를 그대로 다시 렌더해
  // 돌아오는 경우(c3 확인 패스 1 cx H1). A 는 다시 렌더하지 않고 건너뛸 수 있어, useRewindStatus 가 렌더 중에 갈아 끼운 답
  // 처리 함수는 버려진 B 렌더의 것으로 남는다. 답 처리가 렌더의 경로를 믿으면 그 뒤 A 의 정상 답을 모두 버려 멈춘다.
  describe('버려진 전이 렌더 뒤 복귀', () => {
    const NEVER = new Promise<never>(() => {});

    /** suspend 면 영원히 멈춘다 — 그 렌더는 커밋되지 않고 버려진다 */
    function Stall({ suspend }: { suspend: boolean }) {
      if (suspend) throw NEVER;
      return null;
    }

    /** 렌더한 영상 키 — 버려지는 렌더까지 적는다(커밋된 상태는 record 가 받는다) */
    let renderedKeys: (string | null)[];

    function TrackedProbe({ vod, record }: ProbeProps) {
      renderedKeys.push(vod.controlKey);
      return createElement(Probe, { vod, record });
    }

    interface StageProps {
      vod: PastVodInput;
      suspend: boolean;
      record: (state: PastVodState) => void;
    }

    function Stage({ vod, suspend, record }: StageProps) {
      return createElement(
        Suspense,
        { fallback: null },
        createElement(TrackedProbe, { vod, record }),
        createElement(Stall, { suspend }),
      );
    }

    let restoreVisibility: (() => void) | null = null;
    afterEach(() => {
      restoreVisibility?.();
      restoreVisibility = null;
    });

    /** 숨김 복귀를 흉내 내 폴러가 곧바로 다시 묻게 한다(10초 주기를 실제 시계로 기다리지 않게) */
    function askAgainOnVisible() {
      const own = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      Object.defineProperty(document, 'visibilityState', {
        configurable: true,
        get: () => 'visible',
      });
      restoreVisibility = () => {
        if (own === undefined) {
          delete (document as { visibilityState?: unknown }).visibilityState;
        } else {
          Object.defineProperty(document, 'visibilityState', own);
        }
      };
      document.dispatchEvent(new Event('visibilitychange'));
    }

    it('A → 전이로 B 렌더 중단 → A 요소로 복귀하면 A 의 503 은 unavailable, 다음 ready 는 ready 로 간다', async () => {
      renderedKeys = [];
      const requests = stubQuickFetch();
      let latest: PastVodState | null = null;
      const record = (state: PastVodState) => {
        latest = state;
      };
      const committed = (): PastVodState | null => latest;
      const stageA = createElement(Stage, { vod: input(), suspend: false, record });
      const root = mountRoot();
      root.render(stageA);
      await waitUntil(() => requests.length >= 1);
      expect(requests.map((r) => r.url)).toEqual([ROUND_1]);

      startTransition(() => {
        root.render(
          createElement(Stage, { vod: input({ controlKey: 'key-real' }), suspend: true, record }),
        );
      });
      // 전이 렌더가 B 를 렌더하고(useRewindStatus 가 답 처리 함수를 B 렌더의 것으로 갈아 끼움) 형제에서 멈출 때까지
      await waitUntil(() => renderedKeys.includes('key-real'));
      expect(renderedKeys).toContain('key-real');
      root.render(stageA);
      await waitUntil(() => true);
      // 버려진 B 렌더는 커밋되지 않았다 — 요청도 A 하나뿐이다
      expect(committed()).toEqual({ kind: 'checking', answered: false });
      expect(requests.map((r) => r.url)).toEqual([ROUND_1]);

      requests[0]!.answer(503);
      await waitUntil(() => committed()?.kind === 'unavailable');
      expect(committed()).toEqual({ kind: 'unavailable' });

      askAgainOnVisible();
      await waitUntil(() => requests.length >= 2);
      expect(requests.map((r) => r.url)).toEqual([ROUND_1, ROUND_1]);
      requests[1]!.answer(200, READY_BODY);
      await waitUntil(() => committed()?.kind === 'ready');
      expect(committed()?.kind).toBe('ready');
    }, 10_000);

    it('A → 전이로 B 렌더가 멈춘 동안(복귀 전) 온 A 의 503 은 unavailable 이고, A 요소로 복귀한 뒤 ready 도 받는다', async () => {
      renderedKeys = [];
      const requests = stubQuickFetch();
      let latest: PastVodState | null = null;
      const record = (state: PastVodState) => {
        latest = state;
      };
      const committed = (): PastVodState | null => latest;
      const stageA = createElement(Stage, { vod: input(), suspend: false, record });
      const root = mountRoot();
      root.render(stageA);
      await waitUntil(() => requests.length >= 1);

      startTransition(() => {
        root.render(
          createElement(Stage, { vod: input({ controlKey: 'key-real' }), suspend: true, record }),
        );
      });
      await waitUntil(() => renderedKeys.includes('key-real'));
      expect(renderedKeys).toContain('key-real');
      // B 렌더는 멈춘 채 커밋되지 않았다 — 커밋된 A 의 답이라 접힌다
      requests[0]!.answer(503);
      await waitUntil(() => committed()?.kind === 'unavailable');
      expect(committed()).toEqual({ kind: 'unavailable' });
      expect(requests.map((r) => r.url)).toEqual([ROUND_1]);

      root.render(stageA);
      askAgainOnVisible();
      await waitUntil(() => requests.length >= 2);
      expect(requests.map((r) => r.url)).toEqual([ROUND_1, ROUND_1]);
      requests[1]!.answer(200, READY_BODY);
      await waitUntil(() => committed()?.kind === 'ready');
      expect(committed()?.kind).toBe('ready');
    }, 10_000);
  });
});

describe('usePastVod: 세션이 이미 확인한 다시보기(knownVod — POK-547)', () => {
  const KNOWN = {
    url: VOD_URL,
    firstPdtMs: Date.parse(READY_BODY.firstPdt),
    headPdtMs: Date.parse(READY_BODY.headPdt),
  };

  it('첫 그리기부터 ready 이고 회차 경로를 묻지 않는다', async () => {
    const spy = stubControllableFetch();
    const { result } = renderHook(() => usePastVod(input({ knownVod: KNOWN })));
    expect(result.current).toEqual({ kind: 'ready', vod: KNOWN });
    await flush();
    await stepPoll();
    expect(spy).not.toHaveBeenCalled();
    expect(result.current).toEqual({ kind: 'ready', vod: KNOWN });
  });

  it('부른 쪽이 거두면(다른 방송|영상 키) 새 경로를 처음부터 묻는다', async () => {
    const spy = stubControllableFetch();
    const { result, rerender } = renderHook((props: PastVodInput) => usePastVod(props), {
      initialProps: input({ knownVod: KNOWN }),
    });
    expect(result.current.kind).toBe('ready');
    rerender(input({ roundId: 'r2', knownVod: null }));
    expect(result.current).toEqual({ kind: 'checking', answered: false });
    await flush();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(pending[0]?.url).toBe(ROUND_2);
  });

  it('없으면(null) 지금 동작 그대로 묻는다', async () => {
    const spy = stubControllableFetch();
    const { result } = renderHook(() => usePastVod(input({ knownVod: null })));
    expect(result.current).toEqual({ kind: 'checking', answered: false });
    await flush();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('경로를 만들 수 없으면 knownVod 가 있어도 unconfigured 다', () => {
    stubControllableFetch();
    const { result } = renderHook(() => usePastVod(input({ controlKey: null, knownVod: KNOWN })));
    expect(result.current).toEqual({ kind: 'unconfigured' });
  });
});
