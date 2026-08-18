import { LIVE_WINDOW_SECONDS } from './playerMath';

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

/** 라이브 엣지 대비 시차(초) — 정수로 반올림해 같은 값이면 리렌더를 건너뛰게 한다 */
export function behindFromCurrentTime(range: DvrRange, currentTime: number): number {
  return Math.max(0, Math.round(range.end - currentTime));
}
