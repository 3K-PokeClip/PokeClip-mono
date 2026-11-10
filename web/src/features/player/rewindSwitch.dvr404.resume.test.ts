import { beforeEach, describe, expect, it } from 'vitest';
import { resumeBySpec } from './dvr404TestKit';
import { pos, resetEventKit, settleProbe, streamPoll } from './handoffTestKit';
import {
  A1,
  A2,
  ASKED,
  END_REACHED,
  ENDLIST,
  LIVE_AT,
  LIVE_URL,
  MIN,
  NOW,
  P20,
  R,
  T0,
  VOD,
  X_A,
  X_B,
  X_UNKNOWN,
  feed,
  fresh,
  gone,
  liveReady,
  loaded,
  notices,
  poll,
  relistPhase,
  rewindAt,
  rewindFromLive,
  status,
  vodReady,
} from './relistTestKit';
import { SESSION_NOTICES, type PlaybackSessionState, type SessionEvent } from './rewindSwitch';

// 이어 볼 자리(resumeAtMs)의 보존(POK-528 c7 — 계획 3-1 T4 · T5 · 3-4 · 18절 · Q1). 첫
// 위치 전 · 시크 중의 폴링 교체 · 끝남 대기 이음 · 같은 자리 다시보기는 되감기 소스면 앞
// 재생기의 마지막 자리(lastAtMs)가 아니라 시청자가 청한 자리를 쓰고(라이브 소스는 lastAtMs
// 그대로), 이미 본 되감기 url 로는 다시 잇지 않는다. 옛 판 표는 lastAtMs 를 읽으므로 음성
// 대조 트리에서는 단언으로 FAIL 한다.

beforeEach(resetEventKit);

/** 라이브 위치는 품고 청한 자리는 품지 않는 같은 회차 새 url */
function liveOnlyA2(): SessionEvent {
  return poll(liveReady(A2, NOW - 30 * MIN));
}

/** 청한 자리까지 품는 같은 회차 새 url */
function wideA2(): SessionEvent {
  return poll(liveReady(A2, T0));
}

/** 목록 안 시크의 목표 — 청한 자리 ASKED · 확인된 자리 P20 와 다르다 */
const SOUGHT = T0 + 40 * MIN;
const REMOUNT: SessionEvent = { type: 'remount' };

/** 되감기 목록 안 시크 — 목표는 SOUGHT · 하한은 첫 조각 + 여유 */
function seekRewind(): SessionEvent {
  return { type: 'seek', targetMs: SOUGHT, view: { lowerMs: T0 + 12_000, endMs: NOW } };
}

/** 다시보기 목록 안 시크 — 다시보기는 하한 여유가 없다 */
function seekVod(targetMs: number): SessionEvent {
  return { type: 'seek', targetMs, view: { lowerMs: T0, endMs: NOW } };
}

/**
 * 다시보기 재생기를 다시 세운다 — 시작은 이어 볼 자리(확인된 위치 · 시크 목표 · 청한 자리)이고, 첫
 * 위치 전이면 앞 되감기 소스의 lastAtMs(라이브 위치 LIVE_AT)가 아니다(c7 리뷰 V7-1). 세대는 하나 오른다
 */
function expectVodRemountAt(vod: PlaybackSessionState, startAtMs: number | null): void {
  const again = feed(vod, [REMOUNT]).state;
  expect(again.source).toMatchObject({ kind: 'vod', url: VOD, startAtMs });
  expect(again.source.generation).toBe(vod.source.generation + 1);
}

/** 라이브 위치를 남긴 채 되감은 뒤 ENDLIST → 회차별 vod 로 다시보기 제안까지 온 세션 */
function offeredFromLive(): PlaybackSessionState {
  return feed(rewindFromLive(), [ENDLIST, { type: 'round-poll', result: vodReady() }]).state;
}

/**
 * 다시 받는 중('paint')에 시크해 시크가 끝나지 않은 되감기(18절의 relist · S) — 404 → 다시
 * 받기 → 첫 목록 판정 뒤 목록 안 시크. 시작 요청은 ASKED, 시크 목표는 SOUGHT 다
 */
function paintSeeking(): PlaybackSessionState {
  const requested = rewindFromLive();
  return feed(requested, [gone(requested), loaded(A1, false), seekRewind()]).state;
}

describe('Q1 — 폴링의 새 url 교체는 이어 볼 자리로 범위를 본다(계획 3-4)', () => {
  it('① 404 뒤 다시 받는 중 · 라이브 위치만 품은 새 url → 교체 없음 · pendingNext · 청한 자리 유지 → 판정 → 화면', () => {
    const s = rewindFromLive();
    const r = feed(s, [gone(s), liveOnlyA2()]).state;
    expect(r.source).toMatchObject({ url: A1, startAtMs: ASKED });
    expect(r.phase).toMatchObject({
      kind: 'rewind',
      relist: { stage: 'list' },
      pendingNext: { url: A2 },
    });
    const judged = feed(r, [loaded(A1, true), pos(ASKED + 12_000)]);
    expect(notices(judged.effects)).toEqual([SESSION_NOTICES.oldest]);
    expect(judged.state.phase).toMatchObject({ kind: 'rewind', pendingNext: { url: A2 } });
    expect(judged.state.phase).not.toHaveProperty('relist');
  });

  it('② 같은 차림 · 새 url 이 청한 자리를 품음 → 그 url 로 청한 자리에서 · relist 버림 → 404 → 그 url 을 다시 받음', () => {
    const s = rewindFromLive();
    const r = feed(s, [gone(s), wideA2()]).state;
    expect(r.source).toMatchObject({ url: A2, startAtMs: ASKED });
    expect(r.phase).toEqual({ kind: 'rewind', pendingNext: null });
    const again = feed(r, [gone(r)]).state;
    expect(again.source).toMatchObject({ url: A2, startAtMs: ASKED });
    expect(again.phase).toEqual(relistPhase('list'));
  });

  it('③ 끝나지 않은 시크: 시크 전 자리만 품은 새 url → 교체 없음 · pendingNext → 404 → 시크 목표에서 다시 받음', () => {
    const q = T0 + 40 * MIN;
    const s = feed(rewindAt(P20), [
      { type: 'seek', targetMs: q, view: { lowerMs: T0 + 12_000, endMs: NOW } },
      poll(liveReady(A2, T0 + 10 * MIN, T0 + 30 * MIN)),
    ]).state;
    expect(s.source).toMatchObject({ url: A1 });
    expect(s.phase).toMatchObject({ kind: 'rewind', pendingNext: { url: A2 } });
    expect(feed(s, [gone(s)]).state.source.startAtMs).toBe(q);
  });

  it('④ relist 밖 · 라이브에서 막 되감은 직후(404 없음): 라이브 위치만 품은 새 url → 교체 없음 · pendingNext', () => {
    const r = feed(rewindFromLive(), [liveOnlyA2()]).state;
    expect(r.source).toMatchObject({ url: A1, startAtMs: ASKED });
    expect(r.phase).toMatchObject({ kind: 'rewind', pendingNext: { url: A2 } });
    const wide = feed(rewindFromLive(), [wideA2()]).state;
    expect(wide.source).toMatchObject({ url: A2, startAtMs: ASKED });
  });

  it('⑤ 대조(지금 동작 그대로): 확인된 자리를 품은 새 url → 그 자리에서 교체', () => {
    const r = feed(rewindAt(P20), [poll(liveReady(A2, T0 + 10 * MIN))]).state;
    expect(r.source).toMatchObject({ url: A2, startAtMs: P20 });
  });
});

describe('재방문 거르기 — 이 회차에서 이미 본 되감기 url 로는 다시 잇지 않는다(계획 3-4 · P46)', () => {
  it('(가) A → B 교체 → A 가 다시 와도 그대로 — url B · pendingNext 그대로', () => {
    const b = feed(rewindAt(P20), [poll(liveReady(A2, T0 + 10 * MIN))]).state;
    expect(b.source.url).toBe(A2);
    const back = feed(b, [poll(liveReady(A1, T0))]).state;
    expect(back.source).toBe(b.source);
    expect(back.phase).toEqual(b.phase);
  });

  it('(나) 한계 고정: 넘김 확인 중 본 새 url 은 넘김이 깨져 돌아온 되감기에서 잇지 않는다', () => {
    const p = NOW - 2_000;
    const watched = feed(fresh(), [
      poll(liveReady(A1, T0, NOW)),
      { type: 'seek', targetMs: p, view: { lowerMs: p + 1, endMs: NOW + 28_000 } },
      loaded(A1),
      streamPoll(liveReady(A1, T0, NOW), X_A, R),
      pos(p),
    ]).state;
    const handed = feed(watched, [settleProbe(watched, p - 2_000)], R + 500).state;
    expect(handed.phase).toEqual({ kind: 'live' });
    const sawB = feed(handed, [poll(liveReady(A2, T0))]).state;
    expect(sawB.roundRewindUrls.has(A2)).toBe(true);
    const reverted = feed(sawB, [pos(p + 10_000, { runMs: 0 })]).state;
    expect(reverted.source).toMatchObject({ kind: 'rewind', url: A1 });
    const kept = feed(reverted, [poll(liveReady(A2, T0))]).state;
    expect(kept.source).toBe(reverted.source);
  });
});

describe('국면 밖 — 되감기 소스가 남은 국면도 이어 볼 자리를 읽는다(계획 3-4 · 18절)', () => {
  it('1 다시 받기 → ENDLIST → 끝남 대기 → 청한 자리를 품은 새 url 로 그 자리에서 잇는다 / 라이브 위치만 품으면 잇지 않는다', () => {
    const s = rewindFromLive();
    const ended = feed(s, [gone(s), loaded(A1), ENDLIST]).state;
    expect(ended.phase).toMatchObject({ kind: 'ending-wait', origin: 'rewind', atEnd: false });
    const joined = feed(ended, [wideA2()]).state;
    expect(joined.source).toMatchObject({ kind: 'rewind', url: A2, startAtMs: ASKED });
    const kept = feed(ended, [liveOnlyA2()]).state;
    expect(kept.phase.kind).toBe('ending-wait');
    expect(kept.source).toBe(ended.source);
  });

  it('2 다시 받기 → 화면 전 404 → 막힘 → 회차별 vod → 청한 자리에서 다시보기 · 알림 0', () => {
    const s = rewindFromLive();
    const blocked = feed(s, [gone(s), gone(s, s.source.generation + 1)]).state;
    expect(blocked.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    const vod = feed(blocked, [{ type: 'round-poll', result: vodReady() }]);
    expect(vod.state.source).toMatchObject({ kind: 'vod', url: VOD, startAtMs: ASKED });
    expect(notices(vod.effects)).toEqual([]);
    expect(vod.state.lastAtMs).toBe(LIVE_AT);
    expectVodRemountAt(vod.state, ASKED);
  });

  it('3 다시보기 제안(되감기 소스 · 첫 위치 전) — 404 · 단추 모두 청한 자리에서 / 대조: live 기원은 lastAtMs', () => {
    const offered = offeredFromLive();
    expect(offered.phase.kind).toBe('vod-offered');
    const byGone = feed(offered, [gone(offered)]).state;
    expect(byGone.source).toMatchObject({ kind: 'vod', startAtMs: ASKED });
    const byButton = feed(offered, [{ type: 'button', which: 'vod' }]).state;
    expect(byButton.source).toMatchObject({ kind: 'vod', startAtMs: ASKED });
    const liveOffered = feed(fresh(), [
      poll(liveReady(A1, T0, NOW)),
      loaded(LIVE_URL),
      pos(T0 + 30 * MIN),
      ENDLIST,
      { type: 'round-poll', result: vodReady() },
    ]).state;
    expect(liveOffered.phase.kind).toBe('vod-offered');
    const liveVod = feed(liveOffered, [{ type: 'button', which: 'vod' }]).state;
    expect(liveVod.source).toMatchObject({ kind: 'vod', startAtMs: T0 + 30 * MIN });
    expectVodRemountAt(byGone, ASKED);
    expectVodRemountAt(byButton, ASKED);
  });

  it('3 뒤 · 끝에 닿은 뒤의 단추 vod 는 처음부터(null) — 첫 위치 전 리마운트도 처음부터', () => {
    const atEnd = feed(offeredFromLive(), [END_REACHED, { type: 'button', which: 'vod' }]).state;
    expect(atEnd.source).toMatchObject({ kind: 'vod', startAtMs: null });
    expect(atEnd.lastAtMs).toBe(LIVE_AT);
    expectVodRemountAt(atEnd, null);
  });

  it('3 뒤 · 다시보기 안 시크가 끝나기 전 리마운트 → 시크 목표 / 확인된 위치 뒤 → 그 자리(지금 동작)', () => {
    const opened = feed(offeredFromLive(), [{ type: 'button', which: 'vod' }]).state;
    const seeking = feed(opened, [seekVod(SOUGHT)]).state;
    expect(seeking.pendingSeekMs).toBe(SOUGHT);
    expectVodRemountAt(seeking, SOUGHT);
    const watched = feed(opened, [loaded(VOD), pos(ASKED + 30_000)]).state;
    expectVodRemountAt(watched, ASKED + 30_000);
    const settled = feed(seeking, [loaded(VOD), pos(SOUGHT + 4_000)]).state;
    expectVodRemountAt(settled, SOUGHT + 4_000);
  });

  it('[새 함수 — 음성 대조 제외 · 변이 M7z2 로 교정] 목록 하한(0)에서 선 relist → 막힘 → vod 는 처음부터 · 알림 0(손 상태 — J2)', () => {
    const base = rewindFromLive();
    const floor = { ...base, source: { ...base.source, startAtMs: 0 } };
    const blocked = feed(floor, [gone(floor), gone(floor, floor.source.generation + 1)]).state;
    const vod = feed(blocked, [{ type: 'round-poll', result: vodReady() }]);
    expect(vod.state.source).toMatchObject({ kind: 'vod', startAtMs: null });
    expect(notices(vod.effects)).toEqual([]);
  });
});

describe('relist · S — 다시 받는 중의 시크 목표가 시작 요청을 이긴다(18절 · c7 리뷰 cx M)', () => {
  it('paint 진입 뒤 시크 → relist 유지 · 이어 볼 자리는 시크 목표', () => {
    const s = paintSeeking();
    expect(s.phase).toEqual(relistPhase('paint'));
    expect(s.source.startAtMs).toBe(ASKED);
    expect(s.pendingSeekMs).toBe(SOUGHT);
    expect(resumeBySpec(s)).toBe(SOUGHT);
  });

  it('폴링 교체 · 리마운트 · 화면 전 404 → 막힘 → vod 모두 시크 목표에서', () => {
    const s = paintSeeking();
    const swapped = feed(s, [wideA2()]).state;
    expect(swapped.source).toMatchObject({ url: A2, startAtMs: SOUGHT });
    const remounted = feed(s, [REMOUNT]).state;
    expect(remounted.source).toMatchObject({ url: A1, startAtMs: SOUGHT });
    expect(remounted.phase).toEqual(relistPhase('list'));
    const blocked = feed(s, [gone(s)]).state;
    expect(blocked.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    const vod = feed(blocked, [{ type: 'round-poll', result: vodReady() }]).state;
    expect(vod.source).toMatchObject({ kind: 'vod', startAtMs: SOUGHT });
  });
});

describe('T5 — 그대로 칸의 사건은 이어 볼 자리를 바꾸지 않는다(18절 표 · 감싸개가 단언)', () => {
  /** 18절의 위치 상태 — 확인됨(C) · 시크 중(S) · 요청만(R) × relist 없음 · list · paint */
  function starts(): Array<[string, PlaybackSessionState]> {
    const confirmed = rewindAt(P20);
    const seeking = feed(confirmed, [seekRewind()]).state;
    const requested = rewindFromLive();
    const listing = feed(requested, [gone(requested)]).state;
    const paintingS = paintSeeking();
    return [
      ['C', confirmed],
      ['S', seeking],
      ['R', requested],
      ['list · R', listing],
      ['paint · S', paintingS],
    ];
  }

  /** 18절 표의 「그대로」 칸 사건(3a · 3d · 4b · 7 · 8′ · 9′ · 11 · 12 — 3b 는 재방문 (가)) */
  function quietEvents(state: PlaybackSessionState): Array<[string, SessionEvent]> {
    return [
      ['3a readyLive 아님', poll(status({ mode: 'live', ready: false }))],
      ['3a 같은 url', poll(liveReady(A1, T0, NOW))],
      ['3a 다른 회차', poll(liveReady(A2, T0), X_B)],
      ['3a X 모름', poll(liveReady(A2, T0), X_UNKNOWN)],
      ['3d 범위 밖', poll(liveReady(A2, NOW + 2 * MIN, NOW + 3 * MIN))],
      ['4b 옛 세대 404', gone(state, state.source.generation - 1)],
      ['7 roster-ended', { type: 'roster-ended' }],
      ["8' 남의 url 목록", loaded(A2, true)],
      ["9' 옛 시대 위치", pos(NOW - 30_000, { epoch: state.positionEpoch - 1 })],
      ['11 탐침 없는 live-probe', settleProbe(state)],
      ['12 playing', { type: 'playing' }],
      ['12 tick', { type: 'tick' }],
      ['12 live-closed', { type: 'live-closed' }],
      ['12 live-400', { type: 'live-400' }],
      ['12 live-start-truncated', { type: 'live-start-truncated' }],
    ];
  }

  it('위치 상태 다섯 × 그대로 칸 사건 열다섯 — 이어 볼 자리 · 세대가 같다', () => {
    for (const [name, start] of starts()) {
      for (const [label, event] of quietEvents(start)) {
        const after = feed(start, [event]).state;
        expect(resumeBySpec(after), `${name} · ${label}`).toBe(resumeBySpec(start));
        expect(after.source.generation, `${name} · ${label}`).toBe(start.source.generation);
      }
    }
  });
});
