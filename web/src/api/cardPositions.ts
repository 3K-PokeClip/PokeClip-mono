// 카드 · 마크 응답의 위치 칸(POK-196) — clip 이 장면 함수로 정해 실어 준 값을 읽는다. 칸 이름은 이 파일 한 곳에만 둔다.
// 인자는 구조형이라 편집기 JumpCard 와 라이브 카드 스냅숏을 함께 받는다.
//   playerAtMs  카드 시점의 플레이어용 PDT(epoch ms). 장면이 없으면 null 로 실린다(칸 생략이 아니다)

/**
 * 카드 시점의 플레이어용 PDT. 유한 수면 그 값, null(장면 없음)이면 null, 칸이 없으면 undefined 다.
 * null 과 undefined 는 뜻이 달라 접지 않는다 — 칸 없음은 이 칸을 모르는 옛 clip 이다. 숫자가 아닌 값(문자열 · NaN ·
 * Infinity 등)은 믿을 수 없어 칸 없음으로 친다.
 */
export function readPlayerAtMs(card: { playerAtMs?: unknown }): number | null | undefined {
  const value = card.playerAtMs;
  if (value === null) return null;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  return undefined;
}
