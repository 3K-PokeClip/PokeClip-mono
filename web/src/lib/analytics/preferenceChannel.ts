// 「이용 분석 참여」를 바꾼 탭이 같은 브라우저의 다른 탭에 알린다 (PR #382 리뷰).
// 동의 선택은 .pokeclip.com 쿠키 하나를 모든 탭이 같이 쓰는데, 참여 값은 탭마다의 me 캐시(60초 신선)에서 온다 — 알리지 않으면
// 옛 캐시를 든 다른 탭이 화면을 옮길 때(AuthGuard 다시 마운트) 그 값으로 identifyUser를 불러 꺼 둔 선택을 다시 켠다.
// 받는 쪽(AuthGuard)은 me 캐시만 고친다. 끄고 켜는 일은 그 캐시를 보는 AuthGuard → identifyUser가 그대로 한다.

const CHANNEL_NAME = 'pc-analytics-preference';

export interface AnalyticsPreferenceMessage {
  userId: number;
  optOut: boolean;
}

function openChannel(): BroadcastChannel | null {
  // 서버 렌더와 BroadcastChannel이 없는 환경에서는 알리지 않는다 — 그 탭은 다음 me 조회로 따라온다
  if (typeof window === 'undefined' || typeof BroadcastChannel === 'undefined') return null;
  try {
    return new BroadcastChannel(CHANNEL_NAME);
  } catch {
    return null;
  }
}

function isMessage(data: unknown): data is AnalyticsPreferenceMessage {
  if (typeof data !== 'object' || data === null) return false;
  const { userId, optOut } = data as Record<string, unknown>;
  return typeof userId === 'number' && typeof optOut === 'boolean';
}

/** 저장에 성공한 새 값을 다른 탭에 알린다 */
export function announceAnalyticsPreference(message: AnalyticsPreferenceMessage): void {
  const channel = openChannel();
  if (channel === null) return;
  try {
    channel.postMessage(message);
  } finally {
    channel.close();
  }
}

/** 다른 탭이 알린 새 값을 받는다. 돌려준 함수로 그만 듣는다 */
export function subscribeAnalyticsPreference(
  onMessage: (message: AnalyticsPreferenceMessage) => void,
): () => void {
  const channel = openChannel();
  if (channel === null) return () => {};
  channel.onmessage = (event: MessageEvent) => {
    if (isMessage(event.data)) onMessage(event.data);
  };
  return () => channel.close();
}
