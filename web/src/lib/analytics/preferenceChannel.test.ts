import { afterEach, describe, expect, it, vi } from 'vitest';
import { announceAnalyticsPreference, subscribeAnalyticsPreference } from './preferenceChannel';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('「이용 분석 참여」 탭 간 알림 (PR #382 리뷰)', () => {
  it('알린 값을 다른 구독이 받는다 — 모양이 틀린 쪽지는 버린다', async () => {
    const received = vi.fn();
    const stop = subscribeAnalyticsPreference(received);

    const stray = new BroadcastChannel('pc-analytics-preference');
    stray.postMessage({ userId: '1', optOut: 'yes' });
    stray.postMessage(null);
    stray.close();
    announceAnalyticsPreference({ userId: 7, optOut: true });

    await vi.waitFor(() => expect(received).toHaveBeenCalledTimes(1));
    expect(received).toHaveBeenCalledWith({ userId: 7, optOut: true });
    stop();
  });

  it('BroadcastChannel이 없는 환경에서는 조용히 아무것도 안 한다', () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const stop = subscribeAnalyticsPreference(vi.fn());
    expect(() => announceAnalyticsPreference({ userId: 7, optOut: false })).not.toThrow();
    expect(() => stop()).not.toThrow();
  });
});
