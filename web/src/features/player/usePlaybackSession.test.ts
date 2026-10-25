import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import type { XAnswer } from './rewindSwitch';
import { usePlaybackSession, type SessionPlayer, type SessionOptions } from './usePlaybackSession';

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
let answer: (url: string) => Response;

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
  return { seeks, seekToMs: (ms) => seeks.push(ms), seekToLiveEdge: () => seeks.push(-1) };
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
    act(() => result.current?.dispatch({ type: 'position', atMs: t + 5_000 }));
    const before = result.current!.state.source;
    act(() => result.current?.bindPlayer(null));
    act(() => result.current?.bindPlayer(fakePlayer()));
    expect(result.current?.state.source).toEqual({
      kind: 'rewind',
      url: A1,
      startAtMs: t + 5_000,
      generation: before.generation + 1,
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
});
