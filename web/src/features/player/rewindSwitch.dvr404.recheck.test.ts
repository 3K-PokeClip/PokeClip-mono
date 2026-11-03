import { beforeEach, describe, expect, it } from 'vitest';
import type { RewindStatus, RewindStatusResult } from '@/api/rewindStatus';
import { step } from './dvr404TestKit';
import { pos, resetEventKit, settleProbe, startRun, streamPoll } from './handoffTestKit';
import {
  ENDING_WAIT_MS,
  SESSION_NOTICES,
  openSession,
  pollTargets,
  type PlaybackSessionState,
  type SessionEffect,
  type SessionEvent,
  type XAnswer,
} from './rewindSwitch';
import { isPlayerStartHeld, sessionView } from './sessionView';

// 방송 중 되감기 조각 404 시험(POK-528 c3 — 계획 3-2 · 3-3 · 3-5 · 8-1 · W1 · W2 ·
// W3 · U2 의 recheck 몫). 사건은 모두 감싸개 step 을 거친다(전이마다 불변식 단언 —
// 계획 8-0). 옛 판 표는 사건 'dvr-segment-gone' 을 모르는 사건으로 받아 상태를
// 그대로 두므로, 음성 대조 트리에서는 단언으로 FAIL 한다.
//
// 시각은 epoch ms 다. 되감기 목록 A1 의 첫 조각 T0 · 끝 NOW · 맨 앞 조각 셋의 길이
// 12초(창 하한 여유) · 문은 R 에 받은 응답이다. 방송 A(회차 r-A)를 보던 세션이 기준이다.
// F = 제어면 답의 첫 조각 · S = F + 여유(새 재생기의 실제 시작 하한 추정).

const MIN = 60_000;
const T0 = Date.parse('2026-10-25T05:00:00.000Z');
const NOW = T0 + 60 * MIN;
const E = NOW;
const R = NOW + 1_000;
const MARGIN_MS = 12_000;
/** 보던 자리 — 목록 끝에서 멀어 넘김 띠 밖이다 */
const P20 = T0 + 20 * MIN;
const LIVE_URL = 'https://media.pokeclip.com/live/key-1/index.m3u8';
const A1 = 'https://media.pokeclip.com/dvr/key-1/a1/index.m3u8';
const A2 = 'https://media.pokeclip.com/dvr/key-1/a2/index.m3u8';
const VOD = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';
const X_A: XAnswer = { kind: 'known', id: 'r-A' };
const X_B: XAnswer = { kind: 'known', id: 'r-B' };
const X_EMPTY: XAnswer = { kind: 'known', id: '' };
const X_UNKNOWN: XAnswer = { kind: 'unknown' };
const TICK: SessionEvent = { type: 'tick' };
const REFRESH_A1: SessionEffect = { kind: 'refresh', retryWhileUrl: A1 };
const HOLD: SessionEffect = { kind: 'hold-frame' };
const DROP: SessionEffect = { kind: 'drop-pause-mark' };
const OLDEST: SessionEffect = { kind: 'notice', text: SESSION_NOTICES.oldest };

function status(patch: Partial<RewindStatus>): RewindStatusResult {
  return {
    kind: 'ok',
    status: { ready: false, mode: null, url: null, firstPdtMs: null, headPdtMs: null, ...patch },
  };
}

/** 같은 url 의 방송 중 목록 — 첫 조각이 floorMs 로 밀렸다 */
function liveReady(floorMs: number, url = A1): RewindStatusResult {
  return status({ ready: true, mode: 'live', url, firstPdtMs: floorMs, headPdtMs: NOW + MIN });
}

function poll(result: RewindStatusResult, x: XAnswer = X_A): SessionEvent {
  return streamPoll(result, x, R);
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
  return { type: 'dvr-segment-gone', generation };
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

beforeEach(resetEventKit);

function fresh(): PlaybackSessionState {
  startRun();
  return openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: LIVE_URL });
}

/**
 * 라이브에서 A1 의 p 로 되감은 세션 — 목록(첫 조각 T0 · 끝 NOW)을 로드했고, atMs 가
 * 있으면 그 자리의 위치 사건을 먹였다(lastAtMs). 없으면 lastAtMs 는 null 이다
 */
function rewindAt(atMs: number | null, p = P20): PlaybackSessionState {
  const events: SessionEvent[] = [
    streamPoll(status({ ready: true, mode: 'live', url: A1, firstPdtMs: T0, headPdtMs: NOW })),
    { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
    loaded(A1, T0, NOW),
  ];
  if (atMs !== null) events.push(pos(atMs));
  return feed(fresh(), events).state;
}

/** 404 를 받아 제어면을 다시 읽는 중인 되감기 */
const recheckOf = (s: PlaybackSessionState) => feed(s, [gone(s)]).state;

/**
 * (가) 로 옮겨 세운 되감기의 기대 국면. noticed = 이 (가) 사슬에서 하한 알림을 이미
 * 냈다(기본 참 — 대부분의 시험은 첫 옮김에서 알린다)
 */
function moved(floorMs: number, noticed = true) {
  const phase = { kind: 'rewind', pendingNext: null, movedFloorMs: floorMs };
  return noticed ? { ...phase, floorNoticed: true } : phase;
}

describe('되감기 조각 404 → 제어면을 다시 읽는다(계획 3-2)', () => {
  it('처음 404 는 recheck 를 세우고 곧바로 묻는다 — 덮개 없음 · 스트림 폴링만', () => {
    const s = rewindAt(P20);
    const done = feed(s, [gone(s)]);
    expect(done.state.phase).toEqual({ kind: 'rewind', pendingNext: null, recheck: true });
    expect(done.effects).toEqual([{ kind: 'refresh' }]);
    expect(done.state.source).toBe(s.source);
    expect(sessionView(done.state, R).cover).toBeNull();
    expect(pollTargets(done.state)).toEqual({ stream: true, round: false });
  });

  it('recheck 중 404 · 시크는 효과 0(중복 · 복구는 세션 효과만 — P1 · P15)', () => {
    const s = recheckOf(rewindAt(P20));
    const again = feed(s, [gone(s)]);
    expect(again.effects).toEqual([]);
    expect(again.state.phase).toEqual(s.phase);
    const seek: SessionEvent = {
      type: 'seek',
      targetMs: T0 + MIN,
      view: { lowerMs: T0, endMs: E },
    };
    const sought = feed(s, [seek]);
    expect(sought.effects).toEqual([]);
    expect(sought.state).toBe(s);
  });

  it('recheck 중 리마운트는 칸을 두고 세대만 올린다 — 새 재생기는 세우지 않음 · 옛 세대 404 는 버림', () => {
    const s = recheckOf(rewindAt(P20));
    const remounted = feed(s, [{ type: 'remount' }]);
    expect(remounted.effects).toEqual([]);
    expect(remounted.state.phase).toEqual(s.phase);
    expect(remounted.state.source.generation).toBe(s.source.generation + 1);
    expect(sessionView(remounted.state, R).startHeld).toBe(true);
    const late = feed(remounted.state, [gone(remounted.state, s.source.generation)]);
    expect(late.effects).toEqual([]);
    expect(late.state).toBe(remounted.state);
  });

  it('recheck 중 LIVE 는 출구다 · 명부 ended 는 지금처럼 다시 묻기', () => {
    const s = recheckOf(rewindAt(P20));
    expect(s.phase).toMatchObject({ recheck: true });
    const live = feed(s, [{ type: 'live-button', x: X_A, attempt: 0 }]);
    expect(live.state.phase).toEqual({ kind: 'live' });
    expect(live.state.source.generation).toBe(s.source.generation + 1);
    expect(feed(s, [{ type: 'roster-ended' }]).effects).toEqual([{ kind: 'refresh' }]);
  });

  it('멈춘 채 오래 둔 되감기(playing 거짓)도 같은 행 — 정지(hold-frame) 0 · 답에서 옮긴다(8-3 웹 행)', () => {
    const s = feed(rewindAt(null), [pos(P20, { playing: false })]).state;
    const floor = P20 + 10 * MIN;
    const done = feed(s, [gone(s), poll(liveReady(floor))]);
    expect(kinds(done.effects)).not.toContain('hold-frame');
    expect(done.state.phase).toEqual(moved(floor));
  });
});

describe('(가) — 같은 회차 · readyLive 면 그 목록 하한으로 옮긴다(계획 3-3)', () => {
  it('옮긴 국면 · 새 재생기 · 진입 재료 · 문 · 관측 끝을 세우고 멈춤 기록을 버리게 한다', () => {
    const s = recheckOf(rewindAt(P20));
    const floor = P20 + MIN;
    const done = feed(s, [poll(liveReady(floor))]);
    expect(done.state.phase).toEqual(moved(floor));
    expect(done.state.source).toEqual({
      kind: 'rewind',
      url: A1,
      startAtMs: floor + MARGIN_MS,
      generation: s.source.generation + 1,
      strictStart: false,
    });
    expect(done.state.entry).toEqual({ url: A1, firstPdtMs: floor, headPdtMs: NOW + MIN });
    expect(done.state.rewindOpen).toBe(true);
    expect(done.state.handoffDoor).toEqual({ url: A1, atMs: R });
    expect(done.state.observedEndMs).toBe(NOW + MIN);
    expect(done.effects).toEqual([DROP, OLDEST]);
    expect(sessionView(done.state, R).startHeld).toBe(false);
  });

  // 가드 기준 = S(F + 여유) — 새 재생기는 첫 목록에서 그 아래를 잘라 시작한다
  it.each([
    ['lastAtMs < F', P20 + MIN, P20 + MIN + MARGIN_MS, [DROP, OLDEST]],
    ['F ≤ lastAtMs < S(경계 — 말없이 밀리던 구간)', P20 - 4_000, P20 + 8_000, [DROP, OLDEST]],
    ['lastAtMs = S', P20 - MARGIN_MS, P20, [DROP]],
    ['lastAtMs > S', P20 - MIN, P20, [DROP]],
  ])('가드 %s → 시작 자리 · 알림', (...[, floor, start, effects]) => {
    const s = recheckOf(rewindAt(P20));
    const done = feed(s, [poll(liveReady(floor))]);
    expect(done.state.source.startAtMs).toBe(start);
    expect(done.effects).toEqual(effects);
  });

  it('가드 — 자리를 모르면(lastAtMs null) S 에서 · 알림', () => {
    const s = recheckOf(rewindAt(null));
    const floor = T0 + MIN;
    const done = feed(s, [poll(liveReady(floor))]);
    expect(done.state.source.startAtMs).toBe(floor + MARGIN_MS);
    expect(done.effects).toEqual([DROP, OLDEST]);
  });

  it('다른 url 이어도 X 가 이 회차면 그 url 로 옮긴다', () => {
    const s = recheckOf(rewindAt(P20));
    const done = feed(s, [poll(liveReady(P20 + MIN, A2))]);
    expect(done.state.source).toMatchObject({ kind: 'rewind', url: A2 });
    expect(done.state.roundRewindUrls.has(A2)).toBe(true);
  });
});

describe('되풀이 속도 · 알림 한 번(계획 3-3 · P2 · P25)', () => {
  it('옮긴 자리의 404 는 곧바로 묻지 않고 · 같은 F 로는 다시 옮기지 않는다 — 교체 = 진전한 F 수', () => {
    const s = rewindAt(P20);
    const f1 = P20 + MIN;
    const f2 = f1 + 4_000;
    const first = feed(s, [gone(s), poll(liveReady(f1))]);
    const again = feed(first.state, [gone(first.state)]);
    expect(again.state.phase).toEqual({ ...moved(f1), recheck: true });
    expect(again.effects).toEqual([]);
    const sameF = feed(again.state, [poll(liveReady(f1))]);
    expect(sameF.state.phase).toEqual(again.state.phase);
    expect(sameF.state.source).toBe(again.state.source);
    expect(sameF.effects).toEqual([]);
    const next = feed(sameF.state, [poll(liveReady(f2))]);
    expect(next.state.phase).toEqual(moved(f2));
    expect(next.effects).toEqual([DROP]);
    const third = feed(next.state, [gone(next.state)]);
    expect(third.effects).toEqual([]);
    const all = [...first.effects, ...again.effects, ...sameF.effects, ...next.effects];
    expect(kinds([...all, ...third.effects]).filter((k) => k === 'refresh')).toHaveLength(1);
    expect(third.state.source.generation).toBe(s.source.generation + 2);
  });

  it('같은 F 보다 이른 F 도 옮기지 않는다', () => {
    const f1 = P20 + MIN;
    const a = feed(recheckOf(rewindAt(P20)), [poll(liveReady(f1))]).state;
    const r = recheckOf(a);
    expect(r.phase).toMatchObject({ recheck: true });
    const earlier = feed(r, [poll(liveReady(f1 - 4_000))]);
    expect(earlier.state.phase).toEqual(r.phase);
    expect(earlier.effects).toEqual([]);
  });

  it('사슬 안 두 번째 (가) 는 알리지 않고 · 다른 전이로 새 되감기를 세우면 다시 알린다', () => {
    const f1 = P20 + MIN;
    const a = feed(recheckOf(rewindAt(P20)), [poll(liveReady(f1))]).state;
    const chained = feed(recheckOf(a), [poll(liveReady(f1 + 4_000))]);
    expect(chained.effects).toEqual([DROP]);
    // 세 번째도 알리지 않는다 — 알렸다는 표가 사슬 내내 이어진다
    const third = feed(recheckOf(chained.state), [poll(liveReady(f1 + 8_000))]);
    expect(third.effects).toEqual([DROP]);
    const view = { lowerMs: NOW, endMs: NOW + 28_000 };
    const reopened = feed(chained.state, [
      { type: 'live-button', x: X_A, attempt: 0 },
      { type: 'seek', targetMs: f1 + MIN, view },
    ]).state;
    expect(reopened.phase).toEqual({ kind: 'rewind', pendingNext: null });
    const fresh404 = feed(reopened, [gone(reopened)]);
    expect(fresh404.effects).toEqual([{ kind: 'refresh' }]);
    const notified = feed(fresh404.state, [poll(liveReady(f1 + 2 * MIN))]);
    expect(notified.effects).toEqual([DROP, OLDEST]);
  });

  it('옮긴 국면에서 W2 · (나) 답은 지금 행 그대로', () => {
    const f1 = P20 + MIN;
    const r = recheckOf(feed(recheckOf(rewindAt(P20)), [poll(liveReady(f1))]).state);
    const notReady = status({ ready: false, mode: 'live', url: A1, firstPdtMs: f1 + 4_000 });
    const w2 = feed(r, [poll(notReady)]);
    expect(w2.state.phase).toEqual(r.phase);
    expect(w2.effects).toEqual([]);
    const blocked = feed(r, [poll(liveReady(f1 + 4_000), X_B)]);
    expect(blocked.state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
  });

  it('K2: 옮긴 뒤 새 url 을 보류하면(pendingNext) 새 국면이라 movedFloorMs 를 버린다 — 세대 그대로', () => {
    const f1 = P20 + MIN;
    const a = feed(recheckOf(rewindAt(P20)), [poll(liveReady(f1))]).state;
    const far = status({
      ready: true,
      mode: 'live',
      url: A2,
      firstPdtMs: NOW + MIN,
      headPdtMs: NOW + 2 * MIN,
    });
    const held = feed(a, [poll(far)]);
    const range = { url: A2, firstPdtMs: NOW + MIN, headPdtMs: NOW + 2 * MIN };
    expect(held.state.phase).toEqual({ kind: 'rewind', pendingNext: range });
    expect(held.state.source.generation).toBe(a.source.generation);
    expect(feed(held.state, [gone(held.state)]).effects).toEqual([{ kind: 'refresh' }]);
  });
});

describe('알림 한 번 — 이번 사슬에서 이미 알렸는가로 판정한다(kty 「고쳐」 · P16 · P25)', () => {
  it('(i) 첫 (가) 가 그 자리에서 세워 알리지 않았으면 · 다음 (가) 가 앞으로 밀 때 알린다', () => {
    // 첫 (가): lastAtMs P20 ≥ S1 = P20 − 48초 → 그 자리 · 알림 0
    const f1 = P20 - MIN;
    const first = feed(recheckOf(rewindAt(P20)), [poll(liveReady(f1))]);
    expect(first.state.source.startAtMs).toBe(P20);
    expect(first.effects).toEqual([DROP]);
    expect(first.state.phase).toEqual(moved(f1, false));
    // 다음 (가): S2 = P20 + 12초 > lastAtMs → 앞으로 밀림 · 이 사슬의 첫 알림
    const second = feed(recheckOf(first.state), [poll(liveReady(P20))]);
    expect(second.state.source.startAtMs).toBe(P20 + MARGIN_MS);
    expect(second.effects).toEqual([DROP, OLDEST]);
    expect(second.state.phase).toEqual(moved(P20));
    // 같은 사슬의 되풀이는 다시 알리지 않는다
    const third = feed(recheckOf(second.state), [poll(liveReady(P20 + 4_000))]);
    expect(third.effects).toEqual([DROP]);
  });

  it('(ii) 옮긴 새 재생기가 그 자리를 재생하면 사슬이 끝난다 — 오래 재생 뒤 404 의 (가) 는 다시 알린다', () => {
    const f1 = P20 + MIN;
    const s1 = f1 + MARGIN_MS;
    const first = feed(recheckOf(rewindAt(P20)), [poll(liveReady(f1))]);
    expect(first.effects).toEqual([DROP, OLDEST]);
    // 새 재생기의 위치(지금 시대) — 첫 위치에서 사슬이 끝나고 · 같은 url 로 수 분 재생한다
    startRun();
    const played = feed(first.state, [loaded(A1, f1, NOW + MIN), pos(s1)]);
    expect(played.state.phase).toEqual(moved(f1, false));
    const later = feed(played.state, [pos(s1 + 5 * MIN)]).state;
    // 10분 멈춤 → 404 → 하한이 수 분 앞으로 → (가) 가 앞으로 민다 · 새 사슬이라 알린다
    const r = recheckOf(later);
    expect(r.phase).toMatchObject({ recheck: true, movedFloorMs: f1 });
    const f2 = s1 + 15 * MIN;
    const second = feed(r, [poll(liveReady(f2))]);
    expect(second.state.source.startAtMs).toBe(f2 + MARGIN_MS);
    expect(second.effects).toEqual([DROP, OLDEST]);
  });

  it('목록 로드 · 옛 시대 위치는 사슬을 끝내지 않는다 — P25 되풀이(목록을 받고 첫 조각 404)는 알림 한 번', () => {
    const f1 = P20 + MIN;
    const before = recheckOf(rewindAt(P20));
    const oldEpoch = before.positionEpoch;
    const first = feed(before, [poll(liveReady(f1))]);
    expect(first.effects).toEqual([DROP, OLDEST]);
    // 새 재생기가 목록을 받는다 · 옛 재생기의 늦은 위치(옛 시대)가 온다 — 둘 다 끝이 아니다
    const listed = feed(first.state, [loaded(A1, f1, NOW + MIN), pos(P20, { epoch: oldEpoch })]);
    expect(listed.state.phase).toEqual(moved(f1));
    const second = feed(recheckOf(listed.state), [poll(liveReady(f1 + 4_000))]);
    expect(second.effects).toEqual([DROP]);
  });
});

describe('W2 — 같은 회차 · mode live · ready 없음이면 recheck 를 둔 채 다음 폴링', () => {
  it('덮개 없이 기다리다 ready 면 (가) · mode ended 면 (나)', () => {
    const s = recheckOf(rewindAt(P20));
    const notReady = status({ ready: false, mode: 'live', url: A1, firstPdtMs: P20 });
    const waiting = feed(s, [poll(notReady)]);
    expect(waiting.state.phase).toEqual(s.phase);
    expect(waiting.effects).toEqual([]);
    expect(sessionView(waiting.state, R).cover).toBeNull();
    expect(sessionView(waiting.state, R).startHeld).toBe(true);
    expect(pollTargets(waiting.state)).toEqual({ stream: true, round: false });
    const ready = feed(waiting.state, [poll(liveReady(P20 + MIN))]);
    expect(ready.state.phase).toEqual(moved(P20 + MIN));
    const ended = feed(waiting.state, [poll(status({ mode: 'ended' }))]);
    expect(ended.state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
  });
});

describe('(나) — 그 밖의 답은 막힌 끝남 대기로(계획 3-3)', () => {
  const ready = liveReady(P20 + MIN);
  it.each<[string, RewindStatusResult, XAnswer | undefined]>([
    ['다른 회차', ready, X_B],
    ['빈 X(방송 없음)', ready, X_EMPTY],
    ['X 모름(cc B · cx)', ready, X_UNKNOWN],
    ['X 답 없음', ready, undefined],
    ['mode ending', status({ ready: true, mode: 'ending', url: A1, firstPdtMs: T0 }), X_A],
    ['mode vod', status({ ready: true, mode: 'vod', url: VOD, firstPdtMs: T0 }), X_A],
    ['mode ended', status({ mode: 'ended' }), X_A],
    ['mode 모름', status({}), X_A],
    ['잠시 불가', { kind: 'unavailable' }, X_A],
    ['404(none)', { kind: 'none' }, X_A],
  ])('%s → blocked · 덮개 · 두 폴링 · 20분 기준', (...[, result, x]) => {
    const s = recheckOf(rewindAt(P20));
    // x 없음(답 없음)도 그대로 싣는다 — poll 의 기본 X(이 회차)를 타지 않게
    const done = feed(s, [streamPoll(result, x, R)]);
    expect(done.state.phase).toEqual({
      kind: 'ending-wait',
      origin: 'rewind',
      atEnd: false,
      recordOnEnd: false,
      blocked: true,
    });
    expect(done.effects).toEqual([REFRESH_A1, HOLD]);
    expect(done.state.source).toBe(s.source);
    expect(done.state.endingSince).toBe(R);
    expect(sessionView(done.state, R).cover).toBe('preparing');
    expect(pollTargets(done.state)).toEqual({ stream: true, round: true });
  });

  it('cc B · cx: X 가 계속 모름이면 막힌 채 녹화 단추만 — 20분에 녹화', () => {
    let s = feed(recheckOf(rewindAt(P20)), [poll(ready, X_UNKNOWN)]).state;
    for (let i = 1; i <= 3; i += 1) {
      s = feed(s, [poll(ready, X_UNKNOWN)], R + i * MIN).state;
      expect(s.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
      expect(sessionView(s, R + i * MIN).actions).toEqual(['record']);
    }
    expect(feed(s, [TICK], R + ENDING_WAIT_MS).state.closed).toBe('recorded');
  });

  it('cx: 그동안 종국이면 녹화 · 다시보기가 겹치면 같은 자리 다시보기', () => {
    const s = feed(recheckOf(rewindAt(P20)), [poll(ready, X_UNKNOWN)]).state;
    const ended = feed(s, [{ type: 'round-poll', result: status({ mode: 'ended' }) }]);
    expect(ended.state.closed).toBe('recorded');
    const vod = status({ ready: true, mode: 'vod', url: VOD, firstPdtMs: T0, headPdtMs: NOW });
    const watched = feed(s, [{ type: 'round-poll', result: vod }]);
    expect(watched.state.phase).toEqual({ kind: 'vod' });
    expect(watched.state.source).toMatchObject({ kind: 'vod', url: VOD, startAtMs: P20 });
  });
});

describe('recheck 중 끝 신호 → (나) — 404 를 안다는 사실을 버리지 않는다(cc 시나리오 A)', () => {
  it('ENDLIST → 막힌 끝남 대기(끝에 닿기 전) · 덮개 · 두 폴링', () => {
    const s = recheckOf(rewindAt(P20));
    const done = feed(s, [{ type: 'endlist' }]);
    expect(done.state.phase).toEqual({
      kind: 'ending-wait',
      origin: 'rewind',
      atEnd: false,
      recordOnEnd: false,
      blocked: true,
    });
    expect(done.effects).toEqual([REFRESH_A1, HOLD]);
    expect(sessionView(done.state, R).cover).toBe('preparing');
    expect(pollTargets(done.state)).toEqual({ stream: true, round: true });
  });

  it('끝 도달 → 막힌 끝남 대기(끝에 닿음)', () => {
    const s = recheckOf(rewindAt(P20));
    const done = feed(s, [{ type: 'end-reached' }]);
    expect(done.state.phase).toMatchObject({ kind: 'ending-wait', atEnd: true, blocked: true });
    expect(done.effects).toEqual([REFRESH_A1, HOLD]);
  });
});

describe('세대 대조(계획 3-2 첫 행 · P23)', () => {
  it('(가) 뒤 옛 세대의 늦은 404 는 버리고 · 지금 세대의 404 만 옮긴 자리 행으로', () => {
    const s = rewindAt(P20);
    const old = s.source.generation;
    const a = feed(s, [gone(s), poll(liveReady(P20 + MIN))]).state;
    expect(a.source.generation).toBe(old + 1);
    const late = feed(a, [gone(a, old)]);
    expect(late.effects).toEqual([]);
    expect(late.state).toBe(a);
    const now404 = feed(a, [gone(a)]);
    expect(now404.state.phase).toMatchObject({ recheck: true });
    expect(now404.effects).toEqual([]);
  });
});

describe('넘김 갈래 — recheck 는 넘김 · 되돌림이 새 국면을 세울 때 버려진다(계획 3-5 · cc L6)', () => {
  /** 끝 E 앞 2초에서 문을 세우고 404 를 받은 뒤 위치가 탐침을 청한 세션 */
  function probingRecheck(): PlaybackSessionState {
    const near = rewindAt(null, E - 2_000);
    const doored = feed(near, [
      poll(status({ ready: true, mode: 'live', url: A1, firstPdtMs: T0, headPdtMs: NOW })),
    ]);
    const r = recheckOf(doored.state);
    expect(r.phase).toMatchObject({ recheck: true });
    const probed = feed(r, [pos(E - 2_000)]);
    expect(kinds(probed.effects)).toContain('probe-live');
    return probed.state;
  }

  it('탐침 P1 → live · recheck 사라짐', () => {
    const s = probingRecheck();
    const live = feed(s, [settleProbe(s)]);
    expect(live.state.phase).toEqual({ kind: 'live' });
    expect(live.state.source.generation).toBe(s.source.generation + 1);
  });

  it('그 live 의 확인이 시한을 넘기면(revertToRewind) 새 되감기 — recheck · movedFloorMs 없음 · 멈춤 유지', () => {
    const s = probingRecheck();
    const live = feed(s, [settleProbe(s)]).state;
    const reverted = feed(live, [TICK], R + MIN);
    expect(reverted.state.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(kinds(reverted.effects)).toContain('keep-paused-on-swap');
  });

  it('끝난 증거(revertEndedHandoff) → 막히지 않은 끝남 대기', () => {
    const s = probingRecheck();
    const live = feed(s, [settleProbe(s)]).state;
    const ended = feed(live, [{ type: 'endlist' }], R + 500);
    expect(ended.state.phase).toEqual({
      kind: 'ending-wait',
      origin: 'rewind',
      atEnd: false,
      recordOnEnd: false,
    });
  });
});

describe('「새 재생기를 세우지 않음」 — isPlayerStartHeld 의 recheck 몫(U2)', () => {
  it('sessionView.startHeld 는 recheck 중에만 참 — 옮긴 뒤(recheck 거짓)는 거짓', () => {
    const s = rewindAt(P20);
    const r = recheckOf(s);
    const a = feed(r, [poll(liveReady(P20 + MIN))]).state;
    expect(sessionView(s, R).startHeld).toBe(false);
    expect(sessionView(r, R).startHeld).toBe(true);
    expect(sessionView(a, R).startHeld).toBe(false);
    expect(sessionView(recheckOf(a), R).startHeld).toBe(true);
  });

  it('[새 함수 — 음성 대조 제외 · 변이 M2l-3 으로 교정] 국면별 표', () => {
    const s = rewindAt(P20);
    const r = recheckOf(s);
    const a = feed(r, [poll(liveReady(P20 + MIN))]).state;
    expect(isPlayerStartHeld(s)).toBe(false);
    expect(isPlayerStartHeld(r)).toBe(true);
    expect(isPlayerStartHeld(a)).toBe(false);
    expect(isPlayerStartHeld(recheckOf(a))).toBe(true);
  });
});
