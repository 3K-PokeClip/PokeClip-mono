// 방송 정보(플랫폼 · 제목 · 시청자 수)를 기다리는 시한(POK-449). 홈 라이브 띠와 라이브 대시보드가 같은 시한을 쓴다.
//
// 방송 시작 알림은 바로 오지만 방송 정보는 수집기가 방송을 찾아야 온다. 그 사이 빈 칸은 회색 막대로 비운다. 수집기는 1분마다
// 읽으므로 방송 시작에서 2분이 지나도 비어 있으면 그 사이에 막힌 것이다(방송 목록에서 채널을 못 찾음 · 수집기 꺼짐). 그 뒤로는
// 막대를 걷고 못 받았다고 적는다: 끝없이 번쩍이면 곧 온다고 읽힌다.

export const INFO_WAIT_MS = 2 * 60_000;

/** 방송 시작(epoch ms)에서 INFO_WAIT_MS가 지났다. 시작 시각을 모르면 아직 기다린다(false) */
export function infoWaitOver(startedAtMs: number | null, nowMs: number): boolean {
  return startedAtMs !== null && nowMs - startedAtMs >= INFO_WAIT_MS;
}
