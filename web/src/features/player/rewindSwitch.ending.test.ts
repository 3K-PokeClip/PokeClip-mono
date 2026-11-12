import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import {
  ENDING_WAIT_MS,
  RESUME_WAIT_MS,
  openSession,
  pollTargets,
  transition,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { pos, resetEventKit, stamped, startRun, streamPoll } from './handoffTestKit';
import { sessionView } from './sessionView';

// 재생 세션 상태기계의 끝남 대기 표 시험(endingWait.ts — 설계 5.3절 종결 판 R21 · R32 · R43 · R44 ·
// R46 · R53 · R55–R57). rewindSwitch.test.ts 에서 갈랐다(줄 수 800). 도우미는 그 파일과 같다.
// 시각은 모두 epoch ms 다. 방송 A(회차 r-A)를 보던 세션이 기준이다.

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
const B1 = 'https://media.pokeclip.com/dvr/key-1/b1/index.m3u8';
const VOD = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
/** 지금(되감기 목록 끝 = T0 + 60분) */
const NOW = T0 + 60 * MIN;

function status(patch: Partial<RewindStatus>): RewindStatusResult {
  return {
    kind: 'ok',
    status: { ready: false, mode: null, url: null, firstPdtMs: null, headPdtMs: null, ...patch },
  };
}
/** 스트림 경로 mode:live ∧ ready */
function liveReady(url: string, firstPdtMs: number, headPdtMs: number): RewindStatusResult {
  return status({ ready: true, mode: 'live', url, firstPdtMs, headPdtMs });
}
const NONE: RewindStatusResult = { kind: 'none' };
const UNAVAILABLE: RewindStatusResult = { kind: 'unavailable' };
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
const X_B: XAnswer = { kind: 'known', id: 'r-B' };
const X_EMPTY: XAnswer = { kind: 'known', id: '' };
const X_UNKNOWN: XAnswer = { kind: 'unknown' };

beforeEach(resetEventKit);

/** 사건을 차례로 먹이고 마지막 상태와 모든 효과를 돌려준다 */
function run(
  start: PlaybackSessionState,
  steps: Array<[SessionEvent, number?]>,
): { state: PlaybackSessionState; effects: SessionEffect[] } {
  let state = start;
  const effects: SessionEffect[] = [];
  for (const [event, at] of steps) {
    const next = transition(state, stamped(state, event), at ?? NOW);
    state = next.state;
    effects.push(...next.effects);
  }
  return { state, effects };
}

function kinds(effects: SessionEffect[]): string[] {
  return effects.map((e) => e.kind);
}

function fresh(): PlaybackSessionState {
  startRun();
  return openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
}

/** live 세션이 되감기 재료(A1 · 범위 T0 부터 NOW 까지)를 받은 상태 */
function liveWithEntry(): PlaybackSessionState {
  return run(fresh(), [[streamPoll(liveReady(A1, T0, NOW))]]).state;
}

/** A1 을 T 에서 트는 되감기 세션. 목록을 로드했고(E = NOW) 위치는 T 다. 라이브 창은 T 바로 뒤에서 시작한다고 둔다 */
function rewindAt(t: number): PlaybackSessionState {
  return run(liveWithEntry(), [
    [{ type: 'seek', targetMs: t, view: { lowerMs: t + 1, endMs: NOW + 28_000 } }],
    [
      {
        type: 'list-loaded',
        url: A1,
        firstMs: T0,
        lastEndMs: NOW,
        targetDurationS: 4,
        headSpanMs: 12_000,
      },
    ],
    [pos(t)],
  ]).state;
}

/**
 * 목록 사건 없는 길(네이티브 HLS)로 stopAt 에서 끝에 닿은 끝남 대기(rewind 기원 · atEnd:true).
 * 네이티브 <video> 는 list-loaded 를 안 낸다 — list 가 null 이라 이음 지점은 E 대신
 * lastAtMs(계획 1절)
 */
function nativeAtEnd(stopAt: number): PlaybackSessionState {
  return run(liveWithEntry(), [
    [{ type: 'seek', targetMs: stopAt - 20_000, view: { lowerMs: stopAt, endMs: NOW + 28_000 } }],
    [pos(stopAt - 20_000)],
    [pos(stopAt)],
    [{ type: 'end-reached' }],
  ]).state;
}

/** 라이브에서 라이브 닫힘으로 끝남 대기(live 기원 · atEnd:true)에 든 상태. lastAtMs = NOW */
function endingFromLiveClosed(at = NOW): PlaybackSessionState {
  return run(liveWithEntry(), [
    [
      {
        type: 'list-loaded',
        url: LIVE_URL,
        firstMs: NOW - 28_000,
        lastEndMs: NOW,
        targetDurationS: 4,
        headSpanMs: 12_000,
      },
    ],
    [pos(NOW)],
    [{ type: 'live-closed' }, at],
  ]).state;
}

describe('ending-wait 국면', () => {
  it('뒤를 재생하는 rewind 에 ENDLIST → atEnd:false · 두 번째 ENDLIST 는 재조회 0 → 끝 도달에 덮개(R44)', () => {
    const s = rewindAt(T0 + 20 * MIN);
    const first = run(s, [[{ type: 'endlist' }]]);
    expect(sessionView(first.state, NOW).cover).toBeNull();
    const second = run(first.state, [[{ type: 'endlist' }]]);
    expect(second.effects).toEqual([]);
    const ended = run(second.state, [[{ type: 'end-reached' }]]).state;
    expect(ended.phase).toMatchObject({ kind: 'ending-wait', atEnd: true });
    expect(sessionView(ended, NOW).cover).toBe('checking');
  });

  it('회차별 404 + 13초 · 60초 · 290초 뒤 이어짐 증거 → 라이브 재세움 1회 · 닫힘 0(R21 · R53)', () => {
    for (const gapMs of [13_000, 60_000, 290_000]) {
      const start = endingFromLiveClosed();
      const steps: Array<[SessionEvent, number]> = [];
      for (let t = 10_000; t < gapMs; t += 10_000) {
        steps.push([{ type: 'round-poll', result: NONE }, NOW + t]);
      }
      steps.push([streamPoll(liveReady(A2, T0, NOW + gapMs), X_A), NOW + gapMs]);
      const { state, effects } = run(start, steps);
      expect(state.phase).toEqual({ kind: 'live' });
      expect(state.source).toMatchObject({ kind: 'live', generation: start.source.generation + 1 });
      expect(state.endingSince).toBeNull();
      expect(state.closed).toBeNull();
      expect(kinds(effects)).not.toContain('close');
    }
  });

  it('rewind 기원의 이어짐은 같은 방송(X = 이 회차)이면 이음 지점(atEnd 면 E)에서 새 url 로 잇는다', () => {
    const atEnd = run(rewindAt(NOW - 5_000), [[{ type: 'end-reached' }]]).state;
    const poll = streamPoll(liveReady(A2, T0, NOW + 30_000), X_A);
    const { state } = run(atEnd, [[poll, NOW + 30_000]]);
    expect(state.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: NOW });
    expect(state.phase).toEqual({ kind: 'rewind', pendingNext: null });
  });

  it('rewind 기원: 이음 지점을 덮어도 다른 방송(X ≠ 이 회차)이면 잇지 않고 「새 방송 보기」를 내민다', () => {
    // 되감기의 자동 이음은 같은 방송이 끊겼다 돌아온 경우뿐이다(kty 결정 · 설계 r9 4절 이어짐 행)
    const listed = run(rewindAt(NOW - 5_000), [[{ type: 'end-reached' }]]).state;
    for (const start of [listed, nativeAtEnd(NOW - 5_000)]) {
      const poll = streamPoll(liveReady(B1, T0, NOW + 30_000), X_B);
      const { state, effects } = run(start, [[poll, NOW + 30_000]]);
      expect(state.source).toEqual(start.source);
      expect(state.phase).toEqual(start.phase);
      expect(state.roundRewindUrls.has(B1)).toBe(false);
      expect(state.offer).toEqual({ kind: 'new-broadcast', goTo: 'r-B' });
      expect(effects).toEqual([]);
    }
  });

  it('rewind 기원은 끝에 닿기 전에 다른 방송을 봐도 「새 방송 보기」를 내밀지 않는다 — 끝 도달 뒤 폴링이 낸다(live 기원은 그대로)', () => {
    // 되감기 중 다른 회차는 끝까지 두고, 끝에 닿은 뒤 버튼 규칙이 제안한다(kty K1
    // 확장 (가)). ENDLIST 가 끝 도달보다 먼저 와 끝남 대기(atEnd:false)에 든 두
    // 순서 — 다른 방송을 먼저 봄 · ENDLIST 를 먼저 봄
    const other = () => streamPoll(liveReady(B1, NOW + MIN, NOW + 2 * MIN), X_B);
    const orders: Array<() => Array<[SessionEvent, number?]>> = [
      () => [[other()], [{ type: 'endlist' }], [other()]],
      () => [[{ type: 'endlist' }], [other()]],
    ];
    for (const order of orders) {
      const watching = run(rewindAt(T0 + 20 * MIN), order()).state;
      expect(watching.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: false });
      expect(watching.offer).toBeNull();
      expect(sessionView(watching, NOW).actions).toEqual([]);
      const ended = run(watching, [[{ type: 'end-reached' }], [other()]]).state;
      expect(ended.offer).toEqual({ kind: 'new-broadcast', goTo: 'r-B' });
      expect(sessionView(ended, NOW).actions).toEqual(['new-broadcast']);
    }
    // live 기원은 develop 그대로 — 끝에 닿기 전(ENDLIST)에도 띠에 낸다
    const live = run(liveWithEntry(), [[{ type: 'endlist' }], [other()]]).state;
    expect(live.phase).toMatchObject({ kind: 'ending-wait', origin: 'live', atEnd: false });
    expect(live.offer).toEqual({ kind: 'new-broadcast', goTo: 'r-B' });
  });

  it('rewind 기원: X 를 모르면(모름 · 빈 값 · 답 없음) 잇지 않고 제안 없이 기다린다 — 이 회차라는 답이 오면 잇는다', () => {
    const atEnd = run(rewindAt(NOW - 5_000), [[{ type: 'end-reached' }]]).state;
    const covering = liveReady(A2, T0, NOW + 30_000);
    for (const x of [X_UNKNOWN, X_EMPTY, undefined]) {
      const waiting = run(atEnd, [[streamPoll(covering, x), NOW + 30_000]]).state;
      expect(waiting.source).toEqual(atEnd.source);
      expect(waiting.phase).toEqual(atEnd.phase);
      expect(waiting.offer).toBeNull();
      const joined = run(waiting, [[streamPoll(covering, X_A), NOW + 40_000]]).state;
      expect(joined.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: NOW });
    }
  });

  it('live 기원의 이어짐은 X 를 보지 않는다 — develop 그대로(X 대조는 되감기 기원의 이음에만)', () => {
    const start = endingFromLiveClosed();
    const poll = streamPoll(liveReady(A2, T0, NOW + 30_000), X_UNKNOWN);
    const { state } = run(start, [[poll, NOW + 30_000]]);
    expect(state.phase).toEqual({ kind: 'live' });
    expect(state.source).toMatchObject({ kind: 'live', generation: start.source.generation + 1 });
  });

  it('목록 사건 없는 길(네이티브 HLS)의 끝남 대기는 같은 방송 새 url 에 lastAtMs 로 잇는다 — E 이하 · 건너뜀 0', () => {
    const stopAt = NOW - 5_000;
    const native = nativeAtEnd(stopAt);
    expect(native.list).toBeNull();
    expect(native.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: true });
    const poll = streamPoll(liveReady(A2, T0, NOW + 30_000), X_A);
    const { state, effects } = run(native, [[poll, NOW + 30_000]]);
    expect(state.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(state.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: native.lastAtMs });
    // 멈춘 자리에서 잇는다 — A1 이 알린 끝(NOW) 이하라 못 본 녹화를 건너뛰지 않는다
    expect(state.source.startAtMs).toBe(stopAt);
    expect(stopAt).toBeLessThanOrEqual(NOW);
    expect(kinds(effects)).not.toContain('close');
  });

  it('라이브 닫힘 직후 낡은 A url(③) 은 이어짐이 아니다 — 교체 0(R43)', () => {
    const start = endingFromLiveClosed();
    const { state, effects } = run(start, [[streamPoll(liveReady(A1, T0, NOW)), NOW + 2_000]]);
    expect(state.phase.kind).toBe('ending-wait');
    expect(state.source).toEqual(start.source);
    expect(effects).toEqual([]);
  });

  it('회차별 404 · 이어짐 없음: 360초 직전까지 닫힘 0 · 뒤 첫 404 에 atEnd:true 는 past 로 닫는다(R53 · R32 · POK-547)', () => {
    const start = endingFromLiveClosed();
    const before = run(start, [[{ type: 'round-poll', result: NONE }, NOW + RESUME_WAIT_MS - 1]]);
    expect(before.state.closed).toBeNull();
    const after = run(start, [[{ type: 'round-poll', result: NONE }, NOW + RESUME_WAIT_MS]]);
    expect(after.effects).toEqual([{ kind: 'close', reason: 'past' }]);
  });

  it('회차별 404 · 360초 뒤 · atEnd:false 는 closeOnEnd · 폴링 0 · 끝 도달에 덮개 없이 past', () => {
    const start = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
    const after = run(start, [[{ type: 'round-poll', result: NONE }, NOW + RESUME_WAIT_MS]]).state;
    expect(after.phase).toMatchObject({ kind: 'ending-wait', atEnd: false, closeOnEnd: true });
    expect(pollTargets(after)).toEqual({ stream: false, round: false });
    expect(after.source.url).toBe(A1);
    const done = run(after, [[{ type: 'end-reached' }]]);
    expect(done.effects).toEqual([{ kind: 'close', reason: 'past' }]);
  });

  it('회차별 mode:ended 는 대기 안이어도 곧바로 종국이다', () => {
    const { effects } = run(endingFromLiveClosed(), [
      [{ type: 'round-poll', result: status({ mode: 'ended' }) }, NOW + 10_000],
    ]);
    expect(effects).toEqual([{ kind: 'close', reason: 'past' }]);
  });

  it('회차별 ending · live · null · 503 은 기다린다', () => {
    const start = endingFromLiveClosed();
    for (const result of [
      status({ mode: 'ending' }),
      status({ mode: 'live' }),
      status({}),
      UNAVAILABLE,
    ]) {
      const { state, effects } = run(start, [[{ type: 'round-poll', result }, NOW + 400_000]]);
      expect(state).toEqual(start);
      expect(effects).toEqual([]);
    }
  });

  it('스트림 경로 vod 는 무시 · 회차별 vod 범위가 본 구간과 안 겹치면 vod-offered 0(R46)', () => {
    const start = run(rewindAt(T0 + 20 * MIN), [[{ type: 'end-reached' }]]).state;
    const streamVod = status({
      ready: true,
      mode: 'vod',
      url: VOD,
      firstPdtMs: T0,
      headPdtMs: NOW,
    });
    const vodPoll = streamPoll(streamVod);
    const vodState = run(start, [[vodPoll]]).state;
    expect(vodState).toEqual({ ...start, streamAsk: vodPoll.ask.seq });
    // 스트림 경로의 ended 도 종국이 아니다(같은 키 다른 회차일 수 있다)
    const endedPoll = streamPoll(status({ mode: 'ended' }));
    const streamEnded = run(start, [[endedPoll]]);
    const endedAsk = endedPoll.ask.seq;
    expect(streamEnded.state).toEqual({ ...start, streamAsk: endedAsk });
    expect(streamEnded.effects).toEqual([]);
    const otherRound = status({
      ready: true,
      mode: 'vod',
      url: VOD,
      firstPdtMs: NOW + 10 * MIN,
      headPdtMs: NOW + 20 * MIN,
    });
    expect(run(start, [[{ type: 'round-poll', result: otherRound }]]).state.phase.kind).toBe(
      'ending-wait',
    );
  });

  it('회차별 vod 가 본 구간과 겹치면 vod-offered(atEnd 그대로) · 폴링 끔', () => {
    const start = run(rewindAt(T0 + 20 * MIN), [[{ type: 'end-reached' }]]).state;
    const vod = status({ ready: true, mode: 'vod', url: VOD, firstPdtMs: T0, headPdtMs: NOW });
    const { state } = run(start, [[{ type: 'round-poll', result: vod }]]);
    expect(state.phase).toEqual({
      kind: 'vod-offered',
      url: VOD,
      firstPdtMs: T0,
      headPdtMs: NOW,
      atEnd: true,
    });
    expect(pollTargets(state)).toEqual({ stream: false, round: false });
    expect(sessionView(state, NOW).actions).toContain('vod');
  });

  it('20분: atEnd:false 는 소스 교체 0 · 세션 유지 · 폴링 0 / atEnd:true 는 past', () => {
    const playing = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
    const late = run(playing, [[{ type: 'tick' }, NOW + ENDING_WAIT_MS]]);
    expect(late.state.source).toEqual(playing.source);
    expect(late.state.closed).toBeNull();
    expect(pollTargets(late.state)).toEqual({ stream: false, round: false });
    const atEnd = endingFromLiveClosed();
    // 20분 직전에는 닫지 않는다
    expect(run(atEnd, [[{ type: 'tick' }, NOW + ENDING_WAIT_MS - 1]]).effects).toEqual([]);
    expect(run(atEnd, [[{ type: 'tick' }, NOW + ENDING_WAIT_MS]]).effects).toEqual([
      { kind: 'close', reason: 'past' },
    ]);
  });

  it('덮개 동안 시크: rewind 기원은 안에서 시크 → atEnd:false · 덮개 내림(R55)', () => {
    const atEnd = run(rewindAt(NOW - 5_000), [[{ type: 'end-reached' }]]).state;
    const view = { lowerMs: T0 + 12_000, endMs: NOW };
    const { state, effects } = run(atEnd, [[{ type: 'seek', targetMs: NOW - 30_000, view }]]);
    expect(effects).toEqual([{ kind: 'seek-within', atMs: NOW - 30_000 }]);
    expect(state.phase).toMatchObject({ kind: 'ending-wait', atEnd: false });
    expect(state.endingSince).toBe(atEnd.endingSince);
    expect(sessionView(state, NOW).cover).toBeNull();
  });

  it('덮개 동안 live 기원은 시크바를 숨긴다', () => {
    const atEnd = endingFromLiveClosed();
    expect(sessionView(atEnd, NOW).seekHidden).toBe(true);
    const view = { lowerMs: NOW - 16_000, endMs: NOW };
    expect(run(atEnd, [[{ type: 'seek', targetMs: NOW - 5_000, view }]]).effects).toEqual([]);
  });

  it('버튼 규칙 — 이음 지점을 덮지 않는 새 url: X 로 「라이브 보기」 · 「새 방송 보기」 · 없음을 가른다(R56)', () => {
    const start = endingFromLiveClosed();
    const other = liveReady(B1, NOW + MIN, NOW + 2 * MIN);
    const goLive = run(start, [[streamPoll(other, X_A)]]).state;
    expect(sessionView(goLive, NOW).actions).toEqual(['go-live']);
    expect(goLive.phase.kind).toBe('ending-wait');
    const newOne = run(start, [[streamPoll(other, X_B)]]).state;
    expect(sessionView(newOne, NOW).actions).toEqual(['new-broadcast']);
    for (const x of [X_EMPTY, X_UNKNOWN]) {
      const none = run(start, [[streamPoll(other, x)]]).state;
      expect(sessionView(none, NOW).actions).toEqual([]);
    }
  });

  it('버튼 — 라이브 보기 · 새 방송 보기가 세션을 닫는 사유', () => {
    const start = endingFromLiveClosed();
    const other = liveReady(B1, NOW + MIN, NOW + 2 * MIN);
    const a = run(start, [[streamPoll(other, X_A)]]).state;
    expect(run(a, [[{ type: 'button', which: 'go-live' }]]).effects).toEqual([
      { kind: 'close', reason: 'go-live' },
    ]);
    const b = run(start, [[streamPoll(other, X_B)]]).state;
    expect(run(b, [[{ type: 'button', which: 'new-broadcast' }]]).effects).toEqual([
      { kind: 'close', reason: 'left', goTo: 'r-B' },
    ]);
  });

  it('다른 방송 띠가 선 채 끝남 대기로 가도 띠의 「새 방송 보기」는 그 방송으로 닫는다 — 죽은 단추가 아니다(cc L)', () => {
    const bound = run(rewindAt(T0 + 20 * MIN), [
      [{ type: 'live-button', x: X_UNKNOWN, attempt: 3 }],
      [streamPoll(liveReady(B1, NOW, NOW + MIN), X_B)],
      [{ type: 'live-closed' }],
    ]).state;
    expect(bound.phase.kind).toBe('ending-wait');
    expect(sessionView(bound, NOW).banner).toEqual({ goTo: 'r-B' });
    expect(run(bound, [[{ type: 'button', which: 'new-broadcast' }]]).effects).toEqual([
      { kind: 'close', reason: 'left', goTo: 'r-B' },
    ]);
  });

  it('회차 종국이 이미 선 뒤의 시한 검사는 상태를 새로 만들지 않는다(같은 객체 — cc L)', () => {
    const waiting = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
    const decided = run(waiting, [
      [{ type: 'round-poll', result: status({ mode: 'ended' }) }],
    ]).state;
    expect(decided.phase).toMatchObject({ closeOnEnd: true, atEnd: false });
    expect(transition(decided, { type: 'tick' }, NOW + ENDING_WAIT_MS).state).toBe(decided);
  });

  it('덮개 문구: 재접속 대기 동안 「이어지는지 확인」, 뒤에는 「준비 중」 · 제안이 없으면 단추가 없다', () => {
    const atEnd = endingFromLiveClosed();
    expect(sessionView(atEnd, NOW + RESUME_WAIT_MS - 1).cover).toBe('checking');
    expect(sessionView(atEnd, NOW + RESUME_WAIT_MS).cover).toBe('preparing');
    expect(sessionView(atEnd, NOW).actions).toEqual([]);
  });

  it('T1-4 명부 ended 는 끝남 대기의 상태를 바꾸지 않는다 — 효과 0 · 자동 전환 0(POK-547)', () => {
    const start = endingFromLiveClosed();
    const { state, effects } = run(start, [[{ type: 'roster-ended' }]]);
    expect(effects).toEqual([]);
    expect(state).toEqual(start);
    expect(state.closed).toBeNull();
  });

  it('수동 LIVE 복귀 뒤 두 번째 중단은 재접속 대기 360초를 새로 센다(R53 · R57)', () => {
    const waiting = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
    const back = run(waiting, [
      [{ type: 'live-button', x: X_A, attempt: 0 }],
      [{ type: 'playing' }],
    ]).state;
    expect(back.phase).toEqual({ kind: 'live' });
    expect(back.endingSince).toBeNull();
    const second = NOW + 15 * MIN;
    const again = run(back, [[{ type: 'live-closed' }, second]]).state;
    expect(again.endingSince).toBe(second);
    const early = run(again, [[{ type: 'round-poll', result: NONE }, second + RESUME_WAIT_MS - 1]]);
    expect(early.state.closed).toBeNull();
    const late = run(again, [[{ type: 'round-poll', result: NONE }, second + RESUME_WAIT_MS]]);
    expect(late.effects).toEqual([{ kind: 'close', reason: 'past' }]);
  });

  it('LIVE 직후 라이브가 닫히면(재생 실패) endingSince 는 그대로다', () => {
    const waiting = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
    const failed = run(waiting, [
      [{ type: 'live-button', x: X_A, attempt: 0 }, NOW + MIN],
      [{ type: 'live-closed' }, NOW + MIN + 2_000],
    ]).state;
    expect(failed.phase.kind).toBe('ending-wait');
    expect(failed.endingSince).toBe(NOW);
  });
});

describe('끝남 대기 종국 → 세션을 닫고 지난 방송 판정으로(POK-547 T1 · T2)', () => {
  /** 종국 셋 — 회차별 ended · 재접속 대기 뒤 회차별 404 · 20분 시한. 끝남 대기는 NOW 에 들었다 */
  const finals: Array<[string, SessionEvent, number]> = [
    ['회차별 ended', { type: 'round-poll', result: status({ mode: 'ended' }) }, NOW + 10_000],
    ['재접속 대기 뒤 회차별 404', { type: 'round-poll', result: NONE }, NOW + RESUME_WAIT_MS],
    ['20분 시한', { type: 'tick' }, NOW + ENDING_WAIT_MS],
  ];

  for (const [name, event, at] of finals) {
    it(`T1-1 ${name} · 끝에 닿은 시청자 → past 로 닫는다`, () => {
      const { state, effects } = run(endingFromLiveClosed(), [[event, at]]);
      expect(effects).toEqual([{ kind: 'close', reason: 'past' }]);
      expect(state.closed).toBe('past');
    });

    it(`T1-2 ${name} · 아직 보는 시청자 → 끝에서 닫기로 정하고 폴링을 끈 뒤 끝 도달에 past`, () => {
      const playing = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
      const decided = run(playing, [[event, at]]);
      expect(decided.effects).toEqual([]);
      expect(decided.state.phase).toMatchObject({
        kind: 'ending-wait',
        atEnd: false,
        closeOnEnd: true,
      });
      expect(pollTargets(decided.state)).toEqual({ stream: false, round: false });
      const done = run(decided.state, [[{ type: 'end-reached' }, at]]);
      expect(done.effects).toEqual([{ kind: 'close', reason: 'past' }]);
    });
  }

  it('T1-5 본 구간과 안 겹치는 vod 답은 끝남 대기를 풀지 않고(R46) 20분 시한에 past 로 닫는다(cc 1)', () => {
    const start = endingFromLiveClosed();
    const otherRound = status({
      ready: true,
      mode: 'vod',
      url: VOD,
      firstPdtMs: NOW + 10 * MIN,
      headPdtMs: NOW + 20 * MIN,
    });
    const kept = run(start, [[{ type: 'round-poll', result: otherRound }, NOW + MIN]]);
    expect(kept.state.phase.kind).toBe('ending-wait');
    expect(kept.effects).toEqual([]);
    const late = run(kept.state, [[{ type: 'tick' }, NOW + ENDING_WAIT_MS]]);
    expect(late.effects).toEqual([{ kind: 'close', reason: 'past' }]);
  });

  it('T2-1 국면 × 제안 표 — 덮개 · 띠의 단추에 녹화 길이 없다', () => {
    const atEnd = endingFromLiveClosed();
    const offers: Array<[PlaybackSessionState['offer'], string[]]> = [
      [null, []],
      [{ kind: 'go-live' }, ['go-live']],
      [{ kind: 'new-broadcast', goTo: 'r-B' }, ['new-broadcast']],
    ];
    for (const [offer, actions] of offers) {
      expect(sessionView({ ...atEnd, offer }, NOW).actions).toEqual(actions);
      const watching = { ...atEnd, offer, phase: { ...atEnd.phase, atEnd: false } };
      expect(sessionView(watching as PlaybackSessionState, NOW).actions).toEqual(actions);
    }
    const offered = run(rewindAt(T0 + 20 * MIN), [
      [{ type: 'end-reached' }],
      [
        {
          type: 'round-poll',
          result: status({ ready: true, mode: 'vod', url: VOD, firstPdtMs: T0, headPdtMs: NOW }),
        },
      ],
    ]).state;
    expect(offered.phase.kind).toBe('vod-offered');
    expect(sessionView(offered, NOW).actions).toEqual(['vod']);
    expect(sessionView(fresh(), NOW).actions).toEqual([]);
    expect(sessionView(rewindAt(T0 + 20 * MIN), NOW).actions).toEqual([]);
  });
});
