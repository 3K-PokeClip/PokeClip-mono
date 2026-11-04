import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { holeGuard, relistGuard, revisitGuard, step } from './dvr404TestKit';
import { nextSegment, pos, startRun, streamPoll } from './handoffTestKit';
import {
  SESSION_NOTICES,
  openSession,
  type DvrSegment,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';

// 목록 다시 받기 상태표 시험의 차림(POK-528 c7 — 계획 8-1). 사건은 모두 감싸개 step 을
// 거치고(전이마다 불변식 단언 — 계획 8-0), 사건열 하나(feed 한 번)마다 다시 받기 ·
// 재방문 계수기를 새로 건다. 제품 모듈은 옛 판에도 있는 것만 읽는다 — 제품 파일을 되돌린
// 트리(음성 대조)에서도 이 파일이 로드되게.
//
// 시각은 epoch ms 다. 되감기 목록 A1 의 첫 조각 T0 · 끝 NOW · 맨 앞 조각 셋의 길이 12초
// (창 하한 여유) · 응답은 R 에 받는다. 방송 A(회차 r-A)를 보던 세션이 기준이다.

export const MIN = 60_000;
export const T0 = Date.parse('2026-10-25T05:00:00.000Z');
export const NOW = T0 + 60 * MIN;
export const R = NOW + 1_000;
export const MARGIN_MS = 12_000;
/** 보던 자리 — 목록 끝에서 멀어 넘김 띠 밖이다 */
export const P20 = T0 + 20 * MIN;
/** 라이브에서 본 자리(라이브 끝 근처) */
export const LIVE_AT = NOW - 5_000;
/** 라이브에서 되감아 청한 자리(58분 전) */
export const ASKED = T0 + 2 * MIN;
export const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
export const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
export const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
export const VOD = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
export const X_A: XAnswer = { kind: 'known', id: 'r-A' };
export const X_B: XAnswer = { kind: 'known', id: 'r-B' };
export const X_UNKNOWN: XAnswer = { kind: 'unknown' };
export const DROP: SessionEffect = { kind: 'drop-pause-mark' };
export const OLDEST: SessionEffect = { kind: 'notice', text: SESSION_NOTICES.oldest };
export const HOLD: SessionEffect = { kind: 'hold-frame' };
export const REFRESH_A1: SessionEffect = { kind: 'refresh', retryWhileUrl: A1 };
export const ENDLIST: SessionEvent = { type: 'endlist' };
export const END_REACHED: SessionEvent = { type: 'end-reached' };

export function status(patch: Partial<RewindStatus>): RewindStatusResult {
  return {
    kind: 'ok',
    status: { ready: false, mode: null, url: null, firstPdtMs: null, headPdtMs: null, ...patch },
  };
}

/** 방송 중 되감기 목록 하나의 답 — 기본 범위는 첫 조각 firstPdtMs · 끝 NOW + 1분 */
export function liveReady(
  url: string,
  firstPdtMs: number,
  headPdtMs = NOW + MIN,
): RewindStatusResult {
  return status({ ready: true, mode: 'live', url, firstPdtMs, headPdtMs });
}

export function vodReady(firstPdtMs = T0, headPdtMs = NOW): RewindStatusResult {
  return status({ ready: true, mode: 'vod', url: VOD, firstPdtMs, headPdtMs });
}

export function poll(result: RewindStatusResult, x: XAnswer = X_A): SessionEvent {
  return streamPoll(result, x, R);
}

/** 목록 로드 — cut 은 재생기가 첫 목록에서 창 하한으로 잘랐는가(없으면 칸 없음) */
export function loaded(url: string, cut?: boolean, firstMs = T0, lastEndMs = NOW): SessionEvent {
  const list: SessionEvent = {
    type: 'list-loaded',
    url,
    firstMs,
    lastEndMs,
    targetDurationS: 4,
    headSpanMs: MARGIN_MS,
  };
  return cut === undefined ? list : { ...list, startFloorCut: cut };
}

/**
 * 지금 되감기 재생기(지금 세대)의 /dvr 미디어 조각 404 — 조각을 적지 않으면 부를 때마다 다른
 * 조각이다(nextSegment)
 */
export function gone(
  state: PlaybackSessionState,
  generation = state.source.generation,
  segment: DvrSegment = nextSegment(),
): SessionEvent {
  return { type: 'dvr-segment-gone', generation, segment };
}

export interface Fed {
  state: PlaybackSessionState;
  effects: SessionEffect[];
}

/**
 * 사건을 차례로 감싸개로 먹이고 마지막 상태와 모든 효과를 돌려준다. 사건열마다 다시 받기 ·
 * 재방문 · 구멍 계수기를 새로 건다(계획 3-1 · POK-528 후속)
 */
export function feed(start: PlaybackSessionState, events: readonly SessionEvent[], now = R): Fed {
  const guards = [relistGuard(), revisitGuard(), holeGuard()];
  let state = start;
  const effects: SessionEffect[] = [];
  for (const event of events) {
    const next = step(state, event, now, guards);
    state = next.state;
    effects.push(...next.effects);
  }
  return { state, effects };
}

export function kinds(effects: readonly SessionEffect[]): string[] {
  return effects.map((effect) => effect.kind);
}

export function notices(effects: readonly SessionEffect[]): string[] {
  return effects.flatMap((effect) => (effect.kind === 'notice' ? [effect.text] : []));
}

export function fresh(): PlaybackSessionState {
  startRun();
  return openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
}

/**
 * 라이브에서 A1 의 p 로 되감은 세션 — 목록(첫 조각 T0 · 끝 NOW)을 로드했고, atMs 가 있으면
 * 그 자리의 위치 사건을 먹였다(확인된 위치). 없으면 첫 위치 전이다
 */
export function rewindAt(atMs: number | null, p = P20): PlaybackSessionState {
  const events: SessionEvent[] = [
    poll(liveReady(A1, T0, NOW)),
    { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
    loaded(A1),
  ];
  if (atMs !== null) events.push(pos(atMs));
  return feed(fresh(), events).state;
}

/**
 * 라이브 위치 LIVE_AT 를 남긴 채 ASKED 로 되감은 세션 — 되감기 소스의 첫 위치 전이라
 * lastAtMs 는 앞 소스(라이브)의 자리다(cx 재현 차림)
 */
export function rewindFromLive(): PlaybackSessionState {
  return feed(fresh(), [
    poll(liveReady(A1, T0, NOW)),
    pos(LIVE_AT),
    { type: 'seek', targetMs: ASKED, view: { lowerMs: NOW - 16_000, endMs: NOW } },
    loaded(A1),
  ]).state;
}

/** 다시 받는 되감기 국면의 기대 꼴 */
export function relistPhase(
  stage: 'list' | 'paint',
  noticed = false,
  pendingNext: { url: string; firstPdtMs: number; headPdtMs: number } | null = null,
) {
  return { kind: 'rewind', pendingNext, relist: { stage, noticed } };
}
