import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { step } from './dvr404TestKit';
import { nextSegment, pos, resetEventKit, startRun, streamPoll } from './handoffTestKit';
import {
  ENDING_WAIT_MS,
  RESUME_WAIT_MS,
  openSession,
  pollTargets,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
} from './rewindSwitch';
import { isPlayerStartHeld, sessionView } from './sessionView';

// 다시보기 넘김 시험(POK-547) — 다시보기는 「다시보기 보기」 단추로만 넘긴다. 단추는 세션을 past 로
// 닫고 보던 자리 · 확인한 회차를 지난 방송 판정에 넘긴다. 막힘 · 마지막 확인에서 확인한 다시보기는
// 멈춘 화면 위 덮개 단추로 내민다(곧바로 넘기지 않는다). 사건은 감싸개 step 을 거친다(전이마다
// 불변식 단언). 시각은 epoch ms 다. 되감기 목록 A1 의 끝 E = NOW · 방송 A(회차 r-A)를 보던 세션이
// 기준이다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const E = NOW;
/** 되감기로 보던 자리 */
const P20 = T0 + 20 * MIN;
/** 사건을 먹이는 시각(문 · 신선도와 무관한 시험) */
const R = NOW + 1_000;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const VOD = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
const VOD_RANGE = { url: VOD, firstPdtMs: T0, headPdtMs: NOW };
const NONE: RewindStatusResult = { kind: 'none' };
const UNAVAILABLE: RewindStatusResult = { kind: 'unavailable' };
const ENDLIST: SessionEvent = { type: 'endlist' };
const END_REACHED: SessionEvent = { type: 'end-reached' };
const TICK: SessionEvent = { type: 'tick' };
const PRESS_VOD: SessionEvent = { type: 'button', which: 'vod' };

function status(patch: Partial<RewindStatus>): RewindStatusResult {
  return {
    kind: 'ok',
    status: { ready: false, mode: null, url: null, firstPdtMs: null, headPdtMs: null, ...patch },
  };
}

function vodReady(firstPdtMs = T0, headPdtMs = NOW): RewindStatusResult {
  return status({ ready: true, mode: 'vod', url: VOD, firstPdtMs, headPdtMs });
}

function roundPoll(result: RewindStatusResult): SessionEvent {
  return { type: 'round-poll', result };
}

function loaded(url: string, firstMs: number, lastEndMs: number): SessionEvent {
  return { type: 'list-loaded', url, firstMs, lastEndMs, targetDurationS: 4, headSpanMs: 12_000 };
}

function gone(state: PlaybackSessionState): SessionEvent {
  return { type: 'dvr-segment-gone', generation: state.source.generation, segment: nextSegment() };
}

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

/** past 닫힘 효과 하나 — 넘김 재료를 그대로 적는다 */
function pastClose(
  resumeAtMs: number | null,
  seenVod: typeof VOD_RANGE | null,
  chosen: boolean,
): SessionEffect[] {
  return [{ kind: 'close', reason: 'past', handoff: { resumeAtMs, seenVod, chosen } }];
}

beforeEach(resetEventKit);

function fresh(): PlaybackSessionState {
  startRun();
  return openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
}

/** A1 을 P20 에서 보던 중 ENDLIST 로 든 끝남 대기(rewind 기원 · atEnd:false) */
function rewindEnding(): PlaybackSessionState {
  return feed(fresh(), [
    streamPoll(status({ ready: true, mode: 'live', url: A1, firstPdtMs: T0, headPdtMs: NOW })),
    { type: 'seek', targetMs: P20, view: { lowerMs: P20 + 1, endMs: NOW + 28_000 } },
    loaded(A1, T0, NOW),
    pos(P20),
    ENDLIST,
  ]).state;
}

/** 라이브를 NOW 에서 보던 중 ENDLIST(atEnd:false) · 라이브 닫힘(atEnd:true)으로 든 끝남 대기(live 기원) */
function liveEnding(atEnd: boolean): PlaybackSessionState {
  return feed(fresh(), [
    loaded(LIVE_URL, NOW - 28_000, NOW),
    pos(NOW),
    atEnd ? { type: 'live-closed' } : ENDLIST,
  ]).state;
}

/** 다시보기 제안 — origin 기원 · atEnd · stopped. 손으로 만든 stopped 는 같은 제안의 칸만 바꾼다 */
function offered(
  origin: 'live' | 'rewind',
  atEnd: boolean,
  stopped: null | 'drained' | 'held' = null,
): PlaybackSessionState {
  const ending =
    origin === 'live'
      ? liveEnding(atEnd)
      : feed(rewindEnding(), atEnd ? [pos(E), END_REACHED] : []).state;
  const offer = feed(ending, [roundPoll(vodReady())]).state;
  if (offer.phase.kind !== 'vod-offered') throw new Error('다시보기 제안이 아니다');
  return { ...offer, phase: { ...offer.phase, stopped } };
}

/** 404 로 막힌 끝남 대기(rewind 기원 · P20 에서 멈춤 · atEnd:false) */
function blocked(): PlaybackSessionState {
  const s = rewindEnding();
  return feed(s, [gone(s)]).state;
}

/** 닫기로 정한 뒤 404 로 든 마지막 확인(rewind 기원 · atEnd:false) */
function finalCheck(): PlaybackSessionState {
  const decided = feed(rewindEnding(), [TICK], R + ENDING_WAIT_MS).state;
  const checking = feed(decided, [gone(decided)], R + ENDING_WAIT_MS).state;
  expect(checking.phase).toMatchObject({ kind: 'ending-wait', finalCheck: true });
  return checking;
}

describe('「다시보기 보기」 는 past 로 닫고 넘김 재료를 싣는다', () => {
  const origins = ['live', 'rewind'] as const;
  const stops = [null, 'drained', 'held'] as const;
  /** 기원별 이어 볼 자리 — 라이브는 마지막으로 본 자리, 되감기는 확인된 위치 */
  const spotOf = { live: NOW, rewind: P20 };

  for (const origin of origins) {
    for (const atEnd of [false, true]) {
      for (const stopped of stops) {
        it(`${origin} 기원 · atEnd ${atEnd} · stopped ${stopped} → 끝에 닿았으면 처음부터, 아니면 보던 자리`, () => {
          const s = offered(origin, atEnd, stopped);
          const done = feed(s, [PRESS_VOD]);
          const spot = atEnd ? null : spotOf[origin];
          expect(done.effects).toEqual(pastClose(spot, VOD_RANGE, true));
          expect(done.state.closed).toBe('past');
          expect(done.state.source).toBe(s.source);
        });
      }
    }
  }

  it('제안 밖의 「다시보기 보기」 는 아무것도 하지 않는다', () => {
    const s = rewindEnding();
    const done = feed(s, [PRESS_VOD]);
    expect(done.effects).toEqual([]);
    expect(done.state.closed).toBeNull();
  });
});

describe('겹치는 다시보기를 확인해도 사람 단추 없이는 넘기지 않는다', () => {
  it('막히지 않은 끝남 대기 → 제안(stopped null) · 효과 0 · 닫힘 0', () => {
    const done = feed(rewindEnding(), [roundPoll(vodReady())]);
    expect(done.state.phase).toEqual({
      kind: 'vod-offered',
      ...VOD_RANGE,
      atEnd: false,
      stopped: null,
    });
    expect(done.effects).toEqual([]);
    expect(done.state.closed).toBeNull();
    expect(sessionView(done.state, R)).toMatchObject({
      cover: null,
      vodReady: true,
      actions: ['vod'],
    });
  });

  it('막힌 끝남 대기 → 멈춘 그대로 제안(stopped held) · 제안 단추는 거둠 · 재생기 보류 · 시크 무시', () => {
    const s = { ...blocked(), offer: { kind: 'go-live' as const } };
    const done = feed(s, [roundPoll(vodReady())]);
    expect(done.state.phase).toEqual({
      kind: 'vod-offered',
      ...VOD_RANGE,
      atEnd: false,
      stopped: 'held',
    });
    expect(done.state.offer).toBeNull();
    expect(done.effects).toEqual([]);
    expect(done.state.closed).toBeNull();
    expect(isPlayerStartHeld(done.state)).toBe(true);
    expect(sessionView(done.state, R)).toMatchObject({
      cover: 'vod-ready',
      vodReady: false,
      actions: ['vod'],
    });

    const seek: SessionEvent = {
      type: 'seek',
      targetMs: T0 + 10 * MIN,
      view: { lowerMs: T0, endMs: E },
    };
    const sought = feed(done.state, [seek]);
    expect(sought.state).toBe(done.state);
    expect(sought.effects).toEqual([]);

    const pressed = feed(done.state, [PRESS_VOD]);
    expect(pressed.effects).toEqual(pastClose(P20, VOD_RANGE, true));
  });

  it('막힌 뒤 끝에 닿았으면 제안도 끝에 닿은 채다 — 단추는 처음부터', () => {
    const s = feed(blocked(), [pos(E), END_REACHED]).state;
    const done = feed(s, [roundPoll(vodReady())]);
    expect(done.state.phase).toMatchObject({ kind: 'vod-offered', atEnd: true, stopped: 'held' });
    expect(feed(done.state, [PRESS_VOD]).effects).toEqual(pastClose(null, VOD_RANGE, true));
  });

  it('마지막 확인의 유효한 다시보기 → 멈춘 그대로 제안 · 확인을 끝내 폴링 0 · 뒤늦은 시한 · 답은 제안을 닫지 않는다', () => {
    const done = feed(finalCheck(), [roundPoll(vodReady())], R + ENDING_WAIT_MS);
    expect(done.state.phase).toEqual({
      kind: 'vod-offered',
      ...VOD_RANGE,
      atEnd: false,
      stopped: 'held',
    });
    expect(done.effects).toEqual([]);
    expect(done.state.closed).toBeNull();
    expect(pollTargets(done.state)).toEqual({ stream: false, round: false });
    expect(isPlayerStartHeld(done.state)).toBe(true);
    const late = feed(
      done.state,
      [TICK, roundPoll(status({ mode: 'ended' }))],
      R + 2 * ENDING_WAIT_MS,
    );
    expect(late.state).toBe(done.state);
    expect(late.effects).toEqual([]);
  });

  it('겹치지 않는 다시보기는 끝남 대기 · 막힘 · 마지막 확인을 그대로 둔다', () => {
    const far = vodReady(NOW + 10 * MIN, NOW + 20 * MIN);
    for (const s of [rewindEnding(), blocked()]) {
      const done = feed(s, [roundPoll(far)]);
      expect(done.state).toBe(s);
      expect(done.effects).toEqual([]);
    }
    const checked = feed(finalCheck(), [roundPoll(far)], R + ENDING_WAIT_MS);
    expect(checked.effects).toEqual(pastClose(P20, null, false));
  });

  it('준비되지 않은 다시보기(ready:false)는 제안하지 않고 · 막힘을 풀지 않고 · 마지막 확인은 다시보기 없이 닫는다', () => {
    const notReady = status({
      ready: false,
      mode: 'vod',
      url: VOD,
      firstPdtMs: T0,
      headPdtMs: NOW,
    });
    const watching = feed(rewindEnding(), [roundPoll(notReady)]);
    expect(watching.state.phase.kind).toBe('ending-wait');
    const stuck = feed(blocked(), [roundPoll(notReady)]);
    expect(stuck.state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(stuck.state.closed).toBeNull();
    const checked = feed(finalCheck(), [roundPoll(notReady)], R + ENDING_WAIT_MS);
    expect(checked.effects).toEqual(pastClose(P20, null, false));
  });
});

describe('단추 없는 past 닫힘 — 다시보기 없이 · 끝에 닿았으면 처음부터 · 아니면 보던 자리', () => {
  it('마지막 확인의 비 vod 답 · 404 · 잠시 불가(시한 포함) → 보던 자리로 닫는다', () => {
    for (const result of [
      status({ mode: 'ended' }),
      status({ mode: 'ending' }),
      NONE,
      UNAVAILABLE,
    ]) {
      const done = feed(finalCheck(), [roundPoll(result)], R + ENDING_WAIT_MS);
      expect(done.effects).toEqual(pastClose(P20, null, false));
    }
  });

  const finals: Array<[string, SessionEvent, number]> = [
    ['회차별 ended', roundPoll(status({ mode: 'ended' })), R],
    ['재접속 대기 뒤 회차별 404', roundPoll(NONE), R + RESUME_WAIT_MS],
    ['20분 시한', TICK, R + ENDING_WAIT_MS],
  ];
  for (const [name, event, at] of finals) {
    it(`${name} — 끝에 닿은 시청자는 처음부터 · 막힌 시청자는 멈춘 자리`, () => {
      expect(feed(liveEnding(true), [event], at).effects).toEqual(pastClose(null, null, false));
      expect(feed(blocked(), [event], at).effects).toEqual(pastClose(P20, null, false));
    });
  }

  it('끝에서 닫기로 정한 뒤 끝 도달 · 라이브 닫힘 → 처음부터', () => {
    const decided = feed(rewindEnding(), [roundPoll(status({ mode: 'ended' }))]).state;
    expect(decided.phase).toMatchObject({ closeOnEnd: true, atEnd: false });
    expect(feed(decided, [END_REACHED]).effects).toEqual(pastClose(null, null, false));
    const live = feed(liveEnding(false), [roundPoll(status({ mode: 'ended' }))]).state;
    expect(feed(live, [{ type: 'live-closed' }]).effects).toEqual(pastClose(null, null, false));
  });
});

describe('제안의 그리기 · 재생기 보류 — 멈춘 제안은 덮개 단추다', () => {
  const cases: Array<[boolean, null | 'drained' | 'held', 'vod-ready' | null, boolean, boolean]> = [
    [false, null, null, true, false],
    [false, 'drained', 'vod-ready', false, true],
    [false, 'held', 'vod-ready', false, true],
    [true, null, 'vod-ready', false, false],
    [true, 'drained', 'vod-ready', false, true],
    [true, 'held', 'vod-ready', false, true],
  ];
  for (const [atEnd, stopped, cover, vodBand, held] of cases) {
    for (const origin of ['live', 'rewind'] as const) {
      it(`${origin} 기원 · atEnd ${atEnd} · stopped ${stopped} → 덮개 ${cover} · 띠 ${vodBand} · 보류 ${held}`, () => {
        const s = offered(origin, atEnd, stopped);
        expect(sessionView(s, R)).toMatchObject({ cover, vodReady: vodBand, actions: ['vod'] });
        expect(isPlayerStartHeld(s)).toBe(held);
        expect(sessionView(s, R).startHeld).toBe(held);
      });
    }
  }

  it('멈춘 제안(drained)의 시크는 지금 규칙 그대로 받는다 — 덮개는 시크로 내려가지 않는다', () => {
    const s = offered('rewind', false, 'drained');
    const target = T0 + 10 * MIN;
    const done = feed(s, [{ type: 'seek', targetMs: target, view: { lowerMs: T0, endMs: E } }]);
    expect(done.effects).toEqual([{ kind: 'seek-within', atMs: target }]);
    expect(done.state.phase).toMatchObject({ stopped: 'drained' });
  });
});
