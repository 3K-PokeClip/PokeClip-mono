import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from '@/api/client';
import { onCrossTabSessionChange, useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';

function bearerOf(init?: RequestInit): string | null {
  return new Headers(init?.headers).get('Authorization');
}

function seedStorage(refreshToken: string | null) {
  if (refreshToken === null) window.localStorage.removeItem('pc-auth');
  else window.localStorage.setItem('pc-auth', JSON.stringify({ v: 1, refreshToken }));
}

/** 탭 간 락 흉내 — 같은 이름의 요청을 순서대로 돌린다. grant를 붙잡아 두면 대기 상태를 만들 수 있다. */
function installFakeLocks(hold = false) {
  let chain: Promise<unknown> = Promise.resolve();
  let release: (() => void) | null = null;
  const gate = hold
    ? new Promise<void>((resolve) => {
        release = resolve;
      })
    : Promise.resolve();
  const request = vi.fn((_name: string, cb: () => Promise<unknown>) => {
    const run = chain.then(() => gate).then(() => cb());
    chain = run.catch(() => undefined);
    return run;
  });
  Object.defineProperty(window.navigator, 'locks', { configurable: true, value: { request } });
  return { request, grant: () => release?.() };
}

beforeEach(() => {
  window.localStorage.clear();
  // 로그인한 탭의 정본(localStorage)에는 항상 자기 refresh가 있다 — 스토어만 채우면
  // "다른 탭이 로그아웃한 상태"로 읽혀 회전 직전 동기화가 세션을 접는다.
  seedStorage('old-refresh');
  useAuthStore.setState({
    accessToken: 'old-access',
    refreshToken: 'old-refresh',
    hydrated: true,
    rotatedFrom: null,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  // 가짜 락을 걷어낸다 — jsdom에는 Web Locks가 없으므로 기본은 락 없는 폴백 경로다
  delete (window.navigator as { locks?: unknown }).locks;
  if (vi.isFakeTimers()) {
    vi.clearAllTimers();
    vi.useRealTimers();
  }
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

  it('refresh까지 401이면(만료·도난 감지) 옆 탭을 잠깐 기다린 뒤 토큰을 비우고 401로 던진다', async () => {
    vi.useFakeTimers();
    stubFetch(() => jsonResponse(401, { message: '인증 실패' }));

    const pending = apiFetch('/api/protected').catch((e: unknown) => e);
    await vi.runAllTimersAsync(); // 옆 탭의 회전 메시지는 오지 않는다 — 유예가 끝난다

    expect(((await pending) as ApiError).status).toBe(401);
    expect(useAuthStore.getState().refreshToken).toBeNull();
    expect(window.localStorage.getItem('pc-auth')).toBeNull();
  });

  it('회전 성공은 직전 refresh를 prev로 실어 다른 탭에 알린다 — 같은 사슬만 이어받게', async () => {
    stubFetch((url, init) => {
      if (url === '/api/auth/refresh')
        return jsonResponse(200, { accessToken: 'new-access', refreshToken: 'new-refresh' });
      return bearerOf(init) === 'Bearer new-access'
        ? jsonResponse(200, { ok: true })
        : jsonResponse(401, { message: '인증 실패' });
    });
    const received: unknown[] = [];
    const peer = new BroadcastChannel('pc-auth');
    peer.onmessage = (e) => received.push(e.data);

    await apiFetch('/api/protected');

    expect(received).toEqual([
      {
        v: 1,
        type: 'rotate',
        prev: 'old-refresh',
        pair: { accessToken: 'new-access', refreshToken: 'new-refresh' },
      },
    ]);
    peer.close();
  });

  it('회전 401 직후 옆 탭이 같은 토큰을 먼저 회전한 메시지가 오면 세션을 접지 않고 그 access로 재시도한다', async () => {
    vi.useFakeTimers();
    const spy = stubFetch((url, init) => {
      // 동시 회전의 진 쪽 — 서버는 10초 유예 안이라 폐기 없이 401만 준다
      if (url === '/api/auth/refresh') return jsonResponse(401, { message: '인증 실패' });
      return bearerOf(init) === 'Bearer sibling-access'
        ? jsonResponse(200, { ok: true })
        : jsonResponse(401, { message: '인증 실패' });
    });
    useAuthStore.getState().hydrate(); // 채널 바인딩

    const pending = apiFetch('/api/protected');
    await vi.waitFor(() =>
      expect(spy.mock.calls.some(([url]) => url === '/api/auth/refresh')).toBe(true),
    );
    seedStorage('sibling-refresh'); // 이긴 탭은 정본을 먼저 쓰고 알린다
    new BroadcastChannel('pc-auth').postMessage({
      v: 1,
      type: 'rotate',
      prev: 'old-refresh',
      pair: { accessToken: 'sibling-access', refreshToken: 'sibling-refresh' },
    });
    await vi.runAllTimersAsync();

    expect((await pending).status).toBe(200);
    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'sibling-access',
      refreshToken: 'sibling-refresh',
    });
    expect(spy.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1);
    expect(spy.mock.calls.some(([url]) => url === '/api/auth/logout')).toBe(false);
  });

  it('회전 401 대기 중 다른 계정의 로그인이 오면 그 계정의 토큰으로 이 요청을 완료하지 않는다', async () => {
    vi.useFakeTimers();
    const spy = stubFetch(() => jsonResponse(401, { message: '인증 실패' }));
    useAuthStore.getState().hydrate();

    const pending = apiFetch('/api/protected').catch((e: unknown) => e);
    await vi.waitFor(() =>
      expect(spy.mock.calls.some(([url]) => url === '/api/auth/refresh')).toBe(true),
    );
    seedStorage('other-refresh');
    new BroadcastChannel('pc-auth').postMessage({
      v: 1,
      type: 'login',
      pair: { accessToken: 'other-access', refreshToken: 'other-refresh' },
    });
    await vi.runAllTimersAsync();

    expect(((await pending) as ApiError).status).toBe(401);
    // 새 계정 세션은 그대로 두고(clear 없음), 다른 계정의 Bearer로 재시도하지도 않는다
    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'other-access',
      refreshToken: 'other-refresh',
    });
    expect(spy.mock.calls.filter(([url]) => url === '/api/protected')).toHaveLength(1);
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

  it('refresh가 200인데 JSON이 아니면 토큰을 보존하고 ApiError로 던진다 — 프록시 가로채기 내성', async () => {
    stubFetch((url) =>
      url === '/api/auth/refresh'
        ? new Response('<html>점검 중</html>', {
            status: 200,
            headers: { 'Content-Type': 'text/html' },
          })
        : jsonResponse(401, { message: '인증 실패' }),
    );

    // SyntaxError가 아니라 ApiError(401)여야 호출부의 status 분기가 안 깨진다
    await expect(apiFetch('/api/protected')).rejects.toMatchObject({ status: 401 });
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

  it('회전 401 뒤 메시지는 못 받아도 정본이 바뀌어 있으면 세션을 접지 않고 정본을 채택한다', async () => {
    vi.useFakeTimers();
    const spy = stubFetch(() => jsonResponse(401, { message: '인증 실패' }));
    useAuthStore.getState().hydrate();
    const listener = vi.fn();
    onCrossTabSessionChange(listener);

    const pending = apiFetch('/api/protected').catch((e: unknown) => e);
    await vi.waitFor(() =>
      expect(spy.mock.calls.some(([url]) => url === '/api/auth/refresh')).toBe(true),
    );
    seedStorage('sibling-refresh'); // 이긴 탭이 정본은 썼지만 메시지는 유예 안에 오지 않았다
    await vi.runAllTimersAsync();

    expect(((await pending) as ApiError).status).toBe(401);
    // 로그아웃이 아니라 정본 채택 — 다음 요청이 이 토큰으로 회전한다
    expect(useAuthStore.getState()).toMatchObject({
      accessToken: null,
      refreshToken: 'sibling-refresh',
    });
    expect(window.localStorage.getItem('pc-auth')).toContain('sibling-refresh');
    expect(listener).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls.some(([url]) => url === '/api/auth/logout')).toBe(false);
  });

  it('회전 직전에 정본을 다시 읽는다 — 메시지를 놓친 탭이 묵은 refresh로 회전하면 전 세션이 끊긴다', async () => {
    const spy = stubFetch((url, init) => {
      if (url === '/api/auth/refresh')
        return jsonResponse(200, { accessToken: 'new-access', refreshToken: 'new-refresh' });
      return bearerOf(init) === 'Bearer new-access'
        ? jsonResponse(200, { ok: true })
        : jsonResponse(401, { message: '인증 실패' });
    });
    seedStorage('fresh-refresh'); // 다른 탭이 회전했는데 이 탭은 아직 old-refresh를 들고 있다

    const res = await apiFetch('/api/protected');

    expect(res.status).toBe(200);
    const refreshCall = spy.mock.calls.find(([url]) => url === '/api/auth/refresh');
    expect(String(refreshCall?.[1]?.body)).toContain('fresh-refresh');
    expect(String(refreshCall?.[1]?.body)).not.toContain('old-refresh');
  });

  it('회전은 탭 간 락(pc-auth:refresh) 안에서 돈다', async () => {
    const locks = installFakeLocks();
    stubFetch((url, init) => {
      if (url === '/api/auth/refresh')
        return jsonResponse(200, { accessToken: 'new-access', refreshToken: 'new-refresh' });
      return bearerOf(init) === 'Bearer new-access'
        ? jsonResponse(200, { ok: true })
        : jsonResponse(401, { message: '인증 실패' });
    });

    const res = await apiFetch('/api/protected');

    expect(res.status).toBe(200);
    expect(locks.request).toHaveBeenCalledWith('pc-auth:refresh', expect.any(Function));
  });

  it('락을 기다리는 동안 옆 탭이 내 토큰을 회전해 이어받았으면 보내지 않고 그 access로 재시도한다', async () => {
    const locks = installFakeLocks(true);
    const spy = stubFetch((url, init) => {
      if (url === '/api/auth/refresh') return jsonResponse(401, { message: '인증 실패' });
      return bearerOf(init) === 'Bearer sibling-access'
        ? jsonResponse(200, { ok: true })
        : jsonResponse(401, { message: '인증 실패' });
    });
    useAuthStore.getState().hydrate();

    const pending = apiFetch('/api/protected');
    await vi.waitFor(() => expect(locks.request).toHaveBeenCalled());
    // 락을 쥔 옆 탭이 회전을 마치고(정본 저장 → 알림) 락을 놓는다
    seedStorage('sibling-refresh');
    new BroadcastChannel('pc-auth').postMessage({
      v: 1,
      type: 'rotate',
      prev: 'old-refresh',
      pair: { accessToken: 'sibling-access', refreshToken: 'sibling-refresh' },
    });
    locks.grant();

    expect((await pending).status).toBe(200);
    expect(spy.mock.calls.some(([url]) => url === '/api/auth/refresh')).toBe(false);
    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'sibling-access',
      refreshToken: 'sibling-refresh',
    });
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
