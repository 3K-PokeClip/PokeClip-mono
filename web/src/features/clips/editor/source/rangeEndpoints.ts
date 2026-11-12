import { CUT_MAX_MS, CUT_MIN_MS } from '@/api/clipEditor';
import {
  cutMsOf,
  sInputMs,
  sOfPick,
  type CutContext,
  type CutRefusal,
  type EdgeInput,
} from './cutPosition';
import type { HeadCache } from './fileHeadProbe';
import type { EditorPick } from './windowPlacement';

// 편집 범위 끝점 모델(POK-473 설계 prw2 3.5 · 계획 4절 P-4′ · 처분 PK1) — 상태 훅 밖 순수 모듈이다.
//   exact    카드 playerWindow · 레시피 · S 입력(창 밖 개요 끌기 · 화살표). 다시 셈하지 않는다 → 다시 열고 그대로 저장하면 항등
//   pick     창 안에서 고른 프레임. 값이 아니라 프레임을 들고, 저장할 때 savable 이 머리 · 다시 받은 목록으로 셈한다
//   estimate 칸 없는 카드의 지금 식. 화면에는 그리지만 저장 값이 아니다 — 사용자가 그 끝점을 한 번 정해야 바뀐다
// 머리 캐시는 seq 별로 바뀌지 않으므로 같은 고름은 언제 셈해도 같은 값이다. 그래서 실행취소로 돌아온 고름도, 머리가 늦게
// 온 고름도 따로 생애를 두지 않는다.

export type Endpoint =
  | { kind: 'exact'; ms: number }
  | { kind: 'pick'; pick: EditorPick }
  | { kind: 'estimate'; ms: number };

/** 편집 범위 — 실행취소는 이 값을 통째로 되돌린다. 칸 이름은 상태 훅의 edge('start' | 'end')와 같다 */
export interface EndpointRange {
  start: Endpoint;
  end: Endpoint;
}

/** 저장을 막는 까닭 — 끝점에서 나오는 다섯(estimate · CutRefusal 넷)과 길이 둘 */
export type SaveRefusal = 'estimate' | CutRefusal | 'tooShort' | 'tooLong';

export type SaveVerdict =
  { ok: true; inMs: number; outMs: number } | { ok: false; reason: SaveRefusal };

/** 몸짓 입력 → 끝점. S 입력은 그 자리에서 값으로 굳히고(exact), 고름은 그대로 든다 */
export function endpointOf(input: EdgeInput, base: number): Endpoint {
  return input.kind === 's'
    ? { kind: 'exact', ms: sInputMs(input.s, base) }
    : { kind: 'pick', pick: input.pick };
}

/** 끝점의 표시 초 S — 고름은 고름이 든 사실과 머리 캐시로 그린다(머리 전이면 근사 자리) */
export function displaySeconds(endpoint: Endpoint, heads: HeadCache, base: number): number {
  return endpoint.kind === 'pick'
    ? sOfPick(endpoint.pick, heads, base)
    : (endpoint.ms - base) / 1000;
}

function endpointMs(
  endpoint: Endpoint,
  edge: 'start' | 'end',
  ctx: CutContext,
): number | 'estimate' | CutRefusal {
  switch (endpoint.kind) {
    case 'exact':
      return endpoint.ms;
    case 'estimate':
      return 'estimate';
    case 'pick':
      return cutMsOf({ kind: 'pick', pick: endpoint.pick }, edge, ctx);
  }
}

/**
 * 저장 가능 판정과 저장 값. 끝점은 시작 · 끝 차례로 보고 처음 막힌 까닭을 낸다. 길이(5–180초)는 S 축 out − in(계약6 2절
 * 사용자 길이)으로 본다 — 몰래 자르지 않는다
 */
export function savable(range: EndpointRange, ctx: CutContext): SaveVerdict {
  const inMs = endpointMs(range.start, 'start', ctx);
  if (typeof inMs === 'string') return { ok: false, reason: inMs };
  const outMs = endpointMs(range.end, 'end', ctx);
  if (typeof outMs === 'string') return { ok: false, reason: outMs };
  const length = outMs - inMs;
  if (length < CUT_MIN_MS) return { ok: false, reason: 'tooShort' };
  if (length > CUT_MAX_MS) return { ok: false, reason: 'tooLong' };
  return { ok: true, inMs, outMs };
}
