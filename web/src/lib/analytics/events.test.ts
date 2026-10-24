import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const posthog = vi.hoisted(() => ({
  init: vi.fn(),
  capture: vi.fn(),
  identify: vi.fn(),
  reset: vi.fn(),
  _isIdentified: vi.fn(() => false),
  get_distinct_id: vi.fn(() => 'anon-1'),
}));
vi.mock('posthog-js', () => ({ default: posthog }));

async function load(key: string) {
  vi.resetModules();
  vi.stubEnv('NEXT_PUBLIC_POSTHOG_KEY', key);
  vi.stubEnv('NEXT_PUBLIC_POSTHOG_HOST', key === '' ? '' : 'https://us.i.posthog.com');
  const client = await import('./client');
  const events = await import('./events');
  client.initAnalytics();
  await client.whenAnalyticsLoaded();
  return events;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('BroadcastChannel', undefined);
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('track — 행동 이벤트(POK-357)', () => {
  it('분석이 꺼져 있으면(키 없음) 아무 일도 하지 않는다', async () => {
    const { track } = await load('');
    track('login_completed', {});
    expect(posthog.capture).not.toHaveBeenCalled();
  });

  it('켜져 있으면 이름과 속성을 그대로 보낸다 — 호출한 쪽 객체를 SDK에 넘기지 않는다', async () => {
    const { track } = await load('phc_test');
    const properties = { platform: 'chzzk' } as const;
    track('platform_linked', properties);
    expect(posthog.capture).toHaveBeenCalledWith('platform_linked', { platform: 'chzzk' });
    expect(posthog.capture.mock.calls[0]?.[1]).not.toBe(properties);
  });

  it('속성은 정해진 열거형만 받는다 — 자유 문자열은 컴파일에서 막힌다', async () => {
    const { track } = await load('phc_test');
    // @ts-expect-error 정해진 플랫폼이 아니다
    track('platform_linked', { platform: 'twitch' });
    // @ts-expect-error 채널 이름 같은 자유 문자열 속성은 둘 자리가 없다
    track('platform_linked', { platform: 'chzzk', channelName: '게임하는너구리' });
    // @ts-expect-error 목록에 없는 이벤트
    track('signed_up', {});
    expect(posthog.capture).toHaveBeenCalledTimes(3); // 런타임은 막지 않는다 — 타입이 지킨다
  });

  it('유튜브 분류 번호는 목록 안이면 그대로, 밖이면 other로 좁힌다', async () => {
    const { toYoutubeCategoryId } = await load('');
    expect(toYoutubeCategoryId('20')).toBe('20');
    expect(toYoutubeCategoryId('99')).toBe('other');
  });
});
