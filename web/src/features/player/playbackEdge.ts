import { mediaTimeAt, programDateAt, type DatedFragment } from './dvrWindow';
import { REWIND_UI_MAX_SECONDS } from './playerMath';
import type { PlaybackSource, SeekView } from './rewindSwitch';

// 재생기가 로드한 목록의 가장자리 판정(설계 5.3.1 · 5.4절, POK-200 M5). 순수 함수라 jsdom 없이 시험한다.
// hls.js synchronizeToLiveEdge 는 ENDLIST 없는 목록이 갱신될 때마다 돌아, 재생점이 창 밖이면 목록 끝으로 옮긴다. 그래서 창
// 하한에 여유를 둔다. 라이브는 맨 앞 조각 셋이다: 시동은 목록 받기 · 조각 받기 · 디코더 시동이고 창은 조각 하나마다 밀리므로,
// 1 조각 여유로는 시동 한 번에 창 밖이 될 수 있다(라이브 시작 하한 · POK-200 c13). 방송 중 되감기는 첫 내용 조각 하나다 —
// 재생점이 첫 조각 앞이 되면 hls.js 보다 먼저 창 하한 지키기(rewindFloor)가 옮긴다(POK-573). 끝난 목록(ENDLIST) · 다시보기는
// 창이 밀리지 않아 여유가 없다. 여유는 TD 가 아니라 그 조각들의 실제 길이로 센다 — gohlslib 은 TD 를 지금까지 본 조각 최대값으로 올리기만
// 해(첫 조각 5.9초면 방송 끝까지 TD 6) 실제 조각(4초)보다 클 수 있다.

/** 라이브 창 하한 여유 = 목록 맨 앞 조각 이 개수의 길이 합(라이브 시작 하한 · headSpanMs) */
export const EDGE_MARGIN_SEGMENTS = 3;
/** 방송 중 되감기 창 하한 여유 = 첫 내용 조각부터 이 개수의 길이 합(rewindHeadSpanMs · 창 하한 지키기) */
export const REWIND_MARGIN_SEGMENTS = 1;
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
  /**
   * 마지막 조각의 시작(epoch ms). 만드는 쪽 loadedList 는 늘 채운다 — 선택인 까닭은 다른 소비자 리터럴을 건드리지 않으려는
   * 것이다
   */
  lastStartMs?: number;
  targetDurationS: number;
  /** 맨 앞 조각 EDGE_MARGIN_SEGMENTS 개(모자라면 있는 만큼)의 EXTINF 합(ms) — 라이브 창 하한 여유 */
  headSpanMs: number;
  /**
   * 첫 내용 조각(맨 앞 EXT-X-GAP 을 뺀 첫 조각)의 시각(epoch ms) — 되감기 · 다시보기 하한의 기준. 모두 GAP 이면 firstMs.
   * 만드는 쪽 loadedList 는 늘 채운다 — 선택인 까닭은 lastStartMs 와 같다. 없으면 firstMs 로 읽는다
   */
  contentFirstMs?: number;
  /**
   * 첫 내용 조각부터 REWIND_MARGIN_SEGMENTS 개(모자라면 있는 만큼)의 EXTINF 합(ms) — 방송 중 되감기 창 하한 여유. 모두 GAP
   * 이면 그 개수 × TD(rewindFloor 와 같은 셈). 만드는 쪽 loadedList 는 늘 채운다 — 없으면 headSpanMs 로 읽는다
   */
  rewindHeadSpanMs?: number;
}

/** 조각 길이 합(ms) */
function spanMs(fragments: readonly DatedFragment[]): number {
  return fragments.reduce((sum, frag) => sum + frag.duration * 1000, 0);
}

export function loadedList(
  fragments: readonly DatedFragment[],
  targetDurationS: number,
): LoadedList | null {
  const first = fragments[0];
  const last = fragments[fragments.length - 1];
  if (first === undefined || last === undefined) return null;
  if (first.programDateTime === null || last.programDateTime === null) return null;
  const content = liveContent(fragments);
  const contentFirst = content[0];
  return {
    firstMs: first.programDateTime,
    lastEndMs: last.programDateTime + last.duration * 1000,
    lastStartMs: last.programDateTime,
    targetDurationS,
    headSpanMs: spanMs(fragments.slice(0, EDGE_MARGIN_SEGMENTS)),
    contentFirstMs: contentFirst?.programDateTime ?? first.programDateTime,
    rewindHeadSpanMs:
      contentFirst === undefined
        ? REWIND_MARGIN_SEGMENTS * targetDurationS * 1000
        : spanMs(content.slice(0, REWIND_MARGIN_SEGMENTS)),
  };
}

/**
 * 창 하한 여유(ms). 라이브는 맨 앞 조각 셋 · 방송 중(live — hls.js details.live) 되감기는 첫 내용 조각 하나다. 끝난 되감기
 * 목록(ENDLIST)과 vod 는 창이 밀리지 않아 0 이다
 */
export function edgeMarginMs(
  list: LoadedList,
  kind: PlaybackSource['kind'],
  live: boolean,
): number {
  if (kind === 'live') return list.headSpanMs;
  if (kind === 'vod' || !live) return 0;
  return list.rewindHeadSpanMs ?? list.headSpanMs;
}

/** 창 하한(epoch ms) = 기준 조각 시작 + 여유. 기준은 라이브가 첫 조각, 되감기 · 다시보기가 첫 내용 조각이다 */
export function windowLowerMs(
  list: LoadedList,
  kind: PlaybackSource['kind'],
  live: boolean,
): number {
  const baseMs = kind === 'live' ? list.firstMs : (list.contentFirstMs ?? list.firstMs);
  return baseMs + edgeMarginMs(list, kind, live);
}

/** 첫 목록의 시작 — position = startLoad 할 영상 초 · floorCut = 창 하한으로 잘랐다 */
export interface StartCut {
  position: number;
  floorCut: boolean;
}

/**
 * 첫 목록에서 startLoad 할 영상 초와 그것이 창 하한으로 자른 자리인가(POK-528 — 세션의 하한 알림 판정 재료).
 * 실제 시각 startAtMs 를 창 하한 이상 · 목록 끝 이하로 자른다(5.4절 시작 위치). 영상 시각으로 견주므로 PDT 틈 ·
 * 겹침에서도 재생기가 실제로 자른 것과 같다. 하한과 같으면 잘리지 않았다. 시각 표시가 없으면 null — 부르는 쪽이
 * 기본 시작으로 둔다. 첫 목록은 아직 해체한 조각이 없어 판 2 조각도 시작 · 길이가 hls.js 첫 값과 같다
 * (datedFragments 의 해체 전 갈래)
 */
export function startCut(
  fragments: readonly DatedFragment[],
  startAtMs: number,
  kind: PlaybackSource['kind'],
  targetDurationS: number,
  live: boolean,
): StartCut | null {
  const list = loadedList(fragments, targetDurationS);
  const first = fragments[0];
  const last = fragments[fragments.length - 1];
  if (list === null || first === undefined || last === undefined) return null;
  const target = mediaTimeAt(fragments, startAtMs);
  if (target === null) return null;
  const base = kind === 'live' ? first : (liveContent(fragments)[0] ?? first);
  const lower = base.start + edgeMarginMs(list, kind, live) / 1000;
  const end = last.start + last.duration;
  return { position: Math.min(end, Math.max(lower, target)), floorCut: target < lower };
}

/** 첫 목록에서 startLoad 할 영상 초(startCut 의 자리). 시각 표시가 없으면 null */
export function startLoadPosition(
  fragments: readonly DatedFragment[],
  startAtMs: number,
  kind: PlaybackSource['kind'],
  targetDurationS: number,
  live: boolean,
): number | null {
  return startCut(fragments, startAtMs, kind, targetDurationS, live)?.position ?? null;
}

/**
 * 라이브 시작 하한(epoch ms, POK-200 c13 · S10) — 라이브 목록에서 시작해도 창 밖으로 밀리지 않는 가장 이른 시각이다. 창
 * 하한(windowLowerMs)과 같은 식을 맨 앞 GAP 을 뺀 조각에 건다(liveContent). 탐침과 첫 목록 재검사가 이 하한 하나를
 * 쓴다. 시각 표시가 없거나 모두 GAP 이면 null
 */
export function liveStartFloorMs(
  fragments: readonly DatedFragment[],
  targetDurationS: number,
): number | null {
  const list = loadedList(liveContent(fragments), targetDurationS);
  return list === null ? null : windowLowerMs(list, 'live', true);
}

/**
 * 맨 앞 EXT-X-GAP 조각을 뺀 조각 — 재송출 직후 MediaMTX 라이브 목록은 앞이 GAP 으로 차 있어(M5b 2026-10-30 · 약
 * 36초), 그것을 세면 하한이 내용 앞에 선다. 가운데 GAP 은 창이 밀리는 조각이라 그대로 둔다
 */
function liveContent(fragments: readonly DatedFragment[]): readonly DatedFragment[] {
  const first = fragments.findIndex((frag) => !frag.gap);
  if (first < 0) return [];
  return first === 0 ? fragments : fragments.slice(first);
}

/**
 * 시작 위치 startAtMs 가 창 하한보다 앞이라 startLoadPosition 이 하한으로 자를 것인가 — 못 본 녹화가 잘린다. 하한과 같으면
 * 잘리지 않는다. live 는 라이브 시작 하한(liveStartFloorMs — 맨 앞 GAP 을 뺀다)으로 잰다. 시각 표시가 없거나 모두 GAP
 * 이면 판정할 수 없어 false 다(startLoadPosition 도 null 이거나 기본 시작으로 둔다)
 */
export function startIsTruncated(
  fragments: readonly DatedFragment[],
  startAtMs: number,
  kind: PlaybackSource['kind'],
  targetDurationS: number,
): boolean {
  if (kind === 'live') {
    const floor = liveStartFloorMs(fragments, targetDurationS);
    return floor !== null && startAtMs < floor;
  }
  // 비라이브 갈래는 호출자가 없다 — 방송 중 하한으로 잰다
  const list = loadedList(fragments, targetDurationS);
  return list !== null && startAtMs < windowLowerMs(list, kind, true);
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
    // 축 입력에 방송 중 여부가 아직 없어 방송 중 하한으로 둔다
    view: {
      lowerMs: windowLowerMs(list, kind, true),
      endMs: kind === 'live' ? edgeMs : list.lastEndMs,
    },
    windowSeconds: clampSeconds(edgeMs - lower),
    behindSeconds: atMs === null ? 0 : clampSeconds(edgeMs - atMs),
  };
}
