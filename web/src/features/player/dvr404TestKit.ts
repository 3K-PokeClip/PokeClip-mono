import { expect } from 'vitest';
import { stamped } from './handoffTestKit';
import {
  transition,
  type DvrSegment,
  type PlaybackSessionState,
  type SessionEvent,
  type Transition,
} from './rewindSwitch';

// 되감기 조각 404 시험의 전이 감싸개(POK-528 계획 8-0). 모든 전이 직후 상태
// 불변식(계획 3-1 의 I1–I3 · 막힘 몫)과 전이 불변식(T1–T6 — 「새 재생기를 세우지
// 않음」 이 풀리면 세대가 오르거나 세션이 닫힌다 · 다시 받기의 시작 · 이어 볼 자리
// 보존 · T7–T9 — 목록 안 구멍의 기억, POK-528 후속)을 단언한다. 막힘은 다음 사건에서 닫혀 끝 상태에 흔적이 남지 않을 수 있어,
// 끝 상태만 보는 단언으로는 중간의 위반을 못 잡는다.
//
// 제품 모듈에서는 옛 판에도 있는 transition 과 타입만 읽는다 — 제품 파일을 되돌린
// 트리(음성 대조)에서도 이 파일이 로드되게. 그래서 「세우지 않음」 과 「이어 볼
// 자리」 는 제품 함수(isPlayerStartHeld · resumeAtMs)를 부르지 않고 계획 3-2 ·
// 3-6 의 정의를 여기 옮겨 둔다. 이 사본과 제품 함수가 같다는 보장은 없다 —
// rewindSwitch.dvr404*.test.ts 의 국면별 표와 sessionShared.test.ts 의 이어 볼 자리
// 표가 고른 상태에서만 제품 함수를 대조한다. 그래서 정의를 바꿀 때는 둘을 함께 고친다.
// 구멍 기억(dvrSegmentKey · dvrHoles)도 같다 — 키 꼴과 읽기를 여기 옮겨 두고 옛 판
// 상태(칸 없음)는 빈 기억으로 읽는다.

/** 계획 3-2 의 「목록 하한에서」 — 자리를 모를 때 다시 받기의 시작(자리로 읽지 않는다) */
const FROM_FLOOR_MS = 0;
/** relistGuard 가 모든 url 의 수를 0 으로 돌리는 사람 사건 */
const HUMAN_EVENTS: ReadonlySet<SessionEvent['type']> = new Set(['seek', 'live-button', 'button']);

/**
 * 계획 3-6 의 「새로 서는 재생기는 hls.js 를 만들지 않는다」 — 막힌 끝남 대기 ·
 * 마지막 확인 중인 끝남 대기(finalCheck). 목록을 다시 받는 되감기는 세운다
 */
function startHeldBySpec(state: PlaybackSessionState): boolean {
  const { phase } = state;
  return phase.kind === 'ending-wait' && (phase.blocked === true || phase.finalCheck === true);
}

/** 다시 받는 중인 되감기의 칸 — 아니면 undefined(옛 판 상태에는 늘 없다) */
function relistOf(state: PlaybackSessionState) {
  return state.phase.kind === 'rewind' ? state.phase.relist : undefined;
}

/** 구멍 기억의 키 — sessionShared 의 dvrSegmentKey 사본(소리 · 영상 목록의 sn 이 겹칠 수 있어 type 을 함께) */
function holeKeyBySpec(segment: DvrSegment): string {
  return `${segment.type}:${segment.sn}`;
}

/** 그 url 의 구멍 기억. 없으면(옛 판 상태 · 기록 없음) 빈 집합 */
function holesBySpec(state: PlaybackSessionState, url: string): ReadonlySet<string> {
  return state.dvrHoles?.get(url) ?? new Set();
}

/** 다시 받기에 들어간 전이인가 — 앞에는 relist 가 없고 뒤에는 있다 */
function enteredRelist(before: PlaybackSessionState, after: PlaybackSessionState): boolean {
  return relistOf(before) === undefined && relistOf(after) !== undefined;
}

/** 계획 3-2 의 확인된 위치 — 지금 소스 세대가 낸 마지막 위치. 아니면 null */
function confirmedBySpec(state: PlaybackSessionState): number | null {
  return state.positionGeneration === state.source.generation ? state.lastAtMs : null;
}

/**
 * 계획 3-2 의 이어 볼 자리 — 되감기 · 다시보기 소스는 확인된 위치 → 끝나지 않은 시크
 * → 이 소스의 시작 요청(「목록 하한에서」 는 모름), 라이브 소스는 lastAtMs. 모르면 null
 * (다시보기 갈래 — c7 리뷰 V7-1)
 */
export function resumeBySpec(state: PlaybackSessionState): number | null {
  if (state.source.kind === 'live') return state.lastAtMs;
  const start = state.source.startAtMs;
  const requested = start === FROM_FLOOR_MS ? null : start;
  return confirmedBySpec(state) ?? state.pendingSeekMs ?? requested;
}

/** 계획 3-2 의 같은 url 로 다시 세울 시작 — 모르면 목록 하한에서 */
export function restartBySpec(state: PlaybackSessionState): number {
  return resumeBySpec(state) ?? FROM_FLOOR_MS;
}

/**
 * 상태 불변식(계획 3-1) — 막힌 끝남 대기는 녹화로 정해지지 않았고, 마지막 확인은 녹화로
 * 정한 뒤에만 서며 막힘과 겹치지 않는다. 둘 다 되감기 재생기의 것이다. 다시 받는
 * 되감기에는 확인된 위치가 없고(I2), 끝나지 않은 시크가 있으면 위치 칸이 비었다(I3)
 */
export function assertPhaseInvariants(state: PlaybackSessionState): void {
  const relist = relistOf(state);
  if (relist !== undefined) {
    expect(state.source.kind, 'I1 relist ⇒ source.kind rewind').toBe('rewind');
    expect(state.positionGeneration, 'I2 relist ⇒ 확인된 위치 없음').not.toBe(
      state.source.generation,
    );
  }
  if (state.pendingSeekMs !== null && state.pendingSeekMs !== undefined) {
    expect(state.positionGeneration, 'I3 pendingSeekMs ⇒ positionGeneration null').toBeNull();
  }
  const { phase } = state;
  if (phase.kind !== 'ending-wait') return;
  const blocked = phase.blocked === true;
  const finalCheck = phase.finalCheck === true;
  if (finalCheck) {
    expect(phase.closeOnEnd, 'finalCheck ⇒ closeOnEnd').toBe(true);
    expect(blocked, 'finalCheck ⇒ ¬blocked').toBe(false);
  }
  if (blocked) expect(phase.closeOnEnd, 'blocked ⇒ ¬closeOnEnd').toBe(false);
  if (blocked || finalCheck) {
    expect(state.source.kind, 'blocked ∨ finalCheck ⇒ source.kind rewind').toBe('rewind');
  }
}

/** 지금 시대(앞 상태의 positionEpoch)에 잰 위치 사건인가 */
function isCurrentPosition(before: PlaybackSessionState, event: SessionEvent): boolean {
  return event.type === 'position' && event.epoch === before.positionEpoch;
}

/** T2 · T3 · T6 — 다시 받기 · 같은 url 다시 세우기 · 리마운트(되감기 · 다시보기)의 시작 */
function assertRestart(
  before: PlaybackSessionState,
  event: SessionEvent,
  after: PlaybackSessionState,
): void {
  const { source } = after;
  if (relistOf(before) === undefined && relistOf(after) !== undefined) {
    expect(source.url, 'T2 다시 받기 ⇒ url 그대로').toBe(before.source.url);
    expect(source.generation, 'T2 다시 받기 ⇒ 세대 + 1').toBe(before.source.generation + 1);
    expect(source.startAtMs, 'T2 다시 받기 ⇒ 시작 = restartAtMs(앞)').toBe(restartBySpec(before));
  }
  const sameUrlAgain =
    source.kind === 'rewind' &&
    source.url === before.source.url &&
    source.generation > before.source.generation;
  if (sameUrlAgain) expect(source.startAtMs, 'T3 같은 url 다시 세움 ⇒ 시작 있음').not.toBeNull();
  if (event.type !== 'remount' || source.kind !== before.source.kind) return;
  if (source.kind === 'rewind') {
    expect(source.startAtMs, 'T6 리마운트 ⇒ 시작 = restartAtMs(앞)').toBe(restartBySpec(before));
  }
  if (source.kind === 'vod') {
    expect(source.startAtMs, 'T6 vod 리마운트 ⇒ 시작 = resumeAtMs(앞)').toBe(resumeBySpec(before));
  }
}

/** T4 · T5 — 폴링 교체의 시작과 이어 볼 자리의 보존 */
function assertResume(before: PlaybackSessionState, event: SessionEvent, next: Transition): void {
  const after = next.state;
  const swappedByPoll =
    event.type === 'stream-poll' &&
    before.phase.kind === 'rewind' &&
    after.source.url !== before.source.url;
  if (swappedByPoll) {
    expect(after.source.startAtMs, 'T4 폴링 교체 ⇒ 시작 = resumeAtMs(앞)').toBe(
      resumeBySpec(before),
    );
    expect(before.roundRewindUrls.has(after.source.url), 'T4 폴링 교체 ⇒ 처음 보는 url').toBe(
      false,
    );
  }
  const sameGeneration = after.source.generation === before.source.generation;
  const sought = next.effects.some((effect) => effect.kind === 'seek-within');
  if (sameGeneration && !sought && !isCurrentPosition(before, event)) {
    expect(resumeBySpec(after), `T5 이어 볼 자리 보존(${event.type})`).toBe(resumeBySpec(before));
  }
}

/** 구멍 기억에서 url 하나를 뺀 나머지 — url 순으로 [url, 정렬한 키] */
function otherHoles(state: PlaybackSessionState, url: string): Array<[string, string[]]> {
  const entries = [...(state.dvrHoles ?? new Map<string, ReadonlySet<string>>())];
  return entries
    .filter(([key]) => key !== url)
    .map(([key, holes]): [string, string[]] => [key, [...holes].sort()])
    .sort(([a], [b]) => a.localeCompare(b));
}

/**
 * T7 · T8 · T9 — 목록 안 구멍의 기억(POK-528 후속 계획 3절). T7 아는 구멍의 404 뒤에는
 * 「다시 받는 중 ∧ 세대 상승」 이 없다 — 앞에 이미 relist 가 있었어도(다시 받는 중에 세대만
 * 또 올리는 결함도 잡는다) · T8 다시 받기에 들어가면 그 조각이 그 url 에 적히고 다른 url 의
 * 기억은 그대로다 · T9 다시 받기 진입이 아니면 기억은 같은 객체다
 */
function assertHoles(
  before: PlaybackSessionState,
  event: SessionEvent,
  after: PlaybackSessionState,
): void {
  if (event.type === 'dvr-segment-gone') {
    const known = holesBySpec(before, before.source.url).has(holeKeyBySpec(event.segment));
    const relistedAgain =
      relistOf(after) !== undefined && after.source.generation > before.source.generation;
    if (known) expect(relistedAgain, 'T7 아는 구멍의 404 ⇒ 다시 받기 아님').toBe(false);
  }
  const entered = enteredRelist(before, after);
  if (!entered) {
    expect(after.dvrHoles, `T9 다시 받기 진입 아님 ⇒ 구멍 기억 그대로(${event.type})`).toBe(
      before.dvrHoles,
    );
    return;
  }
  expect(event.type, 'T8 다시 받기 진입은 404 사건으로만').toBe('dvr-segment-gone');
  if (event.type !== 'dvr-segment-gone') return;
  const key = holeKeyBySpec(event.segment);
  expect(holesBySpec(after, after.source.url).has(key), `T8 다시 받기 ⇒ ${key} 기록`).toBe(true);
  expect(otherHoles(after, after.source.url), 'T8 다른 url 기억 그대로').toEqual(
    otherHoles(before, after.source.url),
  );
}

/**
 * 전이 하나의 앞 · 뒤에 상태 불변식과 전이 불변식(T1–T9)을 단언한다 — 세션 훅 시험이
 * 감싼 transition 에서 부른다(계획 8-0)
 */
export function assertTransitionInvariants(
  before: PlaybackSessionState,
  event: SessionEvent,
  next: Transition,
): void {
  const after = next.state;
  assertPhaseInvariants(after);
  assertRestart(before, event, after);
  assertResume(before, event, next);
  assertHoles(before, event, after);
  if (!startHeldBySpec(before) || startHeldBySpec(after)) return;
  const regenerated = after.source.generation > before.source.generation;
  const released = regenerated || after.closed !== null;
  expect(released, 'T1 세우지 않음이 풀리면 세대 상승 또는 닫힘').toBe(true);
}

/** 사건열 하나에 걸쳐 전이를 지켜보는 계수기(계획 3-1) */
export interface TransitionGuard {
  observe(before: PlaybackSessionState, event: SessionEvent, after: PlaybackSessionState): void;
}

/**
 * url 마다 「화면 없이 다시 받기에 들어간 수」 를 센다(계획 3-1 다시 받기 계수기). 지금 시대
 * 위치가 다시 받기를 끝낸 전이와 사람 사건(seek · live-button · button)에서 모든 url 의 수를
 * 0 으로 돌린다. 어느 url 이든 2 이상이면 실패다
 */
export function relistGuard(): TransitionGuard {
  const entered = new Map<string, number>();
  return {
    observe(before, event, after) {
      const painted =
        isCurrentPosition(before, event) &&
        relistOf(before) !== undefined &&
        relistOf(after) === undefined;
      if (painted || HUMAN_EVENTS.has(event.type)) entered.clear();
      if (relistOf(before) !== undefined || relistOf(after) === undefined) return;
      const url = after.source.url;
      const count = (entered.get(url) ?? 0) + 1;
      entered.set(url, count);
      expect(count, `화면 없이 같은 url 다시 받기 ≤ 1(${url})`).toBeLessThanOrEqual(1);
    },
  };
}

/**
 * url · 조각마다 다시 받기에 들어간 수를 세션 수명 내내 센다(POK-528 후속 — 같은 url 을 같은
 * 조각으로 두 번 다시 받지 않는다). 지우는 때가 없다 — 사건열 하나(세션 하나)마다 새로 건다
 */
export function holeGuard(): TransitionGuard {
  const entered = new Map<string, number>();
  return {
    observe(before, event, after) {
      if (event.type !== 'dvr-segment-gone' || !enteredRelist(before, after)) return;
      const hole = `${after.source.url} ${holeKeyBySpec(event.segment)}`;
      const count = (entered.get(hole) ?? 0) + 1;
      entered.set(hole, count);
      expect(count, `같은 url · 같은 조각 다시 받기 ≤ 1(${hole})`).toBeLessThanOrEqual(1);
    },
  };
}

/** stream-poll 이 되감기 url 을 바꾼 전이마다 그 url 이 처음 보는 것인지 본다(계획 3-1 재방문 계수기) */
export function revisitGuard(): TransitionGuard {
  return {
    observe(before, event, after) {
      if (event.type !== 'stream-poll' || after.source.kind !== 'rewind') return;
      if (after.source.url === before.source.url) return;
      const seen = before.roundRewindUrls.has(after.source.url);
      expect(seen, `stream-poll 교체는 처음 보는 url 로만(${after.source.url})`).toBe(false);
    },
  };
}

/**
 * 사건 하나를 먹이고(위치 사건은 지금 시대를 찍는다 — stamped) 결과에 상태 불변식과
 * 전이 불변식을 단언하고 계수기에 알린다. 시험은 transition 을 직접 부르지 않고 이것만 쓴다
 */
export function step(
  session: PlaybackSessionState,
  event: SessionEvent,
  now: number,
  guards: readonly TransitionGuard[] = [],
): Transition {
  const fed = stamped(session, event);
  const next = transition(session, fed, now);
  assertTransitionInvariants(session, fed, next);
  for (const guard of guards) {
    guard.observe(session, fed, next.state);
  }
  return next;
}
