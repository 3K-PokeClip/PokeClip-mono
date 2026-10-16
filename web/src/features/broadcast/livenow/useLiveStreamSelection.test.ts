import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { forgetLiveStreamId } from '@/features/broadcast/streamSelection';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { resetLiveData } from './liveDataStore';
import { useLiveStreamSelection } from './useLiveStreamSelection';

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
    await waitFor(() => expect(result.current).toEqual({ streamId: 'a', resolved: true }));

    nav.search = '?stream=b';
    rerender();
    await waitFor(() => expect(result.current.streamId).toBe('b'));

    // 사이드바 「라이브 대시보드」(?stream= 없음): 주소창은 아직 옛 주소여도 방송 중인 것을 묻는다
    nav.search = '';
    rerender();
    await waitFor(() => expect(result.current.streamId).toBe('live-1'));
  });
});
