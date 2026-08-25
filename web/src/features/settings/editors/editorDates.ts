// 편집자 관리 화면의 날짜 라벨 (디자인 1l) — jsdom과 무관한 순수 함수라 분리한다
// (chzzkOAuth 선례). 프로젝트 공용 날짜 유틸이 아직 없어(src/lib 비어 있음) feature
// 로컬로 둔다 — 세 번째 화면이 같은 포맷을 원하면 그때 올린다.

const DAY_MS = 24 * 60 * 60 * 1000;

const JOINED_FORMAT = new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric' });

/** 위임 생성(초대 수락) 시각 → 「8월 25일 합류」. 시안 1l의 행 보조설명 자리다. */
export function joinedLabel(grantedAt: string): string {
  return `${JOINED_FORMAT.format(new Date(grantedAt))} 합류`;
}

/**
 * 초대 만료까지 남은 기간 → 「N일 후 만료」. 유효기간이 7일(ADR-039)이라 일 단위 ceil이면
 * 충분하다 — 시안의 「18시간 후 만료」 같은 시간 단위는 만들지 않는다.
 *
 * PENDING만 그리므로 expiresAt은 원칙상 미래지만, 조회 후 경과·시계 오차로 음수가 될 수
 * 있어 「오늘 만료」로 클램프한다 (서버의 EXPIRED 판정은 조회 시점 계산이다).
 */
export function expiresLabel(expiresAt: string, now: Date = new Date()): string {
  const remaining = new Date(expiresAt).getTime() - now.getTime();
  const days = Math.ceil(remaining / DAY_MS);
  if (days < 1) return '오늘 만료';
  return `${days}일 후 만료`;
}
