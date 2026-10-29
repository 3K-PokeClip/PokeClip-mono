import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { HANDOFF_DOOR_FRESH_MS, doorOpen } from './liveHandoff';
import {
  openSession,
  transition,
  type PlaybackSessionState,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { REWIND_POLL_MS, REWIND_RETRY_MS } from './useRewindStatus';
import { pos, resetEventKit, stamped, startRun, streamPoll } from './handoffTestKit';

// 넘김 문 시험(POK-200 c13d — 설계 r9 1절 넘김 문 · 4절 D 표 · R9 · R10 · 11절 T3 ·
// T4 · T14 · T16 · T21 · T27).
// 문 = 넘김 근거 응답 하나 {url, 받은 시각}. 넘김 자체는 c13h 몫이라 여기서는 문 칸과 doorOpen 만 본다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const R = NOW + 1_000; // 근거 응답을 받은 시각
const P = NOW - 6_000;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
const X_B: XAnswer = { kind: 'known', id: 'r-B' };
const X_UNKNOWN: XAnswer = { kind: 'unknown' };
const NONE: RewindStatusResult = { kind: 'none' };
const UNAVAILABLE: RewindStatusResult = { kind: 'unavailable' };

function status(patch: Partial<RewindStatus>): RewindStatusResult {
  const base: RewindStatus = {
    ready: false,
    mode: null,
    url: null,
    firstPdtMs: null,
    headPdtMs: null,
  };
  return { kind: 'ok', status: { ...base, ...patch } };
}

function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  return status({ ready: true, mode: 'live', url, firstPdtMs, headPdtMs });
}

function run(start: PlaybackSessionState, events: SessionEvent[], at = R): PlaybackSessionState {
  return events.reduce((state, event) => transition(state, stamped(state, event), at).state, start);
}

/** A1 을 P 에서 트는 되감기 세션(목록 끝 E = NOW) */
function rewindAt(): PlaybackSessionState {
  startRun();
  const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
  return run(fresh, [
    streamPoll(liveReady(A1, T0, NOW)),
    { type: 'seek', targetMs: P, view: { lowerMs: P + 1, endMs: NOW + 28_000 } },
    {
      type: 'list-loaded',
      url: A1,
      firstMs: T0,
      lastEndMs: NOW,
      targetDurationS: 6,
      headSpanMs: 18_000,
    },
    pos(P),
  ]);
}

/** 문이 선 되감기 세션 — A1 의 live ∧ ready · x A 를 R 에 받았다 */
function doored(): PlaybackSessionState {
  return run(rewindAt(), [streamPoll(liveReady(A1, T0, NOW), X_A, R)]);
}

/** 끝에 닿은 끝남 대기(되감기 기원 · atEnd) · 문이 섰다. sealed 면 ENDLIST 를 거쳐 list 가 봉인됐다 */
function endingDoored(sealed = false): PlaybackSessionState {
  const before: SessionEvent[] = sealed ? [{ type: 'endlist' }] : [];
  return run(rewindAt(), [
    ...before,
    pos(NOW),
    { type: 'end-reached' },
    streamPoll(liveReady(A1, T0, NOW), X_A, R),
  ]);
}

beforeEach(resetEventKit);

describe('문 세움과 열림(D1 · D3 · T4 · T14)', () => {
  it('T4a: 같은 url 의 live ∧ ready · x A 가 문을 세우고, 받은 시각부터 12000ms 까지 열려 있다', () => {
    const s = doored();
    expect(s.handoffDoor).toEqual({ url: A1, atMs: R });
    expect(doorOpen(s, R + 12_000)).toBe(true);
    expect(doorOpen(s, R + 12_001)).toBe(false);
  });

  it('T14: 문 신선도 = 폴링 주기 + 재시도 간격', () => {
    expect(HANDOFF_DOOR_FRESH_MS).toBe(REWIND_POLL_MS + REWIND_RETRY_MS);
  });

  it('T4b: x 가 다른 방송이면 문을 지운다', () => {
    expect(run(doored(), [streamPoll(liveReady(A1, T0, NOW), X_B, R)]).handoffDoor).toBeNull();
  });

  it('T4c: P 를 덮지 않는 새 url(R10)은 pendingNext 를 적고 문을 지운다', () => {
    const s = run(doored(), [streamPoll(liveReady(A2, NOW + MIN, NOW + 2 * MIN), X_A, R)]);
    expect(s.phase).toMatchObject({ kind: 'rewind', pendingNext: { url: A2 } });
    expect(s.handoffDoor).toBeNull();
  });

  it('T4d: ready:false · 404 는 문을 지운다', () => {
    const notReady = status({ mode: 'live', url: A1 });
    expect(run(doored(), [streamPoll(notReady, X_A, R)]).handoffDoor).toBeNull();
    expect(run(doored(), [streamPoll(NONE, X_A, R)]).handoffDoor).toBeNull();
  });

  it('T4e: x 를 모르면 문을 세우지 않고, 선 문은 그대로 둔다', () => {
    // 순번이 세션의 앞 폴링보다 뒤여야 A0 가 버리지 않는다 — 세션을 만든 뒤에 사건을 만든다
    const doorless = rewindAt();
    const unknown = streamPoll(liveReady(A1, T0, NOW), X_UNKNOWN, R);
    const polled = run(doorless, [unknown]);
    const { streamAsk, handoffDoor } = polled;
    expect([streamAsk, handoffDoor]).toEqual([unknown.ask.seq, null]);
    const s = doored();
    expect(run(s, [streamPoll(liveReady(A1, T0, NOW), X_UNKNOWN, R + 5_000)]).handoffDoor).toBe(
      s.handoffDoor,
    );
  });
});

describe('url 교체 · 이어짐의 문(R9 · 끝남 대기 이어짐 · T3 · T16)', () => {
  const covering = (x: XAnswer) =>
    streamPoll(liveReady(A2, T0 + 30 * MIN, NOW + MIN), x, R + 3_000);

  it('T16: 같은 P 로 url 을 바꾸는 것은 x A 일 때만 — 새 url 의 문 · x 모름 · x B 는 바꾸지 않고 문을 비운다(D4)', () => {
    const known = run(doored(), [covering(X_A)]);
    expect(known.source.url).toBe(A2);
    expect(known.handoffDoor).toEqual({ url: A2, atMs: R + 3_000 });
    for (const x of [X_UNKNOWN, X_B]) {
      const kept = run(doored(), [covering(x)]);
      expect(kept.source.url).toBe(A1);
      expect(kept.handoffDoor).toBeNull();
    }
  });

  it('T16 신선도 기준: 받은 시각이 기준이다 — X 를 기다려 2초 뒤 들어와도', () => {
    const s = transition(rewindAt(), streamPoll(liveReady(A1, T0, NOW), X_A, R), R + 2_000).state;
    expect(s.handoffDoor?.atMs).toBe(R);
    expect(doorOpen(s, R + 12_001)).toBe(false);
  });

  it('T3: 끝남 대기(되감기 기원)에서 새 url 로 이어지면 x A 일 때 새 url 의 문을 세운다', () => {
    const waiting = run(rewindAt(), [pos(NOW), { type: 'end-reached' }]);
    const resumed = run(waiting, [streamPoll(liveReady(A2, NOW - 2_000, NOW + MIN), X_A, R)]);
    expect(resumed.source).toMatchObject({ kind: 'rewind', url: A2 });
    expect(resumed.handoffDoor).toEqual({ url: A2, atMs: R });
    expect(resumed.list).toBeNull();
  });
});

describe('끝남 대기의 문(D 표 · D4 · D5 · T21 · T27)', () => {
  it('T21: 부정 응답(ready:false · 404 · x B · 다른 url · ending)은 문을 지운다', () => {
    const s = endingDoored();
    expect(s.handoffDoor).toEqual({ url: A1, atMs: R });
    const negatives: SessionEvent[] = [
      streamPoll(status({ mode: 'live', url: A1 }), X_A),
      streamPoll(NONE, X_A),
      streamPoll(liveReady(A1, T0, NOW), X_B),
      streamPoll(liveReady(A2, NOW + MIN, NOW + 2 * MIN), X_A),
      streamPoll(status({ mode: 'ending', url: A1 }), X_A),
    ];
    for (const event of negatives) expect(run(s, [event]).handoffDoor).toBeNull();
  });

  it('T21: x 모름 · unavailable 은 문을 그대로 둔다(받은 시각도)', () => {
    const s = endingDoored();
    const unknown = streamPoll(liveReady(A1, T0, NOW), X_UNKNOWN, R + 5_000);
    expect(run(s, [unknown]).handoffDoor).toBe(s.handoffDoor);
    expect(run(s, [streamPoll(UNAVAILABLE, X_A, R + 5_000)]).handoffDoor).toBe(s.handoffDoor);
  });

  it('T27a: 봉인된 목록에서도 문은 선다 — 끝남 대기 · rewind(ENDLIST ∧ pendingNext) 둘 다', () => {
    expect(endingDoored(true).handoffDoor).toEqual({ url: A1, atMs: R });
    const sealedRewind = run(rewindAt(), [
      streamPoll(liveReady(A2, NOW + MIN, NOW + 2 * MIN), X_A),
      { type: 'endlist' },
      streamPoll(liveReady(A1, T0, NOW), X_A, R),
    ]);
    expect(sealedRewind.list?.sealed).toBe(true);
    expect(sealedRewind.handoffDoor).toEqual({ url: A1, atMs: R });
  });

  it('T27b: ending(url null · url 같음)은 열린 문을 지운다', () => {
    const s = endingDoored(true);
    expect(run(s, [streamPoll(status({ mode: 'ending' }), X_A)]).handoffDoor).toBeNull();
    expect(run(s, [streamPoll(status({ mode: 'ending', url: A1 }), X_A)]).handoffDoor).toBeNull();
  });
});
