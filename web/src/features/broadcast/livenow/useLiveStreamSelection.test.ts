import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { forgetLiveStreamId } from '@/features/broadcast/streamSelection';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { publishLiveData, resetLiveData } from './liveDataStore';
import { LIVE_RESELECT_MS, useLiveStreamSelection } from './useLiveStreamSelection';

// 주소가 고른 방송(POK-254). Next는 같은 화면에서 ?stream=만 바뀌면 화면을 다시 세우지 않고, 주소창은 새 화면을 그린 뒤에
// 바꾼다. 그래서 이 시험은 주소를 useSearchParams(nav.search)로만 바꾸고 주소창(window.location)은 처음 주소에 둔다.
const nav = vi.hoisted(() => ({ search: '' }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(nav.search),
  usePathname: () => '/broadcast/livenow',
}));

describe('useLiveStreamSelection: 주소의 ?stream=', () => {
  beforeEach(() => {
    forgetLiveStreamId();
    resetLiveData();
    stubFetch((url) =>
      url.startsWith('/api/clip/broadcasts?state=live')
        ? jsonResponse(200, { broadcasts: [{ streamId: 'live-1' }], nextCursor: null })
        : jsonResponse(404),
    );
    window.history.pushState({}, '', '/broadcast/livenow?stream=a');
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.pushState({}, '', '/');
    nav.search = '';
  });

  it('?stream=만 바뀌어도 그 방송을 다시 고르고, 빠지면 지금 방송 중인 것으로 돌아간다(사이드바·뒤로 가기)', async () => {
    nav.search = '?stream=a';
    const { result, rerender } = renderHook(() => useLiveStreamSelection());
    await waitFor(() =>
      expect(result.current).toMatchObject({ streamId: 'a', resolved: true, deferred: null }),
    );

    nav.search = '?stream=b';
    rerender();
    await waitFor(() => expect(result.current.streamId).toBe('b'));

    // 사이드바 「라이브 대시보드」(?stream= 없음): 주소창은 아직 옛 주소여도 방송 중인 것을 묻는다
    nav.search = '';
    rerender();
    await waitFor(() => expect(result.current.streamId).toBe('live-1'));
  });
});

describe('useLiveStreamSelection: 재생 보유(POK-200 M5 · 설계 5.5절)', () => {
  // 고정하지 않은 대시보드가 보던 재생을 끊지 않게: 보유 중이면 다시 고른 답을 선택에 넣지 않고 deferred 로 낸다(R45)
  let live: string;
  let failing: boolean;
  beforeEach(() => {
    vi.useFakeTimers();
    forgetLiveStreamId();
    resetLiveData();
    live = 'live-1';
    failing = false;
    stubFetch((url) =>
      failing
        ? jsonResponse(503, { error: 'unavailable' })
        : url.startsWith('/api/clip/broadcasts?state=live')
          ? jsonResponse(200, {
              broadcasts: live === '' ? [] : [{ streamId: live }],
              nextCursor: null,
            })
          : jsonResponse(404),
    );
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  async function flush() {
    for (let i = 0; i < 6; i += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
    }
  }

  async function holding() {
    const view = renderHook(() => useLiveStreamSelection());
    await flush();
    expect(view.result.current.streamId).toBe('live-1');
    act(() => publishLiveData({ playbackHold: 'live-1' }));
    return view;
  }

  it('보유 중 10초 재조회가 빈 답이면 선택은 그대로다 — 꺼짐 화면이 서지 않는다', async () => {
    const { result } = await holding();
    live = '';
    await act(async () => vi.advanceTimersByTimeAsync(LIVE_RESELECT_MS));
    await flush();
    expect(result.current).toMatchObject({ streamId: 'live-1', resolved: true, deferred: '' });
  });

  it('보유 중 다른 방송이 답이면 deferred 로 낸다', async () => {
    const { result } = await holding();
    live = 'live-2';
    await act(async () => vi.advanceTimersByTimeAsync(LIVE_RESELECT_MS));
    await flush();
    expect(result.current).toMatchObject({
      streamId: 'live-1',
      resolved: true,
      deferred: 'live-2',
    });
  });

  it('보유 중 ended 재조회의 답도 선택에 넣지 않는다', async () => {
    const { result } = await holding();
    live = 'live-2';
    act(() => publishLiveData({ status: 'ended' }));
    await flush();
    expect(result.current.streamId).toBe('live-1');
    expect(result.current.deferred).toBe('live-2');
  });

  it('보유가 풀리면 곧바로 새로 묻고 답을 넣는다', async () => {
    const { result } = await holding();
    live = 'live-2';
    await act(async () => vi.advanceTimersByTimeAsync(LIVE_RESELECT_MS));
    await flush();
    act(() => publishLiveData({ playbackHold: null }));
    await flush();
    expect(result.current).toMatchObject({ streamId: 'live-2', resolved: true, deferred: null });
  });

  it('사람이 주소를 옮기면(고정 화면 → 라이브 대시보드) 보유를 넘어 지금 방송 중인 것을 고른다(cx M)', async () => {
    nav.search = '?stream=live-1';
    try {
      const { result, rerender } = renderHook(() => useLiveStreamSelection());
      await flush();
      act(() => publishLiveData({ playbackHold: 'live-1' }));
      live = 'live-2';
      nav.search = '';
      rerender();
      await flush();
      expect(result.current).toMatchObject({ streamId: 'live-2', deferred: null });
    } finally {
      nav.search = '';
    }
  });

  it('보유가 없으면 지금 동작이다 — 빈 답이면 꺼짐(빈 선택)', async () => {
    const { result } = renderHook(() => useLiveStreamSelection());
    await flush();
    live = '';
    await act(async () => vi.advanceTimersByTimeAsync(LIVE_RESELECT_MS));
    await flush();
    expect(result.current.streamId).toBe('');
  });

  it('마지막 다시 고르기의 답(latest) — 실패하면 모름(null)이다: 보던 방송이라고 단정하지 않는다(cx H4)', async () => {
    const { result } = await holding();
    expect(result.current.latest).toBe('live-1');
    failing = true;
    await act(async () => vi.advanceTimersByTimeAsync(LIVE_RESELECT_MS));
    await flush();
    expect(result.current.latest).toBeNull();
    expect(result.current.streamId).toBe('live-1');
    failing = false;
    live = 'live-2';
    await act(async () => vi.advanceTimersByTimeAsync(LIVE_RESELECT_MS));
    await flush();
    expect(result.current.latest).toBe('live-2');
  });
});
