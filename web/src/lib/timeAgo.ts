/**
 * 「얼마 전」 표기(만든 시각 기준). 방금 생긴 것은 초까지 세고, 오래된 것은 시간·일로 올린다.
 * 홈 「최근 하이라이트」와 방송 화면 카드가 이 함수 하나를 쓴다: 사본 둘이 갈라져 지난 방송 카드만 분에서 멈춰
 * 「4275분 전」이 됐다(QA 2026-10-11).
 */
export function timeAgo(createdAtIso: string, now: number): string {
  const diff = Math.max(0, Math.floor((now - Date.parse(createdAtIso)) / 1000));
  if (diff < 10) return '방금';
  if (diff < 60) return `${diff}초 전`;
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86_400) return `${Math.floor(diff / 3600)}시간 전`;
  return `${Math.floor(diff / 86_400)}일 전`;
}
