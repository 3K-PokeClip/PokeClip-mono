import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { HANDOFF_SKIP_NOTICE } from './liveHandoff';
import {
  openSession,
  transition,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
} from './rewindSwitch';
import { handedOff, pos, resetEventKit, startRun, streamPoll } from './handoffTestKit';

// 넘김 확인 시험(POK-200 c13e — 설계 r9 4절 H0–H7 · A2 · A4 · 11절 T10 · T11 · T17 · T19 · T23).
// 넘김 행(P1)은 c13h 몫이라 시작 상태는 handedOff 가 P1 결과 모양으로 만든다. E = NOW · TD 6초 · P = E − 6000.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const E = NOW;
const P = E - 6_000;
const DEADLINE = NOW + 6_000 + 12_000; // 넘긴 시각 + (E − P) + 2 × TD
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';

function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  const status: RewindStatus = { ready: true, mode: 'live', url, firstPdtMs, headPdtMs };
  return { kind: 'ok', status };
}

/** 라이브 재생기의 위치 사건 — runMs 는 그 재생기의 played 연속 길이다 */
function at(atMs: number, runMs: number): SessionEvent {
  return { type: 'position', atMs, playing: true, runMs };
}

function run(start: PlaybackSessionState, events: SessionEvent[], now = NOW + 1_000) {
  let state = start;
  const effects: SessionEffect[] = [];
  for (const event of events) {
    const next = transition(state, event, now);
    state = next.state;
    effects.push(...next.effects);
  }
  return { state, effects };
}

/** A1 을 P 에서 보다가 NOW 에 라이브로 넘긴 확인 중 세션 */
function confirming(): PlaybackSessionState {
  startRun();
  const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
  const watching = run(fresh, [
    streamPoll(liveReady(A1, T0, NOW)),
    { type: 'seek', targetMs: P, view: { lowerMs: P + 1, endMs: NOW + 28_000 } },
    { type: 'list-loaded', url: A1, firstMs: T0, lastEndMs: E, targetDurationS: 6 },
    pos(P),
  ]).state;
  return handedOff(watching, NOW);
}

function rewindSource(start: PlaybackSessionState, atMs: number) {
  const generation = start.source.generation + 1;
  return { kind: 'rewind', url: A1, startAtMs: atMs, generation, strictStart: false };
}

let info: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  resetEventKit();
  info = vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => info.mockRestore());

describe('끝 증거 · 연속 검사 · 시한(T10)', () => {
  it('a: 확인 중 라이브가 닫히면 마지막 검증 위치에서 끝남 대기(되감기 기원)로 되돌린다', () => {
    const s = confirming();
    const { state, effects } = run(s, [at(E - 5_500, 500), { type: 'live-closed' }]);
    expect(state.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: false });
    expect(state.source).toEqual(rewindSource(s, E - 5_500));
    expect(state.handoffBlock).toEqual({ url: A1, endMs: E, reason: 'reverted' });
    expect([state.handoff, state.boundLive]).toEqual([null, false]);
    expect(effects).toEqual([{ kind: 'refresh', retryWhileUrl: A1 }]);
  });

  it('b · d: 위치 없이 끝 증거(라이브 닫힘 · ENDLIST · 끝 도달)가 오면 P 로 되돌린다', () => {
    for (const type of ['live-closed', 'endlist', 'end-reached'] as const) {
      const s = confirming();
      const { state } = run(s, [{ type: 'playing' }, { type }]);
      expect(state.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind' });
      expect(state.source).toEqual(rewindSource(s, P));
    }
  });

  it('c · g3: 연속으로 E 에 닿으면 확인이 끝나고, 그 뒤 라이브 닫힘은 현행(live 기원 끝남 대기)이다', () => {
    const seen: SessionEvent[] = [];
    for (let k = 1; k <= 6; k += 1) seen.push(at(P + k * 1_000, k * 1_000));
    const before = run(confirming(), seen.slice(0, 5)).state;
    expect(before.handoff?.seenAtMs).toBe(P + 5_000);
    const done = run(before, seen.slice(5)).state;
    expect(done.handoff).toBeNull();
    const closed = run(done, [{ type: 'live-closed' }]).state;
    expect(closed.phase).toMatchObject({ kind: 'ending-wait', origin: 'live', atEnd: true });
  });

  it('e: 건너뛴 시작(연속 시작 > P + 250)은 P 로 되돌리고, 그 안 · P 앞은 둔다', () => {
    const skipped = run(confirming(), [at(E + 3_000, 0)]).state;
    expect(skipped.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(skipped.source).toEqual(rewindSource(confirming(), P));
    expect([skipped.handoff, skipped.handoffBlock?.reason]).toEqual([null, 'reverted']);
    expect(info).toHaveBeenCalledWith('[player] handoff_confirm_failed', 'start');
    expect(run(confirming(), [at(P + 250, 0)]).state.handoff).not.toBeNull();
    expect(run(confirming(), [at(P + 251, 0)]).state.phase.kind).toBe('rewind');
    expect(run(confirming(), [at(P - 6_000, 0)]).state.handoff).not.toBeNull();
  });

  it('g: 확인 중 자동 시크는 마지막 검증 위치로 되돌린다 — 앞으로 간 적 없으면 start · 있으면 jump', () => {
    const still = run(confirming(), [at(P, 0), at(P + 5_000, 0)]).state;
    expect(still.source.startAtMs).toBe(P);
    expect(info).toHaveBeenLastCalledWith('[player] handoff_confirm_failed', 'start');
    const jumped = run(confirming(), [at(P + 1_000, 1_000), at(P + 5_000, 0)]).state;
    expect(jumped.source.startAtMs).toBe(P + 1_000);
    expect(info).toHaveBeenLastCalledWith('[player] handoff_confirm_failed', 'jump');
    expect(run(confirming(), [at(E, 900)]).state.phase.kind).toBe('rewind');
  });

  it('g2: nudge 로 연속 시작이 P + 2300 이 되면 P + 2000 으로 되돌린다', () => {
    const steps = [at(P + 1_000, 1_000), at(P + 2_000, 2_000), at(P + 3_300, 1_000)];
    expect(run(confirming(), steps).state.source.startAtMs).toBe(P + 2_000);
  });

  it('h: 시한(넘긴 시각 + 18000) 전에는 두고, 닿으면 tick · 폴링 어느 사건이든 되돌린다', () => {
    const s = confirming();
    expect(run(s, [{ type: 'tick' }], DEADLINE - 1).state.handoff).not.toBeNull();
    expect(run(s, [{ type: 'tick' }], DEADLINE).state.phase.kind).toBe('rewind');
    expect(run(s, [streamPoll(liveReady(A1, T0, E))], DEADLINE).state.phase.kind).toBe('rewind');
    expect(info).toHaveBeenLastCalledWith('[player] handoff_confirm_failed', 'deadline');
  });

  it('i: 사람의 창 안 시크 · LIVE 는 확인을 그만둔다 — 그 뒤 라이브 닫힘은 되돌림이 아니다', () => {
    const view = { lowerMs: E - 16_000, endMs: E + 20_000 };
    const sought = run(confirming(), [{ type: 'seek', targetMs: E - 2_000, view }]);
    expect([sought.state.phase.kind, sought.state.handoff]).toEqual(['live', null]);
    const live = run(confirming(), [{ type: 'live-button', x: { kind: 'unknown' }, attempt: 0 }]);
    expect([live.state.handoff, live.effects]).toEqual([null, [{ kind: 'seek-live-edge' }]]);
    const closed = run(live.state, [{ type: 'live-closed' }]).state;
    expect(closed.phase).toMatchObject({ kind: 'ending-wait', origin: 'live' });
  });

  it('H1′: playing 은 endingSince 만 비우고 확인은 그대로 둔다', () => {
    const s = { ...confirming(), endingSince: NOW - MIN };
    const { state } = run(s, [{ type: 'playing' }]);
    expect([state.endingSince, state.handoff]).toEqual([null, s.handoff]);
  });
});

describe('다시 세움 · 이탈 · 잘림(T11 · T17 · T19 · T23)', () => {
  it('T11: 확인 중 리마운트 · 목록 400 은 이어 볼 자리에서 strictStart 로 다시 세우고 연속 검사를 새로 센다', () => {
    for (const event of [{ type: 'remount' }, { type: 'live-400' }] as const) {
      const s = run(confirming(), [at(P + 2_000, 2_000)]).state;
      const { state } = run(s, [event]);
      const generation = s.source.generation + 1;
      expect(state.source).toMatchObject({ startAtMs: P + 2_000, strictStart: true, generation });
      expect(state.handoff).toEqual({ ...s.handoff, atMs: P + 2_000, seenAtMs: null });
    }
    const done = run(confirming(), [at(E, 6_000)]).state;
    expect(run(done, [{ type: 'remount' }]).state.source).toMatchObject({ startAtMs: null });
  });

  it('T17: 확인 중 창 밖 시크로 되감기에 다시 들면 확인이 사라지고, 그 뒤 ENDLIST 는 되돌림이 아니다(H5)', () => {
    const entry = { url: A1, firstPdtMs: T0, headPdtMs: E };
    const s = { ...confirming(), entry, rewindOpen: true };
    const view = { lowerMs: E - 16_000, endMs: E + 20_000 };
    const back = run(s, [{ type: 'seek', targetMs: T0 + 10 * MIN, view }]).state;
    expect([back.phase.kind, back.handoff]).toEqual(['rewind', null]);
    const ended = run(back, [{ type: 'endlist' }]).state;
    expect(ended.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: false });
    expect(ended.handoffBlock).toBeNull();
  });

  it('T19: 라이브 첫 목록이 잘리면 P 로 되돌리고 막음 truncated · url 하나에 안내 한 번', () => {
    const s = confirming();
    const first = run(s, [{ type: 'live-start-truncated' }]);
    expect(first.state.source).toEqual(rewindSource(s, P));
    expect(first.state.handoffBlock).toEqual({ url: A1, endMs: E, reason: 'truncated' });
    expect(first.effects).toEqual([{ kind: 'notice', text: HANDOFF_SKIP_NOTICE }]);
    const again = run({ ...confirming(), liveHintUrl: A1 }, [{ type: 'live-start-truncated' }]);
    expect(again.effects).toEqual([]);
  });

  it('T23: 확인이 없을 때의 잘림은 같은 라이브를 strictStart 없이 다시 세운다(H7)', () => {
    const s = { ...confirming(), handoff: null };
    const { state } = run(s, [{ type: 'live-start-truncated' }]);
    expect(state.phase.kind).toBe('live');
    const generation = s.source.generation + 1;
    expect(state.source).toEqual({ ...s.source, strictStart: false, generation });
  });

  it('A2: 막은 url 의 목록이 막을 때의 끝보다 자라면 막음을 푼다', () => {
    const blocked = run(confirming(), [{ type: 'live-closed' }]).state;
    const loaded = (url: string, lastEndMs: number): SessionEvent => ({
      type: 'list-loaded',
      url,
      firstMs: T0,
      lastEndMs,
      targetDurationS: 6,
    });
    expect(run(blocked, [loaded(A1, E)]).state.handoffBlock).not.toBeNull();
    expect(run(blocked, [loaded(LIVE_URL, E + 4_000)]).state.handoffBlock).not.toBeNull();
    expect(run(blocked, [loaded(A1, E + 4_000)]).state.handoffBlock).toBeNull();
  });
});
