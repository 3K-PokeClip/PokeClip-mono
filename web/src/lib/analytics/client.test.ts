import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const posthog = vi.hoisted(() => ({
  init: vi.fn(),
  identify: vi.fn(),
  reset: vi.fn(),
  capture: vi.fn(),
  _isIdentified: vi.fn(() => false),
  get_distinct_id: vi.fn(() => 'anon-1'),
}));
vi.mock('posthog-js', () => ({ default: posthog }));

async function load(key: string, host: string) {
  vi.resetModules();
  vi.stubEnv('NEXT_PUBLIC_POSTHOG_KEY', key);
  vi.stubEnv('NEXT_PUBLIC_POSTHOG_HOST', host);
  const client = await import('./client');
  const { useAuthStore } = await import('@/stores/auth');
  return { client, useAuthStore };
}

const PAIR = { accessToken: 'a-1', refreshToken: 'r-1' };

beforeEach(() => {
  vi.clearAllMocks();
  posthog._isIdentified.mockReturnValue(false);
  posthog.get_distinct_id.mockReturnValue('anon-1');
  localStorage.clear();
  // 테스트마다 resetModules로 새 스토어를 만든다. 탭 간 채널을 두면 앞 테스트의 스토어들이 「login」을 받아
  // 같은 목에 reset을 더 부른다 — 이 파일은 한 탭 안의 규칙만 본다
  vi.stubGlobal('BroadcastChannel', undefined);
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('initAnalytics — 키가 없으면 꺼진다(POK-356)', () => {
  it.each([
    ['', ''],
    ['phc_test', ''],
    ['', 'https://us.i.posthog.com'],
  ])('키 %j·주소 %j 면 SDK를 부르지 않고, 식별도 버린다', async (key, host) => {
    const { client } = await load(key, host);
    expect(client.analyticsConfigured()).toBe(false);
    client.initAnalytics();
    client.identifyUser(7);
    await client.whenAnalyticsLoaded();
    expect(posthog.init).not.toHaveBeenCalled();
    expect(posthog.identify).not.toHaveBeenCalled();
  });

  it('키·주소가 있으면 한 번만 초기화한다 — 자동 수집·녹화는 끄고, 추적 금지를 존중하고, 주소를 정제한다', async () => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    client.initAnalytics();
    await client.whenAnalyticsLoaded();
    expect(posthog.init).toHaveBeenCalledTimes(1);
    const [key, config] = posthog.init.mock.calls[0] ?? [];
    expect(key).toBe('phc_test');
    expect(config).toMatchObject({
      api_host: 'https://us.i.posthog.com',
      defaults: '2026-08-29',
      cross_subdomain_cookie: true,
      person_profiles: 'identified_only',
      respect_dnt: true,
      autocapture: false,
      disable_session_recording: true,
      // /flags 본문은 before_send를 거치지 않고 첫 방문 주소를 쿼리째 싣는다(실측) — 평가를 꺼 요청을 없앤다
      advanced_disable_feature_flags: true,
    });
    // 원격 설정까지 막는 옵션은 쓰지 않는다 — 녹화가 원격 설정을 요구한다
    expect(config).not.toHaveProperty('advanced_disable_flags');
    expect(typeof config.before_send).toBe('function');
  });

  it('SDK가 붙기 전에 들어온 식별은 대기열에 두었다가 붙자마자 보낸다', async () => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    client.identifyUser(7);
    expect(posthog.identify).not.toHaveBeenCalled();
    await client.whenAnalyticsLoaded();
    expect(posthog.identify).toHaveBeenCalledWith('7');
  });
});

describe('identifyUser — 회원 번호로만 식별한다', () => {
  it('익명이면 그대로 identify한다 — 랜딩에서 온 익명 기록이 그 회원에 이어진다', async () => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    await client.whenAnalyticsLoaded();
    client.identifyUser(7);
    expect(posthog.reset).not.toHaveBeenCalled();
    expect(posthog.identify).toHaveBeenCalledWith('7');
  });

  it('다른 회원으로 식별된 흔적이 있으면 지우고 식별한다(공용 PC A → B)', async () => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    await client.whenAnalyticsLoaded();
    posthog._isIdentified.mockReturnValue(true);
    posthog.get_distinct_id.mockReturnValue('3');
    client.identifyUser(7);
    expect(posthog.reset).toHaveBeenCalledTimes(1);
    const [resetAt] = posthog.reset.mock.invocationCallOrder;
    const [identifyAt] = posthog.identify.mock.invocationCallOrder;
    expect(resetAt).toBeLessThan(identifyAt ?? 0);
    expect(posthog.identify).toHaveBeenCalledWith('7');
  });

  it('같은 회원이면 지우지 않는다(새로고침)', async () => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    await client.whenAnalyticsLoaded();
    posthog._isIdentified.mockReturnValue(true);
    posthog.get_distinct_id.mockReturnValue('7');
    client.identifyUser(7);
    expect(posthog.reset).not.toHaveBeenCalled();
  });

  it('SDK가 던져도 화면으로 새지 않는다', async () => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    await client.whenAnalyticsLoaded();
    posthog.identify.mockImplementationOnce(() => {
      throw new Error('boom');
    });
    expect(() => client.identifyUser(7)).not.toThrow();
  });
});

describe('auth 스토어 구독 — 로그아웃·새 로그인에서 초기화한다', () => {
  async function ready() {
    const loaded = await load('phc_test', 'https://us.i.posthog.com');
    loaded.client.initAnalytics();
    await loaded.client.whenAnalyticsLoaded();
    return loaded;
  }

  it('로그아웃(토큰 → null)이면 reset — 명시·401 자동·탈퇴·다른 탭 로그아웃이 모두 이 길이다', async () => {
    const { useAuthStore } = await ready();
    useAuthStore.getState().setTokens(PAIR);
    posthog.reset.mockClear();
    useAuthStore.getState().clearTokens();
    expect(posthog.reset).toHaveBeenCalledTimes(1);
  });

  it('같은 세션의 회전에서는 지우지 않는다', async () => {
    const { useAuthStore } = await ready();
    useAuthStore.getState().setTokens(PAIR);
    posthog._isIdentified.mockReturnValue(true);
    posthog.reset.mockClear();
    useAuthStore.getState().rotateTokens({ accessToken: 'a-2', refreshToken: 'r-2' });
    expect(posthog.reset).not.toHaveBeenCalled();
  });

  it('새 로그인은 익명이면 지우지 않고(랜딩 → 로그인 흐름 유지), 앞사람으로 식별돼 있으면 지운다', async () => {
    const { useAuthStore } = await ready();
    useAuthStore.getState().setTokens(PAIR);
    expect(posthog.reset).not.toHaveBeenCalled();

    useAuthStore.setState({ accessToken: null, refreshToken: null, rotatedFrom: null });
    posthog.reset.mockClear();
    posthog._isIdentified.mockReturnValue(true);
    useAuthStore.getState().setTokens({ accessToken: 'b-1', refreshToken: 'rb-1' });
    expect(posthog.reset).toHaveBeenCalledTimes(1);
  });

  it('새로고침의 hydrate(저장소 → 토큰)는 새 로그인이 아니다', async () => {
    localStorage.setItem('pc-auth', JSON.stringify({ v: 1, refreshToken: 'r-1' }));
    const { useAuthStore } = await ready();
    posthog._isIdentified.mockReturnValue(true);
    useAuthStore.getState().hydrate();
    expect(useAuthStore.getState().refreshToken).toBe('r-1');
    expect(posthog.reset).not.toHaveBeenCalled();
  });
});
