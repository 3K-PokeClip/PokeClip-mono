import { afterEach, describe, expect, it, vi } from 'vitest';
import { announceAnalyticsPreference, subscribeAnalyticsPreference } from './preferenceChannel';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('「이용 분석 참여」 탭 간 알림 (PR #382 리뷰)', () => {
  it('다른 탭의 「바뀌었다」 알림을 받는다 — 모양이 틀린 쪽지는 버린다', async () => {
    const received = vi.fn();
    const stop = subscribeAnalyticsPreference(received);

    const otherTab = new BroadcastChannel('pc-analytics-preference');
    otherTab.postMessage({ changed: 'yes', from: 'other-tab' });
    otherTab.postMessage({ changed: true }); // 보낸 탭 표식이 없다
    otherTab.postMessage(null);
    otherTab.postMessage({ changed: true, from: 'other-tab' });
    otherTab.close();

    await vi.waitFor(() => expect(received).toHaveBeenCalledTimes(1));
    stop();
  });

  it('알림에 값을 싣지 않는다 — 받는 쪽이 쿠키를 읽는다(늦게 온 쪽지가 마지막 선택을 덮지 않게, PR #382 3차 리뷰)', async () => {
    const heard: unknown[] = [];
    const otherTab = new BroadcastChannel('pc-analytics-preference');
    otherTab.onmessage = (event) => heard.push(event.data);

    announceAnalyticsPreference();

    await vi.waitFor(() => expect(heard).toEqual([{ changed: true, from: expect.any(String) }]));
    otherTab.close();
  });

  it('같은 탭의 구독은 자기 쪽지를 받지 않는다 — 이미 건 값을 또 걸지 않게', async () => {
    const sameTab = vi.fn();
    const stop = subscribeAnalyticsPreference(sameTab);
    const heard: unknown[] = [];
    const otherTab = new BroadcastChannel('pc-analytics-preference');
    otherTab.onmessage = (event) => heard.push(event.data);

    announceAnalyticsPreference();

    await vi.waitFor(() => expect(heard).toHaveLength(1));
    await new Promise((resolve) => setTimeout(resolve, 20)); // 같은 탭 구독에도 배달될 틈을 준다
    expect(sameTab).not.toHaveBeenCalled();
    otherTab.close();
    stop();
  });

  it('BroadcastChannel이 없는 환경에서는 조용히 아무것도 안 한다', () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const stop = subscribeAnalyticsPreference(vi.fn());
    expect(() => announceAnalyticsPreference()).not.toThrow();
    expect(() => stop()).not.toThrow();
  });
});
