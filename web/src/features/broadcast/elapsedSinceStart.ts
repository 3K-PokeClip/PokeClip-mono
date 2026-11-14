/**
 * 방송 안 시각 표기(카드 · 마킹 · 채팅 시점 · 편집기 출처 · 보관함 장면 위치)의 경과(ms). 0초는 이번 방송 시작이다(POK-562).
 *
 * 카드 · 재생 위치의 ms는 서버 시각 기준점(timelineOriginAt) 축인데, 그 기준점은 같은 송출 키로 한 앞 방송들의 길이만큼 시작보다
 * 앞설 수 있다(ADR-079 — prod 에서 181시간). 축 값을 그대로 시계로 쓰면 2분째 카드가 「181:01:00」이 된다. 카드 시각 자체(이동 ·
 * 편집 구간 · API 값)는 이 축 그대로이고, 사람에게 보이는 0초만 방송 시작으로 옮긴다.
 *
 * @param axisMs 기준점 축의 ms(카드 streamTimestampMs · 재생 위치)
 * @param originMs 그 축의 0초인 절대 시각(epoch ms). 모르면 null
 * @param startedAtMs 이번 방송 시작(epoch ms). 모르면 null
 * @returns 방송 시작부터의 ms. 시작보다 앞서면 0이다. 기준점이나 시작을 모르면 null이다: 다른 값으로 대신 재면 어긋난 값이 정확한
 *     값처럼 보인다(2026-10-31 사용자 결정)
 */
export function elapsedSinceStartMs(
  axisMs: number,
  originMs: number | null,
  startedAtMs: number | null,
): number | null {
  if (originMs === null || startedAtMs === null) return null;
  if (!Number.isFinite(originMs) || !Number.isFinite(startedAtMs)) return null;
  return Math.max(0, originMs + axisMs - startedAtMs);
}

/**
 * 기준점 축에서 이번 방송 시작이 놓인 자리(ms). 축 값에서 이것을 빼면 elapsedSinceStartMs 와 같은 경과다 — 눈금처럼 축 값을 많이
 * 그리는 자리가 한 번 셈해 두고 뺀다.
 *
 * @param originMs 그 축의 0초인 절대 시각(epoch ms). 모르면 null
 * @param startedAtMs 이번 방송 시작(epoch ms). 모르면 null
 * @returns 기준점이 시작보다 앞선 만큼(ms). 기준점이나 시작을 모르면 null이다
 */
export function startOnAxisMs(originMs: number | null, startedAtMs: number | null): number | null {
  if (originMs === null || startedAtMs === null) return null;
  if (!Number.isFinite(originMs) || !Number.isFinite(startedAtMs)) return null;
  return startedAtMs - originMs;
}
