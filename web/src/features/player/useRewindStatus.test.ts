import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RewindStatusResult } from '@/api/rewindStatus';
import { jsonResponse } from '@/test/mockFetch';
import { REWIND_POLL_MS, REWIND_RETRY_MS, useRewindStatus } from './useRewindStatus';

const BASE = 'https://media.pokeclip.com';
const STREAM = `${BASE}/api/streams/key-1/rewind`;
const ROUND = `${BASE}/api/streams/key-1/rounds/r1/rewind`;
const URL_A = `${BASE}/dvr/key-1/a/index.m3u8`;
const URL_B = `${BASE}/dvr/key-1/b/index.m3u8`;

function body(url: string | null) {
  return url === null
    ? { ready: false, mode: 'live', url: null, firstPdt: null, headPdt: null }
    : {
        ready: true,
        mode: 'live',
        url,
        firstPdt: '2026-10-25T05:00:00.000Z',
        headPdt: '2026-10-25T05:10:00.000Z',
      };
}

/** 요청마다 답을 시험이 쥔다 — 순서를 뒤집어 늦게 온 답을 흉내 낸다(R14) */
interface Pending {
  url: string;
  signal: AbortSignal | undefined;
  answer: (b: unknown, status?: number) => void;
}
let pending: Pending[];

/** @param honorAbort false 면 끊어도 답이 온다 — 끊기 직전에 이미 도착해 처리를 기다리던 답을 흉내 낸다(순번만 막는다) */
function stubControllableFetch(honorAbort = true) {
  pending = [];
  const spy = vi.fn(
    (input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((resolve, reject) => {
        const signal = init?.signal ?? undefined;
        if (honorAbort)
          signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        pending.push({
          url: String(input),
          signal,
          answer: (b, status = 200) => resolve(jsonResponse(status, b)),
        });
      }),
  );
  vi.stubGlobal('fetch', spy);
  return spy;
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

function urlsOf(results: RewindStatusResult[]): (string | null)[] {
  return results.map((r) => (r.kind === 'ok' ? r.status.url : r.kind));
}

describe('useRewindStatus — 경로 하나를 10초로 폴링한다(설계 5.1절)', () => {
  let results: RewindStatusResult[];
  const onResult = (r: RewindStatusResult) => results.push(r);

  beforeEach(() => {
    vi.useFakeTimers();
    results = [];
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('경로가 켜지면 곧바로 한 번 묻고, 답 뒤 10초마다 다시 묻는다', async () => {
    const spy = stubControllableFetch();
    renderHook(() => useRewindStatus(STREAM, BASE, onResult));
    expect(spy).toHaveBeenCalledTimes(1);
    pending[0]!.answer(body(URL_A));
    await flush();
    expect(urlsOf(results)).toEqual([URL_A]);
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS - 1));
    expect(spy).toHaveBeenCalledTimes(1);
    await act(async () => vi.advanceTimersByTime(1));
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('경로가 없으면(꺼짐 · 제어면 주소 없음) 묻지 않는다', async () => {
    const spy = stubControllableFetch();
    renderHook(() => useRewindStatus(null, BASE, onResult));
    renderHook(() => useRewindStatus(STREAM, null, onResult));
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS * 3));
    expect(spy).not.toHaveBeenCalled();
  });

  it('늦게 온 앞 요청의 답은 버린다 — 새 답을 덮지 않는다(R14)', async () => {
    stubControllableFetch(false);
    const { result } = renderHook(() => useRewindStatus(STREAM, BASE, onResult));
    // 첫 요청이 걸린 채 refresh: 새 요청이 나간다
    act(() => result.current.refresh());
    expect(pending).toHaveLength(2);
    // 새 답이 먼저 오고, 옛 답이 뒤에 온다
    pending[1]!.answer(body(URL_B));
    await flush();
    pending[0]!.answer(body(URL_A));
    await flush();
    expect(urlsOf(results)).toEqual([URL_B]);
  });

  it('경로가 바뀌면 걸린 요청을 끊고 새 경로를 묻는다 · 옛 경로 답은 오지 않는다', async () => {
    stubControllableFetch();
    const { rerender } = renderHook(({ url }) => useRewindStatus(url, BASE, onResult), {
      initialProps: { url: STREAM },
    });
    rerender({ url: ROUND });
    expect(pending[0]!.signal?.aborted).toBe(true);
    expect(pending[1]!.url).toBe(ROUND);
    pending[1]!.answer({ error: 'not_found' }, 404);
    await flush();
    expect(urlsOf(results)).toEqual(['none']);
  });

  it('언마운트하면 걸린 요청을 끊고 더 묻지 않는다', async () => {
    const spy = stubControllableFetch();
    const { unmount } = renderHook(() => useRewindStatus(STREAM, BASE, onResult));
    unmount();
    expect(pending[0]!.signal?.aborted).toBe(true);
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS * 3));
    expect(spy).toHaveBeenCalledTimes(1);
    expect(results).toEqual([]);
  });

  it('숨은 탭이 보이게 되면 곧바로 다시 묻는다(숨은 탭 타이머 묶임)', async () => {
    const spy = stubControllableFetch();
    renderHook(() => useRewindStatus(STREAM, BASE, onResult));
    pending[0]!.answer(body(URL_A));
    await flush();
    const visibility = vi.spyOn(document, 'visibilityState', 'get');
    visibility.mockReturnValue('hidden');
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(spy).toHaveBeenCalledTimes(1);
    visibility.mockReturnValue('visible');
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(spy).toHaveBeenCalledTimes(2);
    visibility.mockRestore();
  });

  it('refresh(같은 url) 의 답이 그 url 이면 2초 간격 세 번 더 묻고 10초로 돌아간다(R16)', async () => {
    const spy = stubControllableFetch();
    const { result } = renderHook(() => useRewindStatus(STREAM, BASE, onResult));
    pending[0]!.answer(body(URL_A));
    await flush();
    act(() => result.current.refresh(URL_A));
    for (let i = 1; i <= 4; i += 1) {
      expect(spy).toHaveBeenCalledTimes(i + 1);
      pending[i]!.answer(body(URL_A));
      await flush();
      await act(async () => vi.advanceTimersByTime(REWIND_RETRY_MS));
    }
    // 세 번 더(모두 같은 url) 뒤에는 2초에 다시 묻지 않는다
    expect(spy).toHaveBeenCalledTimes(5);
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS - REWIND_RETRY_MS));
    expect(spy).toHaveBeenCalledTimes(6);
  });

  it('refresh(같은 url) 의 답이 다른 url 이면 곧바로 10초로 돌아간다', async () => {
    const spy = stubControllableFetch();
    const { result } = renderHook(() => useRewindStatus(STREAM, BASE, onResult));
    pending[0]!.answer(body(URL_A));
    await flush();
    act(() => result.current.refresh(URL_A));
    pending[1]!.answer(body(URL_B));
    await flush();
    await act(async () => vi.advanceTimersByTime(REWIND_RETRY_MS));
    expect(spy).toHaveBeenCalledTimes(2);
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS - REWIND_RETRY_MS));
    expect(spy).toHaveBeenCalledTimes(3);
  });

  it('잠시 불가(503)도 결과로 올리고 다음 주기에 다시 묻는다', async () => {
    const spy = stubControllableFetch();
    renderHook(() => useRewindStatus(STREAM, BASE, onResult));
    pending[0]!.answer({ error: 'unavailable' }, 503);
    await flush();
    expect(urlsOf(results)).toEqual(['unavailable']);
    await act(async () => vi.advanceTimersByTime(REWIND_POLL_MS));
    expect(spy).toHaveBeenCalledTimes(2);
  });
});
