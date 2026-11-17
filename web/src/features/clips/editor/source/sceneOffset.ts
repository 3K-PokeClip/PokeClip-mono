import { floorMs, originOf } from './cutPosition';
import type { EditorPlaylist, PlaylistSegment } from './editorPlaylist';
import { segmentAtMs } from './editorSourceTimeline';
import type { HeadCache } from './fileHeadProbe';
import type { Endpoint } from './rangeEndpoints';
import type { EditorPick, WindowPlacement } from './windowPlacement';

// 업로드 장면(썸네일) offset 을 결과 영상 미디어 거리로 셈한다(POK-473 설계 prw2 3.3 「업로드 장면 offset」 · 11절 c12′ ·
// 처분 H3). offset 은 결과 영상 ms 다 — 렌더는 조각 미디어를 이어 붙이고 장부 틈에는 시간이 없다(경계 답 N9). 판 NULL PDT 는
// 래칫이라 PDT 차(편집기 초 차)는 경계마다 죽은 자리만큼 미디어보다 길다. 그래서 in 부터 장면까지를 미디어로 잰다.
//   같은 창   Blob 시각 차 = ③ PTS 차(remuxWindow 는 창 전체에 Δ 하나만 빼므로 겹친 경계도 PTS 그대로다)
//   창 밖     조각별 길이(EXTINF — 장부 duration_ms) 합. GAP 줄 · 런 사이 틈은 0 · GAP 줄 안 자리는 그 줄의 한 점
//   창 없음   창 밖과 같이 목록으로 센다(창을 받기 전 · 받지 못함 — kty c12′ 결정 1 (가))
//   셀 수 없음 목록이 없거나 그 조각이 목록 밖 · 판 NULL 고름의 ② 머리가 없으면 창의 값(편집기 초 차)을 보내고 표시한다
// 🔴 결과 영상 거리는 ms 로 내린다. 업로드 일꾼은 offset 에서 「pts ≥ t」 인 첫 프레임을 뽑는다(SceneExtractor.java:59 ·
//    126-128 · 경계 답 2026-11-13 렌더) — 프레임 시작이 .67 꼴인 자리를 올리면 다음 프레임이 나온다.
// 🔴 조각이 1초 미만 겹치는 이음(K1 · B8)에서 렌더는 겹친 프레임을 앞 조각 몫으로 잘라 낸다. 길이 합은 그 겹침만큼 클 수 있다
//    — 썸네일의 거친 좌표라 한계로 둔다(경계 답 N9).

/** 썸네일 고르기 — 창 안 자리면 고른 프레임, 창 밖 · 목업이면 표시 초 S */
export type ThumbnailChoice = { kind: 'pick'; pick: EditorPick } | { kind: 's'; s: number };

/** 장면 offset 을 셀 재료 — 창 배치 · 목록 · ② 머리 */
export interface SceneMedia {
  /** 연 순간의 창 배치. 창을 받기 전이면 null */
  placement: WindowPlacement | null;
  /** 들고 있는 목록 — 창 밖 자리의 조각과 길이를 읽는다 */
  list: EditorPlaylist | null;
  /** 편집기 0초의 절대 ms */
  base: number;
  /** ② 머리 캐시 — 창 밖 고름의 조각 원점을 읽는다 */
  heads: HeadCache;
}

/** 업로드 창이 연 순간의 재료 — 장면 offset 을 셀 때 읽는다 */
export interface UploadScene extends SceneMedia {
  /** 연 구간 시작(편집기 초) — 창의 장면 값은 여기부터의 편집기 초 차 ms 다 */
  rangeStartSeconds: number;
  /** 컷을 판정한 시작 끝점. 없으면 목업이다 */
  start: Endpoint | undefined;
}

/** 자리 하나 — 고른 프레임이나 목록 PDT 축 값 */
type Spot = { kind: 'pick'; pick: EditorPick } | { kind: 'ms'; ms: number };

/** 창 안 자리 — 그 자리에 보이는 프레임과 그 프레임 시작부터의 나머지(초) */
interface Anchor {
  pick: EditorPick;
  rest: number;
}

/** 조각 안 자리 — 그 조각 원점부터의 미디어 ms */
interface Local {
  seq: number;
  ms: number;
}

function spotOfEndpoint(endpoint: Endpoint): Spot | null {
  if (endpoint.kind === 'estimate') return null;
  return endpoint.kind === 'pick' ? endpoint : { kind: 'ms', ms: endpoint.ms };
}

function spotOfChoice(choice: ThumbnailChoice, base: number): Spot {
  return choice.kind === 'pick' ? choice : { kind: 'ms', ms: base + choice.s * 1000 };
}

/** 창 안이면 그 자리의 Anchor, 창 밖이면 null */
function anchorOf(spot: Spot, placement: WindowPlacement, base: number): Anchor | null {
  if (spot.kind === 'pick') {
    return placement.mediaDistance(spot.pick, spot.pick) === null
      ? null
      : { pick: spot.pick, rest: 0 };
  }
  const s = (spot.ms - base) / 1000;
  const pick = placement.pickAtS(s);
  // 죽은 자리면 다음 조각 첫 프레임이 보이고 S 는 그 앞이다 — 나머지는 0
  return pick === null ? null : { pick, rest: Math.max(0, s - placement.sAtPick(pick)) };
}

/** 조각 안 자리 — 고름은 그 조각 원점(판 2 = D3 · 판 NULL = ② 머리)으로, 값은 목록의 F 규칙으로(죽은 자리는 다음 조각 0) */
function localOf(spot: Spot, list: EditorPlaylist, heads: HeadCache): Local | null {
  if (spot.kind === 'pick') {
    const { pick } = spot;
    const origin = originOf(pick, heads);
    // 머리를 아직 모르거나 판독이 실패했으면 원점을 몰라 셀 수 없다 — 영상 첫 DTS 로 어림하지 않는다
    return typeof origin === 'string' ? null : { seq: pick.seq, ms: 1000 * (pick.pts - origin.o) };
  }
  const row = segmentAtMs(list, spot.ms);
  if (row === null) return null;
  // GAP 줄은 결과 영상에서 길이 0 의 한 점이다(렌더 N9 「InGap 한 점」) — 그 줄 안 위치를 남기지 않는다
  return { seq: row.seq, ms: row.gap ? 0 : Math.max(0, spot.ms - row.pdtMs) };
}

function lengthOf(row: PlaylistSegment): number {
  return row.gap ? 0 : row.durationMs;
}

/** 두 조각 안 자리 사이의 길이 합. 어느 조각이 목록에 없으면 null */
function lengthBetween(from: Local, to: Local, rows: readonly PlaylistSegment[]): number | null {
  if (from.seq === to.seq) return to.ms - from.ms;
  const a = rows.findIndex((r) => r.seq === from.seq);
  const b = rows.findIndex((r) => r.seq === to.seq);
  if (a < 0 || b < 0) return null;
  if (a > b) {
    const back = lengthBetween(to, from, rows);
    return back === null ? null : -back;
  }
  const middle = rows.slice(a + 1, b).reduce((sum, r) => sum + lengthOf(r), 0);
  return lengthOf(rows[a]!) - from.ms + middle + to.ms;
}

/** 두 자리가 모두 그 창 안이면 Blob 시각 차(초), 아니면 null */
function windowDistance(
  from: Spot,
  to: Spot,
  placement: WindowPlacement,
  base: number,
): number | null {
  const a = anchorOf(from, placement, base);
  const b = anchorOf(to, placement, base);
  if (a === null || b === null) return null;
  return placement.mediaDistance(a.pick, b.pick)! + b.rest - a.rest;
}

/**
 * in 끝점부터 썸네일 장면까지 결과 영상 미디어 거리(ms · 내림). 둘 다 창 안이면 Blob 시각 차, 아니면(창 밖 · 창 없음)
 * 목록의 길이 합이다. 셀 수 없으면 null — 어림 끝점 · 목록 없음 · 그 조각이 목록 밖 · 머리 없는 판 NULL 고름
 */
export function sceneOffsetMs(
  inEnd: Endpoint,
  thumb: ThumbnailChoice,
  media: SceneMedia,
): number | null {
  const { placement, list, base, heads } = media;
  const from = spotOfEndpoint(inEnd);
  if (from === null) return null;
  const to = spotOfChoice(thumb, base);
  const inWindow = placement === null ? null : windowDistance(from, to, placement, base);
  if (inWindow !== null) return floorMs(inWindow);
  if (list === null) return null;
  const start = localOf(from, list, heads);
  const end = localOf(to, list, heads);
  const ms = start === null || end === null ? null : lengthBetween(start, end, list.rows);
  return ms === null ? null : floorMs(ms / 1000);
}

/** 업로드 장면 offset. approximate 면 미디어 거리로 셀 수 없어 창의 값(편집기 초 차)을 그대로 실었다 — 장면이 어긋날 수 있다 */
export interface UploadSceneOffset {
  offsetMs: number;
  approximate: boolean;
}

/**
 * 업로드 창의 장면(연 구간 시작부터 편집기 초 차 ms — 창 미리보기와 같은 값) → 결과 영상 offset. 장면은 그 자리에 보이는
 * 프레임(창 밖이면 그 초)이다. 끝점이 없으면(목업) 창의 값 그대로다. 셀 수 없으면 창의 값을 싣되 업로드는 막지 않고
 * approximate 로 알린다(kty c12′ 결정 1 (가) — 부르는 쪽이 사용자에게 알린다)
 */
export function uploadSceneOffsetMs(dialogMs: number, scene: UploadScene): UploadSceneOffset {
  if (scene.start === undefined) return { offsetMs: dialogMs, approximate: false };
  const s = scene.rangeStartSeconds + dialogMs / 1000;
  const pick = scene.placement?.pickAtS(s) ?? null;
  const thumb: ThumbnailChoice = pick === null ? { kind: 's', s } : { kind: 'pick', pick };
  const offset = sceneOffsetMs(scene.start, thumb, scene);
  if (offset !== null) return { offsetMs: offset, approximate: false };
  console.info('[editor] 장면 offset 을 미디어 거리로 셀 수 없어 편집기 초 차로 보내요', {
    dialogMs,
  });
  return { offsetMs: dialogMs, approximate: true };
}
