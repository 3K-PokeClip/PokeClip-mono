// 디자인 1n 알림 설정의 항목 정의.
//
// 목록은 시안 1n의 8행과 IA v3.3 알림 종류 5개의 합집합이다 — 시안에 없던
// 방송 시작·승인 결과·결제 실패를 IA에서 끌어왔다. 두 문서가 갈린 자리라 어느 한쪽만
// 따르면 나머지 한쪽의 항목이 화면에서 사라진다.
//
// 화면에 그리는 순서가 곧 배열 순서다 — 시안의 행 순서를 그대로 든다.

interface BaseItem {
  key: string;
  title: string;
  desc: string;
  email: boolean;
}

/** 중요 알림 — 인앱은 항상 켜져 있어 끌 값 자체가 없다. */
export interface CriticalItem extends BaseItem {
  inapp?: never;
}

/** 일반 알림 — 인앱 기본값을 반드시 갖는다. */
export interface NormalItem extends BaseItem {
  inapp: boolean;
}

export type NotificationItem = CriticalItem | NormalItem;

/**
 * 중요 알림 — 인앱은 항상 켜져 있고 이메일만 켜고 끈다.
 * 결제 실패는 IA에만 있던 항목이다.
 */
export const CRITICAL_ITEMS = [
  {
    key: 'youtube-link-expired',
    title: '유튜브 연동 만료 · 업로드 실패',
    desc: '재인증이 필요할 때 즉시 알려요',
    email: true,
  },
  {
    key: 'vod-expiring',
    title: 'VOD 만료 임박 (D-7 · D-3 · D-1)',
    desc: '저장하지 않은 카드가 있는 VOD만',
    email: true,
  },
  {
    key: 'chat-collect-broken',
    title: '채팅 수집 끊김 · 플러그인 오류',
    desc: '방송 중 감지가 중단될 때',
    email: false,
  },
  {
    key: 'quota-threshold',
    title: '자동 처리 시간 임계값 (80% · 100%)',
    desc: '각 구간 도달 시 1회 · 방해 금지 중이면 방송 종료 후',
    email: true,
  },
  {
    key: 'billing-failed',
    title: '결제 실패',
    desc: '구독 결제가 처리되지 않았을 때',
    email: true,
  },
] as const satisfies readonly CriticalItem[];

/**
 * 일반 알림 — 인앱·이메일을 각각 켜고 끈다.
 * 방송 시작·승인 결과는 IA에만 있던 항목이다.
 */
export const NORMAL_ITEMS = [
  {
    key: 'broadcast-start',
    title: '방송 시작',
    desc: '연동한 채널에서 방송이 시작되면',
    inapp: true,
    email: false,
  },
  {
    key: 'approval-request',
    title: '승인 요청 도착',
    desc: '편집자가 업로드 승인을 요청할 때',
    inapp: true,
    email: false,
  },
  {
    key: 'approval-result',
    title: '승인 결과',
    desc: '요청한 업로드가 승인되거나 반려되면',
    inapp: true,
    email: false,
  },
  {
    key: 'vod-ready',
    title: 'VOD 준비 완료',
    desc: '방송 종료 후 다시보기가 준비되면',
    inapp: true,
    email: false,
  },
  {
    key: 'clip-done',
    title: '클립 렌더 · 업로드 완료',
    desc: '비동기 작업이 끝났을 때',
    inapp: true,
    email: false,
  },
  {
    key: 'weekly-digest',
    title: '주간 성과 요약',
    desc: '월요일 아침, 지난주 클립 성과',
    inapp: false,
    email: true,
  },
] as const satisfies readonly NormalItem[];

/** 이메일 열을 갖는 항목 키 — 중요·일반 전부 */
export type EmailKey =
  (typeof CRITICAL_ITEMS)[number]['key'] | (typeof NORMAL_ITEMS)[number]['key'];

/** 인앱 열을 갖는 항목 키 — 일반만. 중요의 인앱은 켜짐 고정이라 상태가 없다 */
export type InappKey = (typeof NORMAL_ITEMS)[number]['key'];

/** 「방송 중 방해 금지」 기본값 (디자인 1n은 켜진 상태로 그린다) */
export const DO_NOT_DISTURB_DEFAULT = true;
