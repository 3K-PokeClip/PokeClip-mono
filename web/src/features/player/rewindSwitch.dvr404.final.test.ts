import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { step } from './dvr404TestKit';
import { pos, resetEventKit, startRun, streamPoll } from './handoffTestKit';
import {
  ENDING_WAIT_MS,
  RESUME_WAIT_MS,
  openSession,
  pollTargets,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { isPlayerStartHeld, sessionView } from './sessionView';

// 종국 뒤 되감기 조각 404 시험(POK-528 c4 — 계획 3-2 · 3-4 · 3-6 · 8-1 W6 · E76 ·
// 「종국 → 404」 순서 넷 · U2 의 finalCheck 몫). 녹화로 정한 끝남 대기(recordOnEnd)는
// 끝까지 본 뒤 녹화로 가려고 두 폴링을 끈다. 그 자리에서 404 가 오면 끝에 영영 닿지
// 못하므로(E76) 회차별 조회를 한 번 더 해 다시보기 또는 녹화로 넘긴다. 사건은 모두
// 감싸개 step 을 거친다(전이마다 불변식 단언 — 계획 8-0). 옛 판 표는 이 404 를 무시하므로
// 음성 대조 트리에서는 단언으로 FAIL 한다.
//
// 시각은 epoch ms 다. 되감기 목록 A1 의 첫 조각 T0 · 끝 NOW · 여유 12초 · 끝남 대기는
// R 에 들었다(endingSince = R). 방송 A(회차 r-A)를 P20 에서 보던 세션이 기준이다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const R = NOW + 1_000;
const MARGIN_MS = 12_000;
const P20 = T0 + 20 * MIN;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const VOD = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
const UNAVAILABLE: RewindStatusResult = { kind: 'unavailable' };
const NONE: RewindStatusResult = { kind: 'none' };
const TICK: SessionEvent = { type: 'tick' };
const HOLD: SessionEffect = { kind: 'hold-frame' };
/** 20분 시한 직후 */
const AFTER_DEADLINE = R + ENDING_WAIT_MS;

function status(patch: Partial<RewindStatus>): RewindStatusResult {
  return {
    kind: 'ok',
    status: { ready: false, mode: null, url: null, firstPdtMs: null, headPdtMs: null, ...patch },
  };
}

function vodReady(firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  return status({ ready: true, mode: 'vod', url: VOD, firstPdtMs, headPdtMs });
}

const ENDED = status({ mode: 'ended' });
const roundPoll = (result: RewindStatusResult): SessionEvent => ({ type: 'round-poll', result });

function loaded(url: string, firstMs: number, lastEndMs: number): SessionEvent {
  return {
    type: 'list-loaded',
    url,
    firstMs,
    lastEndMs,
    targetDurationS: 4,
    headSpanMs: MARGIN_MS,
  };
}

/** 지금 되감기 재생기(지금 세대)의 /dvr 미디어 조각 404 */
function gone(state: PlaybackSessionState, generation = state.source.generation): SessionEvent {
  return { type: 'dvr-segment-gone', generation };
}

/** 사건을 차례로 감싸개로 먹이고 마지막 상태와 모든 효과를 돌려준다 */
function feed(
  start: PlaybackSessionState,
  events: SessionEvent[],
  now = R,
): { state: PlaybackSessionState; effects: SessionEffect[] } {
  let state = start;
  const effects: SessionEffect[] = [];
  for (const event of events) {
    const next = step(state, event, now);
    state = next.state;
    effects.push(...next.effects);
  }
  return { state, effects };
}

const kinds = (effects: SessionEffect[]) => effects.map((e) => e.kind);

beforeEach(resetEventKit);

function fresh(): PlaybackSessionState {
  startRun();
  return openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
}

/** P20 에서 보던 중 ENDLIST 로 든 끝남 대기(rewind 기원 · atEnd:false · endingSince = R) */
function endedByEndlist(): PlaybackSessionState {
  return feed(fresh(), [
    streamPoll(liveReady(), X_A),
    { type: 'seek', targetMs: P20, view: { lowerMs: P20 + 1, endMs: NOW + 28_000 } },
    loaded(A1, T0, NOW),
    pos(P20),
    { type: 'endlist' },
  ]).state;
}

function liveReady(): RewindStatusResult {
  return status({ ready: true, mode: 'live', url: A1, firstPdtMs: T0, headPdtMs: NOW });
}

/** 녹화로 정한 끝남 대기 — 종국 셋(20분 시한 · 회차별 ended · 재접속 대기 뒤 회차별 404) */
const byDeadline = () => feed(endedByEndlist(), [TICK], AFTER_DEADLINE).state;
const byEnded = () => feed(endedByEndlist(), [roundPoll(ENDED)]).state;
const byNone = () => feed(endedByEndlist(), [roundPoll(NONE)], R + RESUME_WAIT_MS).state;

/** 녹화로 정한 끝남 대기에서 404 를 받아 마지막 확인에 든 세션 */
function finalOf(recorded: PlaybackSessionState, now = AFTER_DEADLINE): PlaybackSessionState {
  return feed(recorded, [gone(recorded)], now).state;
}

describe('녹화로 정한 끝남 대기의 404 → 마지막 확인(계획 3-2 · E76)', () => {
  it('녹화로 정한 끝남 대기는 두 폴링을 끈다 — 404 가 없으면 끝 도달을 기다린다(지금 동작)', () => {
    const s = byDeadline();
    expect(s.phase).toMatchObject({ recordOnEnd: true, atEnd: false });
    expect(pollTargets(s)).toEqual({ stream: false, round: false });
    expect(sessionView(s, AFTER_DEADLINE).cover).toBeNull();
  });

  it('404 는 마지막 확인을 세우고(막힘 아님) 지금 프레임에서 멈춘다', () => {
    const s = byDeadline();
    const checked = feed(s, [gone(s)], AFTER_DEADLINE);
    expect(checked.state.phase).toEqual({
      kind: 'ending-wait',
      origin: 'rewind',
      atEnd: false,
      recordOnEnd: true,
      finalCheck: true,
    });
    expect(checked.effects).toEqual([HOLD]);
    expect(checked.state.source).toBe(s.source);
    expect(checked.state.endingSince).toBe(s.endingSince);
  });

  it('마지막 확인은 회차별 조회만 켠다 — 스트림 폴링은 끈 채', () => {
    expect(pollTargets(finalOf(byDeadline()))).toEqual({ stream: false, round: true });
  });

  it('마지막 확인은 덮개 「다시보기 준비 중」 · 녹화 단추(계획 3-6 — 13절 통지 대상 편차)', () => {
    const view = sessionView(finalOf(byDeadline()), AFTER_DEADLINE);
    expect(view.cover).toBe('preparing');
    expect(view.actions).toEqual(['record']);
    expect(view.startHeld).toBe(true);
  });

  it('재접속 대기 안에 든 마지막 확인도 「확인 중」 이 아니라 「다시보기 준비 중」', () => {
    const s = finalOf(byEnded(), R);
    expect(sessionView(s, R + MIN).cover).toBe('preparing');
  });

  it('옛 재생기의 늦은 404(세대가 다름)는 마지막 확인을 세우지 않는다', () => {
    const s = byDeadline();
    const late = feed(s, [gone(s, s.source.generation - 1)], AFTER_DEADLINE);
    expect(late.effects).toEqual([]);
    expect(late.state.phase).toEqual(s.phase);
  });
});

describe('W6 「종국 → 404」 순서 넷(계획 8-1)', () => {
  it('1. 20분 시한 → 404 → 회차별 vod · 겹침 통과 → 같은 자리 다시보기(시한 직후 VOD)', () => {
    const s = finalOf(byDeadline());
    const moved = feed(s, [roundPoll(vodReady(T0, NOW))], AFTER_DEADLINE);
    expect(moved.state.phase).toEqual({ kind: 'vod' });
    expect(moved.state.source).toMatchObject({ kind: 'vod', url: VOD, startAtMs: P20 });
    expect(moved.state.source.generation).toBe(s.source.generation + 1);
    expect(moved.state.closed).toBeNull();
    expect(moved.effects).toEqual([]);
    expect(sessionView(moved.state, AFTER_DEADLINE).cover).toBeNull();
  });

  it('2. 회차별 ended → 404 → 회차별 ended → 녹화', () => {
    const s = finalOf(byEnded(), R);
    const done = feed(s, [roundPoll(ENDED)]);
    expect(done.state.closed).toBe('recorded');
    expect(kinds(done.effects)).toEqual(['close']);
  });

  it('3. 재접속 대기(360초) 뒤 회차별 404 → 404 → 회차별 404 → 녹화', () => {
    const at = R + RESUME_WAIT_MS;
    const s = finalOf(byNone(), at);
    const done = feed(s, [roundPoll(NONE)], at);
    expect(done.state.closed).toBe('recorded');
  });

  it('4. 20분 시한 → 404 → 잠시 불가(요청 시한 8초 꼴) → 녹화 — 그 뒤 vod 가 와도 두 번째 확인 없음', () => {
    const s = finalOf(byDeadline());
    const done = feed(s, [roundPoll(UNAVAILABLE)], AFTER_DEADLINE);
    expect(done.state.closed).toBe('recorded');
    const after = feed(done.state, [roundPoll(vodReady(T0, NOW))], AFTER_DEADLINE + 1_000);
    expect(after.state).toBe(done.state);
    expect(after.effects).toEqual([]);
  });

  it('4 덧붙임. 회차별 vod 가 본 구간과 겹치지 않으면 녹화', () => {
    const s = finalOf(byDeadline());
    const far = vodReady(NOW + 10 * MIN, NOW + 20 * MIN);
    expect(feed(s, [roundPoll(far)], AFTER_DEADLINE).state.closed).toBe('recorded');
  });

  it('비 vod 답은 모두 녹화 — ending · live · 국면 모름 · url 없는 vod', () => {
    const answers = [
      status({ mode: 'ending' }),
      status({ ready: true, mode: 'live', url: A1, firstPdtMs: T0, headPdtMs: NOW }),
      status({}),
      status({ mode: 'vod' }),
    ];
    for (const answer of answers) {
      const s = finalOf(byDeadline());
      expect(feed(s, [roundPoll(answer)], AFTER_DEADLINE).state.closed).toBe('recorded');
    }
  });
});

describe('마지막 확인 중 다른 사건(계획 3-4 finalCheck 열)', () => {
  it('404 · 시크는 효과 0(중복 · 복구는 세션 효과만)', () => {
    const s = finalOf(byDeadline());
    const seek: SessionEvent = { type: 'seek', targetMs: T0, view: { lowerMs: T0, endMs: NOW } };
    const kept = feed(s, [gone(s), seek], AFTER_DEADLINE);
    expect(kept.effects).toEqual([]);
    expect(kept.state.phase).toEqual(s.phase);
    expect(kept.state.source).toBe(s.source);
  });

  it('스트림 폴링 답 · 시한은 국면을 바꾸지 않는다', () => {
    const s = finalOf(byDeadline());
    const kept = feed(s, [streamPoll(liveReady(), X_A), TICK], AFTER_DEADLINE + MIN);
    expect(kept.state.phase).toEqual(s.phase);
    expect(kept.effects).toEqual([]);
  });

  it('리마운트는 칸을 두고 세대만 올린다 — 새 재생기는 세우지 않음(U2)', () => {
    const s = finalOf(byDeadline());
    const remounted = feed(s, [{ type: 'remount' }], AFTER_DEADLINE);
    expect(remounted.effects).toEqual([]);
    expect(remounted.state.phase).toEqual(s.phase);
    expect(remounted.state.source.generation).toBe(s.source.generation + 1);
    expect(sessionView(remounted.state, AFTER_DEADLINE).startHeld).toBe(true);
  });

  it('끝 도달 · 녹화 단추 → 녹화 · LIVE(끝에 닿기 전) → 라이브(세대 + 1)', () => {
    const s = finalOf(byDeadline());
    expect(feed(s, [{ type: 'end-reached' }], AFTER_DEADLINE).state.closed).toBe('recorded');
    const record: SessionEvent = { type: 'button', which: 'record' };
    expect(feed(s, [record], AFTER_DEADLINE).state.closed).toBe('recorded');
    const live = feed(s, [{ type: 'live-button', x: X_A, attempt: 0 }], AFTER_DEADLINE);
    expect(live.state.phase).toEqual({ kind: 'live' });
    expect(live.state.source.generation).toBe(s.source.generation + 1);
  });
});

describe('「새 재생기를 세우지 않음」 — isPlayerStartHeld 의 finalCheck 몫(U2)', () => {
  it('sessionView.startHeld 는 녹화로 정한 끝남 대기에서 마지막 확인일 때만 참이다', () => {
    const recorded = byDeadline();
    expect(sessionView(recorded, AFTER_DEADLINE).startHeld).toBe(false);
    expect(sessionView(finalOf(recorded), AFTER_DEADLINE).startHeld).toBe(true);
  });

  it('[새 함수 — 음성 대조 제외 · 변이 M2l-2 로 교정] 국면별 표', () => {
    const recorded = byDeadline();
    expect(isPlayerStartHeld(recorded)).toBe(false);
    expect(isPlayerStartHeld(finalOf(recorded))).toBe(true);
    expect(isPlayerStartHeld(finalOf(byEnded(), R))).toBe(true);
  });
});
