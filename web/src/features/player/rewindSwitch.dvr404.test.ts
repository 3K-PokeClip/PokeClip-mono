import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { step } from './dvr404TestKit';
import { pos, resetEventKit, settleProbe, startRun, streamPoll } from './handoffTestKit';
import {
  ENDING_WAIT_MS,
  RESUME_WAIT_MS,
  SESSION_NOTICES,
  openSession,
  pollTargets,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { isPlayerStartHeld, sessionView } from './sessionView';

// 끝난 회차의 되감기 조각 404 시험(POK-528 c2 — 계획 3-2 · 3-4 · 3-5 · 8-1 · W4 · W5 ·
// W7 · W9 · U1 · U2 의 막힘 몫). 사건은 모두 감싸개 step 을 거친다(전이마다 불변식
// 단언 — 계획 8-0). 사건 'dvr-segment-gone' · 효과 'hold-frame' 은 옛 판 타입에 없으므로
// 음성 대조 트리에서는 옛 표의 default 가 받아 단언으로 FAIL 한다.
//
// 시각은 epoch ms 다. 되감기 목록 A1 의 끝 E = NOW · TD 4초 · 여유 12초 · 문은 R 에
// 받은 응답이다. 방송 A(회차 r-A)를 보던 세션이 기준이다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const E = NOW;
const P = E - 2_000;
const R = NOW + 1_000;
const MARGIN_MS = 12_000;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
const B1 = 'https://media.pokeclip.com/dvr/key-1/b1/index.m3u8';
const VOD = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
const X_B: XAnswer = { kind: 'known', id: 'r-B' };
const X_UNKNOWN: XAnswer = { kind: 'unknown' };
const UNAVAILABLE: RewindStatusResult = { kind: 'unavailable' };
const NONE: RewindStatusResult = { kind: 'none' };
const ENDLIST: SessionEvent = { type: 'endlist' };
const END_REACHED: SessionEvent = { type: 'end-reached' };
const TICK: SessionEvent = { type: 'tick' };

function status(patch: Partial<RewindStatus>): RewindStatusResult {
  return {
    kind: 'ok',
    status: { ready: false, mode: null, url: null, firstPdtMs: null, headPdtMs: null, ...patch },
  };
}

function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  return status({ ready: true, mode: 'live', url, firstPdtMs, headPdtMs });
}

function vodReady(firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  return status({ ready: true, mode: 'vod', url: VOD, firstPdtMs, headPdtMs });
}

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
  return { type: 'dvr-segment-gone', generation } as SessionEvent;
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
const doorPoll = (x: XAnswer = X_A, at = R) => streamPoll(liveReady(A1, T0, NOW), x, at);

beforeEach(resetEventKit);

function fresh(): PlaybackSessionState {
  startRun();
  return openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
}

/** A1 을 p 에서 트는 되감기 세션 — 목록(E = NOW)을 로드했고 위치 사건 전이다 */
function rewindNear(p: number): PlaybackSessionState {
  return feed(fresh(), [
    streamPoll(liveReady(A1, T0, NOW)),
    { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
    loaded(A1, T0, NOW),
  ]).state;
}

/** p 에서 보던 중 ENDLIST 로 든 끝남 대기(rewind 기원 · atEnd:false · 목록 봉인) */
function endedByEndlist(p = T0 + 20 * MIN): PlaybackSessionState {
  return feed(rewindNear(p), [pos(p), ENDLIST]).state;
}

/**
 * 목록 끝 E 에 닿아 든 끝남 대기(rewind 기원 · atEnd:true · 목록 봉인 없음 — D9 기원).
 * probing 이면 닿기 전에 문을 세워 탐침 하나를 청해 둔다(R6 — 늦은 결과의 재료)
 */
function endedAtTail(probing: boolean): PlaybackSessionState {
  // 세션을 먼저 연다 — 폴링 순번과 위치 구간은 만들 때 정해진다
  const near = rewindNear(P);
  const door = probing ? feed(near, [doorPoll()]).state : near;
  return feed(door, [pos(P), pos(E), END_REACHED]).state;
}

/** 404 로 막힌 끝남 대기 */
const blockedOf = (s: PlaybackSessionState) => feed(s, [gone(s)]).state;

/**
 * 조회 실패 기원(K1 ②) — 끝 E 앞에서 보다 404 → recheck → 잠시 불가로 막힌 뒤 끝에
 * 닿은 끝남 대기(목록 봉인 없음). 막힌 뒤의 위치 사건은 재생 중이다(hold-frame 직후 꼴).
 * probing 이면 막히기 전(되감기)에 문을 세워 탐침 하나를 청해 둔다(늦은 결과의 재료)
 */
function blockedByRecheck(probing: boolean): PlaybackSessionState {
  const near = rewindNear(P);
  const door = probing ? feed(near, [doorPoll()]).state : near;
  const watched = feed(door, [pos(P)]).state;
  const blocked = feed(watched, [gone(watched), streamPoll(UNAVAILABLE, X_A, R)]).state;
  return feed(blocked, [pos(E), END_REACHED]).state;
}

/** 같은 상태에서 막힘만 내린 것 — 막히기 전 대조(차림의 판별력)에 쓴다 */
function withoutBlock(state: PlaybackSessionState): PlaybackSessionState {
  const { phase } = state;
  if (phase.kind !== 'ending-wait') throw new Error('끝남 대기가 아니다');
  return { ...state, phase: { ...phase, blocked: false } };
}

/** 막힘이 풀리지 않았다 — 국면 · 세대 · 20분 기준 · 덮개 · 탐침 요청 0 */
function expectStillBlocked(
  result: { state: PlaybackSessionState; effects: SessionEffect[] },
  base: PlaybackSessionState,
): void {
  expect(result.state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
  expect(result.state.source.generation).toBe(base.source.generation);
  expect(result.state.endingSince).toBe(base.endingSince);
  expect(sessionView(result.state, R).cover).toBe('preparing');
  expect(kinds(result.effects)).not.toContain('probe-live');
}

describe('끝남 대기의 되감기 조각 404 → blocked(계획 3-2)', () => {
  it('ENDLIST 뒤 404 는 막힘을 세우고 지금 프레임에서 멈춘다(cc 시나리오 A 셋째)', () => {
    const s = endedByEndlist();
    const done = feed(s, [gone(s)]);
    expect(done.state.phase).toEqual({
      kind: 'ending-wait',
      origin: 'rewind',
      atEnd: false,
      recordOnEnd: false,
      blocked: true,
    });
    expect(done.effects).toEqual([{ kind: 'hold-frame' }]);
    expect(done.state.source).toBe(s.source);
    expect(done.state.endingSince).toBe(s.endingSince);
  });

  it('막힌 세션은 덮개 「다시보기 준비 중」 — 재접속 대기 안이어도 · 두 폴링을 켠다', () => {
    const s = blockedOf(endedByEndlist());
    const view = sessionView(s, R);
    expect(view.cover).toBe('preparing');
    expect(view.actions).toEqual(['record']);
    expect(pollTargets(s)).toEqual({ stream: true, round: true });
  });

  it('막힌 동안 404 가 또 오면 효과 0(중복)', () => {
    const s = blockedOf(endedByEndlist());
    const again = feed(s, [gone(s)]);
    expect(again.effects).toEqual([]);
    expect(again.state.phase).toEqual(s.phase);
  });

  it('옛 재생기의 늦은 404(세대가 다름)는 막힘을 세우지 않는다', () => {
    const s = endedByEndlist();
    const late = feed(s, [gone(s, s.source.generation - 1)]);
    expect(late.effects).toEqual([]);
    expect(late.state.phase).toEqual(s.phase);
  });

  it('라이브 재생기의 끝남 대기(live 닫힘)에는 404 가 막힘을 세우지 않는다 — 되감기 재생기만', () => {
    const s = feed(fresh(), [
      loaded(LIVE_URL, NOW - 28_000, NOW),
      pos(NOW),
      { type: 'live-closed' },
    ]).state;
    expect(s.source.kind).toBe('live');
    const late = feed(s, [gone(s)]);
    expect(late.effects).toEqual([]);
    expect(late.state.phase).toEqual(s.phase);
  });

  it('녹화로 정한 끝남 대기(20분 시한)의 404 는 막힘을 세우지 않는다(blocked ⇒ ¬recordOnEnd)', () => {
    const s = feed(endedByEndlist(), [TICK], R + ENDING_WAIT_MS).state;
    expect(s.phase).toMatchObject({ recordOnEnd: true });
    const late = feed(s, [gone(s)], R + ENDING_WAIT_MS);
    expect(late.state.phase).not.toMatchObject({ blocked: true });
  });

  it('막힌 동안 시크는 무시한다 — 복구는 세션 효과만(P15)', () => {
    const s = blockedOf(endedByEndlist());
    const target = T0 + 10 * MIN;
    const seek: SessionEvent = { type: 'seek', targetMs: target, view: { lowerMs: T0, endMs: E } };
    const moved = feed(s, [seek]);
    expect(moved.effects).toEqual([]);
    expect(moved.state.phase).toEqual(s.phase);
    expect(moved.state.source).toBe(s.source);
  });

  it('막힌 동안 리마운트는 칸을 두고 세대만 올린다 — 새 재생기는 세우지 않음(U2)', () => {
    const s = blockedOf(endedByEndlist());
    const remounted = feed(s, [{ type: 'remount' }]);
    expect(remounted.effects).toEqual([]);
    expect(remounted.state.phase).toEqual(s.phase);
    expect(remounted.state.source.generation).toBe(s.source.generation + 1);
    expect(sessionView(remounted.state, R).startHeld).toBe(true);
    const late = feed(remounted.state, [gone(remounted.state, s.source.generation)]);
    expect(late.effects).toEqual([]);
  });
});

describe('vod-offered 의 404 → 같은 자리 다시보기(W4)', () => {
  it('지금 자리가 다시보기 범위 안이면 그 자리에서 · 알림 0', () => {
    const s = feed(endedByEndlist(), [{ type: 'round-poll', result: vodReady(T0, NOW) }]).state;
    expect(s.phase.kind).toBe('vod-offered');
    const moved = feed(s, [gone(s)]);
    expect(moved.state.phase).toEqual({ kind: 'vod' });
    expect(moved.state.source).toMatchObject({ kind: 'vod', url: VOD, startAtMs: T0 + 20 * MIN });
    expect(moved.state.source.generation).toBe(s.source.generation + 1);
    expect(moved.effects).toEqual([]);
  });

  it('지금 자리가 다시보기 첫 조각보다 앞이면 첫 조각에서 · vodStart 알림', () => {
    const vodFrom = T0 + 30 * MIN;
    const s = feed(endedByEndlist(), [{ type: 'round-poll', result: vodReady(vodFrom, NOW) }]);
    const moved = feed(s.state, [gone(s.state)]);
    expect(moved.state.source).toMatchObject({ kind: 'vod', startAtMs: vodFrom });
    expect(moved.effects).toEqual([{ kind: 'notice', text: SESSION_NOTICES.vodStart }]);
  });

  it('지금 자리가 끝 여유 뒤면 끝 여유 앞에서 · vodStart 알림', () => {
    const s = feed(endedByEndlist(E - 1_000), [{ type: 'round-poll', result: vodReady(T0, NOW) }]);
    const moved = feed(s.state, [gone(s.state)]);
    expect(moved.state.source).toMatchObject({ kind: 'vod', startAtMs: NOW - MARGIN_MS });
    expect(moved.effects).toEqual([{ kind: 'notice', text: SESSION_NOTICES.vodStart }]);
  });

  it('자리를 모르면 처음부터 · 알림 0', () => {
    const s = feed(endedByEndlist(), [{ type: 'round-poll', result: vodReady(T0, NOW) }]).state;
    const unknown = { ...s, lastAtMs: null };
    const moved = feed(unknown, [gone(unknown)]);
    expect(moved.state.source).toMatchObject({ kind: 'vod', startAtMs: null });
    expect(moved.effects).toEqual([]);
  });
});

describe('막힌 세션의 출구 — W5 셋(계획 3-4)', () => {
  it('회차별 vod · 겹침 통과 → vod-offered 를 거치지 않고 같은 자리 다시보기', () => {
    const s = blockedOf(endedByEndlist());
    const moved = feed(s, [{ type: 'round-poll', result: vodReady(T0, NOW) }]);
    expect(moved.state.phase).toEqual({ kind: 'vod' });
    expect(moved.state.source).toMatchObject({ kind: 'vod', url: VOD, startAtMs: T0 + 20 * MIN });
    expect(moved.effects).toEqual([]);
    expect(sessionView(moved.state, R).cover).toBeNull();
  });

  it('회차별 vod 가 본 구간과 겹치지 않으면 막힌 채 그대로', () => {
    const s = blockedOf(endedByEndlist());
    const far = vodReady(NOW + 10 * MIN, NOW + 20 * MIN);
    const kept = feed(s, [{ type: 'round-poll', result: far }]);
    expectStillBlocked(kept, s);
  });

  it('회차별 ended → 곧바로 녹화', () => {
    const s = blockedOf(endedByEndlist());
    const done = feed(s, [{ type: 'round-poll', result: status({ mode: 'ended' }) }]);
    expect(done.state.closed).toBe('recorded');
    expect(kinds(done.effects)).toEqual(['close']);
  });

  it('회차별 404 는 재접속 대기(360초) 안이면 그대로 · 뒤면 녹화', () => {
    const s = blockedOf(endedByEndlist());
    const early = feed(s, [{ type: 'round-poll', result: NONE }], R + MIN);
    expectStillBlocked(early, s);
    const late = feed(s, [{ type: 'round-poll', result: NONE }], R + RESUME_WAIT_MS);
    expect(late.state.closed).toBe('recorded');
  });

  it('20분 시한 → 끝에 닿지 않았어도 곧바로 녹화(roundFinal 의 막힘)', () => {
    const s = blockedOf(endedByEndlist());
    const done = feed(s, [TICK], R + ENDING_WAIT_MS);
    expect(done.state.closed).toBe('recorded');
  });

  it('W9 한계 고정: vod 불통과 · 잠시 불가가 이어지면 20분까지 덮개 · 녹화 단추, 그 뒤 녹화', () => {
    let s = blockedOf(endedByEndlist());
    const base = s;
    const far = vodReady(NOW + 10 * MIN, NOW + 20 * MIN);
    for (let i = 1; i <= 5; i += 1) {
      const at = R + i * MIN;
      const result = i % 2 === 0 ? UNAVAILABLE : far;
      const kept = feed(s, [{ type: 'round-poll', result }], at);
      expectStillBlocked(kept, base);
      expect(sessionView(kept.state, at).actions).toContain('record');
      s = kept.state;
    }
    expect(feed(s, [TICK], R + ENDING_WAIT_MS).state.closed).toBe('recorded');
  });

  it('녹화 단추는 늘 출구다', () => {
    const s = blockedOf(endedByEndlist());
    const done = feed(s, [{ type: 'button', which: 'record' }]);
    expect(done.state.closed).toBe('recorded');
  });
});

describe('막힌 세션의 사람 단추(P22 · U3)', () => {
  it('끝에 닿기 전이면 LIVE 가 라이브로 데려간다(세대 + 1)', () => {
    const s = blockedOf(endedByEndlist());
    const live = feed(s, [{ type: 'live-button', x: X_A, attempt: 0 }]);
    expect(live.state.phase).toEqual({ kind: 'live' });
    expect(live.state.source).toMatchObject({ kind: 'live', generation: s.source.generation + 1 });
  });

  it('한계 고정: 끝에 닿은 막힘에서 LIVE 계열 입력(LIVE 단추 · End 키 · 시크바 onReturnToLive)은 무동작', () => {
    const s = blockedOf(endedAtTail(false));
    expect(s.phase).toMatchObject({ atEnd: true, blocked: true });
    const kept = feed(s, [{ type: 'live-button', x: X_A, attempt: 0 }]);
    expect(kept.state).toBe(s);
    expect(kept.effects).toEqual([]);
  });

  it('같은 url 의 ready:false · 이미 본 url 의 ready 는 제안을 세우지 않는다', () => {
    const s = blockedOf(endedByEndlist());
    const notReady = status({ ready: false, mode: 'live', url: A1, firstPdtMs: T0, headPdtMs: E });
    const a = feed(s, [streamPoll(notReady, X_A)]);
    expect(a.state.offer).toBeNull();
    const b = feed(s, [streamPoll(liveReady(A1, T0, NOW), X_A)]);
    expect(b.state.offer).toBeNull();
    expectStillBlocked(b, s);
  });

  it('같은 회차 새 url 은 막힌 채 「라이브 보기」 제안만 — 누르면 go-live 로 닫는다', () => {
    const s = blockedOf(endedByEndlist());
    const join = T0 + 20 * MIN;
    const offered = feed(s, [streamPoll(liveReady(A2, join - MIN, join + MIN), X_A)]);
    expectStillBlocked(offered, s);
    expect(offered.state.offer).toEqual({ kind: 'go-live' });
    expect(sessionView(offered.state, R).actions).toEqual(['record', 'go-live']);
    const done = feed(offered.state, [{ type: 'button', which: 'go-live' }]);
    expect(done.state.closed).toBe('go-live');
  });

  it('W7: 다른 방송이면 끝에 닿기 전이어도 「새 방송 보기」 제안', () => {
    const s = blockedOf(endedByEndlist());
    const other = feed(s, [streamPoll(liveReady(B1, NOW + MIN, NOW + 2 * MIN), X_B)]);
    expect(other.state.offer).toEqual({ kind: 'new-broadcast', goTo: 'r-B' });
    const view = sessionView(other.state, R);
    expect(view.cover).toBe('preparing');
    expect(view.actions).toEqual(['record', 'new-broadcast']);
  });
});

describe('T1 · U1 회귀 — 막힘은 W5 밖에서 풀리지 않는다(cx H1)', () => {
  // K1: ENDLIST 는 목록을 봉인해(sealList) 넘김 조건이 298 과 무관하게 거짓이 된다.
  // 그래서 지연 P1 · 새 탐침은 봉인 없는 끝 도달(D9) 기원과 ② 조회 실패 기원(recheck →
  // 잠시 불가 → 막힘 — 봉인 없음)에서 잰다. 각 시험은 먼저
  // 막히기 전의 같은 입력이 실제로 넘김 · 탐침 · 이음을 내는지 확인한다(차림의 판별력).

  it('(b) 막히기 전에 청한 탐침의 늦은 P1 은 넘기지 않고 탐침만 비운다', () => {
    const ending = endedAtTail(true);
    expect(ending.handoffProbe).not.toBeNull();
    const unblocked = feed(ending, [settleProbe(ending, E - 2_000)], R + 500);
    expect(unblocked.state.phase.kind).toBe('live');
    const s = feed(blockedOf(ending), [pos(E, { playing: true })], R + 500).state;
    const late = feed(s, [settleProbe(s, E - 2_000)], R + 500);
    expectStillBlocked(late, s);
    expect(late.state.handoffProbe).toBeNull();
    expect(late.effects).toEqual([]);
  });

  it('(c) 같은 url 폴링이 문을 다시 세워도 새 탐침을 청하지 않는다', () => {
    const ending = endedAtTail(false);
    const unblocked = feed(ending, [doorPoll(X_A, R)]);
    expect(kinds(unblocked.effects)).toContain('probe-live');
    const s = feed(blockedOf(ending), [pos(E, { playing: true })]).state;
    const polled = feed(s, [doorPoll(X_A, R)]);
    expectStillBlocked(polled, s);
    expect(polled.state.handoffProbe).toBeNull();
    expect(polled.state.offer).toBeNull();
  });

  it('(a) 끝 도달 기원: 같은 회차 새 url 이 이음 지점을 품어도 잇지 않는다', () => {
    const ending = endedAtTail(false);
    const resume = () => streamPoll(liveReady(A2, E - 2_000, E + MIN), X_A, R);
    expect(feed(ending, [resume()]).state.phase.kind).toBe('rewind');
    const s = blockedOf(ending);
    const kept = feed(s, [resume()]);
    expectStillBlocked(kept, s);
    expect(kept.state.roundRewindUrls.has(A2)).toBe(false);
  });

  it('(a) ENDLIST 기원: 같은 회차 새 url 이 이음 지점을 품어도 잇지 않는다', () => {
    const ending = endedByEndlist();
    const join = T0 + 20 * MIN;
    const resume = () => streamPoll(liveReady(A2, join - MIN, join + MIN), X_A, R);
    expect(feed(ending, [resume()]).state.phase.kind).toBe('rewind');
    const s = blockedOf(ending);
    const kept = feed(s, [resume()]);
    expectStillBlocked(kept, s);
  });

  it('② 조회 실패 기원: 차림이 recheck → 잠시 불가 → 막힘 → 끝 도달이다(봉인 없음)', () => {
    const s = blockedByRecheck(true);
    expect(s.phase).toEqual({
      kind: 'ending-wait',
      origin: 'rewind',
      atEnd: true,
      recordOnEnd: false,
      blocked: true,
    });
    expect(s.list?.sealed).toBe(false);
    expect(s.handoffProbe).not.toBeNull();
  });

  it('(b) ② 조회 실패 기원: 막히기 전에 청한 탐침의 늦은 P1 은 넘기지 않고 탐침만 비운다', () => {
    const s = blockedByRecheck(true);
    const unblocked = feed(withoutBlock(s), [settleProbe(s, E - 2_000)], R + 500);
    expect(unblocked.state.phase.kind).toBe('live');
    const late = feed(s, [settleProbe(s, E - 2_000)], R + 500);
    expectStillBlocked(late, s);
    expect(late.state.handoffProbe).toBeNull();
    expect(late.effects).toEqual([]);
  });

  it('(c) ② 조회 실패 기원: 같은 url 폴링이 문을 다시 세워도 새 탐침을 청하지 않는다', () => {
    const s = blockedByRecheck(false);
    const unblocked = feed(withoutBlock(s), [doorPoll(X_A, R)]);
    expect(kinds(unblocked.effects)).toContain('probe-live');
    const polled = feed(s, [doorPoll(X_A, R)]);
    expectStillBlocked(polled, s);
    expect(polled.state.handoffProbe).toBeNull();
  });

  it('(a) ② 조회 실패 기원: 같은 회차 새 url 이 이음 지점을 품어도 잇지 않는다', () => {
    const s = blockedByRecheck(false);
    const resume = () => streamPoll(liveReady(A2, E - 2_000, E + MIN), X_A, R);
    expect(feed(withoutBlock(s), [resume()]).state.phase.kind).toBe('rewind');
    const kept = feed(s, [resume()]);
    expectStillBlocked(kept, s);
  });

  it('섞인 입력(늦은 P1 · X 모름 · 404 재발 · 새 url · 이미 본 url)을 지나 20분에 녹화', () => {
    const s = blockedOf(endedAtTail(true));
    const events: Array<[SessionEvent, number]> = [
      [pos(E, { playing: true }), R + 200],
      [settleProbe(s, E - 2_000), R + 400],
      [doorPoll(X_UNKNOWN, R + 600), R + 600],
      [{ type: 'dvr-segment-gone', generation: s.source.generation } as SessionEvent, R + 800],
      [doorPoll(X_A, R + 1_000), R + 1_000],
      [streamPoll(liveReady(A2, E - 2_000, E + MIN), X_A, R + 1_200), R + 1_200],
      [streamPoll(UNAVAILABLE, X_A, R + 1_400), R + 1_400],
    ];
    let state = s;
    for (const [event, at] of events) {
      const next = feed(state, [event], at);
      expectStillBlocked(next, s);
      state = next.state;
    }
    expect(feed(state, [TICK], s.endingSince! + ENDING_WAIT_MS).state.closed).toBe('recorded');
  });
});

describe('「새 재생기를 세우지 않음」 — isPlayerStartHeld 의 막힘 몫(U2)', () => {
  it('sessionView.startHeld 는 막힌 끝남 대기에서만 참이다', () => {
    const ending = endedByEndlist();
    expect(sessionView(rewindNear(P), R).startHeld).toBe(false);
    expect(sessionView(ending, R).startHeld).toBe(false);
    expect(sessionView(blockedOf(ending), R).startHeld).toBe(true);
  });

  it('[새 함수 — 음성 대조 제외 · 변이 M2l 로 교정] 국면별 표', () => {
    const ending = endedByEndlist();
    const offered = feed(ending, [{ type: 'round-poll', result: vodReady(T0, NOW) }]).state;
    const vod = feed(offered, [{ type: 'button', which: 'vod' }]).state;
    expect(isPlayerStartHeld(fresh())).toBe(false);
    expect(isPlayerStartHeld(rewindNear(P))).toBe(false);
    expect(isPlayerStartHeld(ending)).toBe(false);
    expect(isPlayerStartHeld(blockedOf(ending))).toBe(true);
    expect(isPlayerStartHeld(offered)).toBe(false);
    expect(isPlayerStartHeld(vod)).toBe(false);
  });
});
