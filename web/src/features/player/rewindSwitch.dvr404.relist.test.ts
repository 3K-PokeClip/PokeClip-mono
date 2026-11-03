import { beforeEach, describe, expect, it } from 'vitest';
import { pos, resetEventKit, settleProbe, streamPoll } from './handoffTestKit';
import {
  A1,
  A2,
  ASKED,
  DROP,
  ENDLIST,
  END_REACHED,
  HOLD,
  MIN,
  NOW,
  OLDEST,
  P20,
  R,
  REFRESH_A1,
  T0,
  X_A,
  feed,
  fresh,
  gone,
  kinds,
  liveReady,
  loaded,
  notices,
  poll,
  relistPhase,
  rewindAt,
  rewindFromLive,
  status,
} from './relistTestKit';
import { SESSION_NOTICES, pollTargets, type PlaybackSessionState } from './rewindSwitch';
import { isPlayerStartHeld, sessionView } from './sessionView';

// 방송 중 되감기 조각 404 → 보던 목록 다시 받기(POK-528 c7 — 계획 3-2 · 3-3 · 3-4 · 3-5 ·
// 8-1). 404 를 받은 되감기는 제어면을 묻지 않고 같은 url 의 재생기를 세대 + 1 로 다시
// 세운다(relist). 새 재생기가 화면을 붙이기 전에 또 404 면 막힘이다. 옛 판 표는 404 에서
// 제어면을 다시 읽으므로(recheck), 음성 대조 트리에서는 단언으로 FAIL 한다.
// rewindSwitch.dvr404.recheck.test.ts(c3)를 대체한다.

beforeEach(resetEventKit);

/** 404 를 받아 목록을 다시 받는 중(첫 목록 전)인 되감기 */
const relisted = (s: PlaybackSessionState) => feed(s, [gone(s)]).state;
/** 다시 받은 첫 목록을 받아 화면을 기다리는 되감기 */
const painting = (s: PlaybackSessionState, cut = false) =>
  feed(relisted(s), [loaded(A1, cut)]).state;
/** 보던 자리 P20 을 품지 않는 같은 회차 새 url A2 의 답(그 범위가 pendingNext 로 든다) */
const laterA2 = () => poll(liveReady(A2, T0 + 50 * MIN));
const laterA2Range = { url: A2, firstPdtMs: T0 + 50 * MIN, headPdtMs: NOW + MIN };

describe('되감기 조각 404 → 같은 목록을 다시 받는다(계획 3-2)', () => {
  it('지금 세대 404 는 같은 url 을 세대 + 1 로 다시 세운다 — 확인된 자리에서 · 효과 [drop-pause-mark] · refresh 0', () => {
    const s = feed(rewindAt(P20), [laterA2()]).state;
    const done = feed(s, [gone(s)]);
    expect(done.state.phase).toEqual(relistPhase('list', false, laterA2Range));
    expect(done.state.source).toEqual({
      kind: 'rewind',
      url: A1,
      startAtMs: P20,
      generation: s.source.generation + 1,
      strictStart: false,
    });
    expect(done.effects).toEqual([DROP]);
    expect(sessionView(done.state, R)).toMatchObject({ cover: null, startHeld: false });
    expect(pollTargets(done.state)).toEqual({ stream: true, round: false });
  });

  it('R1 ② 앞 소스의 자리는 쓰지 않는다 — 라이브 위치를 남긴 채 되감은 첫 위치 전 404 는 청한 자리에서', () => {
    const s = rewindFromLive();
    expect(s.lastAtMs).not.toBe(ASKED);
    // 새로 선 소스(세대 + 1)의 시작이다 — 옛 소스가 그대로 남아도 청한 자리라 세대로 가른다(음성 대조 판별력)
    expect(relisted(s).source).toMatchObject({
      url: A1,
      startAtMs: ASKED,
      generation: s.source.generation + 1,
    });
  });

  it('R1 ③ 끝나지 않은 시크 — 목록 안 시크 뒤 위치 전 404 는 시크 목표에서 · 위치가 오면 목표를 지운다', () => {
    const q = T0 + 40 * MIN;
    const sought = feed(rewindAt(P20), [
      { type: 'seek', targetMs: q, view: { lowerMs: T0 + 12_000, endMs: NOW } },
    ]);
    expect(kinds(sought.effects)).toEqual(['seek-within']);
    expect(sought.state).toMatchObject({ pendingSeekMs: q, positionGeneration: null });
    expect(relisted(sought.state).source.startAtMs).toBe(q);
    const seen = feed(sought.state, [pos(q)]).state;
    expect(seen).toMatchObject({ pendingSeekMs: null, lastAtMs: q });
  });

  it('R1 ④ 자리를 모르면 목록 하한에서(0) — 손 상태: 시작 요청 없는 되감기 소스는 사건열로 닿지 않는다(J2)', () => {
    const base = rewindAt(null);
    for (const startAtMs of [null, 0]) {
      const s = { ...base, source: { ...base.source, startAtMs } };
      expect(relisted(s).source.startAtMs).toBe(0);
    }
  });

  it('옛 재생기의 늦은 404(세대가 다름)는 그대로다 — 평소 되감기 · 다시 받는 중 모두', () => {
    const s = rewindAt(P20);
    expect(feed(s, [gone(s, s.source.generation - 1)])).toEqual({ state: s, effects: [] });
    const r = relisted(s);
    expect(feed(r, [gone(r, s.source.generation)])).toEqual({ state: r, effects: [] });
  });
});

describe('다시 받은 첫 목록의 하한 알림 판정(계획 3-3 (2))', () => {
  it('잘렸으면 알림 하나 · 늘 drop-pause-mark · 뒤 relist = paint · noticed 참', () => {
    const done = feed(relisted(rewindAt(P20)), [loaded(A1, true)]);
    expect(done.effects).toEqual([DROP, OLDEST]);
    expect(done.state.phase).toEqual(relistPhase('paint', true));
  });

  it.each([false, undefined])(
    '잘리지 않았으면(%s) 알림 0 · drop-pause-mark · noticed 그대로',
    (cut) => {
      const done = feed(relisted(rewindAt(P20)), [loaded(A1, cut)]);
      expect(done.effects).toEqual([DROP]);
      expect(done.state.phase).toEqual(relistPhase('paint', false));
    },
  );

  it('남의 url 목록은 판정하지 않는다 — list 유지 · 효과 0', () => {
    const s = relisted(rewindAt(P20));
    const done = feed(s, [loaded(A2, true)]);
    expect(done.effects).toEqual([]);
    expect(done.state.phase).toEqual(relistPhase('list'));
  });

  it('paint 에서 또 받아도 판정이 없다 — 알림 0', () => {
    const done = feed(painting(rewindAt(P20), true), [loaded(A1, true)]);
    expect(done.effects).toEqual([]);
    expect(done.state.phase).toEqual(relistPhase('paint', true));
  });
});

describe('화면 붙음 — 지금 시대 위치가 다시 받기를 끝낸다(계획 3-4)', () => {
  it('paint + 지금 시대 위치 → relist 없음 · 옛 시대 위치 → 그대로', () => {
    const s = painting(rewindAt(P20));
    const stale = feed(s, [pos(P20 + 4_000, { epoch: s.positionEpoch - 1 })]);
    expect(stale.state).toEqual(s);
    expect(feed(s, [pos(P20 + 4_000)]).state.phase).toEqual({ kind: 'rewind', pendingNext: null });
  });

  it('list 에서 지금 시대 위치가 먼저 와도 끝낸다(상태표만 고정 — 재생기 꼴로는 오지 않는다고 본다)', () => {
    const s = relisted(rewindAt(P20));
    expect(feed(s, [pos(P20 + 4_000)]).state.phase).toEqual({ kind: 'rewind', pendingNext: null });
  });
});

describe('되풀이 상한 — 화면 전 404 는 막힘(계획 3-2 셋째 행)', () => {
  it.each(['list', 'paint'] as const)(
    '%s 중 지금 세대 404 → 막힌 끝남 대기 · refresh · hold-frame',
    (stage) => {
      const base = rewindAt(P20);
      const s = stage === 'list' ? relisted(base) : painting(base);
      const done = feed(s, [gone(s)]);
      expect(done.state.phase).toEqual({
        kind: 'ending-wait',
        origin: 'rewind',
        atEnd: false,
        recordOnEnd: false,
        blocked: true,
      });
      expect(done.effects).toEqual([REFRESH_A1, HOLD]);
      expect(done.state.source).toBe(s.source);
      expect(sessionView(done.state, R).cover).toBe('preparing');
    },
  );
});

describe('R2 — 폴링 · 404 교차(목록 전 · 화면 전)', () => {
  it('① list + 같은 회차 새 url(자리를 품지 않음) → list 유지 · pendingNext · 세대 그대로 → 판정 알림 1', () => {
    const s = relisted(rewindAt(P20));
    const polled = feed(s, [laterA2()]).state;
    expect(polled.phase).toEqual(relistPhase('list', false, laterA2Range));
    expect(polled.source).toBe(s.source);
    const judged = feed(polled, [loaded(A1, true)]);
    expect(judged.effects).toEqual([DROP, OLDEST]);
    expect(judged.state.phase).toEqual(relistPhase('paint', true, laterA2Range));
  });

  it('② paint + 같은 폴링 → paint 유지 → 404 는 막힘(다시 받기 아님)', () => {
    const polled = feed(painting(rewindAt(P20)), [laterA2()]).state;
    expect(polled.phase).toEqual(relistPhase('paint', false, laterA2Range));
    expect(feed(polled, [gone(polled)]).state.phase).toMatchObject({
      kind: 'ending-wait',
      blocked: true,
    });
  });

  it('③ 404 → 폴링 → 폴링 → 404 — 같은 url 세대 증가 정확히 1 · 끝은 막힘', () => {
    const s = rewindAt(P20);
    const relistGeneration = s.source.generation + 1;
    const done = feed(s, [gone(s), laterA2(), laterA2(), gone(s, relistGeneration)]);
    expect(done.state.source.generation).toBe(s.source.generation + 1);
    expect(done.state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
  });

  it('④ 대조: 자리를 품은 새 url → 갈아 끼움 · relist 버림 / 같은 url 폴링 → relist 유지', () => {
    const s = relisted(rewindAt(P20));
    const swapped = feed(s, [poll(liveReady(A2, T0 + 10 * MIN))]).state;
    expect(swapped.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(swapped.source).toMatchObject({ url: A2, startAtMs: P20 });
    const same = feed(s, [poll(liveReady(A1, T0, NOW))]).state;
    expect(same.phase).toEqual(relistPhase('list'));
  });
});

describe('리마운트 — 시작은 restartAtMs · 단계는 list 로 · noticed 유지(계획 3-4 · P31 · P47)', () => {
  const remount = { type: 'remount' } as const;

  it('① list(청한 자리) → 리마운트 → 세대 + 1 · 시작 그대로 · list', () => {
    const s = relisted(rewindFromLive());
    const done = feed(s, [remount]).state;
    expect(done.source).toMatchObject({ startAtMs: ASKED, generation: s.source.generation + 1 });
    expect(done.phase).toEqual(relistPhase('list'));
  });

  it('② paint(알리지 않음) → 리마운트 → list → 새 목록이 잘렸으면 알림 1', () => {
    const s = feed(relisted(rewindFromLive()), [loaded(A1, false)]).state;
    const done = feed(s, [remount, loaded(A1, true)]);
    expect(done.state.source.startAtMs).toBe(ASKED);
    expect(done.effects).toEqual([DROP, OLDEST]);
    expect(done.state.phase).toEqual(relistPhase('paint', true));
  });

  it('③ paint(이미 알림) → 리마운트 → list → 같은 relist 안 재알림 억제(알림 0)', () => {
    const s = feed(relisted(rewindFromLive()), [loaded(A1, true)]).state;
    const remounted = feed(s, [remount]).state;
    expect(remounted.phase).toEqual(relistPhase('list', true));
    const done = feed(remounted, [loaded(A1, true)]);
    expect(done.effects).toEqual([DROP]);
  });

  it('④ 목록 하한(0)에서 선 relist → 리마운트 → 0(손 상태 — J2) · 대조: 확인된 자리 → 그 자리', () => {
    const base = rewindAt(null);
    const floor = relisted({ ...base, source: { ...base.source, startAtMs: null } });
    expect(feed(floor, [remount]).state.source.startAtMs).toBe(0);
    expect(feed(rewindAt(P20), [remount]).state.source.startAtMs).toBe(P20);
  });
});

describe('kty 「고쳐」 — 다시 받기마다 실제로 잘렸으면 알린다(계획 3-3 (2))', () => {
  it('(i) 제자리 다시 받기(알림 0) → 화면 → 404 → 잘린 다시 받기(알림 1)', () => {
    const s = rewindAt(P20);
    const first = feed(s, [gone(s), loaded(A1, false), pos(P20 + 4_000)]);
    expect(notices(first.effects)).toEqual([]);
    const next = feed(first.state, [gone(first.state), loaded(A1, true)]);
    expect(notices(next.effects)).toEqual([SESSION_NOTICES.oldest]);
  });

  it('(ii) 다시 받기마다 잘리면 매번 알린다 — 화면 · 긴 재생 사이에 낀 404 둘 → 알림 둘', () => {
    const s = rewindAt(P20);
    const first = feed(s, [gone(s), loaded(A1, true), pos(P20 + 4_000), pos(P20 + 60_000)]);
    const next = feed(first.state, [gone(first.state), loaded(A1, true)]);
    expect(notices([...first.effects, ...next.effects])).toEqual([
      SESSION_NOTICES.oldest,
      SESSION_NOTICES.oldest,
    ]);
  });
});

describe('다시 받은 목록의 ENDLIST(계획 3-3 (3))', () => {
  it('pendingNext 없음 → 일반 끝남 대기(relist 버림) → 404 → 막힘(끝남 대기 c2 행)', () => {
    const ended = feed(relisted(rewindAt(P20)), [loaded(A1), ENDLIST]).state;
    expect(ended.phase).toEqual({
      kind: 'ending-wait',
      origin: 'rewind',
      atEnd: false,
      recordOnEnd: false,
    });
    const done = feed(ended, [gone(ended)]);
    expect(done.state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(done.effects).toEqual([HOLD]);
  });

  it('pendingNext 있음 → refresh · rewind · relist 유지', () => {
    const s = relisted(feed(rewindAt(P20), [laterA2()]).state);
    const done = feed(s, [loaded(A1), ENDLIST]);
    expect(done.state.phase).toEqual(relistPhase('paint', false, laterA2Range));
    expect(done.effects).toEqual([DROP, REFRESH_A1]);
  });
});

describe('앞 사슬 시청자 — 막힘 또는 이음(P29 · 계획 0-2 의 1)', () => {
  it('(가) pendingNext 갈래: 다시 받은 목록이 ENDLIST 여도 rewind → 화면 전 404 → 막힘', () => {
    const s = feed(rewindAt(P20), [laterA2()]).state;
    expect(s.entry?.url).toBe(A1);
    const r = feed(s, [gone(s), loaded(A1), ENDLIST]).state;
    expect(r.phase).toMatchObject({ kind: 'rewind', relist: { stage: 'paint' } });
    expect(feed(r, [gone(r)]).state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
  });

  it('(가) pendingNext 갈래: 화면을 붙여 끝까지 재생하면 끝 도달에서 지금 사슬로 잇는다', () => {
    const s = feed(rewindAt(P20), [laterA2()]).state;
    const r = feed(s, [gone(s), loaded(A1), ENDLIST, pos(NOW - 1_000), END_REACHED]).state;
    expect(r.phase).toEqual({ kind: 'rewind', pendingNext: null });
    expect(r.source).toMatchObject({ url: A2, startAtMs: NOW });
    // 이은 뒤 entry 는 앞 사슬 url 그대로다 — 다시 받는 url 은 entry 가 아니라 지금 소스다(M7b)
    expect(r.entry?.url).toBe(A1);
    expect(feed(r, [pos(NOW + 4_000), gone(r)]).state.source.url).toBe(A2);
  });

  it('(나) pendingNext 없음 갈래: ENDLIST → 끝남 대기 → 이음 범위 밖 새 url → 라이브 보기 제안 → 404 → 막힘 · 제안 유지', () => {
    const s = rewindAt(P20);
    const ended = feed(s, [gone(s), loaded(A1), ENDLIST, laterA2()]).state;
    expect(ended.phase).toMatchObject({ kind: 'ending-wait', atEnd: false });
    expect(ended.offer).toEqual({ kind: 'go-live' });
    const done = feed(ended, [gone(ended)]).state;
    expect(done.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(done.offer).toEqual({ kind: 'go-live' });
  });
});

describe('하한 앞 시크 뒤 알림 둘(P43 — 한계 고정)', () => {
  it('entry 첫 조각 앞으로 되감기(알림 1) → 첫 위치 전 404 → 다시 받은 목록이 또 잘림(알림 2)', () => {
    const floor = T0 + 30 * MIN;
    const done = feed(fresh(), [
      poll(liveReady(A1, floor, NOW)),
      { type: 'seek', targetMs: T0 + 10 * MIN, view: { lowerMs: NOW - 16_000, endMs: NOW } },
      loaded(A1, undefined, floor),
    ]);
    expect(done.state.source.startAtMs).toBe(floor);
    const again = feed(done.state, [gone(done.state), loaded(A1, true, floor)]);
    expect(notices([...done.effects, ...again.effects])).toEqual([
      SESSION_NOTICES.oldest,
      SESSION_NOTICES.oldest,
    ]);
  });
});

describe('다시 받는 중의 다른 사건(계획 3-4)', () => {
  it('시크는 무시하지 않는다 — paint 에서 목록 안 시크는 seek-within · relist 유지', () => {
    const s = painting(rewindAt(P20));
    const done = feed(s, [
      { type: 'seek', targetMs: T0 + 30 * MIN, view: { lowerMs: T0 + 12_000, endMs: NOW } },
    ]);
    expect(kinds(done.effects)).toEqual(['seek-within']);
    expect(done.state.phase).toEqual(relistPhase('paint'));
  });

  it('LIVE → live(relist 버림)', () => {
    const done = feed(relisted(rewindAt(P20)), [{ type: 'live-button', x: X_A, attempt: 0 }]).state;
    expect(done.phase).toEqual({ kind: 'live' });
  });

  it('넘김: 다시 받은 재생기의 첫 위치가 띠 안이면 탐침 → P1 → live(relist 버림)', () => {
    const p = NOW - 2_000;
    const s = feed(fresh(), [
      poll(liveReady(A1, T0, NOW)),
      { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
      loaded(A1),
    ]).state;
    const watching = feed(s, [
      gone(s),
      loaded(A1),
      streamPoll(liveReady(A1, T0, NOW), X_A, R),
    ]).state;
    expect(watching.handoffProbe).toBeNull();
    const seen = feed(watching, [pos(p)]);
    expect(kinds(seen.effects)).toContain('probe-live');
    expect(seen.state.phase).toEqual({ kind: 'rewind', pendingNext: null });
    const handed = feed(seen.state, [settleProbe(seen.state, p - 2_000)], R + 500).state;
    expect(handed.phase).toEqual({ kind: 'live' });
  });
});

describe('「세우지 않음」 — 다시 받는 되감기는 세운다(계획 3-6)', () => {
  it('[새 함수 — 음성 대조 제외 · 변이 M7j 로 교정] 국면별 표', () => {
    const rewind = rewindAt(P20);
    const ended = feed(rewind, [ENDLIST]).state;
    const decided = feed(ended, [{ type: 'round-poll', result: status({ mode: 'ended' }) }]).state;
    expect(decided.phase).toMatchObject({ kind: 'ending-wait', recordOnEnd: true });
    expect(isPlayerStartHeld(fresh())).toBe(false);
    expect(isPlayerStartHeld(rewind)).toBe(false);
    expect(isPlayerStartHeld(relisted(rewind))).toBe(false);
    expect(isPlayerStartHeld(painting(rewind))).toBe(false);
    expect(isPlayerStartHeld(ended)).toBe(false);
    expect(isPlayerStartHeld(feed(ended, [gone(ended)]).state)).toBe(true);
    expect(isPlayerStartHeld(feed(decided, [gone(decided)]).state)).toBe(true);
  });
});
