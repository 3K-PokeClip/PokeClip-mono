// 편집자 관리 화면의 날짜 라벨 (디자인 1l) — jsdom과 무관한 순수 함수라 분리한다
// (chzzkOAuth 선례). 프로젝트 공용 날짜 유틸이 아직 없어(src/lib 비어 있음) feature
// 로컬로 둔다 — 세 번째 화면이 같은 포맷을 원하면 그때 올린다.

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

const JOINED_FORMAT = new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric' });

/** 위임 생성(초대 수락) 시각 → 「8월 25일 합류」. 시안 1l의 행 보조설명 자리다. */
export function joinedLabel(grantedAt: string): string {
  return `${JOINED_FORMAT.format(new Date(grantedAt))} 합류`;
}

/**
 * 초대 만료까지 남은 기간 → 「N일 후 만료」, 하루 미만이면 시안 1l의 「18시간 후 만료」처럼
 * 「N시간 후 만료」. 일 단위는 round다 — 발송 직후(7일 - 몇 분)가 「7일」로, 25시간이
 * 「2일」이 아니라 「1일」로 읽히게. 시간 단위는 floor다 — 18.5시간을 「19시간」으로
 * 올리면 실제보다 여유 있게 말하게 된다.
 *
 * PENDING만 그리므로 expiresAt은 원칙상 미래지만, 조회 후 경과·시계 오차로 0 이하가 될 수
 * 있어 「곧 만료」로 클램프한다 (서버의 EXPIRED 판정은 조회 시점 계산이다).
 */
export function expiresLabel(expiresAt: string, now: Date = new Date()): string {
  const remaining = new Date(expiresAt).getTime() - now.getTime();
  if (remaining >= DAY_MS) return `${Math.round(remaining / DAY_MS)}일 후 만료`;
  const hours = Math.floor(remaining / HOUR_MS);
  if (hours >= 1) return `${hours}시간 후 만료`;
  return '곧 만료';
}
