'use client';

import { useEffect, useReducer } from 'react';
import { sOfPick, type EdgeInput } from './source/cutPosition';
import type { HeadCache } from './source/fileHeadProbe';
import {
  displayRange,
  endpointOf,
  rangeLengthRefusal,
  type Endpoint,
  type EndpointRange,
} from './source/rangeEndpoints';
import type { ThumbnailChoice } from './source/sceneOffset';
import type { EditorPick } from './source/windowPlacement';
import {
  clampEdgeSeconds,
  MIN_RANGE_SECONDS,
  resolveRangeEdge,
  type ClipRange,
} from './timelineMath';

// 편집기 상태의 끝점 · 고름 입력(POK-473 설계 prw2 3.3 몸짓 표 · 4절 상태 훅 입력 꼴 · 계획 5절 E4b-3). 상태 훅
// (useClipEditorMockState — 800줄 넘음)은 부르기만 하고 규칙은 여기 둔다.
//
// 몸짓 규칙은 하나다 — 창 안 자리면 고름(pick), 창 밖 · 목업이면 S. 끌기 · 화살표는 S 를 넘기고 여기서 playback.pickAtS 로
// 바꾼다. 그래서 창 안에서 정한 끝점(찍기 · 끌기 · 화살표)은 그 조각 ② 머리를 읽어야 저장된다(처분 K3 — 판독 없이 저장되는
// 우회가 없다). 창 밖까지 끈 S 입력이 exact 로 저장되는 것은 설계가 받아들인 동작이다.
// 구간(편집기 초)은 끝점의 표시 초다 — 고름은 머리가 오면 머리 원점으로 다시 그려진다(값은 그대로).

/** 끝점을 셈하는 문맥 — 실제 모드에서만 있다(목업은 null: 구간 초가 곧 값) */
export interface EndpointContext {
  /** 편집기 0초의 절대 ms */
  base: number;
  heads: HeadCache;
}

/** 구간을 잡을 수 있는 한계(편집기 초) */
export interface EdgeLimits {
  minSeconds: number;
  durationSeconds: number;
}

/**
 * 한 프레임(초). 끝점을 고르기 전에는 영상의 프레임 길이를 모르므로 낮은 프레임률(10fps)의 한 프레임으로 넉넉히 잡는다 —
 * 시작의 첫 영상 PTS 늦음과 끝의 프레임 시작 자리가 함께 깎는 길이(높은 프레임률의 두 프레임)도 덮는다
 */
const ONE_FRAME_SECONDS = 0.1;

/**
 * 연 범위의 손잡이 한계(편집기 초). 구간은 그 범위 안에서만 잡는다(사용자 확정 2026-09-17). 단 카드 범위가 5초 + 한 프레임보다
 * 짧으면 그 안에서 고를 수 있는 길이가 5초에 못 미쳐 저장할 길이가 없다 — 정확히 5초인데 첫 영상 PTS 가 시작보다 늦은 범위
 * 포함(E4b-3 r1 cx). 그때만 끝 한계를 범위 끝 뒤로 5초 넓혀 사용자가 끝을 직접 끌어 정하게 한다(kty 2026-11-12 (나) — 카드만).
 * 넓힌 5초는 편집기 창 여유(editorWindow PAD_SECONDS 10초) 안이다. 끝을 옮겨 주지는 않는다 — 끌어 정한 끝은 몸짓 규칙 그대로다.
 * 저장된 편집본은 넓히지 않는다 — 저장된 구간이 곧 한계다
 * @param window 연 범위(편집기 ms)
 * @param totalSeconds 방송 길이(편집기 초) — 끝 한계는 이를 넘지 않는다
 * @param widenShort 짧은 범위의 끝 한계를 넓히는가 — 카드로 연 범위만 true
 */
export function rangeEdgeLimits(
  window: { startMs: number; endMs: number },
  totalSeconds: number,
  widenShort: boolean,
): EdgeLimits {
  const minSeconds = window.startMs / 1000;
  const endSeconds = window.endMs / 1000;
  const short = widenShort && endSeconds - minSeconds < MIN_RANGE_SECONDS + ONE_FRAME_SECONDS;
  const limit = short ? endSeconds + MIN_RANGE_SECONDS : endSeconds;
  return { minSeconds, durationSeconds: Math.min(totalSeconds, limit) };
}

/** 편집 대상 — 상태 훅의 레시피 중 구간 · 끝점 */
export interface RangeState {
  range: ClipRange;
  /** 실제 모드의 끝점. 없으면 목업이다 */
  endpoints?: EndpointRange;
}

/** 창 안 그 초에 보이는 프레임. 창 밖 · 목업이면 null(ClockedPlayback.pickAtS) */
export type PickAtS = (seconds: number) => EditorPick | null;

/** 몸짓의 S → 입력. 창 안이면 그 자리 고름, 밖이면 S 그대로 */
export function inputAtS(seconds: number, pickAtS: PickAtS): EdgeInput {
  const pick = pickAtS(seconds);
  return pick === null ? { kind: 's', s: seconds } : { kind: 'pick', pick };
}

/** 재생 위치 찍기 — 보이는 프레임(pickAtPlayhead)이 있으면 고름, 없으면(목업 · 창 없음) 그 초 */
export function playheadInput(pick: EditorPick | null, seconds: number): EdgeInput {
  return pick === null ? { kind: 's', s: seconds } : { kind: 'pick', pick };
}

/** 입력의 표시 초 */
function inputSeconds(input: EdgeInput, ctx: EndpointContext | null): number {
  if (input.kind === 's') return input.s;
  if (ctx === null) throw new Error('끝점 문맥 없이 고름을 받았다(목업은 고름이 없다)');
  return sOfPick(input.pick, ctx.heads, ctx.base);
}

function sameEndpoint(a: Endpoint, b: Endpoint): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** 목업 — 구간 초가 곧 값이라 길이(5초–3분)도 구간 초로 resolveRangeEdge 가 본다 */
function editMockEdge(
  edge: 'start' | 'end',
  seconds: number,
  current: RangeState,
  limits: EdgeLimits,
): RangeState | null {
  const result = resolveRangeEdge(edge, seconds, current.range, limits.durationSeconds);
  if (result.rejection !== null) return null;
  const same =
    result.range.startSeconds === current.range.startSeconds &&
    result.range.endSeconds === current.range.endSeconds;
  return same ? null : { range: result.range };
}

/**
 * 구간 끝 하나를 바꾼다. 실제 모드의 길이(5초–3분)는 저장과 같은 규칙의 ms 로 본다(rangeLengthRefusal) — 표시 초는 고름의
 * 프레임 시작이라 저장 값과 갈려, 화면이 받은 범위를 저장이 거절하거나 그 반대가 된다. 밖이면 바꾸지 않는다(null). 한계(왼쪽
 * 끝 · 오른쪽 끝) 밖으로 끈 입력은 한계 자리에서 다시 고른다(그 자리도 창 안이면 고름). 바뀐 것이 없으면 null
 */
export function editRangeEdge(
  edge: 'start' | 'end',
  input: EdgeInput,
  current: RangeState,
  limits: EdgeLimits,
  ctx: EndpointContext | null,
  pickAtS: PickAtS,
): RangeState | null {
  const resolved = input.kind === 's' ? inputAtS(input.s, pickAtS) : input;
  const raw = inputSeconds(resolved, ctx);
  const requested = Math.max(limits.minSeconds, raw);
  if (current.endpoints === undefined || ctx === null) {
    return editMockEdge(edge, requested, current, limits);
  }
  const clamped = raw < Math.max(0, limits.minSeconds) || raw > limits.durationSeconds;
  const edgeSeconds = clampEdgeSeconds(requested, limits.durationSeconds);
  const settled = clamped ? inputAtS(edgeSeconds, pickAtS) : resolved;
  const endpoint = endpointOf(settled, ctx.base);
  if (sameEndpoint(endpoint, current.endpoints[edge])) return null;
  const endpoints = { ...current.endpoints, [edge]: endpoint };
  if (rangeLengthRefusal(endpoints, ctx.heads) !== null) return null;
  return { range: displayRange(endpoints, ctx.heads, ctx.base), endpoints };
}

/** 끝점의 표시 구간을 레시피에 싣는다. 끝점이 없거나(목업) 구간이 그대로면 같은 객체를 돌려준다 */
export function withDisplayRange<T extends RangeState>(recipe: T, ctx: EndpointContext | null): T {
  if (recipe.endpoints === undefined || ctx === null) return recipe;
  const range = displayRange(recipe.endpoints, ctx.heads, ctx.base);
  const same =
    range.startSeconds === recipe.range.startSeconds &&
    range.endSeconds === recipe.range.endSeconds;
  return same ? recipe : { ...recipe, range };
}

/** 고른 썸네일 장면의 표시 초 */
export function thumbnailSecondsOf(choice: ThumbnailChoice, ctx: EndpointContext | null): number {
  return inputSeconds(choice, ctx);
}

/** 머리 캐시가 바뀔 때마다 오르는 수 — 고름 끝점의 표시 구간을 다시 그리게 한다. 캐시가 없으면(목업) 0 그대로 */
export function useHeadsVersion(heads: HeadCache | null): number {
  const [version, bump] = useReducer((n: number) => n + 1, 0);
  useEffect(() => (heads === null ? undefined : heads.subscribe(bump)), [heads]);
  return version;
}
