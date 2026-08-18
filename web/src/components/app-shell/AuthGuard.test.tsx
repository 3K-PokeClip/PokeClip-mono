import { screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthGuard, markIntentionalLogout } from '@/components/app-shell/AuthGuard';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => '/home',
}));

const ME = { id: 1, email: 'raccoon@example.com', name: '게임하는너구리', profileImageUrl: '' };

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  replace.mockReset();
  useAuthStore.setState({ accessToken: null, refreshToken: null, hydrated: false });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('AuthGuard', () => {
  it('비로그인이면 콘텐츠를 그리지 않고 가려던 경로를 남긴 뒤 /login으로 보낸다', async () => {
    stubFetch(() => jsonResponse(401, { message: '인증 실패' }));

    renderWithProviders(
      <AuthGuard>
        <div>보호 콘텐츠</div>
      </AuthGuard>,
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/login'));
    expect(screen.queryByText('보호 콘텐츠')).not.toBeInTheDocument();
    expect(window.sessionStorage.getItem('pc-auth-return')).toBe('/home');
  });

  it('의도적 로그아웃 직후에는 로그아웃한 화면을 복원 경로로 남기지 않는다', async () => {
    stubFetch(() => jsonResponse(401, { message: '인증 실패' }));
    markIntentionalLogout(); // useLogout이 clearTokens 직전에 남기는 표식

    renderWithProviders(
      <AuthGuard>
        <div>보호 콘텐츠</div>
      </AuthGuard>,
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/login'));
    expect(window.sessionStorage.getItem('pc-auth-return')).toBeNull();
    // 표식은 1회용 — 남아 있으면 다음 만료 때 복원 경로 저장을 잘못 억제한다
    expect(window.sessionStorage.getItem('pc-auth-logout')).toBeNull();
  });

  it('세션이 있으면 콘텐츠를 즉시 그리고, refresh 회전으로 me를 부트스트랩한다', async () => {
    window.localStorage.setItem('pc-auth', JSON.stringify({ v: 1, refreshToken: 'stored-1' }));
    // 새로고침 직후 재현 — access가 없어 me 401 → refresh 회전 → 재시도 200 경로를 탄다
    const spy = stubFetch((url, init) => {
      if (url === '/api/auth/refresh')
        return jsonResponse(200, { accessToken: 'boot-access', refreshToken: 'boot-refresh' });
      if (url === '/api/auth/me')
        return new Headers(init?.headers).get('Authorization') === 'Bearer boot-access'
          ? jsonResponse(200, ME)
          : jsonResponse(401, { message: '인증 실패' });
      return jsonResponse(404);
    });

    renderWithProviders(
      <AuthGuard>
        <div>보호 콘텐츠</div>
      </AuthGuard>,
    );

    expect(await screen.findByText('보호 콘텐츠')).toBeInTheDocument();
    await waitFor(() => expect(useAuthStore.getState().refreshToken).toBe('boot-refresh'));
    expect(spy.mock.calls.some(([url]) => url === '/api/auth/me')).toBe(true);
    expect(replace).not.toHaveBeenCalled();
  });

  it('refresh까지 401이면(만료·도난 감지) 세션을 접고 /login으로 보낸다', async () => {
    window.localStorage.setItem('pc-auth', JSON.stringify({ v: 1, refreshToken: 'stolen-1' }));
    stubFetch(() => jsonResponse(401, { message: '인증 실패' }));

    renderWithProviders(
      <AuthGuard>
        <div>보호 콘텐츠</div>
      </AuthGuard>,
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/login'));
    expect(useAuthStore.getState().refreshToken).toBeNull();
    expect(screen.queryByText('보호 콘텐츠')).not.toBeInTheDocument();
  });
});
