import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const posthog = vi.hoisted(() => ({
  init: vi.fn(),
  identify: vi.fn(),
  reset: vi.fn(),
  capture: vi.fn(),
  _isIdentified: vi.fn(() => false),
  get_distinct_id: vi.fn(() => 'anon-1'),
  get_explicit_consent_status: vi.fn((): 'granted' | 'denied' | 'pending' => 'pending'),
  opt_out_capturing: vi.fn(),
  opt_in_capturing: vi.fn(),
  stopSessionRecording: vi.fn(),
  register: vi.fn(),
}));
vi.mock('posthog-js', () => ({ default: posthog }));

/** beforeEach가 지우기 전의 진짜 BroadcastChannel — 탭 간 알림을 보는 테스트만 되살린다 */
const RealBroadcastChannel = globalThis.BroadcastChannel;

async function load(key: string, host: string, replay = '') {
  vi.resetModules();
  vi.stubEnv('NEXT_PUBLIC_POSTHOG_KEY', key);
  vi.stubEnv('NEXT_PUBLIC_POSTHOG_HOST', host);
  vi.stubEnv('NEXT_PUBLIC_POSTHOG_REPLAY', replay);
  const client = await import('./client');
  const { useAuthStore } = await import('@/stores/auth');
  return { client, useAuthStore };
}

const PAIR = { accessToken: 'a-1', refreshToken: 'r-1' };

/** SDK가 이 브라우저에 저장한 「이용 분석 참여」 선택(`__ph_opt_in_out_<키>`, 값은 JSON을 주소 인코딩) — 끔 0·켬 1 */
const CONSENT_COOKIE = '__ph_opt_in_out_phc_test';
function storeConsent(value: 0 | 1 | null) {
  document.cookie =
    value === null
      ? `${CONSENT_COOKIE}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
      : `${CONSENT_COOKIE}=${encodeURIComponent(JSON.stringify(value))}; path=/`;
}

beforeEach(() => {
  vi.clearAllMocks();
  posthog._isIdentified.mockReturnValue(false);
  posthog.get_distinct_id.mockReturnValue('anon-1');
  posthog.get_explicit_consent_status.mockReturnValue('pending');
  storeConsent(null);
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

  it.each([
    ['', true],
    ['true', true],
    ['on', false],
  ])(
    '녹화는 NEXT_PUBLIC_POSTHOG_REPLAY=%j 일 때 끔=%s — 정확히 on일 때만 켠다(POK-358)',
    async (replay, off) => {
      const { client } = await load('phc_test', 'https://us.i.posthog.com', replay);
      client.initAnalytics();
      await client.whenAnalyticsLoaded();
      const [, config] = posthog.init.mock.calls[0] ?? [];
      expect(config.disable_session_recording).toBe(off);
    },
  );

  it('녹화 설정은 켜든 끄든 늘 가리기를 싣는다 — 글자·입력 전부, 이미지·영상 차단, 헤더·본문 미기록', async () => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com', 'on');
    client.initAnalytics();
    await client.whenAnalyticsLoaded();
    const [, config] = posthog.init.mock.calls[0] ?? [];
    expect(config.session_recording).toMatchObject({
      maskAllInputs: true,
      maskTextSelector: '*',
      blockSelector: expect.stringContaining('img'),
      recordHeaders: false,
      recordBody: false,
      streamNetworkBody: false,
    });
    expect(config.session_recording.blockSelector).toContain('video');
    // 콘솔 로그는 원격 설정(새 프로젝트는 켜짐)을 따르지 않고 늘 끈다
    expect(config.enable_recording_console_log).toBe(false);
    expect(config.session_recording.maskAttributeFn('href', '/x?stream=S-1')).toBe('/x');
    expect(
      config.session_recording.maskCapturedNetworkRequestFn({ name: '/auth/callback?code=c' }),
    ).toEqual({ name: '/auth/callback' });
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

describe('「이용 분석 참여」 — 이 브라우저에만 저장한다(처리방침 7·9절)', () => {
  async function ready() {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    await client.whenAnalyticsLoaded();
    return client;
  }

  it('꺼 둔 브라우저에서는 식별하지 않는다 — $identify가 새지 않는다', async () => {
    const client = await ready();
    posthog.get_explicit_consent_status.mockReturnValue('denied');
    client.identifyUser(7);
    expect(posthog.identify).not.toHaveBeenCalled();
    expect(posthog.opt_in_capturing).not.toHaveBeenCalled(); // 로그인은 이 브라우저의 선택을 바꾸지 않는다
  });

  it('공용 PC에서 앞사람 흔적을 지워도 꺼 둔 선택은 남는다 — reset이 동의 선택까지 지우기 때문이다', async () => {
    const client = await ready();
    posthog._isIdentified.mockReturnValue(true);
    posthog.get_distinct_id.mockReturnValue('3');
    posthog.get_explicit_consent_status.mockReturnValue('denied');
    storeConsent(0);
    client.identifyUser(7);
    const [resetAt] = posthog.reset.mock.invocationCallOrder;
    const [optOutAt] = posthog.opt_out_capturing.mock.invocationCallOrder;
    expect(resetAt).toBeLessThan(optOutAt ?? 0);
    expect(posthog.identify).not.toHaveBeenCalled();
  });

  it('끄면 이벤트·녹화를 멈춘다', async () => {
    const client = await ready();
    client.setAnalyticsOptOut(true);
    expect(posthog.opt_out_capturing).toHaveBeenCalledTimes(1);
    expect(posthog.stopSessionRecording).toHaveBeenCalledTimes(1);
  });

  it('다시 켜면 opt_in으로 전송을 되살린 뒤 로그인한 회원을 식별한다 — $opt_in 이벤트는 보내지 않는다', async () => {
    const client = await ready();
    posthog.get_explicit_consent_status.mockReturnValue('denied');
    client.identifyUser(7);
    client.setAnalyticsOptOut(false);
    // clear(선택만 지우기)는 SDK 전송 대기열을 다시 시작하지 않는다 — 꺼진 채 연 페이지에서 아무것도 안 나갔다(실측)
    expect(posthog.opt_in_capturing.mock.calls).toEqual([[{ captureEventName: false }]]);
    const [optInAt] = posthog.opt_in_capturing.mock.invocationCallOrder;
    const [identifyAt] = posthog.identify.mock.invocationCallOrder;
    expect(optInAt).toBeLessThan(identifyAt ?? 0);
    expect(posthog.identify.mock.calls).toEqual([['7']]);
  });

  it('로그아웃한 뒤 켜면 식별하지 않는다 — 앞사람 번호로 다시 묶지 않게', async () => {
    const client = await ready();
    client.identifyUser(7);
    client.resetIdentity();
    posthog.identify.mockClear();
    client.setAnalyticsOptOut(false);
    expect(posthog.opt_in_capturing).toHaveBeenCalledTimes(1);
    expect(posthog.identify).not.toHaveBeenCalled();
  });

  it('바꾸면 같은 브라우저의 다른 탭에 알린다 — 다른 탭의 SDK도 따라 건다', async () => {
    vi.stubGlobal('BroadcastChannel', RealBroadcastChannel);
    const client = await ready();
    const heard: unknown[] = [];
    const otherTab = new RealBroadcastChannel('pc-analytics-preference');
    otherTab.onmessage = (event) => heard.push(event.data);
    client.setAnalyticsOptOut(true);
    await vi.waitFor(() => expect(heard).toEqual([{ optOut: true, from: expect.any(String) }]));
    otherTab.close();
  });

  it('선택의 기억은 .pokeclip.com 쿠키에 둔다 — 같은 브라우저의 랜딩도 따른다', async () => {
    await ready();
    const [, config] = posthog.init.mock.calls[0] ?? [];
    expect(config.opt_out_capturing_persistence_type).toBe('cookie');
    expect(config.cross_subdomain_cookie).toBe(true);
  });
});

describe('readAnalyticsChoice — 설정 화면이 이 브라우저의 상태를 읽는다', () => {
  it('키가 없으면(SDK를 안 부름) unavailable', async () => {
    const { client } = await load('', '');
    client.initAnalytics();
    expect(await client.readAnalyticsChoice()).toBe('unavailable');
  });

  it.each([
    ['pending', 'participating'],
    ['granted', 'participating'],
    ['denied', 'opted_out'],
  ] as const)('SDK의 선택이 %s면 %s', async (status, expected) => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    posthog.get_explicit_consent_status.mockReturnValue(status);
    expect(await client.readAnalyticsChoice()).toBe(expected);
  });

  it.each([
    ['navigator.doNotTrack', () => vi.stubGlobal('navigator', { ...navigator, doNotTrack: '1' })],
    [
      'navigator.globalPrivacyControl',
      () => vi.stubGlobal('navigator', { ...navigator, globalPrivacyControl: true }),
    ],
  ])(
    '추적 금지(%s)가 켜져 있으면 저장된 선택과 상관없이 dnt — SDK와 같은 판정',
    async (_, stub) => {
      const { client } = await load('phc_test', 'https://us.i.posthog.com');
      client.initAnalytics();
      stub();
      posthog.get_explicit_consent_status.mockReturnValue('denied'); // SDK는 추적 금지면 denied로 답한다
      expect(await client.readAnalyticsChoice()).toBe('dnt');
    },
  );
});

describe('setActiveRole — 지금 채널에서의 역할을 모든 이벤트에 싣는다', () => {
  it('열거 하나만 공통 속성으로 등록한다 — 상대 채널 번호는 싣지 않는다', async () => {
    const { client } = await load('phc_test', 'https://us.i.posthog.com');
    client.initAnalytics();
    await client.whenAnalyticsLoaded();
    client.setActiveRole('editor');
    expect(posthog.register).toHaveBeenCalledWith({ active_role: 'editor' });
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
    expect(posthog.opt_out_capturing).not.toHaveBeenCalled(); // 선택한 적 없으면 기본(참여)으로 둔다
  });

  it('「이용 분석 참여」를 끈 채 로그아웃하면 reset 뒤에 다시 끈다 — 이 브라우저의 선택이라 로그아웃을 넘어 남는다', async () => {
    const { useAuthStore } = await ready();
    useAuthStore.getState().setTokens(PAIR);
    posthog.get_explicit_consent_status.mockReturnValue('denied');
    storeConsent(0);
    useAuthStore.getState().clearTokens();
    const [resetAt] = posthog.reset.mock.invocationCallOrder.slice(-1);
    const [optOutAt] = posthog.opt_out_capturing.mock.invocationCallOrder.slice(-1);
    expect(optOutAt).toBeGreaterThan(resetAt ?? Infinity);
  });

  it.each([
    ['저장된 선택 없음', null],
    ['켬(1)', 1],
  ] as const)(
    '추적 금지만 켠 브라우저(%s)는 로그아웃해도 끔 쿠키를 쓰지 않는다 — SDK가 denied로 답해도 저장값만 본다 (PR #382 2차 리뷰)',
    async (_, stored) => {
      const { useAuthStore } = await ready();
      useAuthStore.getState().setTokens(PAIR);
      posthog.get_explicit_consent_status.mockReturnValue('denied'); // 추적 금지면 저장값과 상관없이 denied
      storeConsent(stored);
      useAuthStore.getState().clearTokens();
      expect(posthog.reset).toHaveBeenCalled();
      expect(posthog.opt_out_capturing).not.toHaveBeenCalled();
    },
  );

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
