// 플레이어 카드 이동의 시각 규칙(POK-196) — clip 이 카드 · 마크 응답에 실어 준 플레이어용 PDT(playerAtMs, 목록 PDT 축 epoch ms)가
// 있으면 그 시각으로, 없으면(장면 없음 null · 칸을 모르는 옛 clip) 지금 식(기준점 + 카드 초)으로 옮긴다. 웹은 값을 고치지 않는다:
// 틈 안 카드도 clip 이 정해 실은 자리(다음 회차 귀속 행 시작) 그대로다
import { readPlayerAtMs } from '@/api/cardPositions';

/**
 * 카드로 옮길 절대 시각(epoch ms). 플레이어용 PDT 가 있으면 그 값, 없으면 기준점 + 카드 초다
 * @returns 기준점도 플레이어용 PDT 도 모르면 null — 시각으로는 옮기지 못한다
 */
export function cardSeekAtMs(
  atMs: number | null | undefined,
  cardBase: number | null,
  seconds: number,
): number | null {
  if (typeof atMs === 'number') return atMs;
  return cardBase === null ? null : cardBase + seconds * 1000;
}

/**
 * 다시보기가 서면 그 자리부터 틀 받아 둔 초(다시보기 축 0초부터). 받아 둔 절대 시각(atMs)이 있으면 다시보기 축(timeBaseMs)이 선
 * 뒤에만 그 축의 초로 바꾼다 — 서기 전에는 null 을 넘겨 재생기가 기다린다(받아 두는 동안의 기준점은 다시보기 축과 다를 수 있다).
 * 받아 둔 절대 시각이 없으면 받아 둔 초 그대로다
 */
export function pendingStartSeconds(
  atMs: number | null,
  seconds: number | null,
  timeBaseMs: number | null,
): number | null {
  if (seconds === null || atMs === null) return seconds;
  return timeBaseMs === null ? null : (atMs - timeBaseMs) / 1000;
}

/**
 * 같은 카드의 새 판이 플레이어용 PDT 칸 없이 왔으면(통로 · 마크 답) 앞 판의 값을 지킨다. null 은 장면이 없다는 서버 답이라 받는다
 */
export function keepKnownPlayerAtMs<T extends { playerAtMs?: unknown }>(
  prev: T | undefined,
  next: T,
): T {
  if (prev === undefined || readPlayerAtMs(next) !== undefined) return next;
  if (readPlayerAtMs(prev) === undefined) return next;
  return { ...next, playerAtMs: prev.playerAtMs };
}
