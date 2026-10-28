import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { handedOff } from './handoffTestKit';
import { transition, type SessionEvent, type XAnswer } from './rewindSwitch';
import { usePlaybackSession, type SessionPlayer, type SessionOptions } from './usePlaybackSession';

// 세션에 들어간 사건을 보려고 상태기계를 감싼다 — 동작은 원본 그대로다
vi.mock('./rewindSwitch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./rewindSwitch')>();
  return { ...actual, transition: vi.fn(actual.transition) };
});

// 재생 세션의 수명 소유자(설계 5.5절) — 여는 조건 · 폴링 켜기 · 효과 적용 · 리마운트 잇기(R36)

const BASE = 'https://media.pokeclip.com';
const NOW = Date.parse('2026-10-25T06:00:00.000Z');
const A1 = `${BASE}/dvr/key-1/a1/index.m3u8`;
const B1 = `${BASE}/dvr/key-1/b1/index.m3u8`;
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const ROUND = `${BASE}/api/streams/key-1/rounds/r-A/rewind`;

function readyBody(url: string, first: number, head: number) {
  return {
    ready: true,
    mode: 'live',
    url,
    firstPdt: new Date(first).toISOString(),
    headPdt: new Date(head).toISOString(),
  };
}

let calls: string[];
let answer: (url: string) => Response | Promise<Response>;

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
  calls = [];
  answer = () => jsonResponse(404, { error: 'not_found' });
  stubFetch((url) => {
    calls.push(url);
    return answer(url);
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

async function flush() {
  await act(async () => {
    for (let i = 0; i < 6; i += 1) await Promise.resolve();
  });
}

function options(patch: Partial<SessionOptions> = {}): SessionOptions {
  return {
    canOpen: true,
    roundId: 'r-A',
    accessKey: 'r-A|key-1',
    liveUrl: `${BASE}/live/key-1/index.m3u8`,
    controlKey: 'key-1',
    controlBase: BASE,
    resolveX: () => Promise.resolve<XAnswer>({ kind: 'known', id: 'r-A' }),
    onClose: vi.fn(),
    onNotice: vi.fn(),
    ...patch,
  };
}

function fakePlayer(): SessionPlayer & { seeks: number[] } {
  const seeks: number[] = [];
  return {
    seeks,
    seekToMs: (ms) => seeks.push(ms),
    seekToLiveEdge: () => seeks.push(-1),
    keepPausedOnSwap: vi.fn(),
  };
}

describe('여는 조건 · 폴링', () => {
  it('열 수 있으면 live 세션을 열고 스트림 경로만 묻는다', async () => {
    const { result } = renderHook(() => usePlaybackSession(options()));
    await flush();
    expect(result.current?.state.phase).toEqual({ kind: 'live' });
    expect(calls).toEqual([STREAM]);
  });

  it('제어면 주소 · 키가 없거나 열 수 없으면 세션이 없다(지금 동작)', async () => {
    for (const patch of [{ controlBase: null }, { controlKey: null }, { canOpen: false }]) {
      const { result } = renderHook(() => usePlaybackSession(options(patch)));
      await flush();
      expect(result.current).toBeNull();
    }
    expect(calls).toEqual([]);
  });

  it('열린 뒤에는 여는 조건이 꺼져도(명부 ended) 닫지 않는다 — 명부 ended 는 재조회 신호다', async () => {
    const { result, rerender } = renderHook((o: SessionOptions) => usePlaybackSession(o), {
      initialProps: options(),
    });
    await flush();
    rerender(options({ canOpen: false }));
    await flush();
    expect(result.current?.state.closed).toBeNull();
  });

  it('끝남 대기에 들면 두 경로를 묻는다 · 회차별 404 는 360초 동안 닫지 않는다', async () => {
    const onClose = vi.fn();
    const { result } = renderHook(() => usePlaybackSession(options({ onClose })));
    await flush();
    act(() => result.current?.dispatch({ type: 'live-closed' }));
    await flush();
    expect(calls).toContain(ROUND);
    await act(async () => vi.advanceTimersByTime(350_000));
    await flush();
    expect(onClose).not.toHaveBeenCalled();
    await act(async () => vi.advanceTimersByTime(20_000));
    await flush();
    expect(onClose).toHaveBeenCalledWith(expect.objectContaining({ reason: 'recorded' }));
  });
});

describe('효과 적용', () => {
  it('세션의 「안에서 시크」 · 「라이브 끝」 을 묶은 재생기에 건다', async () => {
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW)) : jsonResponse(404);
    const { result } = renderHook(() => usePlaybackSession(options()));
    await flush();
    const player = fakePlayer();
    act(() => result.current?.bindPlayer(player));
    act(() =>
      result.current?.dispatch({
        type: 'seek',
        targetMs: NOW - 10_000,
        view: { lowerMs: NOW - 16_000, endMs: NOW },
      }),
    );
    act(() => result.current?.liveButton());
    await flush();
    expect(player.seeks).toEqual([NOW - 10_000, -1]);
  });

  it('LIVE 는 지금 방송 번호를 새로 묻고(fresh) 다른 방송이면 그 방송으로 닫는다(R58)', async () => {
    const resolveX = vi.fn((fresh: boolean) =>
      Promise.resolve<XAnswer>(fresh ? { kind: 'known', id: 'r-B' } : { kind: 'unknown' }),
    );
    const onClose = vi.fn();
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW)) : jsonResponse(404);
    const { result } = renderHook(() => usePlaybackSession(options({ resolveX, onClose })));
    await flush();
    act(() =>
      result.current?.dispatch({
        type: 'seek',
        targetMs: NOW - 600_000,
        view: { lowerMs: NOW - 16_000, endMs: NOW },
      }),
    );
    expect(result.current?.state.phase.kind).toBe('rewind');
    act(() => result.current?.liveButton());
    await flush();
    expect(resolveX).toHaveBeenCalledWith(true);
    expect(onClose).toHaveBeenCalledWith(expect.objectContaining({ reason: 'left', goTo: 'r-B' }));
  });

  it('알림 효과는 onNotice 로 올린다', async () => {
    const onNotice = vi.fn();
    const { result } = renderHook(() => usePlaybackSession(options({ onNotice })));
    await flush();
    act(() =>
      result.current?.dispatch({
        type: 'seek',
        targetMs: NOW - 600_000,
        view: { lowerMs: NOW - 16_000, endMs: NOW },
      }),
    );
    expect(onNotice).toHaveBeenCalledTimes(1);
  });
});

describe('리마운트 잇기(R36 — 세션은 GlassPlayer key 바깥에 산다)', () => {
  it('재생기가 내려갔다 다시 묶이면 같은 소스를 마지막 자리에서 세대만 올려 잇는다', async () => {
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW)) : jsonResponse(404);
    const { result } = renderHook(() => usePlaybackSession(options()));
    await flush();
    const first = fakePlayer();
    act(() => result.current?.bindPlayer(first));
    const t = NOW - 600_000;
    act(() =>
      result.current?.dispatch({
        type: 'seek',
        targetMs: t,
        view: { lowerMs: NOW - 16_000, endMs: NOW },
      }),
    );
    act(() =>
      result.current?.dispatch({ type: 'position', atMs: t + 5_000, playing: true, runMs: 5_000 }),
    );
    const before = result.current!.state.source;
    act(() => result.current?.bindPlayer(null));
    act(() => result.current?.bindPlayer(fakePlayer()));
    expect(result.current?.state.source).toEqual({
      kind: 'rewind',
      url: A1,
      startAtMs: t + 5_000,
      generation: before.generation + 1,
      strictStart: false,
    });
  });

  it('처음 묶을 때는 세대를 올리지 않는다', async () => {
    const { result } = renderHook(() => usePlaybackSession(options()));
    await flush();
    act(() => result.current?.bindPlayer(fakePlayer()));
    expect(result.current?.state.source.generation).toBe(0);
  });
});

describe('세션 수명 — 앞 세션의 늦은 답(cx H3)', () => {
  it('A 의 끝남 대기 폴링이 X 를 기다리는 동안 B 로 바뀌면 A 의 답은 B 세션에 들지 않는다', async () => {
    let releaseX!: (x: XAnswer) => void;
    const resolveX = vi.fn(
      () =>
        new Promise<XAnswer>((resolve) => {
          releaseX = resolve;
        }),
    );
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW)) : jsonResponse(404);
    const { result, rerender } = renderHook((o: SessionOptions) => usePlaybackSession(o), {
      initialProps: options({ resolveX }),
    });
    await flush();
    // A 가 끝남 대기 — 다음 스트림 폴링은 X 를 묻는다
    act(() => result.current?.dispatch({ type: 'live-closed' }));
    await flush();
    expect(resolveX).toHaveBeenCalled();
    // 사용자가 B 를 고른다(같은 영상 키 · 다른 방송 번호). 이제 스트림 경로는 B 의 목록을 준다
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(B1, NOW - 60_000, NOW)) : jsonResponse(404);
    rerender(options({ resolveX, roundId: 'r-B', accessKey: 'r-B|key-1' }));
    await flush();
    expect(result.current?.state.roundId).toBe('r-B');
    expect(result.current?.state.entry?.url).toBe(B1);
    const before = result.current!.state;
    // 늦게 온 A 의 X 답
    await act(async () => {
      releaseX({ kind: 'known', id: 'r-A' });
      await Promise.resolve();
    });
    await flush();
    expect(result.current?.state.entry).toEqual(before.entry);
    expect(result.current?.state.roundRewindUrls).toEqual(before.roundRewindUrls);
  });

  it('A 에서 누른 LIVE 의 X 답이 B 로 바뀐 뒤 오면 버린다', async () => {
    let releaseX!: (x: XAnswer) => void;
    const resolveX = vi.fn(
      () =>
        new Promise<XAnswer>((resolve) => {
          releaseX = resolve;
        }),
    );
    const onClose = vi.fn();
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW)) : jsonResponse(404);
    const { result, rerender } = renderHook((o: SessionOptions) => usePlaybackSession(o), {
      initialProps: options({ resolveX, onClose }),
    });
    await flush();
    act(() =>
      result.current?.dispatch({
        type: 'seek',
        targetMs: NOW - 600_000,
        view: { lowerMs: NOW - 16_000, endMs: NOW },
      }),
    );
    act(() => result.current?.liveButton());
    rerender(options({ resolveX, onClose, roundId: 'r-B', accessKey: 'r-B|key-1' }));
    await flush();
    // B 도 되감기 중이다 — A 의 늦은 답(X = r-C)이 들면 B 세션을 「새 방송 보기」로 닫는다
    act(() =>
      result.current?.dispatch({
        type: 'seek',
        targetMs: NOW - 600_000,
        view: { lowerMs: NOW - 16_000, endMs: NOW },
      }),
    );
    expect(result.current?.state.phase.kind).toBe('rewind');
    await act(async () => {
      releaseX({ kind: 'known', id: 'r-C' });
      await Promise.resolve();
    });
    await flush();
    expect(onClose).not.toHaveBeenCalled();
    expect(result.current?.state.roundId).toBe('r-B');
  });

  it('화면을 떠난 뒤 온 LIVE 의 X 답은 닫기 콜백을 부르지 않는다(cx H2 — 언마운트가 순번을 올린다)', async () => {
    let releaseX!: (x: XAnswer) => void;
    const resolveX = vi.fn(
      () =>
        new Promise<XAnswer>((resolve) => {
          releaseX = resolve;
        }),
    );
    const onClose = vi.fn();
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW)) : jsonResponse(404);
    const { result, unmount } = renderHook(() =>
      usePlaybackSession(options({ resolveX, onClose })),
    );
    await flush();
    act(() =>
      result.current?.dispatch({
        type: 'seek',
        targetMs: NOW - 600_000,
        view: { lowerMs: NOW - 16_000, endMs: NOW },
      }),
    );
    act(() => result.current?.liveButton());
    unmount();
    await act(async () => {
      releaseX({ kind: 'known', id: 'r-B' });
      await Promise.resolve();
    });
    await flush();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('지금 방송 번호 재조회는 예약 때의 세션에 묶인다 — 같은 키로 다시 연 세션의 되감기를 LIVE 로 돌리지 않는다(cx M)', async () => {
    let asked = 0;
    // 첫 물음(LIVE)은 모름 → 2초 뒤 재조회 예약. 재조회는 보던 방송이라 답한다
    const resolveX = vi.fn(() => {
      asked += 1;
      return Promise.resolve<XAnswer>(
        asked === 1 ? { kind: 'unknown' } : { kind: 'known', id: 'r-A' },
      );
    });
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW)) : jsonResponse(404);
    const { result } = renderHook(() => usePlaybackSession(options({ resolveX })));
    await flush();
    const rewindTo = () =>
      act(() =>
        result.current?.dispatch({
          type: 'seek',
          targetMs: NOW - 600_000,
          view: { lowerMs: NOW - 16_000, endMs: NOW },
        }),
      );
    rewindTo();
    act(() => result.current?.liveButton());
    await flush();
    expect(result.current?.state.liveChecking).toBe(true);
    // 재조회 전에 세션이 녹화 보기로 닫히고 같은 방송|영상 키로 새로 열린다
    act(() => result.current?.dispatch({ type: 'end-reached' }));
    act(() => result.current?.dispatch({ type: 'button', which: 'record' }));
    await flush();
    expect(result.current?.state.phase).toEqual({ kind: 'live' });
    rewindTo();
    expect(result.current?.state.phase.kind).toBe('rewind');
    await act(async () => vi.advanceTimersByTime(2_000));
    await flush();
    expect(result.current?.state.phase.kind).toBe('rewind');
  });
});

describe('물음 순번 — 스트림 폴링 응답마다 순번과 수신 시각(설계 r9 2절)', () => {
  function streamPolls(): Extract<SessionEvent, { type: 'stream-poll' }>[] {
    return vi
      .mocked(transition)
      .mock.calls.map(([, event]) => event)
      .filter((e): e is Extract<SessionEvent, { type: 'stream-poll' }> => e.type === 'stream-poll');
  }

  // 앞 시험의 사건을 지우고 원본 상태기계를 단다 — 이 묶음은 사건 기록과 원본 동작을 둘 다 처음부터 쥔다
  beforeEach(async () => {
    const actual = await vi.importActual<typeof import('./rewindSwitch')>('./rewindSwitch');
    vi.mocked(transition).mockReset().mockImplementation(actual.transition);
  });

  it('응답마다 순번이 1씩 오르고 수신 시각을 싣는다', async () => {
    renderHook(() => usePlaybackSession(options()));
    await flush();
    await act(async () => vi.advanceTimersByTime(10_000));
    await flush();
    expect(streamPolls().map((e) => e.ask)).toEqual([
      { seq: 1, receivedAtMs: NOW },
      { seq: 2, receivedAtMs: NOW + 10_000 },
    ]);
  });

  it('rewind 국면 폴링도 지금 방송 번호를 묻고(재사용 허용) 그 답을 싣는다', async () => {
    const resolveX = vi.fn(() => Promise.resolve<XAnswer>({ kind: 'known', id: 'r-A' }));
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW)) : jsonResponse(404);
    const { result } = renderHook(() => usePlaybackSession(options({ resolveX })));
    await flush();
    const view = { lowerMs: NOW - 16_000, endMs: NOW };
    act(() => result.current?.dispatch({ type: 'seek', targetMs: NOW - 600_000, view }));
    await act(async () => vi.advanceTimersByTime(10_000));
    await flush();
    expect(result.current?.state.phase.kind).toBe('rewind');
    expect(resolveX).toHaveBeenCalledWith(false);
    expect(streamPolls().at(-1)?.x).toEqual({ kind: 'known', id: 'r-A' });
  });

  it('지금 방송 번호를 기다리는 응답도 받은 즉시 찍은 시각을 싣는다', async () => {
    let releaseX!: (x: XAnswer) => void;
    const resolveX = vi.fn(
      () =>
        new Promise<XAnswer>((resolve) => {
          releaseX = resolve;
        }),
    );
    const { result } = renderHook(() => usePlaybackSession(options({ resolveX })));
    await flush();
    act(() => result.current?.dispatch({ type: 'live-closed' }));
    await flush();
    const receivedAt = Date.now();
    await act(async () => vi.advanceTimersByTime(2_000));
    await act(async () => {
      releaseX({ kind: 'known', id: 'r-A' });
      await Promise.resolve();
    });
    await flush();
    // 마지막 사건이 X 갈래 사건이다(첫 live 폴링도 receivedAt 이 같아 헛통과하지 않게)
    expect(streamPolls().at(-1)?.x).toEqual({ kind: 'known', id: 'r-A' });
    expect(streamPolls().at(-1)?.ask?.receivedAtMs).toBe(receivedAt);
  });
});

describe('넘김 확인 — 시한 tick · 멈춘 채 되돌림(설계 r9 11절 · 16절 6)', () => {
  const P = NOW - 6_000;
  const E = NOW - 2_000;
  /** 꼬리 넘김 직후 — P · E 로 확인을 시작한다. 시한 = 넘긴 때 + (E − P) + 2 × TD = 넘긴 때 + 12초 */
  const DEADLINE_AFTER_MS = 12_000;

  function ticks(): number {
    return vi.mocked(transition).mock.calls.filter(([, event]) => event.type === 'tick').length;
  }

  beforeEach(async () => {
    const actual = await vi.importActual<typeof import('./rewindSwitch')>('./rewindSwitch');
    vi.mocked(transition).mockReset().mockImplementation(actual.transition);
    answer = (url) =>
      url === STREAM ? jsonResponse(200, readyBody(A1, NOW - 3_600_000, E)) : jsonResponse(404);
  });

  /** 되감기에서 P 를 보다가 라이브로 넘긴 세션. 넘김 행(c13h)이 들기 전이라 결과 모양만 끼운다 */
  async function handedOffSession() {
    const view = renderHook(() => usePlaybackSession(options()));
    await flush();
    const player = fakePlayer();
    act(() => view.result.current?.bindPlayer(player));
    // 라이브 창 하한(NOW − 5초)보다 앞인 P 로 옮겨 되감기에 든다
    const seek = { lowerMs: NOW - 5_000, endMs: NOW };
    act(() => view.result.current?.dispatch({ type: 'seek', targetMs: P, view: seek }));
    const list = { url: A1, firstMs: NOW - 3_600_000, lastEndMs: E, targetDurationS: 4 };
    act(() => view.result.current?.dispatch({ type: 'list-loaded', ...list }));
    act(() =>
      view.result.current?.dispatch({ type: 'position', atMs: P, playing: true, runMs: 0 }),
    );
    vi.mocked(transition).mockImplementationOnce((...[s, , now]) => ({
      state: handedOff(s, now),
      effects: [],
    }));
    act(() => view.result.current?.dispatch({ type: 'playing' }));
    expect(view.result.current?.state.handoff).not.toBeNull();
    return { ...view, player };
  }

  function pause(view: Awaited<ReturnType<typeof handedOffSession>>) {
    act(() =>
      view.result.current?.dispatch({ type: 'position', atMs: P, playing: false, runMs: 0 }),
    );
  }

  it('확인 중이면 시한에 tick 하나를 쏘고 시한 전에는 쏘지 않는다', async () => {
    const view = await handedOffSession();
    await act(async () => vi.advanceTimersByTime(DEADLINE_AFTER_MS - 1));
    expect(ticks()).toBe(0);
    await act(async () => vi.advanceTimersByTime(1));
    expect(ticks()).toBe(1);
    expect(view.result.current?.state.source.kind).toBe('rewind');
  });

  it('시한 타이머가 시한보다 일찍 깨면 남은 만큼 다시 걸어 시한에 되돌린다', async () => {
    const view = await handedOffSession();
    // 브라우저 타이머는 시계 단위만큼 일찍 깰 수 있다 — 깬 순간의 지금을 시한 1ms 앞으로 둔다
    const fakeNow = Date.now;
    const early = vi.spyOn(Date, 'now').mockImplementation(() => fakeNow() - 1);
    await act(async () => vi.advanceTimersByTime(DEADLINE_AFTER_MS));
    expect(view.result.current?.state.source.kind).toBe('live');
    early.mockRestore();

    await act(async () => vi.advanceTimersByTime(1));

    expect(view.result.current?.state.source.kind).toBe('rewind');
    expect(ticks()).toBe(1);
  });

  it('확인이 끝나면 그 시한의 타이머를 지우고 다음 확인의 시한에만 쏜다', async () => {
    const view = await handedOffSession();
    // H6 — 창 안의 시크가 확인을 끝낸다
    const seek = { lowerMs: NOW - 16_000, endMs: NOW };
    act(() => view.result.current?.dispatch({ type: 'seek', targetMs: NOW - 4_000, view: seek }));
    expect(view.result.current?.state.handoff).toBeNull();
    await act(async () => vi.advanceTimersByTime(6_000));
    vi.mocked(transition).mockImplementationOnce((...[s, , now]) => ({
      state: handedOff({ ...s, lastAtMs: P, list: { endMs: E, tdMs: 4_000, sealed: false } }, now),
      effects: [],
    }));
    act(() => view.result.current?.dispatch({ type: 'playing' }));
    await act(async () => vi.advanceTimersByTime(DEADLINE_AFTER_MS - 1));
    expect(ticks()).toBe(0);
    await act(async () => vi.advanceTimersByTime(1));
    expect(ticks()).toBe(1);
  });

  it('멈춘 채 확인 시한(H0)이 되감기로 되돌리면 재생기에 멈춤 유지를 건다', async () => {
    const view = await handedOffSession();
    pause(view);
    await act(async () => vi.advanceTimersByTime(DEADLINE_AFTER_MS));
    expect(view.result.current?.state.source.kind).toBe('rewind');
    expect(view.player.keepPausedOnSwap).toHaveBeenCalledTimes(1);
  });

  it('멈춘 채 라이브가 닫혀(H2 · 라이브 404) 되감기로 되돌리면 재생기에 멈춤 유지를 건다', async () => {
    const view = await handedOffSession();
    pause(view);
    act(() => view.result.current?.dispatch({ type: 'live-closed' }));
    expect(view.result.current?.state.source.kind).toBe('rewind');
    expect(view.player.keepPausedOnSwap).toHaveBeenCalledTimes(1);
  });

  it('세션 칸이 재생 중이어도 되돌림이면 건다 — 멈췄는지는 재생기가 사람의 사건으로 가린다(c13f cx H1)', async () => {
    const view = await handedOffSession();
    act(() => view.result.current?.dispatch({ type: 'live-closed' }));
    expect(view.result.current?.state.playing).toBe(true);
    expect(view.player.keepPausedOnSwap).toHaveBeenCalledTimes(1);
  });

  it('멈춘 채 사람이 창 밖으로 옮긴 시크(H6)는 멈춤 유지를 걸지 않는다(현행)', async () => {
    const seeking = await handedOffSession();
    pause(seeking);
    const seek = { lowerMs: NOW - 16_000, endMs: NOW };
    act(() =>
      seeking.result.current?.dispatch({ type: 'seek', targetMs: NOW - 600_000, view: seek }),
    );
    expect(seeking.result.current?.state.source.kind).toBe('rewind');
    expect(seeking.player.keepPausedOnSwap).not.toHaveBeenCalled();
  });
});

describe('라이브 창 탐침 — probe-live 효과(설계 r9 2절 · c13g 휴면)', () => {
  const LIVE = `${BASE}/live/key-1/index.m3u8`;
  const PDT0 = NOW - 28_000;
  /** 4초 조각 일곱(첫 조각에만 PDT) — 하한 = 첫 PDT + 3 × TD · 끝 = 첫 PDT + 28초 */
  const LIVE_MEDIA = [
    '#EXTM3U',
    '#EXT-X-TARGETDURATION:4',
    `#EXT-X-PROGRAM-DATE-TIME:${new Date(PDT0).toISOString()}`,
    ...[...Array(7).keys()].map((i) => `#EXTINF:4.000,\nseg${i}.mp4`),
  ].join('\n');
  type LiveProbeEvent = Extract<SessionEvent, { type: 'live-probe' }>;
  /** 라이브 목록은 liveMedia 로 · 스트림 경로는 되감기 진입을 연다 */
  function liveAnswer(liveMedia: () => Response | Promise<Response>) {
    return (url: string) => {
      if (url === LIVE) return liveMedia();
      if (url === STREAM) return jsonResponse(200, readyBody(A1, NOW - 3_600_000, NOW));
      return jsonResponse(404);
    };
  }

  function probeEvents(): LiveProbeEvent[] {
    return vi
      .mocked(transition)
      .mock.calls.map(([, event]) => event)
      .filter((e): e is LiveProbeEvent => e.type === 'live-probe');
  }

  /** 받기가 끝날 때까지 마이크로태스크를 넉넉히 돌린다(본문 읽기가 몇 단 더 걸린다) */
  async function settle() {
    for (let i = 0; i < 4; i += 1) await flush();
  }

  beforeEach(async () => {
    const actual = await vi.importActual<typeof import('./rewindSwitch')>('./rewindSwitch');
    vi.mocked(transition).mockReset().mockImplementation(actual.transition);
  });

  /**
   * 되감기 중인 세션에 probe-live 효과 하나를 끼운다 — 탐침을 청하는 행(R6 · E1)은 c13h 라 결과 모양만 넣는다
   */
  async function probingSession() {
    const view = renderHook((o: SessionOptions) => usePlaybackSession(o), {
      initialProps: options(),
    });
    await flush();
    const seek = { lowerMs: NOW - 16_000, endMs: NOW };
    act(() => view.result.current?.dispatch({ type: 'seek', targetMs: NOW - 600_000, view: seek }));
    expect(view.result.current?.state.phase.kind).toBe('rewind');
    vi.mocked(transition).mockImplementationOnce((s) => ({
      state: s,
      effects: [{ kind: 'probe-live', seq: 1, url: LIVE }],
    }));
    act(() => view.result.current?.dispatch({ type: 'playing' }));
    return view;
  }

  it('효과 하나에 라이브 목록 받기 1 · live-probe 1 — 순번과 하한 · 끝을 싣는다', async () => {
    answer = liveAnswer(() => new Response(LIVE_MEDIA));
    await probingSession();
    await settle();
    expect(calls.filter((url) => url === LIVE)).toHaveLength(1);
    expect(probeEvents()).toEqual([
      { type: 'live-probe', seq: 1, result: { kind: 'ok', floorMs: PDT0 + 12_000, endMs: NOW } },
    ]);
  });

  it('휴면 — live-probe 는 아직 상태도 효과도 바꾸지 않는다(결과 행 onLiveProbe 는 c13h)', async () => {
    answer = liveAnswer(() => new Response(LIVE_MEDIA));
    const view = await probingSession();
    await settle();
    const transitionCalls = vi.mocked(transition).mock.calls;
    const at = transitionCalls.findIndex(([, event]) => event.type === 'live-probe');
    expect(at).toBeGreaterThanOrEqual(0);
    const out = vi.mocked(transition).mock.results[at]?.value;
    expect(out?.state).toBe(transitionCalls[at]![0]);
    expect(out?.effects).toEqual([]);
    expect(view.result.current?.state.phase.kind).toBe('rewind');
  });

  it('받는 사이 세션이 바뀌면(다른 방송) 결과를 버린다', async () => {
    let release!: () => void;
    answer = liveAnswer(
      () =>
        new Promise<Response>((resolve) => {
          release = () => resolve(new Response(LIVE_MEDIA));
        }),
    );
    const view = await probingSession();
    await flush();
    expect(calls.filter((url) => url === LIVE)).toHaveLength(1);
    view.rerender(options({ roundId: 'r-B', accessKey: 'r-B|key-1' }));
    await flush();
    expect(view.result.current?.state.roundId).toBe('r-B');
    release();
    await settle();
    expect(probeEvents()).toEqual([]);
  });

  /** 라이브 목록 받기를 붙잡아 두고 그 signal 을 모은다 */
  function holdLiveFetches(): AbortSignal[] {
    const signals: AbortSignal[] = [];
    stubFetch((url, init) => {
      if (url !== LIVE) return liveAnswer(() => jsonResponse(404))(url);
      signals.push(init?.signal as AbortSignal);
      return new Promise<Response>(() => {});
    });
    return signals;
  }

  it('다음 탐침은 나가 있는 앞 탐침을 끊는다', async () => {
    const signals = holdLiveFetches();
    const view = await probingSession();
    await flush();
    expect(signals).toHaveLength(1);
    vi.mocked(transition).mockImplementationOnce((s) => ({
      state: s,
      effects: [{ kind: 'probe-live', seq: 2, url: LIVE }],
    }));
    act(() => view.result.current?.dispatch({ type: 'playing' }));
    await flush();

    expect(signals).toHaveLength(2);
    expect(signals.map((signal) => signal.aborted)).toEqual([true, false]);
  });

  it('화면을 떠나면 나가 있는 탐침을 끊는다', async () => {
    const signals = holdLiveFetches();
    const view = await probingSession();
    await flush();
    expect(signals.map((signal) => signal.aborted)).toEqual([false]);

    view.unmount();

    expect(signals.map((signal) => signal.aborted)).toEqual([true]);
  });
});
