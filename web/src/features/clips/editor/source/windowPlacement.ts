import { displayOriginOf, sOfPick } from './cutPosition';
import type { PlaylistSegment } from './editorPlaylist';
import { segmentAtMs } from './editorSourceTimeline';
import type { HeadCache } from './fileHeadProbe';
import type { RemuxedSegment, RemuxedWindow } from './remuxWindow';

// 창 Blob 시각 ↔ 편집기 표시 초 S 를 바꾸는 단 한 곳(POK-473 설계 prw2 3.2 · 4절 · 계획 4절 P-4′). 재생 시계 · 시크 · 레시피
// 열기 · 필름띠 · 장면 그림 · 찍기가 모두 이 배치를 거쳐 같은 원점을 쓴다.
//   Blob 시각 t 의 ③ PTS p = t + Δ(E3 shiftSeconds — 변환 뒤 출력에서 읽은 값. 원점을 따로 셈하지 않는다)
//   조각 k 안에서 S = (PDT_k − base)/1000 + (p − o_k) · o_k 는 cutPosition.originOf(판 2 = D3 · 판 NULL = ③ 축 Hd)
// 판 NULL 머리가 아직 없으면 그 조각만 영상 첫 DTS 로 근사해 먼저 보이고, 머리가 오면 부르는 쪽이 다시 배치한다(표시만
// 바뀐다 — 저장 값은 savable 이 머리를 요구한다).
// 🔴 Δ 는 출력 timescale 로 양자화돼 최대 약 3.5µs 어긋난다. 「PTS ≤ t」 비교에 반 눈금 넘는 여유를 둔다.
// 🔴 실물 표본 길이는 일정하지 않고(2997 · 3015 · 2970 /90000) 조각 경계가 몇 십 틱 겹친다. 프레임 끝은 표본 길이로 짐작하지
//    않고 다음 표시 프레임 PTS 로 잡는다 — 조각 마지막 프레임도 창 안 다음 조각의 표시 PTS 로 잡는다. B 프레임 원본은 표본
//    길이(디코드 순)가 표시 간격보다 길어, 끝점 out 이 다음 프레임 PTS 를 넘으면 결과 끝에 한 프레임이 더 붙는다(렌더 리허설
//    2026-11-13 · 표본 길이 3/5 G+1 · 표시 간격 3/3 G). 끝점 out 은 이 길이의 반 — G 와 다음 프레임 사이 가운데 — 에
//    선다(cutPosition · kty 결정 (다)). 창 끝에서 짐작한 길이 · ms 내림 · ③ 와 PDT 의 반 ms 차를 그 여유가 덮는다.

/** 조각 안 미디어 자리 — ③ 축 PTS(초) */
export interface MediaPos {
  seq: number;
  pts: number;
}

/**
 * 화면에서 고른 프레임. 그 seq 에서 바뀌지 않는 사실만 든다 — ③ 바이트의 PTS · 길이 · D3 와 목록의 PDT · EXTINF · 판. 머리 ·
 * 이웃은 들지 않고 저장할 때 읽는다(머리 = 캐시 · 이웃 = 다시 받은 목록). 그래서 목록이 미끄러지거나 머리가 늦게 와도 같다.
 * 길이만 예외다 — 조각 마지막 프레임은 고른 창에 다음 조각이 있었는지에 따라 다를 수 있다(dur)
 */
export interface EditorPick extends MediaPos {
  /**
   * 그 프레임의 표시 간격(초) — 다음 표시 프레임 PTS 까지. 조각 마지막 프레임이면 창 안 다음 조각(이어진 seq)의 이 PTS 뒤
   * 첫 프레임까지, 그런 조각이 없으면 조각 안 앞 프레임과의 간격(프레임이 하나뿐이면 표본 길이)으로 짐작한다. 끝점 out 은
   * 프레임 끝(pts + dur)이 아니라 가운데(pts + dur/2)에 선다 — cutPosition
   */
  dur: number;
  /** D3_k — ③ 영상 첫 DTS(초) */
  videoDts: number;
  /** PDT_k(epoch ms) */
  pdtMs: number;
  /** EXTINF_k(ms) */
  durationMs: number;
  /** 판 — 'v2' 는 판 2, null 은 판 NULL */
  timing: 'v2' | null;
}

export interface WindowPlacement {
  /** 그 Blob 시각에 보이는 프레임(③ PTS ≤ t + Δ 인 마지막 · 첫 프레임 앞이면 첫 프레임) */
  pickAtBlob(blobSeconds: number): EditorPick;
  /** 창 [첫 PDT, 마지막 PDT + EXTINF) 안이면 그 자리에 보이는 프레임, 밖이면 null */
  pickAtS(s: number): EditorPick | null;
  /** S 의 Blob 시각 — F 규칙으로 조각을 정하고 o_k 로 옮긴다. 죽은 자리 · 머리 앞섬은 그 조각 첫 화면으로 */
  blobAtS(s: number): number;
  /** 재생 시계 — 그 Blob 시각에 보이는 프레임의 조각 원점으로 S 를 셈한다 */
  sAtBlob(blobSeconds: number): number;
  /** 고름의 표시 초 — 고름이 든 사실로 셈하므로 이 창에 없는 고름도 그린다 */
  sAtPick(pick: EditorPick): number;
  /** 같은 창 안 두 자리의 미디어 거리(초). 어느 한쪽이 이 창 밖이면 null */
  mediaDistance(from: MediaPos, to: MediaPos): number | null;
}

/** Δ 양자화(출력 57,600 틱의 반 눈금 ≈ 8.7µs)보다 크고 한 프레임(수십 ms)보다 훨씬 작은 비교 여유(초) */
const BLOB_TOLERANCE_SECONDS = 1e-5;

/** 배치에 쓰는 조각 하나 — 목록 행 · ③ 표 · 원점 */
interface PlacedSegment {
  row: PlaylistSegment;
  table: RemuxedSegment;
  origin: number;
}

function placedSegments(
  remuxed: RemuxedWindow,
  rows: readonly PlaylistSegment[],
  heads: HeadCache,
): PlacedSegment[] {
  return remuxed.segments.map((table) => {
    const row = rows.find((r) => r.seq === table.seq);
    if (row === undefined) throw new Error(`창 조각 ${table.seq} 의 목록 행이 없다`);
    const origin = displayOriginOf(
      { seq: row.seq, timing: row.timing, videoDts: table.videoDts },
      heads,
    );
    return { row, table, origin };
  });
}

/**
 * 그 프레임이 화면에 머무는 길이(초) — 다음 표시 프레임 PTS 까지. 조각 마지막 프레임이면 창 안 다음 조각(이어진 seq)에서 이
 * PTS 뒤 첫 프레임까지다 — 겹친 이음에서는 뒤 조각이 이기므로 이 PTS 이하인 뒤 조각 프레임은 이 프레임을 덮지 않아 건너뛴다.
 * 그런 프레임이 없으면(창 끝 · 끊긴 seq) 조각 안 앞 프레임과의 간격, 프레임이 하나뿐이면 표본 길이로 물러난다
 */
function displayDur(
  placed: PlacedSegment,
  index: number,
  after: PlacedSegment | undefined,
): number {
  const { frames } = placed.table;
  const frame = frames[index]!;
  const inner = frames[index + 1];
  if (inner !== undefined) return inner.pts - frame.pts;
  const joined = after !== undefined && after.row.seq === placed.row.seq + 1;
  const following = joined ? after.table.frames.find((f) => f.pts > frame.pts) : undefined;
  if (following !== undefined) return following.pts - frame.pts;
  const previous = frames[index - 1];
  return previous === undefined ? frame.dur : frame.pts - previous.pts;
}

function pickOf(segments: readonly PlacedSegment[], at: number, index: number): EditorPick {
  const placed = segments[at]!;
  const { row, table } = placed;
  const frame = table.frames[index]!;
  return {
    seq: row.seq,
    pts: frame.pts,
    dur: displayDur(placed, index, segments[at + 1]),
    videoDts: table.videoDts,
    pdtMs: row.pdtMs,
    durationMs: row.durationMs,
    timing: row.timing,
  };
}

/** ③ PTS p 에 보이는 프레임 — 겹친 경계에서는 PTS 가 같거나 큰 뒤 조각 프레임이 이긴다 */
function visibleAt(segments: readonly PlacedSegment[], p: number): EditorPick {
  let best = { at: 0, index: 0 };
  let bestPts = Number.NEGATIVE_INFINITY;
  for (const [at, placed] of segments.entries()) {
    for (const [index, frame] of placed.table.frames.entries()) {
      if (frame.pts > p + BLOB_TOLERANCE_SECONDS || frame.pts < bestPts) continue;
      best = { at, index };
      bestPts = frame.pts;
    }
  }
  return pickOf(segments, best.at, best.index);
}

/**
 * 창 배치. rows 는 이 창을 지은 목록 행(segmentsForWindow 결과)이다 — 같은 seq 의 PDT · EXTINF 는 바뀌지 않으므로 창을 지은
 * 뒤 목록이 미끄러져도 그대로 쓴다. 머리가 바뀌면 다시 부른다
 * @param base 편집기 0초의 절대 ms — 세션 동안 바꾸지 않는다
 */
export function placeWindow(
  remuxed: RemuxedWindow,
  rows: readonly PlaylistSegment[],
  base: number,
  heads: HeadCache,
): WindowPlacement {
  const segments = placedSegments(remuxed, rows, heads);
  if (segments.length === 0) throw new Error('배치할 창 조각이 없다');
  const windowRows = segments.map((placed) => placed.row);
  const windowList = { runs: [windowRows], rows: windowRows };
  const shift = remuxed.shiftSeconds;
  const first = windowRows[0]!;
  const last = windowRows.at(-1)!;
  const bySeq = new Map(segments.map((placed) => [placed.row.seq, placed]));
  const sOf = (placed: PlacedSegment, p: number) =>
    (placed.row.pdtMs - base) / 1000 + (p - placed.origin);

  const pickAtBlob = (blobSeconds: number) => visibleAt(segments, blobSeconds + shift);

  const blobAtS = (s: number) => {
    const ms = base + s * 1000;
    const row = segmentAtMs(windowList, ms) ?? (ms < first.pdtMs ? first : last);
    const placed = bySeq.get(row.seq)!;
    const p = placed.origin + Math.max(0, ms - row.pdtMs) / 1000;
    return Math.max(p, placed.table.mediaStart) - shift;
  };

  return {
    pickAtBlob,
    pickAtS: (s) => {
      const ms = base + s * 1000;
      if (ms < first.pdtMs || ms >= last.pdtMs + last.durationMs) return null;
      return pickAtBlob(blobAtS(s));
    },
    blobAtS,
    sAtBlob: (blobSeconds) => {
      const placed = bySeq.get(pickAtBlob(blobSeconds).seq)!;
      return sOf(placed, blobSeconds + shift);
    },
    sAtPick: (pick) => sOfPick(pick, heads, base),
    mediaDistance: (from, to) =>
      bySeq.has(from.seq) && bySeq.has(to.seq) ? to.pts - from.pts : null,
  };
}
