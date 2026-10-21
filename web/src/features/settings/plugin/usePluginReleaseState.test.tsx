import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { createTestQueryClient, makeQueryWrapper } from '@/test/testProviders';
import { usePluginReleaseState } from './usePluginReleaseState';

const READY = {
  status: 'ready',
  release: {
    version: '0.2.0',
    tag: 'obs-plugin-v0.2.0',
    prerelease: false,
    publishedAt: '2026-10-20T03:00:00Z',
    releaseUrl: 'https://github.com/3K-PokeClip/PokeClip-mono/releases/tag/obs-plugin-v0.2.0',
    assets: {},
  },
};

function setup() {
  const queryClient = createTestQueryClient();
  const view = renderHook(() => usePluginReleaseState(), {
    wrapper: makeQueryWrapper(queryClient),
  });
  return { ...view, queryClient };
}

afterEach(() => vi.unstubAllGlobals());

describe('usePluginReleaseState: 다운로드 배너가 그릴 상태', () => {
  it('응답 전엔 loading, Release가 있으면 ready다', async () => {
    stubFetch(() => jsonResponse(200, READY));
    const { result } = setup();
    expect(result.current.kind).toBe('loading');
    await waitFor(() => expect(result.current.kind).toBe('ready'));
    expect(result.current).toMatchObject({ release: { tag: 'obs-plugin-v0.2.0' } });
  });

  it('Release가 없으면 unavailable이다', async () => {
    stubFetch(() => jsonResponse(200, { status: 'unavailable' }));
    const { result } = setup();
    await waitFor(() => expect(result.current.kind).toBe('unavailable'));
  });

  it('처음부터 못 읽으면 error이고, 다시 시도가 다시 묻는다', async () => {
    const spy = stubFetch(() => jsonResponse(503, { error: 'upstream_unavailable' }));
    const { result } = setup();
    await waitFor(() => expect(result.current.kind).toBe('error'));

    spy.mockImplementation(async () => jsonResponse(200, READY));
    act(() => {
      if (result.current.kind === 'error') result.current.retry();
    });
    await waitFor(() => expect(result.current.kind).toBe('ready'));
  });

  it('한 번 읽은 뒤 재조회가 실패해도 ready를 지킨다', async () => {
    const spy = stubFetch(() => jsonResponse(200, READY));
    const { result, queryClient } = setup();
    await waitFor(() => expect(result.current.kind).toBe('ready'));

    spy.mockImplementation(async () => jsonResponse(503, {}));
    await act(() => queryClient.refetchQueries({ queryKey: ['pluginRelease'] }));
    expect(result.current.kind).toBe('ready');
  });
});
