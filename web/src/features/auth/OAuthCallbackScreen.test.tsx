import { StrictMode, type ReactElement } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OAuthCallbackScreen } from '@/features/auth/OAuthCallbackScreen';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { createTestQueryClient } from '@/test/testProviders';

const { replace, searchRef } = vi.hoisted(() => ({
  replace: vi.fn(),
  searchRef: { current: new URLSearchParams() },
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  useSearchParams: () => searchRef.current,
}));

const STATE_KEY = 'pc-oauth-state';

/** 화면이 useQueryClient를 쓰므로 항상 QueryClientProvider로 감싼다. */
function renderWithClient(ui: ReactElement) {
  const queryClient = createTestQueryClient();
  render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
  return queryClient;
}

beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
  replace.mockReset();
  useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: false });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('OAuthCallbackScreen', () => {
  it('정상 code는 StrictMode 이중 마운트에도 한 번만 교환하고 가려던 곳으로 복원한다', async () => {
    window.sessionStorage.setItem(
      STATE_KEY,
      JSON.stringify({ state: 'state-1', returnTo: '/settings/plugin' }),
    );
    searchRef.current = new URLSearchParams('code=code-1&state=state-1');
    const spy = stubFetch((url) =>
      url === '/api/auth/google'
        ? jsonResponse(200, { accessToken: 'access-1', refreshToken: 'refresh-1' })
        : jsonResponse(404),
    );

    // 실제 dev 환경과 같은 StrictMode 이중 이펙트 — code는 1회용이라 POST가 두 번이면 안 된다
    const queryClient = renderWithClient(
      <StrictMode>
        <OAuthCallbackScreen />
      </StrictMode>,
    );
    // 이전 계정이 남긴 캐시 재현 — 로그인 성공이 이것을 비워야 한다 (리뷰 #72)
    queryClient.setQueryData(['auth', 'me'], { name: '이전 사용자' });

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/settings/plugin'));
    expect(spy.mock.calls.filter(([url]) => url === '/api/auth/google')).toHaveLength(1);
    expect(useAuthStore.getState().accessToken).toBe('access-1');
    expect(queryClient.getQueryData(['auth', 'me'])).toBeUndefined();
  });

  it('state가 어긋나면 교환하지 않고 에러 안내를 띄운다', () => {
    window.sessionStorage.setItem(STATE_KEY, JSON.stringify({ state: 'state-1', returnTo: null }));
    searchRef.current = new URLSearchParams('code=code-1&state=state-other');
    const spy = stubFetch(() => jsonResponse(404));

    renderWithClient(<OAuthCallbackScreen />);

    expect(screen.getByText('로그인을 확인할 수 없어요')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('?error=(동의 취소)는 취소 안내를 띄운다', () => {
    searchRef.current = new URLSearchParams('error=access_denied');
    stubFetch(() => jsonResponse(404));

    renderWithClient(<OAuthCallbackScreen />);

    expect(screen.getByText('로그인이 취소되었어요')).toBeInTheDocument();
  });

  it('교환 401이면 실패 안내를 띄우고 버튼이 로그인 화면으로 보낸다', async () => {
    window.sessionStorage.setItem(STATE_KEY, JSON.stringify({ state: 'state-1', returnTo: null }));
    searchRef.current = new URLSearchParams('code=code-1&state=state-1');
    stubFetch(() => jsonResponse(401, { message: '인증 실패' }));

    renderWithClient(<OAuthCallbackScreen />);

    expect(await screen.findByText('로그인에 실패했어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '로그인 화면으로' }));
    expect(replace).toHaveBeenCalledWith('/login');
  });
});
