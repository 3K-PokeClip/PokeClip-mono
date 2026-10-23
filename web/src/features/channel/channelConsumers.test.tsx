import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLiveStreamSelection } from '@/features/broadcast/livenow/useLiveStreamSelection';
import { resetLiveData } from '@/features/broadcast/livenow/liveDataStore';
import { forgetLiveStreamId } from '@/features/broadcast/streamSelection';
import { useVodListMockState } from '@/features/broadcast/vod/useVodListMockState';
import { useLibraryMockState } from '@/features/clips/library/useLibraryMockState';
import { useHomeMockState } from '@/features/home/useHomeMockState';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { createTestQueryClient, makeQueryWrapper } from '@/test/testProviders';
import { ChannelStreamerIdContext } from './ChannelScope';

// 화면 훅 넷(홈·지난 방송·보관함·라이브)이 ChannelScope가 내려 준 번호를 목록 호출에 싣는가(POK-390).
// 공급자가 없을 때(null) 번호를 안 싣는 것은 각 화면의 기존 시험이 그대로 돈다는 것으로 잰다.

vi.mock('next/navigation', () => ({
  usePathname: () => '/broadcast/livenow',
  useSearchParams: () => new URLSearchParams(''),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

function stub() {
  return stubFetch((url) => {
    if (url.startsWith('/api/clip/broadcasts?'))
      return jsonResponse(200, { broadcasts: [], nextCursor: null });
    if (url.startsWith('/api/clip/library?'))
      return jsonResponse(200, { items: [], nextCursor: null });
    if (url === '/api/auth/me')
      return jsonResponse(200, {
        id: 7,
        email: 'me@example.com',
        name: '나',
        profileImageUrl: null,
      });
    return jsonResponse(404, { error: 'not_in_this_test' });
  });
}

function inChannel(id: number) {
  const Query = makeQueryWrapper(createTestQueryClient());
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <Query>
        <ChannelStreamerIdContext.Provider value={id}>{children}</ChannelStreamerIdContext.Provider>
      </Query>
    );
  };
}

/** 그 경로로 간 호출의 streamerId 값들 */
function streamerIdsOf(spy: ReturnType<typeof stubFetch>, path: string) {
  return spy.mock.calls
    .map(([url]) => String(url))
    .filter((url) => url.startsWith(path))
    .map((url) => new URL(url, 'http://localhost').searchParams.get('streamerId'));
}

beforeEach(() => {
  forgetLiveStreamId();
  resetLiveData();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('화면 훅이 고른 채널 번호를 싣는다', () => {
  it('홈: 라이브·지난 방송·보관함 셋 다', async () => {
    const spy = stub();
    renderHook(() => useHomeMockState(), { wrapper: inChannel(3) });

    await waitFor(() => expect(streamerIdsOf(spy, '/api/clip/library?')).toEqual(['3']));
    await waitFor(() => expect(streamerIdsOf(spy, '/api/clip/broadcasts?')).toEqual(['3', '3']));
  });

  it('지난 방송', async () => {
    const spy = stub();
    renderHook(() => useVodListMockState(), { wrapper: inChannel(3) });

    await waitFor(() =>
      expect(streamerIdsOf(spy, '/api/clip/broadcasts?state=past')).toEqual(['3']),
    );
  });

  it('보관함', async () => {
    const spy = stub();
    renderHook(() => useLibraryMockState(), { wrapper: inChannel(3) });

    await waitFor(() => expect(streamerIdsOf(spy, '/api/clip/library?')).toEqual(['3']));
  });

  it('라이브', async () => {
    const spy = stub();
    renderHook(() => useLiveStreamSelection(), { wrapper: inChannel(3) });

    await waitFor(() =>
      expect(streamerIdsOf(spy, '/api/clip/broadcasts?state=live')).toEqual(['3']),
    );
  });
});
