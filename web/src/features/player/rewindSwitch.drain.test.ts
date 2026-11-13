import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { step } from './dvr404TestKit';
import { nextSegment, pos, resetEventKit, startRun, streamPoll } from './handoffTestKit';
import {
  loadStoppedNow,
  openSession,
  type OfferStopped,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
} from './rewindSwitch';
import { resumeAtMs } from './sessionShared';
import { isPlayerStartHeld, sessionView, watchesDrain } from './sessionView';

// 다시보기 제안 중 되감기 조각 404 시험(POK-547) — 404 는 국면을 바꾸지 않고 받기만 멈춘다. 받기 중단은 세션 상태에
// 그 재생기의 세대로 적고(dvr-load-stopped), 받기를 멈춘 재생기가 받은 버퍼를 다 틀고 멎으면(dvr-drain) 제안을 덮개
// 단추로 바꾼다. 리마운트가 끼어도 새 재생기는 받지 않는다. 사건은 감싸개 step 을 거친다(전이마다 불변식 단언). 시각은
// epoch ms 다. 되감기 목록 A1 의 끝 E = NOW · 방송 A(회차 r-A)를 보던 세션이 기준이다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
/** 되감기로 보던 자리 */
const P20 = T0 + 20 * MIN;
/** 받은 버퍼를 다 틀고 멎은 자리 */
const DRAINED_AT = P20 + 30_000;
/** 사건을 먹이는 시각 */
const R = NOW + 1_000;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const VOD = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
const ENDLIST: SessionEvent = { type: 'endlist' };
const REMOUNT: SessionEvent = { type: 'remount' };
const PRESS_VOD: SessionEvent = { type: 'button', which: 'vod' };

function status(patch: Partial<RewindStatus>): RewindStatusResult {
  return {
    kind: 'ok',
    status: { ready: false, mode: null, url: null, firstPdtMs: null, headPdtMs: null, ...patch },
  };
}

function vodReady(): RewindStatusResult {
  return status({ ready: true, mode: 'vod', url: VOD, firstPdtMs: T0, headPdtMs: NOW });
}

function loaded(url: string, firstMs: number, lastEndMs: number): SessionEvent {
  return { type: 'list-loaded', url, firstMs, lastEndMs, targetDurationS: 4 };
}

function feed(
  start: PlaybackSessionState,
  events: SessionEvent[],
): { state: PlaybackSessionState; effects: SessionEffect[] } {
  let state = start;
  const effects: SessionEffect[] = [];
  for (const event of events) {
    const next = step(state, event, R);
    state = next.state;
    effects.push(...next.effects);
  }
  return { state, effects };
}

/** 지금 세대(또는 generation) 재생기의 404 */
function gone(state: PlaybackSessionState, generation = state.source.generation): SessionEvent {
  return { type: 'dvr-segment-gone', generation, segment: nextSegment() };
}

/** 지금 세대(또는 generation) 재생기의 받기 중단 */
function loadStopped(
  state: PlaybackSessionState,
  generation = state.source.generation,
): SessionEvent {
  return { type: 'dvr-load-stopped', generation };
}

/** 지금 세대 재생기의 고갈(drained) · 회복 통지 */
function drain(
  state: PlaybackSessionState,
  drained: boolean,
  atPdtMs: number | null = DRAINED_AT,
  generation = state.source.generation,
): SessionEvent {
  return { type: 'dvr-drain', generation, drained, atPdtMs };
}

beforeEach(resetEventKit);

function fresh(): PlaybackSessionState {
  startRun();
  return openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
}

/** A1 을 P20 에서 되감아 보는 중(확인된 위치 P20) */
function rewinding(): PlaybackSessionState {
  return feed(fresh(), [
    streamPoll(status({ ready: true, mode: 'live', url: A1, firstPdtMs: T0, headPdtMs: NOW })),
    { type: 'seek', targetMs: P20, view: { lowerMs: P20 + 1, endMs: NOW + 28_000 } },
    loaded(A1, T0, NOW),
    pos(P20),
  ]).state;
}

/** P20 에서 보던 중 ENDLIST 로 든 끝남 대기(rewind 기원 · atEnd:false) */
function rewindEnding(): PlaybackSessionState {
  return feed(rewinding(), [ENDLIST]).state;
}

/** 되감기 재생기로 보는 중 선 다시보기 제안(띠 단추 · 아직 튼다). stopped 를 주면 그 칸만 바꾼다 */
function offered(stopped: OfferStopped = null): PlaybackSessionState {
  const offer = feed(rewindEnding(), [{ type: 'round-poll', result: vodReady() }]).state;
  if (offer.phase.kind !== 'vod-offered') throw new Error('다시보기 제안이 아니다');
  return { ...offer, phase: { ...offer.phase, stopped } };
}

/** 라이브 재생기로 보는 중 선 다시보기 제안(live 기원) */
function liveOffered(): PlaybackSessionState {
  const ending = feed(fresh(), [loaded(LIVE_URL, NOW - 28_000, NOW), pos(NOW), ENDLIST]).state;
  return feed(ending, [{ type: 'round-poll', result: vodReady() }]).state;
}

describe('제안 중 404 — 국면은 그대로 두고 받기만 멈춘다', () => {
  it('지금 세대의 404 는 상태 · 효과를 바꾸지 않는다 — 같은 자리 다시보기로 옮기지 않는다', () => {
    const s = offered();

    const next = step(s, gone(s), R);

    expect(next.state).toBe(s);
    expect(next.effects).toEqual([]);
    expect(next.state.closed).toBeNull();
  });

  const recorded: Array<[string, () => PlaybackSessionState]> = [
    ['되감기', rewinding],
    ['끝남 대기', rewindEnding],
    ['다시보기 제안', offered],
  ];
  for (const [name, make] of recorded) {
    it(`${name} — 받기 중단은 그 세대만 적고 국면 · 소스 · 다른 칸 · 효과는 그대로다`, () => {
      const s = make();

      const next = step(s, loadStopped(s), R);

      expect(next.state).toEqual({ ...s, loadStoppedGeneration: s.source.generation });
      expect(next.state.phase).toBe(s.phase);
      expect(next.state.source).toBe(s.source);
      expect(next.effects).toEqual([]);
    });
  }

  it('옛 세대 재생기 · 라이브 재생기의 404 와 받기 중단은 상태를 바꾸지 않는다', () => {
    const s = offered();
    const live = liveOffered();
    const events: Array<[PlaybackSessionState, SessionEvent]> = [
      [s, gone(s, s.source.generation - 1)],
      [s, loadStopped(s, s.source.generation - 1)],
      [live, gone(live)],
      [live, loadStopped(live)],
    ];

    for (const [state, event] of events) {
      const next = step(state, event, R);
      expect(next.state, event.type).toBe(state);
      expect(next.effects).toEqual([]);
    }
  });

  it('받기 중단 기록은 다시보기 제안에서만 새 재생기를 세우지 않게 한다', () => {
    const offer = offered();
    const stoppedOffer = feed(offer, [loadStopped(offer)]).state;
    const rewind = rewinding();
    const stoppedRewind = feed(rewind, [loadStopped(rewind)]).state;
    const ending = rewindEnding();
    const stoppedEnding = feed(ending, [loadStopped(ending)]).state;

    expect(loadStoppedNow(stoppedOffer)).toBe(true);
    expect(isPlayerStartHeld(stoppedOffer)).toBe(true);
    expect(isPlayerStartHeld(stoppedRewind)).toBe(isPlayerStartHeld(rewind));
    expect(isPlayerStartHeld(stoppedEnding)).toBe(isPlayerStartHeld(ending));
    // 덮개 · 띠 · 고갈 감시는 받기 중단을 보지 않는다 — 받은 버퍼를 다 틀 때까지 띠 단추다
    expect(sessionView(stoppedOffer, R)).toMatchObject({ cover: null, vodReady: true });
    expect(watchesDrain(stoppedOffer)).toBe(true);
  });
});

describe('고갈 통지 — 받기를 멈춘 재생기가 멎으면 덮개 단추로 바꾼다', () => {
  /** 다시보기 제안에서 지금 세대 재생기가 받기를 멈춘 세션 — 고갈 통지는 받기를 멈춘 재생기만 낸다 */
  function stoppedOffer(stopped: OfferStopped = null): PlaybackSessionState {
    const s = offered(stopped);
    return feed(s, [loadStopped(s)]).state;
  }

  it('고갈 · 끝나지 않은 시크 없음 → 덮개 단추 · 멎은 자리를 확인된 위치로 적는다', () => {
    const s = stoppedOffer();

    const next = step(s, drain(s, true), R);

    expect(next.state.phase).toMatchObject({ kind: 'vod-offered', stopped: 'drained' });
    expect(next.state.lastAtMs).toBe(DRAINED_AT);
    expect(next.state.positionGeneration).toBe(s.source.generation);
    expect(next.state.watchedFromMs).toBe(s.watchedFromMs);
    expect(next.effects).toEqual([]);
    expect(sessionView(next.state, R)).toMatchObject({ cover: 'vod-ready', vodReady: false });
  });

  it('고갈 · 끝나지 않은 시크 있음 → 시크 목표를 그대로 이어 볼 자리로 둔다', () => {
    const target = P20 + 5 * MIN;
    const s = stoppedOffer();
    const sought = feed(s, [
      { type: 'seek', targetMs: target, view: { lowerMs: T0, endMs: NOW } },
    ]).state;
    expect(sought.pendingSeekMs).toBe(target);

    const next = step(sought, drain(sought, true), R);

    expect(next.state.phase).toMatchObject({ stopped: 'drained' });
    expect(next.state.pendingSeekMs).toBe(target);
    expect(resumeAtMs(next.state)).toBe(target);
  });

  it('고갈 자리를 모르면(시각 표시 없음) 덮개만 세우고 위치 칸은 그대로다', () => {
    const s = stoppedOffer();

    const next = step(s, drain(s, true, null), R);

    expect(next.state.phase).toMatchObject({ stopped: 'drained' });
    expect(next.state.lastAtMs).toBe(s.lastAtMs);
    expect(resumeAtMs(next.state)).toBe(P20);
  });

  it('고갈 중 다시 고갈이면 자리만 새로 적는다', () => {
    const s = stoppedOffer();
    const once = feed(s, [drain(s, true)]).state;
    const later = DRAINED_AT + 4_000;

    const next = step(once, drain(once, true, later), R);

    expect(next.state.phase).toEqual(once.phase);
    expect(next.state.lastAtMs).toBe(later);
  });

  it('회복 통지는 고갈 덮개를 내리고 띠 단추로 돌린다', () => {
    const s = stoppedOffer();
    const drained = feed(s, [drain(s, true)]).state;

    const next = step(drained, drain(drained, false), R);

    expect(next.state.phase).toMatchObject({ stopped: null });
    expect(sessionView(next.state, R)).toMatchObject({ cover: null, vodReady: true });
  });

  it('막힘 · 마지막 확인에서 넘어온 제안은 고갈 · 회복 통지로 바뀌지 않는다', () => {
    const s = stoppedOffer('held');

    for (const drained of [true, false]) {
      const next = step(s, drain(s, drained), R);
      expect(next.state, `drained ${drained}`).toBe(s);
    }
  });

  it('옛 세대 재생기의 통지 · 제안 밖 국면의 통지는 상태를 바꾸지 않는다', () => {
    const s = stoppedOffer();
    const ending = rewindEnding();
    const events: Array<[PlaybackSessionState, SessionEvent]> = [
      [s, drain(s, true, DRAINED_AT, s.source.generation - 1)],
      [ending, drain(ending, true)],
    ];

    for (const [state, event] of events) {
      expect(step(state, event, R).state).toBe(state);
    }
  });

  it('고갈 덮개의 「다시보기 보기」 는 멎은 자리부터 넘긴다', () => {
    const s = stoppedOffer();
    const drained = feed(s, [drain(s, true)]).state;

    const pressed = step(drained, PRESS_VOD, R);

    expect(pressed.effects).toEqual([
      {
        kind: 'close',
        reason: 'past',
        handoff: {
          resumeAtMs: DRAINED_AT,
          seenVod: { url: VOD, firstPdtMs: T0, headPdtMs: NOW },
          chosen: true,
        },
      },
    ]);
  });
});

describe('받기를 멈춘 뒤 리마운트 — 새 재생기도 받지 않는다', () => {
  /** 다시보기 제안에서 지금 세대 재생기가 받기를 멈춘 세션 */
  function stoppedOffer(stopped: OfferStopped = null): PlaybackSessionState {
    const s = offered(stopped);
    return feed(s, [loadStopped(s)]).state;
  }

  it('고갈 판정 전 리마운트 → 덮개 단추 · 기록을 새 세대로 옮김 · 보류 · 이어 볼 자리는 그대로', () => {
    const s = stoppedOffer();

    const next = step(s, REMOUNT, R);

    expect(next.state.source.generation).toBe(s.source.generation + 1);
    expect(next.state.phase).toMatchObject({ kind: 'vod-offered', stopped: 'drained' });
    expect(next.state.loadStoppedGeneration).toBe(s.source.generation + 1);
    expect(isPlayerStartHeld(next.state)).toBe(true);
    expect(sessionView(next.state, R).cover).toBe('vod-ready');
    expect(resumeAtMs(next.state)).toBe(resumeAtMs(s));
    expect(next.effects).toEqual([]);
  });

  it('리마운트로 선 새 재생기는 받지 않으므로 시크해도 덮개가 내려가지 않는다', () => {
    const s = stoppedOffer();
    const remounted = feed(s, [REMOUNT]).state;
    const target = P20 + 2 * MIN;

    const sought = feed(remounted, [
      { type: 'seek', targetMs: target, view: { lowerMs: T0, endMs: NOW } },
    ]).state;

    expect(sought.phase).toMatchObject({ stopped: 'drained' });
    expect(isPlayerStartHeld(sought)).toBe(true);
  });

  it('받기 중단 기록이 없으면 리마운트는 제안을 그대로 둔다', () => {
    const s = offered();

    const next = step(s, REMOUNT, R);

    expect(next.state.phase).toEqual(s.phase);
    expect(next.state.loadStoppedGeneration).toBe(s.loadStoppedGeneration);
    expect(isPlayerStartHeld(next.state)).toBe(false);
  });

  it('막힘 · 마지막 확인에서 넘어온 제안은 그대로 두고 기록만 옮긴다', () => {
    const s = stoppedOffer('held');

    const next = step(s, REMOUNT, R);

    expect(next.state.phase).toMatchObject({ stopped: 'held' });
    expect(next.state.loadStoppedGeneration).toBe(s.source.generation + 1);
  });

  it('제안 밖(되감기)의 기록은 리마운트가 옮기지 않는다 — 새 재생기는 지금처럼 선다', () => {
    const rewind = rewinding();
    const s = feed(rewind, [loadStopped(rewind)]).state;

    const next = step(s, REMOUNT, R);

    expect(next.state.phase).toEqual(s.phase);
    expect(next.state.loadStoppedGeneration).toBe(s.source.generation);
    expect(loadStoppedNow(next.state)).toBe(false);
    expect(isPlayerStartHeld(next.state)).toBe(false);
  });
});

describe('고갈 감시 · 재생기 보류 표 — 받기 중단은 보류만 바꾼다', () => {
  const stops: OfferStopped[] = [null, 'drained', 'held'];
  for (const stopped of stops) {
    for (const stoppedLoad of [false, true]) {
      it(`stopped ${stopped} · 받기 중단 ${stoppedLoad}`, () => {
        const base = offered(stopped);
        const s = stoppedLoad ? feed(base, [loadStopped(base)]).state : base;
        const view = sessionView(s, R);

        expect(view.cover).toBe(sessionView(base, R).cover);
        expect(view.vodReady).toBe(sessionView(base, R).vodReady);
        expect(watchesDrain(s)).toBe(stopped === null);
        expect(isPlayerStartHeld(s)).toBe(stopped !== null || stoppedLoad);
      });
    }
  }

  it('고갈 감시는 되감기 재생기의 제안에서만 — 라이브 재생기 · 제안 밖 국면은 보지 않는다', () => {
    expect(watchesDrain(liveOffered())).toBe(false);
    expect(watchesDrain(rewinding())).toBe(false);
    expect(watchesDrain(rewindEnding())).toBe(false);
  });
});
