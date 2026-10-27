import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import {
  openSession,
  pollTargets,
  transition,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { pos, resetEventKit, startRun, streamPoll } from './handoffTestKit';
import { sessionView } from './sessionView';

// 꼬리 넘김이 들어와도 현행 그대로여야 하는 행의 시험(POK-200 c13 — 설계 r9 11절 T12 등).
// 기존 모듈과 시험 도우미만 읽는다 — 제품 파일을 되돌린 트리(음성 대조)에서도 이 파일이 로드되게.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
const A3 = 'https://media.pokeclip.com/dvr/key-1/a3/index.m3u8';
const VOD = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
const X_B: XAnswer = { kind: 'known', id: 'r-B' };
const X_UNKNOWN: XAnswer = { kind: 'unknown' };

function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  const status: RewindStatus = { ready: true, mode: 'live', url, firstPdtMs, headPdtMs };
  return { kind: 'ok', status };
}

function run(start: PlaybackSessionState, events: SessionEvent[]): PlaybackSessionState {
  return events.reduce((state, event) => transition(state, event, NOW).state, start);
}

/** 봉인된 끝 — A1 을 E(= NOW) 까지 보고 ENDLIST · 끝 도달로 끝남 대기(되감기 기원 · atEnd)에 들었다. 재생 중 · 띠 안 */
function sealedEnd(): PlaybackSessionState {
  const p = NOW - 6_000;
  startRun();
  const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
  return run(fresh, [
    streamPoll(liveReady(A1, T0, NOW)),
    { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
    { type: 'list-loaded', url: A1, firstMs: T0, lastEndMs: NOW, targetDurationS: 6 },
    pos(p),
    { type: 'endlist' },
    pos(NOW),
    { type: 'end-reached' },
  ]);
}

/** 사건 하나의 상태와 효과 종류 */
function step(s: PlaybackSessionState, event: SessionEvent) {
  const { state, effects } = transition(s, event, NOW);
  return { state, kinds: effects.map((e: SessionEffect) => e.kind) };
}

beforeEach(resetEventKit);

describe('끝남 대기의 이음 지점(현행 보존)', () => {
  it('T12: 다음 url 로 이은(R16) 직후의 끝 도달은 마지막 위치에서 셋째 url 로 잇는다', () => {
    const p = T0 + 20 * MIN;
    startRun();
    const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
    const joined = run(fresh, [
      streamPoll(liveReady(A1, T0, NOW)),
      { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
      { type: 'list-loaded', url: A1, firstMs: T0, lastEndMs: NOW, targetDurationS: 6 },
      pos(p),
      streamPoll(liveReady(A2, NOW - 2_000, NOW + MIN), X_A),
      pos(NOW),
      { type: 'end-reached' },
    ]);
    expect(joined.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: NOW });
    const waiting = run(joined, [{ type: 'end-reached' }]);
    expect(waiting.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: true });
    const resumed = run(waiting, [streamPoll(liveReady(A3, NOW - 1_000, NOW + 2 * MIN), X_A)]);
    expect(resumed.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(resumed.source).toMatchObject({ kind: 'rewind', url: A3, startAtMs: NOW });
  });
});

describe('봉인된 끝 — 넘김 0 · 현행 제안 경로(T27)', () => {
  it('T27a: 같은 url 의 live ∧ ready · x A 가 와도 탐침을 청하지 않는다 — 끝남 대기 · rewind 둘 다', () => {
    const waiting = sealedEnd();
    expect(waiting.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: true });
    expect(step(waiting, streamPoll(liveReady(A1, T0, NOW), X_A)).kinds).toEqual([]);
    startRun();
    const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
    const p = NOW - 6_000;
    const sealedRewind = run(fresh, [
      streamPoll(liveReady(A1, T0, NOW)),
      { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
      { type: 'list-loaded', url: A1, firstMs: T0, lastEndMs: NOW, targetDurationS: 6 },
      streamPoll(liveReady(A2, NOW + MIN, NOW + 2 * MIN), X_A),
      { type: 'endlist' },
      pos(NOW - 1_000),
    ]);
    expect(sealedRewind.phase.kind).toBe('rewind');
    expect(step(sealedRewind, streamPoll(liveReady(A1, T0, NOW), X_A)).kinds).toEqual([]);
  });

  it('T27c: E 를 덮지 않는 새 url 은 이어짐 없이 제안 — x A 「라이브 보기」 · x B 「새 방송 보기」 · x 모름 없음', () => {
    const later = (x: XAnswer) => streamPoll(liveReady(A2, NOW + MIN, NOW + 2 * MIN), x);
    const goLive = step(sealedEnd(), later(X_A));
    expect(goLive.kinds).toEqual([]);
    expect(goLive.state.source.url).toBe(A1);
    expect(goLive.state.offer).toEqual({ kind: 'go-live' });
    expect(sessionView(goLive.state, NOW).actions).toEqual(['record', 'go-live']);
    expect(step(sealedEnd(), later(X_B)).state.offer).toMatchObject({ goTo: 'r-B' });
    expect(step(sealedEnd(), later(X_UNKNOWN)).state.offer).toBeNull();
  });

  it('T27d: E 를 덮는 새 url 은 현행 이어짐 — E 에서 되감기로 잇고 넘기지 않는다', () => {
    const resumed = step(sealedEnd(), streamPoll(liveReady(A2, NOW - 2_000, NOW + MIN), X_A));
    expect(resumed.kinds).toEqual([]);
    expect(resumed.state.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: NOW });
  });

  it('T27e: 다시보기 제안 뒤에는 폴링을 끄고, 스트림 응답 · atEnd 의 LIVE 는 아무것도 바꾸지 않는다', () => {
    const vod: RewindStatusResult = {
      kind: 'ok',
      status: { ready: true, mode: 'vod', url: VOD, firstPdtMs: T0, headPdtMs: NOW },
    };
    const offered = run(sealedEnd(), [{ type: 'round-poll', result: vod }]);
    expect(offered.phase).toMatchObject({ kind: 'vod-offered', atEnd: true });
    expect(pollTargets(offered)).toEqual({ stream: false, round: false });
    const polled = step(offered, streamPoll(liveReady(A2, NOW - 2_000, NOW + MIN), X_A));
    expect(polled.state.phase).toBe(offered.phase);
    expect([polled.state.offer, polled.kinds]).toEqual([null, []]);
    const live = transition(offered, { type: 'live-button', x: X_A, attempt: 0 }, NOW);
    expect(live).toEqual({ state: offered, effects: [] });
  });
});
