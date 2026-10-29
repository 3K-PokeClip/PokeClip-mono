import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { HANDOFF_NOTICE, HANDOFF_SKIP_NOTICE } from './liveHandoff';
import {
  openSession,
  transition,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { pos, resetEventKit, settleProbe, stamped, startRun, streamPoll } from './handoffTestKit';

// 꼬리 넘김 시험(POK-200 c13h — 설계 r9 4절 R3 · R6 · R6′ · R7 · R11 · P0–P4 · E1 ·
// 11절 T1–T9 · T18 · T21 · T27). 「→ 1」 = 탐침 요청 1 → 무잘림 결과 → 넘김 1 ·
// 「→ 0」 = 탐침 요청 0. E = NOW · TD 6초 · 문은 R 에 받은 응답이다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const E = NOW;
const P = E - 6_000;
const R = NOW + 1_000;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
const X_UNKNOWN: XAnswer = { kind: 'unknown' };
const UNAVAILABLE: RewindStatusResult = { kind: 'unavailable' };

function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  const status: RewindStatus = { ready: true, mode: 'live', url, firstPdtMs, headPdtMs };
  return { kind: 'ok', status };
}

function loaded(url: string, firstMs: number, lastEndMs: number, td = 6): SessionEvent {
  return {
    type: 'list-loaded',
    url,
    firstMs,
    lastEndMs,
    targetDurationS: td,
    headSpanMs: td * 3_000,
  };
}

const ENDLIST: SessionEvent = { type: 'endlist' };
const END_REACHED: SessionEvent = { type: 'end-reached' };
const doorPoll = (x: XAnswer = X_A, at = R) => streamPoll(liveReady(A1, T0, NOW), x, at);
/** P 를 덮지 않는 다음 url — 순번이 세션의 앞 폴링보다 뒤여야 하므로 세션을 만든 뒤에 부른다 */
const laterPoll = () => streamPoll(liveReady(A2, NOW + MIN, NOW + 2 * MIN), X_A);

function feed(start: PlaybackSessionState, events: SessionEvent[], now = R) {
  let state = start;
  const effects: SessionEffect[] = [];
  for (const event of events) {
    const next = transition(state, stamped(state, event), now);
    state = next.state;
    effects.push(...next.effects);
  }
  return { state, effects };
}

const probes = (effects: SessionEffect[]) => effects.filter((e) => e.kind === 'probe-live').length;

/** 사건들을 먹이고 나가 있는 탐침을 무잘림 결과로 끝낸다 — 탐침 1 · 넘김 1 이면 1, 탐침이 없으면 0 */
function handOffs(start: PlaybackSessionState, events: SessionEvent[], now = R): number {
  const asked = feed(start, events, now);
  if (asked.state.handoffProbe === null) return 0;
  const done = feed(asked.state, [settleProbe(asked.state)], now);
  return probes(asked.effects) === 1 && done.state.phase.kind === 'live' ? 1 : 0;
}

/** A1 을 p 에서 트는 되감기 세션(목록 끝 E · 문 없음 · 위치 사건 전) */
function rewindNear(p = P, td = 6): PlaybackSessionState {
  startRun();
  const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
  return feed(fresh, [
    streamPoll(liveReady(A1, T0, NOW)),
    { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
    loaded(A1, T0, NOW, td),
  ]).state;
}

/** 문이 선 되감기 세션 — 위치 사건 전이라 아직 띠 밖이다 */
const watching = (p = P, td = 6) => feed(rewindNear(p, td), [doorPoll()]).state;

/** 끝(lastAtMs = at)에 닿은 끝남 대기(되감기 기원 · atEnd) — 문은 아직 없다 */
function endingAt(at = E): PlaybackSessionState {
  return feed(rewindNear(), [pos(P), pos(at), END_REACHED]).state;
}

let info: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  resetEventKit();
  info = vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => info.mockRestore());

describe('rewind 의 탐침과 넘김(R6 · R11 · P1 · T1–T5 · T7–T9)', () => {
  it('T1: 띠 끝(E − 6000)의 위치가 탐침을 청하고, 무잘림 결과가 P 에서 라이브로 넘긴다', () => {
    const asked = feed(watching(), [pos(P)]);
    expect(asked.effects).toEqual([{ kind: 'probe-live', seq: 1, url: LIVE_URL }]);
    expect(asked.state.handoffProbe).toEqual({ seq: 1, fromUrl: A1, endMs: E });
    const generation = asked.state.source.generation + 1;
    const done = feed(asked.state, [settleProbe(asked.state)], R + 500);
    expect(done.state.phase).toEqual({ kind: 'live' });
    const source = { kind: 'live', url: LIVE_URL, startAtMs: P, strictStart: true, generation };
    expect(done.state.source).toEqual(source);
    expect(done.effects).toEqual([{ kind: 'notice', text: HANDOFF_NOTICE }, { kind: 'refresh' }]);
    const handoff = {
      fromUrl: A1,
      endMs: E,
      atMs: P,
      deadlineMs: R + 500 + 18_000,
      seenAtMs: null,
    };
    expect(done.state.handoff).toEqual(handoff);
    expect(done.state).toMatchObject({ handoffDoor: null, handoffProbe: null, boundLive: true });
  });

  it('T1 음성: E − 6001 은 띠 밖이라 탐침을 청하지 않는다', () => {
    expect(handOffs(watching(E - 6_001), [pos(E - 6_001)])).toBe(0);
  });

  it('T2: 목록 안 PDT 공백으로 위치가 띠 안으로 뛰어도 넘긴다', () => {
    const s = watching(E - 20_000);
    expect(handOffs(s, [pos(E - 20_000), pos(E - 4_000, { runMs: 0 })])).toBe(1);
  });

  it('T3: 재접속 계승으로 url2 에 이으면 목록이 오기 전에는 0, url2 목록 뒤 띠 안 위치면 1', () => {
    const resumed = feed(endingAt(), [streamPoll(liveReady(A2, NOW - 2_000, NOW + MIN), X_A, R)]);
    expect(resumed.state.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: E });
    expect(handOffs(resumed.state, [pos(E + 500)])).toBe(0);
    expect(handOffs(resumed.state, [loaded(A2, NOW - 2_000, NOW + 6_000), pos(E + 500)])).toBe(1);
  });

  it('T4a: 문은 받은 시각부터 12000ms 까지만 넘김을 연다', () => {
    expect(handOffs(watching(), [pos(P)], R + 12_000)).toBe(1);
    expect(handOffs(watching(), [pos(P)], R + 12_001)).toBe(0);
  });

  it('T4e: 열린 문 뒤의 x 모름은 문을 두고 넘긴다', () => {
    expect(handOffs(watching(), [doorPoll(X_UNKNOWN, R + 5_000), pos(P)])).toBe(1);
  });

  it('T4f: 일시정지 위치는 0, 재생을 다시 시작한 위치는 1', () => {
    const paused = feed(watching(), [pos(P, { playing: false })]);
    expect(probes(paused.effects)).toBe(0);
    expect(handOffs(paused.state, [pos(P + 500)])).toBe(1);
  });

  it('T4g: P 를 덮지 않는 다음 url(pendingNext)이 있으면 문이 다시 열려도 0', () => {
    const s = rewindNear();
    expect(handOffs(s, [pos(P), laterPoll(), doorPoll(), pos(P + 500)])).toBe(0);
  });

  it('T5: 띠 안에서 멈춰 있다가 같은 url 의 live ∧ ready · x A 가 문을 열면 그 응답이 탐침을 청한다(R11)', () => {
    const stalled = feed(rewindNear(), [pos(P)]);
    expect(probes(stalled.effects)).toBe(0);
    expect(handOffs(stalled.state, [doorPoll()])).toBe(1);
  });

  it('T7: 띠 = 1 × 지금 목록의 TD — TD 6 · TD 7 경계', () => {
    expect(handOffs(watching(E - 5_500), [pos(E - 5_500)])).toBe(1);
    expect(handOffs(watching(E - 7_000, 7), [pos(E - 7_000)])).toBe(1);
    expect(handOffs(watching(E - 7_001, 7), [pos(E - 7_001)])).toBe(0);
  });

  it('T8: E 뒤로의 시크는 E 로 자르고, 그 자리의 재생 위치가 넘긴다 — 일시정지면 0(R3)', () => {
    const far = E - 20_000;
    const s = feed(watching(far), [pos(far)]).state;
    const view = { lowerMs: T0, endMs: E };
    const seek: SessionEvent = { type: 'seek', targetMs: E + 5_000, view };
    expect(feed(s, [seek]).effects).toEqual([{ kind: 'seek-within', atMs: E }]);
    expect(handOffs(s, [seek, pos(E)])).toBe(1);
    expect(handOffs(s, [seek, pos(E, { playing: false })])).toBe(0);
  });

  it('T9: live 국면의 위치 사건은 어떤 자리여도 rewind 로 가지 않고 탐침도 없다', () => {
    startRun();
    const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
    const { state, effects } = feed(fresh, [doorPoll(), pos(P), pos(E), pos(E + 3_000)]);
    expect([state.phase.kind, state.source.kind, probes(effects)]).toEqual(['live', 'live', 0]);
  });
});

describe('끝남 대기의 탐침과 넘김(E1 · T6 · T21 · T27)', () => {
  it('T6: 끝에 닿은 끝남 대기에서 문이 열리면 탐침을 청하고, 넘길 때 제안도 비운다', () => {
    const offered = feed(endingAt(), [laterPoll()]).state;
    expect(offered.offer).toEqual({ kind: 'go-live' });
    const asked = feed(offered, [doorPoll()]);
    expect(probes(asked.effects)).toBe(1);
    const done = feed(asked.state, [settleProbe(asked.state)]);
    expect(done.state).toMatchObject({ phase: { kind: 'live' }, offer: null });
    expect(done.state.source.startAtMs).toBe(E);
  });

  it('T21c · d: 탐침 사이의 x 모름 · 잠시 불가는 문을 두어 넘긴다 — 문이 12001ms 지나 온 결과면 0', () => {
    const middles = [() => doorPoll(X_UNKNOWN), () => streamPoll(UNAVAILABLE, X_A)];
    for (const middle of middles) {
      const s = endingAt();
      expect(handOffs(s, [doorPoll(), middle()])).toBe(1);
    }
    const asked = feed(endingAt(), [doorPoll(), streamPoll(UNAVAILABLE, X_A)]).state;
    const late = feed(asked, [settleProbe(asked)], R + 12_001);
    expect(late.state).toMatchObject({ phase: { kind: 'ending-wait' }, handoffProbe: null });
    expect(late.effects).toEqual([]);
  });

  it('A1 음성: 끝남 대기의 위치 사건은 넘김 조건이어도 탐침을 청하지 않는다 — E1 은 폴링만', () => {
    // 멈춘 채 결과가 와 P4 가 탐침을 비운 끝남 대기 — 문 · 띠 · 끝 도달은 그대로다
    const paused = pos(E, { playing: false });
    const asked = feed(endingAt(), [doorPoll(), paused]).state;
    const cleared = feed(asked, [settleProbe(asked)]).state;
    expect(cleared.phase.kind).toBe('ending-wait');
    expect(cleared.handoffProbe).toBeNull();
    expect(probes(feed(cleared, [pos(E)]).effects)).toBe(0);
  });

  it('T27a 재확인: 봉인된 목록(ENDLIST → 끝 도달)은 문이 서도 탐침을 청하지 않는다', () => {
    const sealed = feed(rewindNear(), [pos(P), ENDLIST, pos(E), END_REACHED]);
    expect(sealed.state.list?.sealed).toBe(true);
    const polled = feed(sealed.state, [doorPoll()]);
    expect([polled.state.handoffDoor?.url, probes(polled.effects)]).toEqual([A1, 0]);
  });
});

describe('무잘림 게이트(P0–P4 · T18)', () => {
  const settleAt = (floorMs: number) => {
    const asked = feed(watching(), [pos(P)]).state;
    return feed(asked, [settleProbe(asked, floorMs)]);
  };
  const truncated = { url: A1, endMs: E, reason: 'truncated' };

  it('a · b: 하한이 P 이하(d = 8 · d = 10 경계)면 넘긴다', () => {
    expect(settleAt(E - 8_000).state.phase.kind).toBe('live');
    expect(settleAt(P).state.phase.kind).toBe('live');
  });

  it('c · e: 하한이 P 뒤(d = 11 · d = 60)면 되감기 그대로 막고 LIVE 안내를 한 번 한다', () => {
    for (const floorMs of [E - 5_000, E + 44_000]) {
      const { state, effects } = settleAt(floorMs);
      expect(state).toMatchObject({ source: { kind: 'rewind', url: A1 }, handoffProbe: null });
      expect([state.handoffBlock, state.liveHintUrl]).toEqual([truncated, A1]);
      expect(effects).toEqual([{ kind: 'notice', text: HANDOFF_SKIP_NOTICE }]);
    }
    const logged = { dMs: 60_000, truncatedMs: 50_000 };
    expect(info).toHaveBeenCalledWith('[player] handoff_skipped_truncated', logged);
  });

  it('잘림 로그는 url 을 싣지 않는다 — 탐침(P2) · 첫 목록 재검사(H2t) 둘 다', () => {
    const live = settleAt(E - 8_000).state;
    feed(live, [{ type: 'live-start-truncated' }]);
    settleAt(E - 5_000);
    const skipped = info.mock.calls.filter(([tag]) => tag === '[player] handoff_skipped_truncated');
    expect(skipped).toHaveLength(2);
    // 주소 전체만이 아니라 스트림 키 · 세션 값 · 경로 조각 하나도 싣지 않는다
    const leaks = /https?:|key-1|session=|\/dvr\/|\/live\//;
    expect(JSON.stringify(skipped)).not.toMatch(leaks);
  });

  it('d: 막은 url 은 목록이 자라기 전에는 0, 자란 뒤 다시 잘리면 안내 없이 다시 막는다', () => {
    const blocked = settleAt(E - 5_000).state;
    const grown = (lastEndMs: number) => loaded(A1, T0, lastEndMs);
    expect(probes(feed(blocked, [grown(E), pos(P + 100)]).effects)).toBe(0);
    const asked = feed(blocked, [grown(E + 4_000), pos(E - 1_000)]);
    expect(probes(asked.effects)).toBe(1);
    const again = feed(asked.state, [settleProbe(asked.state, E)]);
    expect([again.state.handoffBlock?.endMs, again.effects]).toEqual([E + 4_000, []]);
  });

  it('f: url 이 바뀌어 비운 탐침의 결과는 순번이 달라 버린다 — 새 탐침이 나가 있어도', () => {
    const asked = feed(watching(), [pos(P)]).state;
    const old: SessionEvent = { ...settleProbe(asked), seq: 1 };
    const covering = streamPoll(liveReady(A2, T0 + 30 * MIN, NOW + MIN), X_A, R);
    const moved = feed(asked, [covering]).state;
    expect([moved.source.url, moved.handoffProbe]).toEqual([A2, null]);
    expect(feed(moved, [old])).toEqual({ state: moved, effects: [] });
    const second = feed(moved, [loaded(A2, T0 + 30 * MIN, NOW + 2_000), pos(NOW - 3_000)]).state;
    expect(second.handoffProbe?.seq).toBe(2);
    expect(feed(second, [old])).toEqual({ state: second, effects: [] });
  });

  it('g: 잠시 불가 결과는 안내 없이 막고, 같은 자리의 위치가 다시 청하지 않는다', () => {
    const asked = feed(watching(), [pos(P)]).state;
    const result: SessionEvent = { type: 'live-probe', seq: 1, result: { kind: 'unavailable' } };
    const { state, effects } = feed(asked, [result]);
    expect([state.handoffBlock, effects]).toEqual([{ url: A1, endMs: E, reason: 'unknown' }, []]);
    expect(probes(feed(state, [pos(P + 100)]).effects)).toBe(0);
  });

  it('h: 결과가 올 때 일시정지면 탐침만 비우고 넘기지도 막지도 않는다(P4)', () => {
    const asked = feed(watching(), [pos(P), pos(P + 100, { playing: false })]).state;
    const { state, effects } = feed(asked, [settleProbe(asked, P)]);
    expect(state).toMatchObject({
      phase: { kind: 'rewind' },
      handoffProbe: null,
      handoffBlock: null,
    });
    expect(effects).toEqual([]);
  });

  it('i: 끝남 대기(P ≈ E − 500)는 d = 15 까지 넘긴다', () => {
    const asked = feed(endingAt(E - 500), [doorPoll()]).state;
    const done = feed(asked, [settleProbe(asked, E - 1_000)]).state;
    expect([done.phase.kind, done.source.startAtMs]).toEqual(['live', E - 500]);
  });
});

describe('새 소스의 첫 위치 전에는 넘기지 않는다(조기 넘김 실측 2/70)', () => {
  /** 라이브를 liveAt 에서 보다가 p 로 되감은 세션 — 되감기 재생기는 아직 위치를 내지 않았다 */
  function enteredFromLive(liveAt: number, p = P): PlaybackSessionState {
    startRun();
    const fresh = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
    const seek: SessionEvent = { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: E } };
    const entered = feed(fresh, [streamPoll(liveReady(A1, T0, NOW)), pos(liveAt), seek]).state;
    startRun();
    return entered;
  }

  it('진입 직후 목록 · 문 응답이 첫 위치보다 먼저 와도 라이브 위치로 넘기지 않는다 — 두 순서', () => {
    const liveAt = NOW + 20_000;
    const orders = [
      () => [loaded(A1, T0, NOW), doorPoll()],
      () => [doorPoll(), loaded(A1, T0, NOW), doorPoll(X_A, R + 2_000)],
    ];
    for (const order of orders) {
      const entered = enteredFromLive(liveAt);
      const early = feed(entered, order());
      expect([early.state.phase.kind, probes(early.effects)]).toEqual(['rewind', 0]);
      const asked = feed(early.state, [pos(P)]);
      expect(probes(asked.effects)).toBe(1);
      const done = feed(asked.state, [settleProbe(asked.state)]).state;
      expect([done.phase.kind, done.source.startAtMs]).toEqual(['live', P]);
    }
  });

  it('나가 있던 탐침의 결과도 새 재생기(리마운트)의 첫 위치 전이면 탐침만 비운다(P4)', () => {
    const asked = feed(watching(), [pos(P)]).state;
    const remounted = feed(asked, [{ type: 'remount' }]).state;
    const { state, effects } = feed(remounted, [settleProbe(remounted)]);
    expect(state).toMatchObject({
      phase: { kind: 'rewind' },
      handoffProbe: null,
      handoffBlock: null,
    });
    expect(effects).toEqual([]);
    startRun();
    expect(handOffs(state, [pos(P + 500)])).toBe(1);
  });

  it('되감기 안 뒤로 시크하면 옮긴 자리의 위치가 오기 전에는 시크 전 위치로 넘기지 않는다', () => {
    const view = { lowerMs: T0, endMs: E };
    const back: SessionEvent = { type: 'seek', targetMs: E - MIN, view };
    const stalled = feed(rewindNear(), [pos(P), back]).state;
    const polled = feed(stalled, [doorPoll()]);
    expect([polled.state.phase.kind, probes(polled.effects)]).toEqual(['rewind', 0]);
    const asked = feed(watching(), [pos(P)]).state;
    const moved = feed(asked, [back]).state;
    const settled = feed(moved, [settleProbe(moved)]);
    const outcome = [settled.state.phase.kind, settled.state.handoffProbe, settled.effects];
    expect(outcome).toEqual(['rewind', null, []]);
  });

  it('되감기로 바꾸기 전에 잰 라이브 위치가 늦게 와도 버린다 — 새 재생기의 첫 위치 전에는 0', () => {
    const liveAt = NOW + 20_000;
    const entered = enteredFromLive(liveAt);
    const stale = pos(liveAt + 500, { epoch: entered.positionEpoch - 1 });
    const early = feed(entered, [stale, loaded(A1, T0, NOW), doorPoll()]);
    expect([early.state.phase.kind, probes(early.effects)]).toEqual(['rewind', 0]);
    const asked = feed(early.state, [pos(P)]);
    expect(probes(asked.effects)).toBe(1);
  });

  it('안에서 시크하기 전에 잰 위치가 늦게 와도 버린다 — 시크 전 자리로 넘기지 않는다', () => {
    const view = { lowerMs: T0, endMs: E };
    const back: SessionEvent = { type: 'seek', targetMs: E - MIN, view };
    const stalled = feed(rewindNear(), [pos(P), back]).state;
    const stale = pos(P + 500, { epoch: stalled.positionEpoch - 1 });
    const polled = feed(stalled, [stale, doorPoll()]);
    expect([polled.state.lastAtMs, probes(polled.effects)]).toEqual([P, 0]);
    const landed = feed(polled.state, [pos(E - MIN)]).state;
    expect(landed.lastAtMs).toBe(E - MIN);
  });

  it('앞 세션에서 더 높은 시대로 잰 위치는 다시 연 세션에 들지 않는다 — 같은 시대만 받는다', () => {
    const reopened = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
    const late = feed(reopened, [pos(P, { epoch: reopened.positionEpoch + 3 })]).state;
    expect([late.lastAtMs, late.positionGeneration]).toEqual([null, null]);
    expect(feed(late, [pos(P)]).state.lastAtMs).toBe(P);
  });
});
