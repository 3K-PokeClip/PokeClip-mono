// 시험 전용 — 편집기가 낸 컷 값 T 로 clip · 렌더가 어떤 프레임을 담는지 보는 작은 판정 함수(POK-473 설계 prw2 11절).
// 웹 시험은 T 를 단언하고, 그 T 가 담는 프레임은 여기서 본다. 옮긴 것은 아래 프레임 고르기 규칙뿐이다 — 렌더의 끝 조각 후퇴
// (RenderTimeline.java:486-499) · 시작 전진 · 표시 소유 · 상한은 옮기지 않았다.
//   clip  LedgerTimeline.locate · locateEnd — F 규칙(시작 ≤ t 인 행 가운데 seq 최대) · 이어진 이웃이면 같은 매체 자리 ·
//         끊긴 이웃이면 공백(시작은 뒤 조각 머리 · 끝은 앞 조각 끝까지 toSegmentEnd) · 마지막 행 뒤면 AfterLast 거절
//   렌더 새 경로(PR-B2) RenderTimeline.java:427-443 — 시작 = 「PTS − D0 ≤ x′(+0.5µs)」 인 마지막(없으면 첫 프레임) ·
//         끝 = 「PTS − D0 < x′(−0.5µs)」 인 프레임까지. x′ = FILE_HEAD 면 x − m, VIDEO_START 면 x
//   렌더 옛 경로(지금 prod · PR-B1) -ss in -t (out − in) — 0점 = 첫 조각 트랙별 첫 PTS 최솟값 Hp(= Hd + e) · 시작 =
//         목표 이후 첫 프레임 · 끝 = 출력 시각 < out − in. 덮음 검사 ClipRenderer.checkRange(±100ms · 조각 사이 500ms)
// 이어짐(continuesPrevious)은 판정의 입력일 뿐이다 — 웹 코드는 목록에서 그것을 모른다(렌더 답 3).
// 프레임 PTS 는 ③ 축 초다. ② 와 ③ 은 모든 트랙에 같은 상수를 더한 축이라 PTS − D0 · PTS − Hp 는 어느 축에서 재도 같다.

/** 장부 행(clip 이 보는 것) */
export interface JudgeRow {
  seq: number;
  pdtMs: number;
  durationMs: number;
  /** 앞 행과 매체가 이어졌다(장부 continuesPrevious) */
  continuesPrevious: boolean;
}

/** 조각 하나의 매체 — 렌더가 그 조각 파일에서 읽는 것 */
export interface JudgeMedia {
  /** D0 — 영상 첫 DTS(③ 축 초) */
  videoDts: number;
  /** 머리 앞섬 m = D0 − Hd(초) — 판 2 는 0 */
  m: number;
  /** e = Hp − Hd(초) — 옛 경로 0점이 Hd 보다 늦은 몫 */
  e: number;
  /** 영상 프레임 PTS(③ 축 초 · 오름차순) */
  frames: readonly number[];
}

export type ClipPlace =
  | { kind: 'media'; seq: number; offsetMs: number }
  | { kind: 'gap'; beforeSeq: number; afterSeq: number }
  | { kind: 'beforeFirst' }
  | { kind: 'afterLast' };

export type RenderOrigin = 'VIDEO_START' | 'FILE_HEAD';

/** 고른 프레임 — 조각과 그 PTS */
export interface JudgedFrame {
  seq: number;
  pts: number;
}

/** 렌더 비교식의 반 µs 여유(2·10⁶ 배 정수식의 ±1) */
const HALF_MICRO = 0.5e-6;
/** 옛 checkRange 의 덮음 여유(ClipRenderer.TOLERANCE_MS) */
const OLD_TOLERANCE_MS = 100;

function endOf(row: JudgeRow): number {
  return row.pdtMs + row.durationMs;
}

function lastStarting(rows: readonly JudgeRow[], t: number, inclusive: boolean): number {
  return rows.findLastIndex((row) => (inclusive ? row.pdtMs <= t : row.pdtMs < t));
}

function beyond(rows: readonly JudgeRow[], k: number, endSide: boolean): ClipPlace {
  const row = rows[k]!;
  const next = rows[k + 1];
  if (next === undefined) return { kind: 'afterLast' };
  if (next.continuesPrevious) {
    return endSide
      ? { kind: 'media', seq: row.seq, offsetMs: row.durationMs }
      : { kind: 'media', seq: next.seq, offsetMs: 0 };
  }
  return { kind: 'gap', beforeSeq: row.seq, afterSeq: next.seq };
}

/** clip LedgerTimeline.locate — 컷 시작 t 의 자리 */
export function clipLocate(rows: readonly JudgeRow[], t: number): ClipPlace {
  const k = lastStarting(rows, t, true);
  if (k < 0) return { kind: 'beforeFirst' };
  const row = rows[k]!;
  if (t < endOf(row)) return { kind: 'media', seq: row.seq, offsetMs: t - row.pdtMs };
  return beyond(rows, k, false);
}

/** clip LedgerTimeline.locateEnd — 컷 끝 out 의 자리(「out 직전」) */
export function clipLocateEnd(rows: readonly JudgeRow[], out: number): ClipPlace {
  const k = lastStarting(rows, out, false);
  if (k < 0) return { kind: 'beforeFirst' };
  const row = rows[k]!;
  const next = rows[k + 1];
  if (next !== undefined && next.continuesPrevious && next.pdtMs === out) {
    return { kind: 'media', seq: row.seq, offsetMs: row.durationMs };
  }
  if (out <= endOf(row)) return { kind: 'media', seq: row.seq, offsetMs: out - row.pdtMs };
  return beyond(rows, k, true);
}

function mediaOf(media: ReadonlyMap<number, JudgeMedia>, seq: number): JudgeMedia {
  const found = media.get(seq);
  if (found === undefined) throw new Error(`판정에 조각 ${seq} 매체가 없다`);
  return found;
}

function shiftedMs(media: JudgeMedia, offsetMs: number, origin: RenderOrigin): number {
  return origin === 'FILE_HEAD' ? offsetMs - media.m * 1000 : offsetMs;
}

/** 새 경로 첫 프레임. clip 이 거절하면(처음 앞 · 마지막 뒤) null */
export function newPathFirstFrame(
  rows: readonly JudgeRow[],
  media: ReadonlyMap<number, JudgeMedia>,
  inMs: number,
  origin: RenderOrigin,
): JudgedFrame | null {
  const place = clipLocate(rows, inMs);
  if (place.kind === 'beforeFirst' || place.kind === 'afterLast') return null;
  const seq = place.kind === 'media' ? place.seq : place.afterSeq;
  const offsetMs = place.kind === 'media' ? place.offsetMs : 0;
  const seg = mediaOf(media, seq);
  const limit = shiftedMs(seg, offsetMs, origin) / 1000 + HALF_MICRO;
  const upTo = seg.frames.filter((pts) => pts - seg.videoDts <= limit);
  return { seq, pts: upTo.at(-1) ?? seg.frames[0]! };
}

/** 새 경로 끝 프레임(남는 마지막 프레임). clip 이 거절하면 null */
export function newPathLastFrame(
  rows: readonly JudgeRow[],
  media: ReadonlyMap<number, JudgeMedia>,
  outMs: number,
  origin: RenderOrigin,
): JudgedFrame | null {
  const place = clipLocateEnd(rows, outMs);
  if (place.kind === 'beforeFirst' || place.kind === 'afterLast') return null;
  if (place.kind === 'gap') {
    return { seq: place.beforeSeq, pts: mediaOf(media, place.beforeSeq).frames.at(-1)! };
  }
  const seg = mediaOf(media, place.seq);
  const limit = shiftedMs(seg, place.offsetMs, origin) / 1000 - HALF_MICRO;
  const kept = seg.frames.filter((pts) => pts - seg.videoDts < limit);
  const last = kept.at(-1);
  return last === undefined ? null : { seq: place.seq, pts: last };
}

/** 옛 경로 0점(③ 축) — Hp = Hd + e = D0 − m + e */
function oldZero(seg: JudgeMedia): number {
  return seg.videoDts - seg.m + seg.e;
}

/** 옛 경로 첫 프레임(한 조각 안 컷) — 목표 이후 첫 프레임. 없으면 null */
export function oldPathFirstFrame(seg: JudgeMedia, pdtMs: number, inMs: number): number | null {
  const target = oldZero(seg) + (inMs - pdtMs) / 1000 - HALF_MICRO;
  return seg.frames.find((pts) => pts >= target) ?? null;
}

/** 옛 경로 끝 프레임(한 조각 안 컷) — 출력 시각이 out − in 보다 앞인 마지막 프레임. 없으면 null */
export function oldPathLastFrame(seg: JudgeMedia, pdtMs: number, outMs: number): number | null {
  const target = oldZero(seg) + (outMs - pdtMs) / 1000 - HALF_MICRO;
  return seg.frames.findLast((pts) => pts < target) ?? null;
}

/** 옛 덮음 검사 — [in, out) 과 겹치는 행이 ±100ms 안에서 덮고 사이가 500ms 안이면 'ok' */
export function oldPathRange(
  rows: readonly JudgeRow[],
  inMs: number,
  outMs: number,
): 'ok' | 'SOURCE_RANGE' {
  const sources = rows.filter((row) => row.pdtMs < outMs && endOf(row) > inMs);
  const first = sources[0];
  const last = sources.at(-1);
  if (first === undefined || last === undefined) return 'SOURCE_RANGE';
  if (first.pdtMs > inMs + OLD_TOLERANCE_MS || endOf(last) < outMs - OLD_TOLERANCE_MS) {
    return 'SOURCE_RANGE';
  }
  const broken = sources.some(
    (row, i) => i > 0 && Math.abs(row.pdtMs - endOf(sources[i - 1]!)) > OLD_TOLERANCE_MS * 5,
  );
  return broken ? 'SOURCE_RANGE' : 'ok';
}
