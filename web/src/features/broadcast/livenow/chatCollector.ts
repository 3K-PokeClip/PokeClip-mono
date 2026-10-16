// 수집기 채팅 창구(clip을 거친 chat-messages)의 줄 형식과 한도. 줄 형식은 라이브 채팅 패널과 지난 방송 다시 보기(chatReplay.ts)가
// 같이 쓰고, 창 길이는 차트 창(useLiveMockState)도 쓴다. 쪽 크기는 다시 보기만 쓴다(라이브 폴링은 200줄씩 따로 묻는다). 다시 보기
// 로더가 훅 파일에서 형식을 가져오면 두 파일이 서로를 가리켰다(리뷰 1라운드). 받는 코드(apiFetch)는 두지 않는다: 순수한 로더가
// 상수 하나 때문에 인증 저장소까지 끌어왔다(리뷰 2라운드)

/** 수집기가 한 번에 받는 가장 긴 창: chat-collector pokeclip.query.window-max(넘으면 400 too_wide). 차트도 같다 */
export const COLLECTOR_WINDOW_MS = 60 * 60_000;
/** 한 쪽에 받는 줄 수 상한: chat-collector pokeclip.query.page-max(더 달라 하면 잘라 준다) */
export const CHAT_PAGE_MAX = 500;

/** chat-messages 한 줄 */
export interface WireItem {
  kind: 'chat' | 'donation';
  id: number;
  time: string;
  nickname: string | null;
  senderChannelId: string;
  text: string | null;
  amount: number | null;
}
/**
 * 라이브에서 다 읽은 끝을 이만큼 겹쳐 다시 읽는다. 적재가 여유보다 늦게 들어온 줄을 다음 읽기에서 잡는다. 겹친 줄은 지문(chatFingerprint)으로
 * 거른다. 라이브 폴링과 라이브 되감기 로더가 같이 쓴다(POK-254)
 */
export const LIVE_REREAD_MS = 10_000;

/** 겹친 구간 중복 제거 열쇠 — 채팅 표 지문 UNIQUE와 같다(README 「웹이 지켜야 하는 것 넷」 3) */
export function chatFingerprint(it: {
  kind: string;
  time: string;
  senderChannelId: string;
  text: string | null;
}): string {
  return `${it.kind}|${it.time}|${it.senderChannelId}|${it.text ?? ''}`;
}

/** chat-messages 한 쪽 */
export interface WirePage {
  items: WireItem[];
  nextCursor: string | null;
  appliedOffsetMs: number;
}
