import { describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import {
  ENDING_WAIT_MS,
  RESUME_WAIT_MS,
  openSession,
  pollTargets,
  rewindAxisLowerMs,
  sessionView,
  transition,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';

// 재생 세션 상태기계 표 시험(설계 5.3절 종결 판 — R5 · R16 · R21 · R28 · R32 · R43 · R44 · R46 · R47 · R53 · R55–R58).
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

/** 사건을 차례로 먹이고 마지막 상태와 모든 효과를 돌려준다 */
function run(
  start: PlaybackSessionState,
  steps: [SessionEvent, number?][],
): { state: PlaybackSessionState; effects: SessionEffect[] } {
  let state = start;
  const effects: SessionEffect[] = [];
  for (const [event, at] of steps) {
    const next = transition(state, event, at ?? NOW);
    state = next.state;
    effects.push(...next.effects);
  }
  return { state, effects };
}

function kinds(effects: SessionEffect[]): string[] {
  return effects.map((e) => e.kind);
}

function fresh(): PlaybackSessionState {
  return openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
}

/** live 세션이 되감기 재료(A1 · 범위 T0 ~ NOW)를 받은 상태 */
function liveWithEntry(): PlaybackSessionState {
  return run(fresh(), [[{ type: 'stream-poll', result: liveReady(A1, T0, NOW) }]]).state;
}

/** A1 을 T 에서 트는 되감기 세션. 목록을 로드했고(E = NOW) 위치는 T 다. 라이브 창은 T 바로 뒤에서 시작한다고 둔다 */
function rewindAt(t: number): PlaybackSessionState {
  return run(liveWithEntry(), [
    [{ type: 'seek', targetMs: t, view: { lowerMs: t + 1, endMs: NOW + 28_000 } }],
    [{ type: 'list-loaded', url: A1, firstMs: T0, lastEndMs: NOW, targetDurationS: 4 }],
    [{ type: 'position', atMs: t }],
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
      },
    ],
    [{ type: 'position', atMs: NOW }],
    [{ type: 'live-closed' }, at],
  ]).state;
}

describe('세션 열기와 폴링 켜기(설계 5.1절 표)', () => {
  it('열면 live 국면 · 라이브 소스 · 스트림 경로만 켠다', () => {
    const s = fresh();
    expect(s.phase).toEqual({ kind: 'live' });
    expect(s.source).toEqual({ kind: 'live', url: LIVE_URL, startAtMs: null, generation: 0 });
    expect(pollTargets(s)).toEqual({ stream: true, round: false });
  });

  it('끝남 대기는 두 경로를 켜고, recordOnEnd · vod-offered · 닫힘은 둘 다 끈다', () => {
    const waiting = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
    expect(pollTargets(waiting)).toEqual({ stream: true, round: true });
    const record = run(waiting, [
      [{ type: 'round-poll', result: status({ mode: 'ended' }) }],
    ]).state;
    expect(record.phase).toMatchObject({ kind: 'ending-wait', recordOnEnd: true });
    expect(pollTargets(record)).toEqual({ stream: false, round: false });
    const closed = run(fresh(), [[{ type: 'leave' }]]).state;
    expect(pollTargets(closed)).toEqual({ stream: false, round: false });
  });
});

describe('live 국면', () => {
  it('라이브 범위 안 시크는 라이브 재생기 안에서 옮긴다', () => {
    const { state, effects } = run(liveWithEntry(), [
      [{ type: 'seek', targetMs: NOW - 10_000, view: { lowerMs: NOW - 16_000, endMs: NOW } }],
    ]);
    expect(effects).toEqual([{ kind: 'seek-within', atMs: NOW - 10_000 }]);
    expect(state.source.kind).toBe('live');
  });

  it('창 밖 시크 ∧ ready ∧ 범위 안이면 되감기 소스로 갈아 끼운다', () => {
    const t = T0 + 20 * MIN;
    const { state } = run(liveWithEntry(), [
      [{ type: 'seek', targetMs: t, view: { lowerMs: NOW - 16_000, endMs: NOW } }],
    ]);
    expect(state.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(state.source).toEqual({ kind: 'rewind', url: A1, startAtMs: t, generation: 1 });
  });

  it('firstPdt 보다 이른 시크는 firstPdt 로 잘라 들어가며 알린다', () => {
    const { state, effects } = run(liveWithEntry(), [
      [{ type: 'seek', targetMs: T0 - 5 * MIN, view: { lowerMs: NOW - 16_000, endMs: NOW } }],
    ]);
    expect(state.source).toMatchObject({ kind: 'rewind', startAtMs: T0 });
    expect(kinds(effects)).toContain('notice');
  });

  it('headPdt 와 라이브 창 사이(끊긴 공백) 시크는 headPdt 로 붙여 들어간다', () => {
    const s = run(fresh(), [
      [{ type: 'stream-poll', result: liveReady(A1, T0, NOW - 60_000) }],
      [{ type: 'seek', targetMs: NOW - 30_000, view: { lowerMs: NOW - 16_000, endMs: NOW } }],
    ]);
    expect(s.state.source).toMatchObject({ kind: 'rewind', startAtMs: NOW - 60_000 });
    expect(kinds(s.effects)).toContain('notice');
  });

  it('ready 가 아니면 라이브 창 하한으로 잘라 알린다', () => {
    const { state, effects } = run(fresh(), [
      [{ type: 'seek', targetMs: T0, view: { lowerMs: NOW - 16_000, endMs: NOW } }],
    ]);
    expect(state.source.kind).toBe('live');
    expect(effects).toContainEqual({ kind: 'seek-within', atMs: NOW - 16_000 });
    expect(kinds(effects)).toContain('notice');
  });

  it('스트림 폴링이 ending · vod · ended · null · 404 · 503 이면 국면 · 소스 그대로다(W3 · W4 · R28)', () => {
    const start = liveWithEntry();
    const results = [
      status({ mode: 'ending' }),
      status({ ready: true, mode: 'vod', url: VOD, firstPdtMs: T0, headPdtMs: NOW }),
      status({ mode: 'ended' }),
      status({}),
      NONE,
      UNAVAILABLE,
    ];
    for (const result of results) {
      const { state, effects } = run(start, [[{ type: 'stream-poll', result }]]);
      expect(state.phase).toEqual(start.phase);
      expect(state.source).toEqual(start.source);
      expect(state.entry).toEqual(start.entry);
      expect(state.roundRewindUrls).toEqual(start.roundRewindUrls);
      expect(effects).toEqual([]);
    }
  });

  it('ready 가 깜박이면 진입 재료는 남기고 되감기 노출만 닫는다 — 다시 ready 면 든다(R5 · 계약3 7-2)', () => {
    const view = { lowerMs: NOW - 16_000, endMs: NOW };
    const blinked = run(liveWithEntry(), [
      [{ type: 'stream-poll', result: status({ mode: 'live' }) }],
      [{ type: 'stream-poll', result: status({}) }],
    ]).state;
    expect(blinked.entry).toMatchObject({ url: A1 });
    expect(rewindAxisLowerMs(blinked)).toBeNull();
    const closed = run(blinked, [[{ type: 'seek', targetMs: T0 + MIN, view }]]);
    expect(closed.state.source.kind).toBe('live');
    expect(closed.effects).toContainEqual({ kind: 'seek-within', atMs: NOW - 16_000 });
    const reopened = run(blinked, [
      [{ type: 'stream-poll', result: liveReady(A1, T0, NOW) }],
      [{ type: 'seek', targetMs: T0 + MIN, view }],
    ]).state;
    expect(rewindAxisLowerMs(reopened)).toBe(T0);
    expect(reopened.source).toMatchObject({ kind: 'rewind', url: A1 });
  });

  it('잠시 불가(503)는 되감기 노출을 바꾸지 않는다 — 직전 상태 유지', () => {
    const state = run(liveWithEntry(), [[{ type: 'stream-poll', result: UNAVAILABLE }]]).state;
    expect(rewindAxisLowerMs(state)).toBe(T0);
  });

  it('스트림 폴링 mode:live ∧ ready 의 url 은 roundRewindUrls ③ 에 든다', () => {
    expect(liveWithEntry().roundRewindUrls.has(A1)).toBe(true);
  });

  it('ENDLIST 는 끝남 대기(atEnd:false)로 들고 재생은 계속한다 — 덮개 없음 · 재조회 1회', () => {
    const { state, effects } = run(liveWithEntry(), [[{ type: 'endlist' }]]);
    expect(state.phase).toEqual({
      kind: 'ending-wait',
      origin: 'live',
      atEnd: false,
      recordOnEnd: false,
    });
    expect(state.source.kind).toBe('live');
    expect(state.endingSince).toBe(NOW);
    expect(effects.filter((e) => e.kind === 'refresh')).toHaveLength(1);
    expect(sessionView(state, NOW).cover).toBeNull();
  });

  it('끝 도달 · 라이브 닫힘은 끝남 대기(atEnd:true)와 덮개다', () => {
    for (const event of [{ type: 'end-reached' }, { type: 'live-closed' }] as const) {
      const { state, effects } = run(liveWithEntry(), [[event]]);
      expect(state.phase).toMatchObject({ kind: 'ending-wait', origin: 'live', atEnd: true });
      expect(kinds(effects)).toContain('refresh');
      expect(sessionView(state, NOW).cover).toBe('checking');
    }
  });

  it('목록 치명 400 은 세대를 올려 라이브를 다시 세운다(R17)', () => {
    const { state } = run(fresh(), [[{ type: 'live-400' }]]);
    expect(state.source).toEqual({ kind: 'live', url: LIVE_URL, startAtMs: null, generation: 1 });
  });

  it('명부 ended 는 재조회만 한다 — 끝 증거가 아니다', () => {
    const start = liveWithEntry();
    const { state, effects } = run(start, [[{ type: 'roster-ended' }]]);
    expect(state.phase).toEqual({ kind: 'live' });
    expect(kinds(effects)).toEqual(['refresh']);
  });

  it('LIVE 버튼은 라이브 끝으로 옮긴다(국면 그대로)', () => {
    const { state, effects } = run(fresh(), [[{ type: 'live-button', x: X_A, attempt: 0 }]]);
    expect(state.phase).toEqual({ kind: 'live' });
    expect(effects).toEqual([{ kind: 'seek-live-edge' }]);
  });
});

describe('rewind 국면', () => {
  const T = T0 + 20 * MIN;

  it('목록 안 시크는 안에서 · 하한 앞은 하한으로 알림 · E 뒤는 E 로(라이브로 가지 않는다)', () => {
    const view = { lowerMs: T0 + 12_000, endMs: NOW };
    expect(run(rewindAt(T), [[{ type: 'seek', targetMs: T + MIN, view }]]).effects).toEqual([
      { kind: 'seek-within', atMs: T + MIN },
    ]);
    const early = run(rewindAt(T), [[{ type: 'seek', targetMs: T0, view }]]).effects;
    expect(early).toContainEqual({ kind: 'seek-within', atMs: T0 + 12_000 });
    expect(kinds(early)).toContain('notice');
    const late = run(rewindAt(T), [[{ type: 'seek', targetMs: NOW + MIN, view }]]);
    expect(late.effects).toEqual([{ kind: 'seek-within', atMs: NOW }]);
    expect(late.state.source.kind).toBe('rewind');
  });

  it('새 url ∧ P 가 새 범위 안이면 같은 P 로 갈아 끼운다(계승) · ② 에 넣는다', () => {
    const { state } = run(rewindAt(T), [
      [{ type: 'stream-poll', result: liveReady(A2, T0, NOW + 10_000) }],
    ]);
    expect(state.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: T, generation: 2 });
    expect(state.roundRewindUrls.has(A2)).toBe(true);
  });

  it('새 url 인데 P 가 범위 밖이면 pendingNext 로만 적는다 · roundRewindUrls 에 넣지 않는다', () => {
    const { state } = run(rewindAt(T), [
      [{ type: 'stream-poll', result: liveReady(B1, NOW + MIN, NOW + 2 * MIN) }],
    ]);
    expect(state.phase).toEqual({
      kind: 'rewind',
      pendingNext: { url: B1, firstPdtMs: NOW + MIN, headPdtMs: NOW + 2 * MIN },
    });
    expect(state.source.url).toBe(A1);
    expect(state.roundRewindUrls.has(B1)).toBe(false);
  });

  it('ready 깜박임(false · null · 503 · 404)은 도는 되감기를 끊지 않는다(W3 · R5)', () => {
    const start = rewindAt(T);
    for (const result of [status({ mode: 'live' }), status({}), UNAVAILABLE, NONE]) {
      const { state, effects } = run(start, [[{ type: 'stream-poll', result }]]);
      expect(state).toEqual(start);
      expect(effects).toEqual([]);
    }
  });

  it('같은 url 폴링은 범위 · observedEndMs 만 갱신한다', () => {
    const { state, effects } = run(rewindAt(T), [
      [{ type: 'stream-poll', result: liveReady(A1, T0, NOW + 8_000) }],
    ]);
    expect(effects).toEqual([]);
    expect(state.observedEndMs).toBe(NOW + 8_000);
  });

  it('ENDLIST 는 끝남 대기(atEnd:false) · 재조회는 지금 url 로 2초 × 3 을 건다(R16)', () => {
    const { state, effects } = run(rewindAt(T), [[{ type: 'endlist' }]]);
    expect(state.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: false });
    expect(effects).toEqual([{ kind: 'refresh', retryWhileUrl: A1 }]);
  });

  it('ENDLIST ∧ pendingNext 면 재조회 한 번 · 국면 그대로', () => {
    const { state, effects } = run(rewindAt(T), [
      [{ type: 'stream-poll', result: liveReady(B1, NOW + MIN, NOW + 2 * MIN) }],
      [{ type: 'endlist' }],
    ]);
    expect(state.phase.kind).toBe('rewind');
    expect(effects).toEqual([{ kind: 'refresh', retryWhileUrl: A1 }]);
  });

  it('끝 도달에 pendingNext 가 E 를 덮으면 E 에서 잇는다(계승)', () => {
    const { state } = run(rewindAt(T), [
      [{ type: 'stream-poll', result: liveReady(A2, NOW - 10_000, NOW + MIN) }],
    ]);
    // P(T) 가 A2 범위 밖이라 보류
    expect(state.phase).toMatchObject({ kind: 'rewind', pendingNext: { url: A2 } });
    const after = run(state, [[{ type: 'end-reached' }]]).state;
    expect(after.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: NOW });
    expect(after.roundRewindUrls.has(A2)).toBe(true);
  });

  it('끝 도달에 pendingNext 가 E 를 덮지 않으면(다른 방송) 교체 없이 끝남 대기 · 덮개', () => {
    const { state, effects } = run(rewindAt(T), [
      [{ type: 'stream-poll', result: liveReady(B1, NOW + MIN, NOW + 2 * MIN) }],
      [{ type: 'end-reached' }],
    ]);
    expect(state.source.url).toBe(A1);
    expect(state.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: true });
    expect(kinds(effects)).toEqual(['refresh']);
  });

  it('LIVE 버튼 — X 가 보던 방송이면 라이브로, 다른 방송이면 세션을 닫고 그 방송으로(R58)', () => {
    const same = run(rewindAt(T), [[{ type: 'live-button', x: X_A, attempt: 0 }]]).state;
    expect(same.phase).toEqual({ kind: 'live' });
    expect(same.source).toMatchObject({ kind: 'live', url: LIVE_URL, startAtMs: null });
    const other = run(rewindAt(T), [[{ type: 'live-button', x: X_B, attempt: 0 }]]);
    expect(other.effects).toEqual([{ kind: 'close', reason: 'left', goTo: 'r-B' }]);
    expect(other.state.source.kind).toBe('rewind');
    expect(other.state.closed).toBe('left');
  });

  it('LIVE 버튼 — X 를 모르면 「확인 중」으로 세 번까지 다시 묻고, 끝내 모르면 라이브로(R58)', () => {
    const asking = run(rewindAt(T), [[{ type: 'live-button', x: X_UNKNOWN, attempt: 0 }]]);
    expect(asking.effects).toEqual([{ kind: 'recheck-x', attempt: 1 }]);
    expect(sessionView(asking.state, NOW).liveChecking).toBe(true);
    expect(asking.state.phase.kind).toBe('rewind');
    const third = run(asking.state, [[{ type: 'live-button', x: X_UNKNOWN, attempt: 2 }]]);
    expect(third.effects).toEqual([{ kind: 'recheck-x', attempt: 3 }]);
    const giveUp = run(third.state, [[{ type: 'live-button', x: X_UNKNOWN, attempt: 3 }]]).state;
    expect(giveUp.phase).toEqual({ kind: 'live' });
    expect(sessionView(giveUp, NOW).liveChecking).toBe(false);
  });

  it('세션에 묶인 live 가 폴링마다 X 를 보고, 다른 방송을 보면 띠 · 세션 데이터 반영을 멈춘다(R58)', () => {
    const live = run(rewindAt(T), [[{ type: 'live-button', x: X_UNKNOWN, attempt: 3 }]]).state;
    const before = live.observedEndMs;
    const { state } = run(live, [
      [{ type: 'stream-poll', result: liveReady(B1, NOW, NOW + MIN), x: X_B }],
      [
        {
          type: 'list-loaded',
          url: LIVE_URL,
          firstMs: NOW,
          lastEndMs: NOW + 2 * MIN,
          targetDurationS: 4,
        },
      ],
    ]);
    expect(sessionView(state, NOW).banner).toEqual({ goTo: 'r-B' });
    expect(state.roundRewindUrls.has(B1)).toBe(false);
    expect(state.observedEndMs).toBe(before);
    expect(state.source.kind).toBe('live');
  });

  it('세션에 묶인 live 가 X 를 모르는 폴링은 건너뛴다 — 새 방송 B 가 A 세션 재료에 섞이지 않는다(R58 · cx H2 = cc H1)', () => {
    const live = run(rewindAt(T), [[{ type: 'live-button', x: X_UNKNOWN, attempt: 3 }]]).state;
    for (const x of [X_UNKNOWN, undefined]) {
      const { state, effects } = run(live, [
        [{ type: 'stream-poll', result: liveReady(B1, NOW, NOW + MIN), x }],
      ]);
      expect(state.roundRewindUrls.has(B1)).toBe(false);
      expect(state.entry).toEqual(live.entry);
      expect(state.roundId).toBe('r-A');
      expect(effects).toEqual([]);
    }
    // 뒤에 X = B 가 확인되면 띠 · 반영 중지이고, 그 뒤 X 를 모르는 폴링도 띠를 풀지 않는다
    const flagged = run(live, [
      [{ type: 'stream-poll', result: liveReady(B1, NOW, NOW + MIN), x: X_UNKNOWN }],
      [{ type: 'stream-poll', result: liveReady(B1, NOW, NOW + MIN), x: X_B }],
      [{ type: 'stream-poll', result: liveReady(B1, NOW, NOW + MIN), x: X_UNKNOWN }],
    ]).state;
    expect(flagged.foreignLive).toBe('r-B');
    expect(flagged.roundRewindUrls.has(B1)).toBe(false);
  });
});

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

  it('회차별 404 + 13초 · 60초 · 290초 뒤 이어짐 증거 → 라이브 재세움 1회 · recorded 0(R21 · R53)', () => {
    for (const gapMs of [13_000, 60_000, 290_000]) {
      const start = endingFromLiveClosed();
      const steps: [SessionEvent, number][] = [];
      for (let t = 10_000; t < gapMs; t += 10_000)
        steps.push([{ type: 'round-poll', result: NONE }, NOW + t]);
      steps.push([
        { type: 'stream-poll', result: liveReady(A2, T0, NOW + gapMs), x: X_A },
        NOW + gapMs,
      ]);
      const { state, effects } = run(start, steps);
      expect(state.phase).toEqual({ kind: 'live' });
      expect(state.source).toMatchObject({ kind: 'live', generation: start.source.generation + 1 });
      expect(state.endingSince).toBeNull();
      expect(state.closed).toBeNull();
      expect(kinds(effects)).not.toContain('close');
    }
  });

  it('rewind 기원의 이어짐은 이음 지점(atEnd 면 E)에서 새 url 로 잇는다', () => {
    const atEnd = run(rewindAt(NOW - 5_000), [[{ type: 'end-reached' }]]).state;
    const { state } = run(atEnd, [
      [{ type: 'stream-poll', result: liveReady(A2, T0, NOW + 30_000) }, NOW + 30_000],
    ]);
    expect(state.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: NOW });
    expect(state.phase).toEqual({ kind: 'rewind', pendingNext: null });
  });

  it('라이브 닫힘 직후 낡은 A url(③) 은 이어짐이 아니다 — 교체 0(R43)', () => {
    const start = endingFromLiveClosed();
    const { state, effects } = run(start, [
      [{ type: 'stream-poll', result: liveReady(A1, T0, NOW) }, NOW + 2_000],
    ]);
    expect(state.phase.kind).toBe('ending-wait');
    expect(state.source).toEqual(start.source);
    expect(effects).toEqual([]);
  });

  it('회차별 404 · 이어짐 없음: 360초 직전까지 recorded 0 · 뒤 첫 404 에 atEnd:true 는 recorded(R53 · R32)', () => {
    const start = endingFromLiveClosed();
    const before = run(start, [[{ type: 'round-poll', result: NONE }, NOW + RESUME_WAIT_MS - 1]]);
    expect(before.state.closed).toBeNull();
    const after = run(start, [[{ type: 'round-poll', result: NONE }, NOW + RESUME_WAIT_MS]]);
    expect(after.effects).toEqual([{ kind: 'close', reason: 'recorded' }]);
  });

  it('회차별 404 · 360초 뒤 · atEnd:false 는 recordOnEnd · 폴링 0 · 끝 도달에 덮개 없이 recorded', () => {
    const start = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
    const after = run(start, [[{ type: 'round-poll', result: NONE }, NOW + RESUME_WAIT_MS]]).state;
    expect(after.phase).toMatchObject({ kind: 'ending-wait', atEnd: false, recordOnEnd: true });
    expect(pollTargets(after)).toEqual({ stream: false, round: false });
    expect(after.source.url).toBe(A1);
    const done = run(after, [[{ type: 'end-reached' }]]);
    expect(done.effects).toEqual([{ kind: 'close', reason: 'recorded' }]);
  });

  it('회차별 mode:ended 는 대기 안이어도 곧바로 종국이다', () => {
    const { effects } = run(endingFromLiveClosed(), [
      [{ type: 'round-poll', result: status({ mode: 'ended' }) }, NOW + 10_000],
    ]);
    expect(effects).toEqual([{ kind: 'close', reason: 'recorded' }]);
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
    expect(run(start, [[{ type: 'stream-poll', result: streamVod }]]).state).toEqual(start);
    // 스트림 경로의 ended 도 종국이 아니다(같은 키 다른 회차일 수 있다)
    const streamEnded = run(start, [[{ type: 'stream-poll', result: status({ mode: 'ended' }) }]]);
    expect(streamEnded.state).toEqual(start);
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

  it('20분: atEnd:false 는 소스 교체 0 · 세션 유지 · 폴링 0 / atEnd:true 는 recorded', () => {
    const playing = run(rewindAt(T0 + 20 * MIN), [[{ type: 'endlist' }]]).state;
    const late = run(playing, [[{ type: 'tick' }, NOW + ENDING_WAIT_MS]]);
    expect(late.state.source).toEqual(playing.source);
    expect(late.state.closed).toBeNull();
    expect(pollTargets(late.state)).toEqual({ stream: false, round: false });
    const atEnd = endingFromLiveClosed();
    // 20분 직전에는 닫지 않는다
    expect(run(atEnd, [[{ type: 'tick' }, NOW + ENDING_WAIT_MS - 1]]).effects).toEqual([]);
    expect(run(atEnd, [[{ type: 'tick' }, NOW + ENDING_WAIT_MS]]).effects).toEqual([
      { kind: 'close', reason: 'recorded' },
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
    const goLive = run(start, [[{ type: 'stream-poll', result: other, x: X_A }]]).state;
    expect(sessionView(goLive, NOW).actions).toEqual(['record', 'go-live']);
    expect(goLive.phase.kind).toBe('ending-wait');
    const newOne = run(start, [[{ type: 'stream-poll', result: other, x: X_B }]]).state;
    expect(sessionView(newOne, NOW).actions).toEqual(['record', 'new-broadcast']);
    for (const x of [X_EMPTY, X_UNKNOWN]) {
      const none = run(start, [[{ type: 'stream-poll', result: other, x }]]).state;
      expect(sessionView(none, NOW).actions).toEqual(['record']);
    }
  });

  it('버튼 — 녹화 보기 · 라이브 보기 · 새 방송 보기가 세션을 닫는 사유', () => {
    const start = endingFromLiveClosed();
    const other = liveReady(B1, NOW + MIN, NOW + 2 * MIN);
    expect(run(start, [[{ type: 'button', which: 'record' }]]).effects).toEqual([
      { kind: 'close', reason: 'recorded' },
    ]);
    const a = run(start, [[{ type: 'stream-poll', result: other, x: X_A }]]).state;
    expect(run(a, [[{ type: 'button', which: 'go-live' }]]).effects).toEqual([
      { kind: 'close', reason: 'go-live' },
    ]);
    const b = run(start, [[{ type: 'stream-poll', result: other, x: X_B }]]).state;
    expect(run(b, [[{ type: 'button', which: 'new-broadcast' }]]).effects).toEqual([
      { kind: 'close', reason: 'left', goTo: 'r-B' },
    ]);
  });

  it('덮개 문구: 재접속 대기 동안 「이어지는지 확인」, 뒤에는 「준비 중」 · 녹화 보기는 늘 있다', () => {
    const atEnd = endingFromLiveClosed();
    expect(sessionView(atEnd, NOW + RESUME_WAIT_MS - 1).cover).toBe('checking');
    expect(sessionView(atEnd, NOW + RESUME_WAIT_MS).cover).toBe('preparing');
    expect(sessionView(atEnd, NOW).actions).toContain('record');
  });

  it('명부 ended 는 「녹화 보기」를 강조할 뿐 자동 전환하지 않는다', () => {
    const { state, effects } = run(endingFromLiveClosed(), [[{ type: 'roster-ended' }]]);
    expect(effects).toEqual([]);
    expect(sessionView(state, NOW).recordEmphasis).toBe(true);
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
    expect(late.effects).toEqual([{ kind: 'close', reason: 'recorded' }]);
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

describe('vod-offered · vod 국면', () => {
  const VOD_RANGE = status({ ready: true, mode: 'vod', url: VOD, firstPdtMs: T0, headPdtMs: NOW });

  function offered(atEnd: boolean, p = T0 + 20 * MIN): PlaybackSessionState {
    const base = rewindAt(p);
    const waiting = atEnd
      ? run(base, [[{ type: 'end-reached' }]]).state
      : run(base, [[{ type: 'endlist' }]]).state;
    return run(waiting, [[{ type: 'round-poll', result: VOD_RANGE }]]).state;
  }

  it('「다시보기 보기」 — atEnd:true 는 처음부터', () => {
    const { state } = run(offered(true), [[{ type: 'button', which: 'vod' }]]);
    expect(state.phase).toEqual({ kind: 'vod' });
    expect(state.source).toMatchObject({ kind: 'vod', url: VOD, startAtMs: null });
  });

  it('「다시보기 보기」 — atEnd:false 는 지금 자리(P)에서 잇는다', () => {
    const p = T0 + 20 * MIN;
    const { state, effects } = run(offered(false, p), [[{ type: 'button', which: 'vod' }]]);
    expect(state.source).toMatchObject({ kind: 'vod', startAtMs: p });
    expect(kinds(effects)).not.toContain('notice');
  });

  it('「다시보기 보기」 — P 가 VOD 끝 여유 안이면 끝 − EDGE_MARGIN 으로 · 알림', () => {
    const p = NOW - 2_000;
    const { state, effects } = run(offered(false, p), [[{ type: 'button', which: 'vod' }]]);
    expect(state.source).toMatchObject({ startAtMs: NOW - 12_000 });
    expect(kinds(effects)).toContain('notice');
  });

  it('「다시보기 보기」 — 3 TD 보다 짧은 VOD 는 첫 조각에서 연다(하한 우선 · R47)', () => {
    const short = status({
      ready: true,
      mode: 'vod',
      url: VOD,
      firstPdtMs: NOW - 8_000,
      headPdtMs: NOW,
    });
    const base = run(rewindAt(NOW - 6_000), [[{ type: 'endlist' }]]).state;
    const offer = run(base, [[{ type: 'round-poll', result: short }]]).state;
    const { state } = run(offer, [[{ type: 'button', which: 'vod' }]]);
    expect(state.source).toMatchObject({ kind: 'vod', startAtMs: NOW - 8_000 });
  });

  it('vod-offered atEnd:false 의 끝 도달은 버튼을 덮개로 올린다(자동 교체 없음)', () => {
    const { state } = run(offered(false), [[{ type: 'end-reached' }]]);
    expect(state.phase).toMatchObject({ kind: 'vod-offered', atEnd: true });
    expect(state.source.kind).toBe('rewind');
  });

  /** 라이브를 보다 ENDLIST 로 끝남 대기 → 회차별 vod(본 구간과 겹침) — live 기원 · atEnd:false */
  function offeredFromLive(): PlaybackSessionState {
    return run(liveWithEntry(), [
      [
        {
          type: 'list-loaded',
          url: LIVE_URL,
          firstMs: NOW - 28_000,
          lastEndMs: NOW,
          targetDurationS: 4,
        },
      ],
      [{ type: 'position', atMs: NOW - 5_000 }],
      [{ type: 'endlist' }],
      [{ type: 'round-poll', result: VOD_RANGE }],
    ]).state;
  }

  it('live 기원 vod-offered(atEnd:false) 의 LIVE 는 라이브 끝으로 시크 · 국면 그대로다(cc M1 = cx M)', () => {
    const start = offeredFromLive();
    expect(start.phase).toMatchObject({ kind: 'vod-offered', atEnd: false });
    const { state, effects } = run(start, [[{ type: 'live-button', x: X_A, attempt: 0 }]]);
    expect(effects).toEqual([{ kind: 'seek-live-edge' }]);
    expect(state.phase).toEqual(start.phase);
    expect(state.source).toEqual(start.source);
  });

  it('live 기원 vod-offered(atEnd:false) 의 창 안 시크는 라이브 재생기 안에서 · 창 밖은 되감기 진입 규칙', () => {
    const start = offeredFromLive();
    const view = { lowerMs: NOW - 16_000, endMs: NOW };
    expect(run(start, [[{ type: 'seek', targetMs: NOW - 10_000, view }]]).effects).toEqual([
      { kind: 'seek-within', atMs: NOW - 10_000 },
    ]);
    const out = run(start, [[{ type: 'seek', targetMs: T0 + MIN, view }]]).state;
    expect(out.source).toMatchObject({ kind: 'rewind', url: A1, startAtMs: T0 + MIN });
  });

  it('vod 국면의 시크는 문서 안 · 끝 도달은 국면 그대로다', () => {
    const vod = run(offered(true), [[{ type: 'button', which: 'vod' }]]).state;
    const view = { lowerMs: T0, endMs: NOW };
    expect(run(vod, [[{ type: 'seek', targetMs: T0 + MIN, view }]]).effects).toEqual([
      { kind: 'seek-within', atMs: T0 + MIN },
    ]);
    expect(run(vod, [[{ type: 'seek', targetMs: NOW + MIN, view }]]).effects).toEqual([
      { kind: 'seek-within', atMs: NOW },
    ]);
    const ended = run(vod, [[{ type: 'end-reached' }]]);
    expect(ended.state).toEqual(vod);
    expect(ended.effects).toEqual([]);
  });

  it('vod 의 LIVE 버튼은 숨긴다', () => {
    const vod = run(offered(true), [[{ type: 'button', which: 'vod' }]]).state;
    expect(sessionView(vod, NOW).liveHidden).toBe(true);
    expect(run(vod, [[{ type: 'live-button', x: X_A, attempt: 0 }]]).effects).toEqual([]);
  });
});

describe('아무 국면', () => {
  it('되감기 목록 로드는 ① 에 · observedEndMs 는 조각 끝 최대 · watchedFromMs 는 본 가장 이른 자리', () => {
    const s = rewindAt(T0 + 20 * MIN);
    expect(s.roundRewindUrls.has(A1)).toBe(true);
    expect(s.observedEndMs).toBe(NOW);
    const later = run(s, [[{ type: 'position', atMs: T0 + 30 * MIN }]]).state;
    expect(later.watchedFromMs).toBe(T0 + 20 * MIN);
    expect(later.lastAtMs).toBe(T0 + 30 * MIN);
  });

  it('끝남 대기 중 다른 회차 B 의 폴링 headPdt 는 observedEndMs 에 들지 않는다(R42)', () => {
    let s = run(rewindAt(T0 + 20 * MIN), [[{ type: 'end-reached' }]]).state;
    for (let i = 0; i < 10; i += 1)
      s = run(s, [
        [{ type: 'stream-poll', result: liveReady(B1, NOW + 30 * MIN, NOW + 30 * MIN), x: X_B }],
      ]).state;
    expect(s.observedEndMs).toBe(NOW);
  });

  it('리마운트는 같은 kind · url 을 lastAtMs 에서 세대만 올려 잇는다(live 는 null)(R36)', () => {
    const s = rewindAt(T0 + 20 * MIN);
    const { state } = run(s, [[{ type: 'remount' }]]);
    expect(state.source).toEqual({
      kind: 'rewind',
      url: A1,
      startAtMs: T0 + 20 * MIN,
      generation: s.source.generation + 1,
    });
    const live = run(fresh(), [[{ type: 'position', atMs: NOW }], [{ type: 'remount' }]]).state;
    expect(live.source).toMatchObject({ kind: 'live', startAtMs: null, generation: 1 });
  });

  it('화면 이탈은 left 로 닫고, 닫힌 뒤 사건은 무시한다', () => {
    const { state, effects } = run(fresh(), [[{ type: 'leave' }], [{ type: 'endlist' }]]);
    expect(effects).toEqual([{ kind: 'close', reason: 'left' }]);
    expect(state.phase).toEqual({ kind: 'live' });
  });
});
