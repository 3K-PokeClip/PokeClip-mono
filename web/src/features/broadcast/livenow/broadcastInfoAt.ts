import type { BroadcastTimeline } from '@/api/clipEditor';

// 보는 시점의 방송 제목 · 카테고리 · 태그(POK-462). 라이브를 되감거나 지난 방송에서 시점을 옮기면 그 시점 값을 그린다. 보는 시각은
// 카테고리 사진과 같은 focusAtMs(categoryThumb)이고, 실시간이면(null) 화면은 지금처럼 최신 관측(통로 broadcast-info)을 그린다.

export type TimelineSegment = BroadcastTimeline['segments'][number];

/**
 * 그 시각의 구간. 같은 플랫폼에서 from ≤ t < until(until이 없으면 지금도)인 줄이다. 대표 플랫폼에 그 시각 구간이 없으면(그 플랫폼을
 * 늦게 켰다) 그 시각의 다른 플랫폼 구간을, 플랫폼을 모르면 아무 플랫폼 구간을 쓴다. 첫 관측 전(방송 시작 직후 1분 안)이면 가장 이른
 * 구간이다: 화면이 대신 최신 관측으로 떨어지면 나중에 바꾼 제목이 방송 시작 자리에 뜬다. 구간이 하나도 없거나, 목록이 잘렸으면
 * (truncated, 오래된 쪽을 버림) 첫 구간 앞은 null이다: 그때 가장 이른 구간은 첫 관측이 아니라 잘린 자리다.
 * 카테고리 사진(categoryAt)은 일부러 더 좁다: 그 플랫폼 구간이 아니면 모른다(null)로 두고 프로필 사진을 세운다(POK-467). 글자는 빈칸보다
 * 가까운 값이 낫고, 사진은 다른 플랫폼 것을 붙이면 엉뚱한 포스터가 뜬다. 한쪽만 맞추지 않는다
 */
export function segmentAt(
  segments: readonly TimelineSegment[],
  platform: string | null,
  atMs: number,
  truncated = false,
): TimelineSegment | null {
  const covering = segments.filter(
    (s) => Date.parse(s.from) <= atMs && (s.until === null || atMs < Date.parse(s.until)),
  );
  const at = covering.find((s) => s.platform === platform) ?? covering[0];
  if (at) return at;
  if (truncated) return null;
  const earliest = [...segments].sort((a, b) => Date.parse(a.from) - Date.parse(b.from));
  return atMs < Date.parse(earliest[0]?.from ?? '')
    ? (earliest.find((s) => s.platform === platform) ?? earliest[0] ?? null)
    : null;
}

/** 보는 시각의 구간. 실시간(시각 null)이거나 기록이 없으면 null이고, 그때 화면은 최신 관측을 그린다 */
export function viewedSegment(
  timeline: BroadcastTimeline | undefined,
  platform: string | null,
  atMs: number | null,
): TimelineSegment | null {
  return atMs === null || !timeline
    ? null
    : segmentAt(timeline.segments, platform, atMs, timeline.truncated);
}
