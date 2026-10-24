import { afterEach, describe, expect, it, vi } from 'vitest';
import { announceAnalyticsPreference, subscribeAnalyticsPreference } from './preferenceChannel';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('「이용 분석 참여」 탭 간 알림 (PR #382 리뷰)', () => {
  it('다른 탭이 알린 값을 받는다 — 모양이 틀린 쪽지는 버린다', async () => {
    const received = vi.fn();
    const stop = subscribeAnalyticsPreference(received);

    const otherTab = new BroadcastChannel('pc-analytics-preference');
    otherTab.postMessage({ optOut: 'yes', from: 'other-tab' });
    otherTab.postMessage({ optOut: true }); // 보낸 탭 표식이 없다
    otherTab.postMessage(null);
    otherTab.postMessage({ optOut: true, from: 'other-tab' });
    otherTab.close();

    await vi.waitFor(() => expect(received).toHaveBeenCalledTimes(1));
    expect(received).toHaveBeenCalledWith({ optOut: true });
    stop();
  });

  it('알린 값은 다른 탭에 가고, 같은 탭의 구독은 자기 쪽지를 받지 않는다 — 이미 건 값을 또 걸지 않게', async () => {
    const sameTab = vi.fn();
    const stop = subscribeAnalyticsPreference(sameTab);
    const heard: unknown[] = [];
    const otherTab = new BroadcastChannel('pc-analytics-preference');
    otherTab.onmessage = (event) => heard.push(event.data);

    announceAnalyticsPreference({ optOut: false });

    await vi.waitFor(() => expect(heard).toEqual([{ optOut: false, from: expect.any(String) }]));
    await new Promise((resolve) => setTimeout(resolve, 20)); // 같은 탭 구독에도 배달될 틈을 준다
    expect(sameTab).not.toHaveBeenCalled();
    otherTab.close();
    stop();
  });

  it('BroadcastChannel이 없는 환경에서는 조용히 아무것도 안 한다', () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const stop = subscribeAnalyticsPreference(vi.fn());
    expect(() => announceAnalyticsPreference({ optOut: false })).not.toThrow();
    expect(() => stop()).not.toThrow();
  });
});
