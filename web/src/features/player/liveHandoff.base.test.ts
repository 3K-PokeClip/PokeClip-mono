import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { settleHandoffFields } from './liveHandoff';
import {
  openSession,
  transition,
  type PlaybackSessionState,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { handedOff, pos, resetEventKit, stamped, startRun, streamPoll } from './handoffTestKit';

// 꼬리 넘김의 바탕 칸 시험(POK-200 c13c2 — 설계 r9 4절 A0 · A1 · A3 · A6 · 11절 T13 · T15).
// 시각은 epoch ms 다. 방송 A(회차 r-A)를 보던 세션이 기준이다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
const X_B: XAnswer = { kind: 'known', id: 'r-B' };
const NONE: RewindStatusResult = { kind: 'none' };
const UNAVAILABLE: RewindStatusResult = { kind: 'unavailable' };

function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  const status: RewindStatus = { ready: true, mode: 'live', url, firstPdtMs, headPdtMs };
  return { kind: 'ok', status };
}

function run(start: PlaybackSessionState, events: SessionEvent[]): PlaybackSessionState {
  return events.reduce(
    (state, event) => transition(state, stamped(state, event), NOW).state,
    start,
  );
}

function listLoaded(url: string, lastEndMs: number): SessionEvent {
  return {
    type: 'list-loaded',
    url,
    firstMs: T0,
    lastEndMs,
    targetDurationS: 6,
    headSpanMs: 18_000,
  };
}

/** A1 을 P 에서 트는 되감기 세션. 목록 끝 E = NOW */
function rewindAt(p: number): PlaybackSessionState {
  startRun();
  const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
  return run(fresh, [
    streamPoll(liveReady(A1, T0, NOW)),
    { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
    listLoaded(A1, NOW),
    pos(p),
  ]);
}

beforeEach(resetEventKit);

describe('물음 순서 — 늦게 온 옛 응답은 버린다(T15 · A0)', () => {
  it('a: 새 물음의 none 이 먼저 반영되면 늦게 온 옛 ok 는 효과 없이 버린다', () => {
    const start = rewindAt(T0 + 20 * MIN);
    const ask1 = streamPoll(liveReady(A1, T0, NOW + 8_000), X_A);
    const ask2 = streamPoll(NONE, X_A);
    const after2 = run(start, [ask2]);
    expect(after2.streamAsk).toBe(ask2.ask.seq);
    const late = transition(after2, ask1, NOW);
    expect(late.state).toBe(after2);
    expect(late.effects).toEqual([]);
  });

  it('b: unavailable 은 순번을 올리지 않는다 — 그 앞 물음의 ok 가 나중에 와도 반영된다', () => {
    const start = rewindAt(T0 + 20 * MIN);
    const ask1 = streamPoll(liveReady(A1, T0, NOW + 8_000), X_A);
    const ask2 = streamPoll(UNAVAILABLE);
    const after2 = run(start, [ask2]);
    expect(after2.streamAsk).toBe(start.streamAsk);
    const after1 = run(after2, [ask1]);
    expect(after1.streamAsk).toBe(ask1.ask.seq);
    expect(after1.observedEndMs).toBe(NOW + 8_000);
  });

  it('b′: 잠시 불가라도 x 가 다른 방송이면 순번을 올린다 — 그 앞 물음의 ok · x A 가 늦게 와도 문을 다시 세우지 않는다', () => {
    const start = rewindAt(NOW - 6_000);
    const ask1 = streamPoll(liveReady(A1, T0, NOW), X_A);
    const ask2 = streamPoll(UNAVAILABLE, X_B);
    const after2 = run(start, [ask2]);
    expect(after2.streamAsk).toBe(ask2.ask.seq);
    const late = transition(after2, ask1, NOW);
    expect(late.state).toBe(after2);
    expect(late.state.handoffDoor).toBeNull();
  });

  it('c: 반영된 ok 뒤에 온 옛 unavailable(x B)은 묶인 live 의 띠를 세우지 않는다', () => {
    const bound = run(rewindAt(T0 + 20 * MIN), [{ type: 'live-button', x: X_A, attempt: 0 }]);
    expect(bound.boundLive).toBe(true);
    const ask1 = streamPoll(UNAVAILABLE, X_B);
    const ask2 = streamPoll(liveReady(A1, T0, NOW + 4_000), X_A);
    const after = run(bound, [ask2, ask1]);
    expect(after.streamAsk).toBe(ask2.ask.seq);
    expect(after.foreignLive).toBeNull();
  });
});

describe('목록 칸 list(A3 · A6 · 목록 로드)', () => {
  it('지금 url 의 목록 로드만 list 를 고친다 — 끝 · TD · 봉인 안 됨', () => {
    const s = rewindAt(T0 + 20 * MIN);
    expect(s.list).toEqual({ endMs: NOW, tdMs: 6_000, sealed: false });
    expect(run(s, [listLoaded(A2, NOW + MIN)]).list).toEqual(s.list);
  });

  it('A3: ENDLIST 는 list 를 봉인하고, 같은 url 의 목록 로드가 다시 와도 봉인은 남는다', () => {
    const sealed = run(rewindAt(T0 + 20 * MIN), [{ type: 'endlist' }]);
    expect(sealed.list).toEqual({ endMs: NOW, tdMs: 6_000, sealed: true });
    expect(run(sealed, [listLoaded(A1, NOW + 2_000)]).list).toEqual({
      endMs: NOW + 2_000,
      tdMs: 6_000,
      sealed: true,
    });
  });

  it('A3: list 가 없으면 ENDLIST 는 list 를 세우지 않는다', () => {
    startRun();
    const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
    expect(run(fresh, [{ type: 'endlist' }]).list).toBeNull();
  });

  it('A6: 이어짐으로 url 이 바뀌면 봉인된 옛 list 를 비운다', () => {
    const waiting = run(rewindAt(T0 + 20 * MIN), [{ type: 'endlist' }]);
    const resumed = run(waiting, [streamPoll(liveReady(A2, T0, NOW + MIN), X_A)]);
    expect(resumed.source.url).toBe(A2);
    expect(resumed.list).toBeNull();
  });

  it('T13: LIVE 뒤 같은 되감기 url 로 재진입한 직후에는 옛 E 의 list 가 없다', () => {
    const live = run(rewindAt(T0 + 20 * MIN), [{ type: 'live-button', x: X_A, attempt: 0 }]);
    expect(live.list).toBeNull();
    const back = run(live, [
      { type: 'seek', targetMs: T0 + 30 * MIN, view: { lowerMs: NOW - 16_000, endMs: NOW } },
    ]);
    expect(back.source).toMatchObject({ kind: 'rewind', url: A1 });
    expect(back.list).toBeNull();
  });
});

describe('재생 여부(A1)와 넘김 칸 후처리(A6)', () => {
  it('A1: 위치 사건이 playing 을 고친다', () => {
    const paused = run(rewindAt(T0 + 20 * MIN), [pos(T0 + 20 * MIN, { playing: false })]);
    expect(paused.playing).toBe(false);
    expect(run(paused, [pos(T0 + 20 * MIN + 1_000)]).playing).toBe(true);
  });

  it('A6: live 를 떠나면 handoff, url 이 바뀌거나 rewind · 끝남 대기를 떠나면 탐침을 비운다', () => {
    const live = run(rewindAt(T0 + 20 * MIN), [{ type: 'live-button', x: X_A, attempt: 0 }]);
    const handoff = { fromUrl: A1, endMs: NOW, atMs: NOW - 6_000, deadlineMs: NOW, seenAtMs: null };
    const handoffProbe = { seq: 1, fromUrl: A1, endMs: NOW };
    const prev = { ...live, handoff, handoffProbe };
    const leftLive = settleHandoffFields(prev, { ...prev, phase: { kind: 'vod' } });
    expect([leftLive.handoff, leftLive.handoffProbe]).toEqual([null, null]);
    const confirming = { ...prev, handoffProbe: null };
    expect(settleHandoffFields(confirming, confirming)).toBe(confirming);
    const rewind = {
      ...prev,
      handoff: null,
      phase: { kind: 'rewind' as const, pendingNext: null },
    };
    expect(settleHandoffFields(rewind, rewind)).toBe(rewind);
    const moved = { ...rewind, source: { ...rewind.source, url: A2 } };
    expect(settleHandoffFields(rewind, moved).handoffProbe).toBeNull();
  });
});

describe('시험 도우미 — 연속 길이 구간', () => {
  it('pos 의 runMs 는 한 재생 구간에서만 이어 세고, 새 세션(startRun) · 넘김(handedOff) 뒤에는 0 부터 센다', () => {
    pos(T0);
    expect(pos(T0 + 1_000).runMs).toBe(1_000);
    startRun();
    expect(pos(T0 + 2_000).runMs).toBe(0);
    handedOff(rewindAt(T0 + 20 * MIN), NOW);
    expect(pos(T0 + 20 * MIN + 1_000).runMs).toBe(0);
  });
});
