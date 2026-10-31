import { LIVE_EDGE_BACKOFF_SECONDS, REWIND_UI_MAX_SECONDS } from './playerMath';

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
  windowSeconds: number = REWIND_UI_MAX_SECONDS,
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
 * syncPosition은 필수 인자다 — 옵셔널이면 호출부에서 빠뜨렸을 때 조용히 백오프로
 * 폴백해 위 버그가 되살아나고 테스트로는 잡히지 않는다. 값이 없으면 null을 명시한다.
 * null이면(Safari 네이티브·MSE 미부착) 상수만큼 물러난다 — 정확히 range.end에 붙이면
 * 부분 세그먼트를 기다리며 멎기 때문이다.
 *
 * 상단을 range.end로 자르는 게 안전한 것은 hls.js 기본값 liveDurationInfinity: false에
 * 기대고 있다. 그 값이 false면 MediaSource duration이 재생목록 edge로 잡혀 seekable.end가
 * 곧 edge이고, liveSyncPosition은 edge - (partTarget || targetduration)으로 캡되므로
 * 항상 seekable.end보다 뒤다. true로 바꾸면 seekable이 버퍼 기준이 되어 이 전제가 깨진다.
 */
export function liveEdgePosition(range: DvrRange, syncPosition: number | null): number {
  const pos =
    typeof syncPosition === 'number' && Number.isFinite(syncPosition)
      ? syncPosition
      : range.end - LIVE_EDGE_BACKOFF_SECONDS;
  return Math.min(range.end, Math.max(range.start, pos));
}

/**
 * 라이브 엣지 대비 시차(초) — 기준은 range.end가 아니라 liveEdgePosition이다.
 * 정수로 반올림해 같은 값이면 리렌더를 건너뛰게 하고, 표기·ARIA와 같은 상한(창 크기)으로 자른다.
 */
export function behindFromCurrentTime(
  range: DvrRange,
  currentTime: number,
  syncPosition: number | null,
): number {
  const behind = Math.round(liveEdgePosition(range, syncPosition) - currentTime);
  return Math.min(REWIND_UI_MAX_SECONDS, Math.max(0, behind));
}

/**
 * 되감을 수 있는 폭(초) — 시크바 좌측 끝이자 seekToBehind의 클램프 상한이다 (POK-32).
 *
 * 방송이 창보다 짧으면 이 값이 곧 방송 길이다 (계약3 4절 4번 "seekable 범위 기준,
 * 상한은 DVR 창"). 두 용도가 같은 식을 각자 적으면 나중에 한쪽만 바뀌고, 그 순간
 * 시크바 왼쪽에 눌러도 안 가는 영역이 생긴다 — 이 티켓이 고치는 버그와 같은 종류다.
 */
export function rewindWindowSeconds(range: DvrRange, syncPosition: number | null): number {
  const width = liveEdgePosition(range, syncPosition) - range.start;
  return Math.min(REWIND_UI_MAX_SECONDS, Math.max(0, width));
}

/** 영상 목록의 조각 하나. 구조 타입이라 hls.js의 Fragment를 그대로 넘긴다 */
export interface DatedFragment {
  /** 영상 시각(초) */
  start: number;
  duration: number;
  /** 그 조각 첫 화면의 실제 시각(epoch ms, EXT-X-PROGRAM-DATE-TIME). 목록에 없으면 null */
  programDateTime: number | null;
  /** EXT-X-GAP 조각 — 영상 시각은 차지하지만 내용이 없다(hls.js Fragment.gap 과 같다) */
  gap?: boolean;
}

/**
 * 영상 시각 t(초)의 실제 시각(epoch ms). 계약3: 「재생 위치 ↔ PDT 환산은 조각마다 그 조각의 PDT를 기준으로 한다」. t를 품은
 * 조각의 시각에 조각 안 거리를 더하고, 마지막 조각 뒤(LL-HLS의 만드는 중인 조각은 아직 목록에 조각으로 없다)는 마지막 조각에서
 * 이어 센다. 첫 조각보다 앞이면 첫 조각에서 거꾸로 센다: 미닫이 목록(라이브 28초 · 되감기 1시간 — 길이와 무관)은 조각이 생길
 * 때마다 맨 앞 조각을 버려, 맨 앞을 보던 재생 위치가 목록 밖으로 밀린다(그 조각은 버퍼에 남아 계속 튼다). 모른다고 하면 영상은
 * 창 처음인데 채팅이 최신으로 튀었다(POK-326 리뷰 2라운드). 첫 조각을 받기 전의 0초는 부르는 쪽이 거른다(재생 위치에
 * 영상이 없다). 조각이 없거나 시각 표시가 없으면 모른다(null). hls.js playingDate를 쓰지 않는다: 그것은
 * 재생 위치가 0이면 「값 없음」으로 보고 직전 자리의 시각을 줘, 시작 직후·맨 앞으로 옮긴 직후에 시차와 다른
 * 자리를 가리켰다(POK-326 리뷰 1라운드)
 */
export function programDateAt(fragments: readonly DatedFragment[], t: number): number | null {
  for (let i = fragments.length - 1; i >= 0; i -= 1) {
    const frag = fragments[i]!;
    if (frag.start > t && i > 0) continue;
    return frag.programDateTime === null ? null : frag.programDateTime + (t - frag.start) * 1000;
  }
  return null;
}

/**
 * 실제 시각(epoch ms)의 영상 시각(초): programDateAt의 거꾸로. 그 시각 앞에서 시작한 마지막 조각에서 센다: 조각 사이에서 시각이
 * 뛴 틈(송출이 다시 붙은 자리) 안이면 영상이 없으니 뒤 조각의 시작이다. 첫 조각보다 이르면 첫 조각에서 거꾸로 이어 센다(부르는
 * 쪽이 되감기 창 안으로 자른다). 조각이 없거나 시각 표시가 없으면 null
 */
export function mediaTimeAt(fragments: readonly DatedFragment[], epochMs: number): number | null {
  const first = fragments[0];
  if (first === undefined) return null;
  for (let i = fragments.length - 1; i >= 0; i -= 1) {
    const frag = fragments[i]!;
    if (frag.programDateTime === null) return null;
    if (frag.programDateTime > epochMs) continue;
    const offset = (epochMs - frag.programDateTime) / 1000;
    const next = fragments[i + 1];
    // 이 조각 끝을 넘었는데 다음 조각이 더 늦게 시작하면 틈 안이다
    if (offset > frag.duration && next !== undefined) return next.start;
    return frag.start + offset;
  }
  return first.programDateTime === null
    ? null
    : first.start + (epochMs - first.programDateTime) / 1000;
}
