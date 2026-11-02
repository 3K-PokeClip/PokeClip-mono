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
  /** 후원 종류(채팅이면 null). 라벨은 {@link donationLabel}로 고른다. 옛 서버 · 시험 줄은 빠져 올 수 있다 */
  donationType?: string | null;
  /**
   * 그 줄이 들어온 플랫폼 코드(`CHZZK`·`SOOP`, POK-466). 한 방송을 두 플랫폼에 같이 내보내면 두 플랫폼 줄이 섞여 오므로 채팅 창이
   * 줄마다 보인다. 옛 서버 · 시험 줄은 빠져 올 수 있다
   */
  platform?: string | null;
}

/**
 * 후원 종류별 단위(POK-431). 수집기는 종류를 플랫폼 이름 그대로 싣고 금액은 그 단위의 개수다(원 환산 없음). 치지직(CHAT ·
 * VIDEO)과 SOOP(SDK action 이름)은 이름이 겹치지 않아 종류만으로 플랫폼을 가른다. 미션 후원의 단위는 실방송 확인 전이라
 * 「미션 후원」으로 둔다
 */
const DONATION_UNITS: Record<string, { unit: string; counter: string }> = {
  CHAT: { unit: '치즈', counter: '' },
  VIDEO: { unit: '치즈', counter: '' },
  BALLOON_GIFTED: { unit: '별풍선', counter: '개' },
  ADBALLOON_GIFTED: { unit: '애드벌룬', counter: '개' },
  VIDEOBALLOON_GIFTED: { unit: '영상풍선', counter: '개' },
  STICKER_GIFTED: { unit: '스티커', counter: '개' },
  BATTLE_MISSION_GIFTED: { unit: '미션 후원', counter: '개' },
  CHALLENGE_MISSION_GIFTED: { unit: '미션 후원', counter: '개' },
};

/**
 * 후원 줄에 보일 금액(「치즈 5,000」 · 「별풍선 1,000개」). 종류가 비면 치지직이다(SOOP 이전 줄은 치지직뿐이었다). 모르는 종류는
 * 단위를 지어내지 않고 「후원 N」이다. 개수를 모르면(null) 숫자 없이 단위만 보인다: 0이라고 하지 않는다
 */
export function donationLabel(
  donationType: string | null | undefined,
  amount: number | null,
): string {
  // 객체 기본 속성 이름(constructor 등)이 단위로 잡히지 않게 자기 칸만 본다
  const known = !donationType
    ? DONATION_UNITS.CHAT
    : Object.hasOwn(DONATION_UNITS, donationType)
      ? DONATION_UNITS[donationType]
      : undefined;
  const { unit, counter } = known ?? { unit: '후원', counter: '' };
  return amount === null ? unit : `${unit} ${amount.toLocaleString()}${counter}`;
}
/**
 * 라이브에서 다 읽은 끝을 이만큼 겹쳐 다시 읽는다. 적재가 여유보다 늦게 들어온 줄을 다음 읽기에서 잡는다. 라이브 폴링과
 * 라이브 되감기 로더가 같이 쓴다(POK-254). 겹친 줄은 폴링이 지문(chatFingerprint, 중계 줄은 서버 번호가 없다)으로, 되감기 로더가
 * 표와 서버 번호로 거른다
 */
export const LIVE_REREAD_MS = 10_000;

/**
 * 겹친 구간 중복 제거 열쇠 — 채팅 표 지문 UNIQUE와 같다(README 「웹이 지켜야 하는 것 넷」 3). 표 지문에 플랫폼이 들어 있으므로
 * 여기도 넣는다(POK-466): 두 플랫폼에서 같은 글이 같은 시각에 오면 둘 다 보인다
 */
export function chatFingerprint(it: {
  kind: string;
  time: string;
  senderChannelId: string;
  text: string | null;
  platform?: string | null;
}): string {
  return `${it.kind}|${it.time}|${it.senderChannelId}|${it.text ?? ''}|${it.platform ?? ''}`;
}

/** chat-messages 한 쪽 */
export interface WirePage {
  items: WireItem[];
  nextCursor: string | null;
  appliedOffsetMs: number;
}
