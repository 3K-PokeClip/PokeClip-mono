import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';

function bearerOf(init?: RequestInit): string | null {
  return new Headers(init?.headers).get('Authorization');
}

beforeEach(() => {
  window.localStorage.clear();
  useAuthStore.setState({ accessToken: 'old-access', refreshToken: 'old-refresh', hydrated: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('apiFetch', () => {
  it('401이면 refresh 회전 후 새 access로 딱 한 번 재시도한다', async () => {
    const spy = stubFetch((url, init) => {
      if (url === '/api/auth/refresh')
        return jsonResponse(200, { accessToken: 'new-access', refreshToken: 'new-refresh' });
      // 옛 access는 401, 새 access는 200 — 회전이 실제로 반영됐는지가 관건
      return bearerOf(init) === 'Bearer new-access'
        ? jsonResponse(200, { ok: true })
        : jsonResponse(401, { message: '인증 실패' });
    });

    const res = await apiFetch('/api/protected');

    expect(res.status).toBe(200);
    expect(useAuthStore.getState().refreshToken).toBe('new-refresh');
    const protectedCalls = spy.mock.calls.filter(([url]) => url === '/api/protected');
    expect(protectedCalls).toHaveLength(2);
  });

  it('동시 401 여러 건에도 refresh는 한 번만 나간다 — 회전 재사용은 곧 전 세션 폐기다', async () => {
    const spy = stubFetch((url, init) => {
      if (url === '/api/auth/refresh')
        return jsonResponse(200, { accessToken: 'new-access', refreshToken: 'new-refresh' });
      return bearerOf(init) === 'Bearer new-access'
        ? jsonResponse(200, { ok: true })
        : jsonResponse(401, { message: '인증 실패' });
    });

    await Promise.all([apiFetch('/api/a'), apiFetch('/api/b')]);

    const refreshCalls = spy.mock.calls.filter(([url]) => url === '/api/auth/refresh');
    expect(refreshCalls).toHaveLength(1);
  });

  it('refresh까지 401이면(만료·도난 감지) 토큰을 비우고 401로 던진다', async () => {
    stubFetch((url) =>
      url === '/api/auth/refresh'
        ? jsonResponse(401, { message: '인증 실패' })
        : jsonResponse(401, { message: '인증 실패' }),
    );

    await expect(apiFetch('/api/protected')).rejects.toMatchObject({ status: 401 });
    expect(useAuthStore.getState().refreshToken).toBeNull();
    expect(window.localStorage.getItem('pc-auth')).toBeNull();
  });

  it('회전 응답 대기 중 로그아웃되면 세션을 되살리지 않고 새 refresh를 폐기한다', async () => {
    let resolveRefresh!: (r: Response) => void;
    const spy = stubFetch((url) => {
      if (url === '/api/auth/refresh')
        return new Promise<Response>((resolve) => {
          resolveRefresh = resolve;
        });
      if (url === '/api/auth/logout') return new Response(null, { status: 204 });
      return jsonResponse(401, { message: '인증 실패' });
    });

    const pending = apiFetch('/api/protected').catch((e: unknown) => e);
    await vi.waitFor(() =>
      expect(spy.mock.calls.some(([url]) => url === '/api/auth/refresh')).toBe(true),
    );

    useAuthStore.getState().clearTokens(); // 응답이 오기 전에 사용자가 로그아웃
    resolveRefresh(jsonResponse(200, { accessToken: 'late-access', refreshToken: 'late-refresh' }));

    const err = await pending;
    expect((err as ApiError).status).toBe(401);
    // 세션이 부활하면 안 되고, 서버에 살아남은 새 refresh는 폐기 요청이 나가야 한다
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().refreshToken).toBeNull();
    await vi.waitFor(() =>
      expect(
        spy.mock.calls.some(
          ([url, init]) =>
            url === '/api/auth/logout' && String(init?.body).includes('late-refresh'),
        ),
      ).toBe(true),
    );
  });

  it('refresh가 5xx면 토큰을 보존한다 — 배포 중 일시 장애로 세션을 죽이면 안 된다', async () => {
    stubFetch((url) =>
      url === '/api/auth/refresh'
        ? jsonResponse(503, { message: '점검 중' })
        : jsonResponse(401, { message: '인증 실패' }),
    );

    await expect(apiFetch('/api/protected')).rejects.toMatchObject({ status: 401 });
    // 세션은 살아 있어야 한다 — 다음 시도가 같은 refresh로 다시 회전한다
    expect(useAuthStore.getState().refreshToken).toBe('old-refresh');
  });

  it('회전 대기 중 다른 탭이 세션을 바꾸면 덮어쓰지 않고 새 refresh를 폐기한다', async () => {
    let resolveRefresh!: (r: Response) => void;
    const spy = stubFetch((url) => {
      if (url === '/api/auth/refresh')
        return new Promise<Response>((resolve) => {
          resolveRefresh = resolve;
        });
      if (url === '/api/auth/logout') return new Response(null, { status: 204 });
      return jsonResponse(401, { message: '인증 실패' });
    });

    const pending = apiFetch('/api/protected').catch((e: unknown) => e);
    await vi.waitFor(() =>
      expect(spy.mock.calls.some(([url]) => url === '/api/auth/refresh')).toBe(true),
    );

    // 다른 탭에서 로그아웃 후 다른 계정 로그인 — storage 동기화로 이 탭에 유입된 상태
    useAuthStore.setState({ accessToken: null, refreshToken: 'other-account-refresh' });
    resolveRefresh(jsonResponse(200, { accessToken: 'late-access', refreshToken: 'late-refresh' }));

    const err = await pending;
    expect((err as ApiError).status).toBe(401);
    // 늦게 도착한 회전이 새 계정의 토큰을 이전 계정으로 되돌리면 안 된다
    expect(useAuthStore.getState().refreshToken).toBe('other-account-refresh');
    await vi.waitFor(() =>
      expect(
        spy.mock.calls.some(
          ([url, init]) =>
            url === '/api/auth/logout' && String(init?.body).includes('late-refresh'),
        ),
      ).toBe(true),
    );
  });

  it('401이 아닌 오류는 refresh 없이 상태 코드 그대로 던진다 — 429 발급 제한 분기용', async () => {
    const spy = stubFetch(() => jsonResponse(429, { reason: '발급 한도 초과' }));

    const err = await apiFetch('/api/stream-keys/pairing-codes', { method: 'POST' }).catch(
      (e: unknown) => e,
    );

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(429);
    expect((err as ApiError).message).toBe('발급 한도 초과');
    expect(spy.mock.calls.some(([url]) => url === '/api/auth/refresh')).toBe(false);
  });
});
