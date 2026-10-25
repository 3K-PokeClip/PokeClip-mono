// 「이용 분석 참여」를 바꾼 탭이 같은 브라우저의 다른 탭에 알린다 (PR #382 리뷰).
// 선택 자체는 .pokeclip.com 쿠키 하나라 모든 탭이 같이 읽지만, SDK의 전송 대기열·녹화·식별은 탭마다 메모리에 있다 —
// 알리지 않으면 다른 탭은 꺼진 채 연 페이지에서 다시 켠 뒤에도 새로고침 전까지 보내지 않고, 설정 화면의 스위치도 옛 값이다.
// 받는 쪽: AuthGuard(이 탭의 SDK에 다시 건다)·설정 화면(스위치 표시).
// 🔴 쪽지는 「바뀌었으니 다시 읽어라」 신호뿐이고 값을 싣지 않는다 — 받는 쪽이 쿠키를 읽는다. 쪽지는 도착 순서가 바뀔 수
// 있어, 값을 실어 그대로 걸면 두 탭에서 거의 동시에 바꿀 때 늦게 온 옛 값이 마지막 선택을 덮었다(PR #382 3차 리뷰).

const CHANNEL_NAME = 'pc-analytics-preference';

/**
 * 이 탭의 표식 — 같은 탭의 다른 구독도 쪽지를 받으므로(BroadcastChannel은 보낸 객체만 뺀다) 자기 탭이 보낸 것은 버린다.
 * 기억하는 데만 쓰고 어디로도 보내지 않는다
 */
const TAB_ID = Math.random().toString(36).slice(2);

interface Envelope {
  changed: true;
  from: string;
}

function openChannel(): BroadcastChannel | null {
  // 서버 렌더와 BroadcastChannel이 없는 환경에서는 알리지 않는다 — 그 탭은 다음에 열 때 쿠키를 따른다
  if (typeof window === 'undefined' || typeof BroadcastChannel === 'undefined') return null;
  try {
    return new BroadcastChannel(CHANNEL_NAME);
  } catch {
    return null;
  }
}

function isFromOtherTab(data: unknown): data is Envelope {
  if (typeof data !== 'object' || data === null) return false;
  const { changed, from } = data as Record<string, unknown>;
  return changed === true && typeof from === 'string' && from !== TAB_ID;
}

/** 이 탭에서 바꿨다고 다른 탭에 알린다 */
export function announceAnalyticsPreference(): void {
  const channel = openChannel();
  if (channel === null) return;
  try {
    channel.postMessage({ changed: true, from: TAB_ID } satisfies Envelope);
  } finally {
    channel.close();
  }
}

/** 다른 탭이 바꿨다는 알림을 받는다 — 값은 쿠키에서 다시 읽는다. 돌려준 함수로 그만 듣는다 */
export function subscribeAnalyticsPreference(onChanged: () => void): () => void {
  const channel = openChannel();
  if (channel === null) return () => {};
  channel.onmessage = (event: MessageEvent) => {
    if (isFromOtherTab(event.data)) onChanged();
  };
  return () => channel.close();
}
