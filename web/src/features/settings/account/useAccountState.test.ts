import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '@/api/auth';
import { useAccountState } from './useAccountState';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { createTestQueryClient, makeQueryWrapper } from '@/test/testProviders';

// 취소 뒤 「한 번 더 읽기」는 9초 시계에 걸려 있어 화면 테스트로는 잡기 어렵다(가짜 시계와
// userEvent·findBy* 가 서로를 기다리다 멎는다). 훅만 렌더해 시계를 직접 감는다.

const ME: Me = {
  id: 1,
  email: 'raccoon.games@gmail.com',
  name: '게임하는너구리',
  profileImageUrl: 'https://example.test/a.png',
};

function meReadsOf(spy: ReturnType<typeof stubFetch>): number {
  return spy.mock.calls.filter(
    ([url, init]) => url === '/api/auth/me' && (init?.method ?? 'GET') === 'GET',
  ).length;
}

beforeEach(() => {
  window.localStorage.clear();
  useAuthStore.setState({ accessToken: 'access-1', refreshToken: 'refresh-1', hydrated: true });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('useAccountState — 취소 뒤 진실 맞추기', () => {
  it('즉시 한 번, 9초 뒤 한 번 더 읽는다 — 첫 조회가 서버 커밋을 앞질렀을 수 있다', async () => {
    // 서버는 창고에 쓴 뒤(상한 8초) 표를 갱신하므로 취소 직후의 조회는 옛 값을 받을 수 있다.
    // 헤더·사이드바는 계속 마운트라 새 관찰자도 안 생겨, 예약해 둔 두 번째 조회가 유일한 교정이다.
    const spy = stubFetch(() => jsonResponse(200, ME));
    const { result } = renderHook(() => useAccountState(), {
      wrapper: makeQueryWrapper(createTestQueryClient()),
    });
    await waitFor(() => expect(result.current.me).toEqual(ME));

    vi.useFakeTimers();
    const before = meReadsOf(spy);
    act(() => result.current.refetchMe());

    // 즉시 한 번
    await vi.waitFor(() => expect(meReadsOf(spy)).toBe(before + 1));

    // 9초가 되기 전에는 더 읽지 않는다
    await act(() => vi.advanceTimersByTimeAsync(8_000));
    expect(meReadsOf(spy)).toBe(before + 1);

    // 넘기면 한 번 더 — 이것이 없으면 취소 뒤 옛 아바타가 그대로 굳는다
    await act(() => vi.advanceTimersByTimeAsync(2_000));
    await vi.waitFor(() => expect(meReadsOf(spy)).toBe(before + 2));
  });

  it('화면이 사라져도 예약된 조회는 살아 있다 — 취소 직후 다른 화면으로 옮겨도 교정된다', async () => {
    // 콜백이 컴포넌트 상태가 아니라 queryClient만 만지므로 언마운트에서 걷을 이유가 없다.
    // 걷으면 취소 9초 안에 이동한 사용자에게 옛 아바타가 그대로 남는다(헤더·사이드바는 계속
    // 마운트라 그 화면들이 관찰자로 남아 실제로는 재조회까지 간다 — 여기서는 훅만 떼므로
    // 관찰자가 0이 되어 네트워크 대신 무효화 호출로 확인한다).
    stubFetch(() => jsonResponse(200, ME));
    const queryClient = createTestQueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const { result, unmount } = renderHook(() => useAccountState(), {
      wrapper: makeQueryWrapper(queryClient),
    });
    await waitFor(() => expect(result.current.me).toEqual(ME));

    vi.useFakeTimers();
    act(() => result.current.refetchMe());
    const afterCancel = invalidate.mock.calls.length;

    unmount();
    await act(() => vi.advanceTimersByTimeAsync(10_000));

    // 정리에서 타이머를 걷으면 여기서 멈춘다
    expect(invalidate.mock.calls.length).toBeGreaterThan(afterCancel);
  });
});
