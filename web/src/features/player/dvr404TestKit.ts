import { expect } from 'vitest';
import { stamped } from './handoffTestKit';
import {
  transition,
  type PlaybackSessionState,
  type SessionEvent,
  type Transition,
} from './rewindSwitch';

// 되감기 조각 404 시험의 전이 감싸개(POK-528 계획 8-0). 모든 전이 직후 국면 불변식
// (계획 3-1)과 전이 불변식(「새 재생기를 세우지 않음」 이 풀리면 세대가 오르거나
// 세션이 닫힌다 — U2)을 단언한다. 막힘은 다음 사건에서 닫혀 끝 상태에 흔적이 남지
// 않을 수 있어, 끝 상태만 보는 단언으로는 중간의 위반을 못 잡는다.
//
// 제품 모듈에서는 옛 판에도 있는 transition 과 타입만 읽는다 — 제품 파일을 되돌린
// 트리(음성 대조)에서도 이 파일이 로드되고 시험이 단언으로 FAIL 하게. 그래서 「세우지
// 않음」 은 제품 함수(isPlayerStartHeld)를 부르지 않고 계획 3-6 의 정의를 여기
// 옮겨 둔다. 제품 함수가 이 정의와 같은지는 rewindSwitch.dvr404.test.ts 가 따로 본다.

/** 계획 3-6 의 「새로 서는 재생기는 hls.js 를 만들지 않는다」 — 막힌 끝남 대기 */
function startHeldBySpec(state: PlaybackSessionState): boolean {
  const { phase } = state;
  return phase.kind === 'ending-wait' && phase.blocked === true;
}

/** 국면 불변식(계획 3-1) — 막힌 끝남 대기는 녹화로 정해지지 않았고 되감기 재생기의 것이다 */
export function assertPhaseInvariants(state: PlaybackSessionState): void {
  const { phase } = state;
  if (phase.kind !== 'ending-wait' || phase.blocked !== true) return;
  expect(phase.recordOnEnd, 'blocked ⇒ ¬recordOnEnd').toBe(false);
  expect(state.source.kind, 'blocked ⇒ source.kind rewind').toBe('rewind');
}

/**
 * 사건 하나를 먹이고(위치 사건은 지금 시대를 찍는다 — stamped) 결과에 국면 불변식과
 * 전이 불변식을 단언한다. 시험은 transition 을 직접 부르지 않고 이것만 쓴다
 */
export function step(session: PlaybackSessionState, event: SessionEvent, now: number): Transition {
  const next = transition(session, stamped(session, event), now);
  assertPhaseInvariants(next.state);
  if (startHeldBySpec(session) && !startHeldBySpec(next.state)) {
    const regenerated = next.state.source.generation > session.source.generation;
    const released = regenerated || next.state.closed !== null;
    expect(released, '세우지 않음이 풀리면 세대 상승 또는 닫힘').toBe(true);
  }
  return next;
}
