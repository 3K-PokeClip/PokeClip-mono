import { LIVE_EDGE_BACKOFF_SECONDS, LIVE_WINDOW_SECONDS, isAtEdge } from './playerMath';

// video.seekable 기반 DVR 창 계산 — infra/dev-media/player.html의 dvrRange 이식.
// jsdom에 미디어 구현이 없어 TimeRanges 대신 구조 타입을 받아 plain object로
// 단위 테스트한다 (playerMath.ts와 같은 분리 이유).

/** TimeRanges 호환 구조 타입 — 실코드는 video.seekable을 그대로 넘긴다 */
export interface SeekableLike {
  length: number;
  start(index: number): number;
  end(index: number): number;
}

export interface DvrRange {
  start: number;
  end: number;
}

/**
 * 시크 가능 범위 — seekable 기준, 창은 1시간으로 자른다 (계약3 4절 4번).
 * 아직 seekable이 비어 있으면(매니페스트 로드 전) null.
 */
export function dvrRange(
  seekable: SeekableLike,
  windowSeconds: number = LIVE_WINDOW_SECONDS,
): DvrRange | null {
  if (seekable.length === 0) return null;
  const end = seekable.end(seekable.length - 1);
  const start = Math.max(seekable.start(0), end - windowSeconds);
  return end > start ? { start, end } : null;
}

/**
 * 이 플레이어가 도달 가능한 가장 라이브한 지점 — 시크·시차 계산의 공통 기준점.
 *
 * 실재생은 range.end에 앉지 않는다. hls.js는 liveSyncPosition(LL-HLS는 PART-HOLD-BACK,
 * 아니면 liveSyncDurationCount x targetduration, 스톨마다 추가 지연)만큼 뒤에 앉으며
 * 그 지점이 이 플레이어의 "라이브"다 — VOD 플레이리스트에도 값이 잡힌다.
 * range.end를 기준으로 시차를 재면 그 지연분이 그대로 시차로 남아 "실시간" 표기가
 * 영영 안 뜬다 (POK-31 리뷰).
 *
 * 값이 없으면(Safari 네이티브·MSE 미부착) 상수만큼 물러난다 — 정확히 range.end에
 * 붙이면 부분 세그먼트를 기다리며 멎기 때문이다.
 */
export function liveEdgePosition(range: DvrRange, syncPosition?: number | null): number {
  const pos =
    typeof syncPosition === 'number' && Number.isFinite(syncPosition)
      ? syncPosition
      : range.end - LIVE_EDGE_BACKOFF_SECONDS;
  return Math.min(range.end, Math.max(range.start, pos));
}

/**
 * 라이브 엣지 대비 시차(초) — 기준은 range.end가 아니라 liveEdgePosition이다.
 * 정수로 반올림해 같은 값이면 리렌더를 건너뛰고, 임계값 미만은 0으로 스냅한다
 * (behindFromSeekFraction과 같은 규칙) — seekable.end가 파트 단위로 튀는 톱니에서
 * 반올림 때문에 엣지 판정이 깜빡이지 않게 한다.
 */
export function behindFromCurrentTime(
  range: DvrRange,
  currentTime: number,
  syncPosition?: number | null,
): number {
  const behind = Math.max(0, Math.round(liveEdgePosition(range, syncPosition) - currentTime));
  return isAtEdge(behind) ? 0 : behind;
}
