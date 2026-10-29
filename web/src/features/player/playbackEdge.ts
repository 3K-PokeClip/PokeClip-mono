import { mediaTimeAt, programDateAt, type DatedFragment } from './dvrWindow';
import { REWIND_UI_MAX_SECONDS } from './playerMath';
import type { PlaybackSource, SeekView } from './rewindSwitch';

// 재생기가 로드한 목록의 가장자리 판정(설계 5.3.1 · 5.4절, POK-200 M5). 순수 함수라 jsdom 없이 시험한다.
// hls.js synchronizeToLiveEdge 는 ENDLIST 없는 목록이 갱신될 때마다 돌아, 재생점이 창 밖이면 목록 끝으로 옮긴다. 되감기 목록도
// ENDLIST 없는 미닫이 목록이라 같은 일이 생긴다. 그래서 창 하한에 여유(맨 앞 조각 셋)를 둔다: 시동은 목록 받기 · 조각
// 받기 · 디코더 시동이고 창은 조각 하나마다 밀리므로, 1 조각 여유로는 시동 한 번에 창 밖이 될 수 있다. 여유는 TD 가 아니라
// 그 조각들의 실제 길이로 센다 — gohlslib 은 TD 를 지금까지 본 조각 최대값으로 올리기만 해(첫 조각 5.9초면 방송 끝까지 TD 6)
// 실제 조각(4초)보다 클 수 있다.

/** 창 하한 여유 = 목록 맨 앞 조각 이 개수의 길이 합 */
export const EDGE_MARGIN_SEGMENTS = 3;
/** 끝 정체 판정의 「목록이 이 값 × TD 넘게 자라지 않음」 — 목록 갱신 주기라 TD 로 센다 */
const END_STALL_TD = 3;
/** 끝 정체 판정의 「끝 1초 안」 */
const END_SLACK_MS = 1_000;

/** 로드한 목록의 실제 시각 범위 */
export interface LoadedList {
  /** 첫 조각의 시각(epoch ms) */
  firstMs: number;
  /** 마지막 조각의 끝(epoch ms) — E */
  lastEndMs: number;
  targetDurationS: number;
  /** 맨 앞 조각 EDGE_MARGIN_SEGMENTS 개(모자라면 있는 만큼)의 EXTINF 합(ms) — 창 하한 여유 */
  headSpanMs: number;
}

export function loadedList(
  fragments: readonly DatedFragment[],
  targetDurationS: number,
): LoadedList | null {
  const first = fragments[0];
  const last = fragments[fragments.length - 1];
  if (first === undefined || last === undefined) return null;
  if (first.programDateTime === null || last.programDateTime === null) return null;
  return {
    firstMs: first.programDateTime,
    lastEndMs: last.programDateTime + last.duration * 1000,
    targetDurationS,
    headSpanMs: fragments
      .slice(0, EDGE_MARGIN_SEGMENTS)
      .reduce((sum, frag) => sum + frag.duration * 1000, 0),
  };
}

/** 창 하한 여유(ms) — vod 는 ENDLIST 문서라 창이 밀리지 않아 0 이다 */
export function edgeMarginMs(list: LoadedList, kind: PlaybackSource['kind']): number {
  return kind === 'vod' ? 0 : list.headSpanMs;
}

/** 창 하한(epoch ms) = 첫 조각 시작 + 여유 */
export function windowLowerMs(list: LoadedList, kind: PlaybackSource['kind']): number {
  return list.firstMs + edgeMarginMs(list, kind);
}

/**
 * 첫 목록에서 startLoad 할 영상 초. 실제 시각 startAtMs 를 창 하한 이상 · 목록 끝 이하로 자른다(5.4절 시작 위치).
 * 시각 표시가 없으면 null — 부르는 쪽이 기본 시작으로 둔다
 */
export function startLoadPosition(
  fragments: readonly DatedFragment[],
  startAtMs: number,
  kind: PlaybackSource['kind'],
  targetDurationS: number,
): number | null {
  const list = loadedList(fragments, targetDurationS);
  const first = fragments[0];
  const last = fragments[fragments.length - 1];
  if (list === null || first === undefined || last === undefined) return null;
  const target = mediaTimeAt(fragments, startAtMs);
  if (target === null) return null;
  const lower = first.start + edgeMarginMs(list, kind) / 1000;
  const end = last.start + last.duration;
  return Math.min(end, Math.max(lower, target));
}

/**
 * 라이브 시작 하한(epoch ms, POK-200 c13 · S10) — 라이브 목록에서 시작해도 창 밖으로 밀리지 않는 가장 이른 시각이다. 창
 * 하한(windowLowerMs)과 같은 식 하나라, 탐침과 첫 목록 재검사가 같은 하한을 쓴다. 시각 표시가 없으면 null
 */
export function liveStartFloorMs(
  fragments: readonly DatedFragment[],
  targetDurationS: number,
): number | null {
  const list = loadedList(fragments, targetDurationS);
  return list === null ? null : windowLowerMs(list, 'live');
}

/**
 * 시작 위치 startAtMs 가 창 하한보다 앞이라 startLoadPosition 이 하한으로 자를 것인가 — 못 본 녹화가 잘린다. 하한과 같으면
 * 잘리지 않는다. 시각 표시가 없으면 판정할 수 없어 false 다(startLoadPosition 도 null 이라 기본 시작으로 둔다)
 */
export function startIsTruncated(
  fragments: readonly DatedFragment[],
  startAtMs: number,
  kind: PlaybackSource['kind'],
  targetDurationS: number,
): boolean {
  const list = loadedList(fragments, targetDurationS);
  return list !== null && startAtMs < windowLowerMs(list, kind);
}

/**
 * 목록 끝 정체(D9) — 재생 위치가 E 1초 안 ∧ waiting ∧ 목록이 3 × TD 넘게 자라지 않음. `ended` 이벤트와 함께 「끝 도달」이다.
 * ENDLIST 없는 목록의 회차 경계를 이것이 맡는다
 */
export function endStalled(input: {
  list: LoadedList;
  atMs: number;
  waiting: boolean;
  /** 목록이 마지막으로 자란 뒤 흐른 ms */
  sinceGrowMs: number;
}): boolean {
  const { list, atMs, waiting, sinceGrowMs } = input;
  return (
    waiting &&
    atMs >= list.lastEndMs - END_SLACK_MS &&
    sinceGrowMs > END_STALL_TD * list.targetDurationS * 1000
  );
}

export interface SessionAxisInput {
  kind: PlaybackSource['kind'];
  fragments: readonly DatedFragment[];
  targetDurationS: number;
  /** 재생 위치(영상 초) */
  currentTime: number;
  /** 라이브 재생기가 서는 라이브 끝(영상 초). 라이브 재생기만 쓴다 — 모르면 null */
  liveEdgeTime: number | null;
  nowMs: number;
  /** 라이브 재생기에서 마지막으로 잰 「지금 − 라이브 끝」 */
  lagMs: number;
  /** 시크바 왼쪽 끝(되감기 범위 첫 시각). 모르면 로드한 목록 첫 시각 */
  axisLowerMs: number | null;
}

export interface SessionAxis {
  list: LoadedList;
  /** 재생 위치의 실제 시각. 시각 표시가 없으면 null */
  atMs: number | null;
  /** 시크바 오른쪽 끝의 실제 시각 — 라이브 끝(되감기 재생기는 「지금 − 라이브 지연」, vod 는 문서 끝) */
  edgeMs: number;
  /** 세션에 넘기는 시크 범위 — 창 하한과 로드한 목록의 끝 */
  view: SeekView;
  windowSeconds: number;
  behindSeconds: number;
}

function clampSeconds(ms: number): number {
  return Math.min(REWIND_UI_MAX_SECONDS, Math.max(0, Math.round(ms / 1000)));
}

/**
 * 세 재생기(라이브 · 되감기 · 다시보기)가 같은 시크바를 쓰도록 실제 시각 축으로 센다(설계 5.2절). 시차는 라이브 끝에서 몇 초
 * 뒤인가이고 폭은 시크바 왼쪽 끝부터 라이브 끝까지다(표시 상한 1시간). 라이브 끝을 모르면 null
 */
export function sessionAxis(input: SessionAxisInput): SessionAxis | null {
  const { kind, fragments } = input;
  const list = loadedList(fragments, input.targetDurationS);
  if (list === null) return null;
  let edgeMs: number;
  if (kind === 'live') {
    const edge = input.liveEdgeTime === null ? null : programDateAt(fragments, input.liveEdgeTime);
    if (edge === null) return null;
    edgeMs = edge;
  } else {
    edgeMs = kind === 'rewind' ? input.nowMs - input.lagMs : list.lastEndMs;
  }
  const atMs = programDateAt(fragments, input.currentTime);
  const lower =
    kind === 'vod' ? list.firstMs : Math.min(input.axisLowerMs ?? list.firstMs, list.firstMs);
  return {
    list,
    atMs,
    edgeMs,
    view: { lowerMs: windowLowerMs(list, kind), endMs: kind === 'live' ? edgeMs : list.lastEndMs },
    windowSeconds: clampSeconds(edgeMs - lower),
    behindSeconds: atMs === null ? 0 : clampSeconds(edgeMs - atMs),
  };
}
