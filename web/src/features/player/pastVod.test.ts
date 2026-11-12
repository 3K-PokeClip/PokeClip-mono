import { describe, expect, it } from 'vitest';
import type { RewindMode, RewindStatusResult } from '@/api/rewindStatus';
import {
  foldPastVodAnswer,
  nextPastVodState,
  pastCardMode,
  pastVodNeedsPoll,
  type PastBlockReason,
  type PastCardMode,
  type PastVodClock,
  type PastVodHold,
  type PastVodState,
  type VodPhase,
} from './pastVod';
import { RESUME_WAIT_MS, type RewindRange } from './sessionShared';

// 끝난 뒤 들어온 화면(A)의 다시보기 판정(POK-546 설계 2-1 · 4-5). 회차 경로 답 하나가
// 상태를 어떻게 옮기는지는 답의 종류와 직전 상태에 따라 다르다 — ready 는 내려가지 않고,
// 404 는 원점에서 360초 안이면 아직 모름 · 지나면 없음이고, 503 류는 직전을 둔다(아직 아무
// 답도 없을 때만 잠시 불가). 카드 갈래는 판정표 10줄의 위에서부터 처음 맞는 줄이다.

/** 기준 시각(epoch ms) — 시계 값은 이것에서 잰다 */
const NOW = 1_800_000_000_000;
const VOD: RewindRange = {
  url: 'https://media.example/vod/r-A/index.m3u8',
  firstPdtMs: NOW - 7_200_000,
  headPdtMs: NOW - 900_000,
};

const NOT_ANSWERED: PastVodState = { kind: 'checking', answered: false };
const ANSWERED: PastVodState = { kind: 'checking', answered: true };
const PREPARING: PastVodState = { kind: 'preparing' };
const READY: PastVodState = { kind: 'ready', vod: VOD };
const NONE: PastVodState = { kind: 'none' };
const UNAVAILABLE: PastVodState = { kind: 'unavailable' };
const UNCONFIGURED: PastVodState = { kind: 'unconfigured' };

/** 상태 이름표 — 시험 이름과 표가 같은 이름으로 상태를 가리킨다 */
const STATES = {
  unconfigured: UNCONFIGURED,
  'checking(답 없음)': NOT_ANSWERED,
  'checking(답 받음)': ANSWERED,
  preparing: PREPARING,
  ready: READY,
  none: NONE,
  unavailable: UNAVAILABLE,
} as const satisfies Record<string, PastVodState>;
type StateName = keyof typeof STATES;

/** ready 가 아닌 직전 상태 — 「답이 상태를 정한다」 사례는 이 모두에서 같은 결과다 */
const NOT_READY_PREVS: ReadonlyArray<[StateName, PastVodState]> = (
  Object.entries(STATES) as Array<[StateName, PastVodState]>
).filter(([name]) => name !== 'ready');

/** 200 답. ready 면 범위가 다 있고, vod 미준비면 url 만 있다(parseRewindStatus 가 받는 꼴) */
function ok(mode: RewindMode | null, ready: boolean): RewindStatusResult {
  const hasRange = ready;
  return {
    kind: 'ok',
    status: {
      ready,
      mode,
      url: hasRange || mode === 'vod' ? VOD.url : null,
      firstPdtMs: hasRange ? VOD.firstPdtMs : null,
      headPdtMs: hasRange ? VOD.headPdtMs : null,
    },
  };
}

const NOT_FOUND: RewindStatusResult = { kind: 'none' };
/** 503 · 그 밖 응답 · 망 · 형식 오류 · 시한 — fetchRewindStatus · useRewindStatus 가 모두 이 꼴로 접는다 */
const TRY_LATER: RewindStatusResult = { kind: 'unavailable' };

/** 원점에서 elapsedMs 지난 시계 */
function clockAfter(elapsedMs: number): PastVodClock {
  return { nowMs: NOW, unknownSinceMs: NOW - elapsedMs };
}
const IN_GRACE = clockAfter(100_000);

describe('nextPastVodState — 설계 4-5 답 처리 표의 일곱 줄', () => {
  it('ended → none(곧바로 없음)', () => {
    expect(nextPastVodState(NOT_ANSWERED, ok('ended', false), IN_GRACE)).toEqual(NONE);
  });

  it('404 · 원점 + 360초 안 → 직전 유지, 아직 아무 답 없으면 checking(답 받음)', () => {
    expect(nextPastVodState(PREPARING, NOT_FOUND, IN_GRACE)).toEqual(PREPARING);
    expect(nextPastVodState(NOT_ANSWERED, NOT_FOUND, IN_GRACE)).toEqual(ANSWERED);
  });

  it('404 · 원점 + 360초 뒤 → none', () => {
    expect(nextPastVodState(ANSWERED, NOT_FOUND, clockAfter(RESUME_WAIT_MS))).toEqual(NONE);
  });

  it('503 류 → 직전 유지, 아직 아무 답 없을 때만 unavailable', () => {
    expect(nextPastVodState(PREPARING, TRY_LATER, IN_GRACE)).toEqual(PREPARING);
    expect(nextPastVodState(NOT_ANSWERED, TRY_LATER, IN_GRACE)).toEqual(UNAVAILABLE);
  });

  it('mode null → checking(답 받음)', () => {
    expect(nextPastVodState(NOT_ANSWERED, ok(null, false), IN_GRACE)).toEqual(ANSWERED);
  });

  it('vod ∧ ready → ready(범위를 싣는다)', () => {
    expect(nextPastVodState(NOT_ANSWERED, ok('vod', true), IN_GRACE)).toEqual(READY);
  });

  it('ready 뒤 어떤 답 → ready 유지', () => {
    expect(nextPastVodState(READY, ok('ended', false), IN_GRACE)).toEqual(READY);
  });
});

describe('nextPastVodState — T1-1 답 × 직전 상태', () => {
  /** 답이 상태를 정하는 200 답 — ready 가 아닌 직전이면 어느 것이든 이 결과다 */
  const DECIDING: ReadonlyArray<[string, StateName, RewindStatusResult]> = [
    ['live · ready', 'preparing', ok('live', true)],
    ['live · 미준비', 'preparing', ok('live', false)],
    ['ending', 'preparing', ok('ending', false)],
    ['vod · 미준비', 'preparing', ok('vod', false)],
    ['vod · ready', 'ready', ok('vod', true)],
    ['ended', 'none', ok('ended', false)],
    ['mode null', 'checking(답 받음)', ok(null, false)],
  ];

  describe.each(NOT_READY_PREVS)('직전 %s', (_name, prev) => {
    it.each(DECIDING)('200 %s → %s', (_label, expected, result) => {
      expect(nextPastVodState(prev, result, IN_GRACE)).toEqual(STATES[expected]);
    });
  });

  /** ready 뒤에 올 수 있는 답 전부 — 이름 · 답 · 시계 */
  const AFTER_READY: ReadonlyArray<[string, RewindStatusResult, PastVodClock]> = [
    ...DECIDING.map(([label, , result]): [string, RewindStatusResult, PastVodClock] => [
      `200 ${label}`,
      result,
      IN_GRACE,
    ]),
    ['404 유예 안', NOT_FOUND, IN_GRACE],
    ['404 유예 뒤', NOT_FOUND, clockAfter(RESUME_WAIT_MS + 1)],
    ['503 류', TRY_LATER, IN_GRACE],
  ];

  it.each(AFTER_READY)(
    '직전 ready · %s → ready 유지(재생 중 목록을 끊지 않는다)',
    (_label, result, clock) => {
      expect(nextPastVodState(READY, result, clock)).toEqual(READY);
    },
  );

  it.each<[StateName, StateName]>([
    ['unconfigured', 'unconfigured'],
    ['checking(답 없음)', 'checking(답 받음)'],
    ['checking(답 받음)', 'checking(답 받음)'],
    ['preparing', 'preparing'],
    ['none', 'none'],
    ['unavailable', 'unavailable'],
  ])('직전 %s · 404 유예 안 → %s', (prev, expected) => {
    expect(nextPastVodState(STATES[prev], NOT_FOUND, IN_GRACE)).toEqual(STATES[expected]);
  });

  it.each(NOT_READY_PREVS)('직전 %s · 404 유예 뒤 → none', (_name, prev) => {
    expect(nextPastVodState(prev, NOT_FOUND, clockAfter(RESUME_WAIT_MS + 1))).toEqual(NONE);
  });

  it.each<[StateName, StateName]>([
    ['unconfigured', 'unconfigured'],
    ['checking(답 없음)', 'unavailable'],
    ['checking(답 받음)', 'checking(답 받음)'],
    ['preparing', 'preparing'],
    ['none', 'none'],
    ['unavailable', 'unavailable'],
  ])('직전 %s · 503 류 → %s', (prev, expected) => {
    expect(nextPastVodState(STATES[prev], TRY_LATER, IN_GRACE)).toEqual(STATES[expected]);
  });

  it('503 류는 유예가 지났어도 직전을 둔다 — 404 만 시계를 본다', () => {
    expect(nextPastVodState(ANSWERED, TRY_LATER, clockAfter(RESUME_WAIT_MS * 2))).toEqual(ANSWERED);
  });

  it('ready 의 범위는 답의 url · 첫 · 끝 시각이다', () => {
    const next = nextPastVodState(PREPARING, ok('vod', true), IN_GRACE);
    expect(next).toEqual({ kind: 'ready', vod: VOD });
  });

  it('범위가 빠진 vod ∧ ready 는 형식 오류처럼 — 직전 유지, 아직 아무 답 없으면 unavailable', () => {
    const noRange: RewindStatusResult = {
      kind: 'ok',
      status: { ready: true, mode: 'vod', url: VOD.url, firstPdtMs: null, headPdtMs: null },
    };
    expect(nextPastVodState(PREPARING, noRange, IN_GRACE)).toEqual(PREPARING);
    expect(nextPastVodState(NOT_ANSWERED, noRange, IN_GRACE)).toEqual(UNAVAILABLE);
  });
});

describe('nextPastVodState — T1-2 직전 유지(G5)', () => {
  it('아직 아무 답 없음 + 503 → unavailable', () => {
    expect(nextPastVodState(NOT_ANSWERED, TRY_LATER, IN_GRACE)).toEqual(UNAVAILABLE);
  });

  it('mode null → checking(답 받음), 그 뒤 503 → checking(답 받음) 그대로', () => {
    const afterNull = nextPastVodState(NOT_ANSWERED, ok(null, false), IN_GRACE);
    expect(afterNull).toEqual(ANSWERED);
    expect(nextPastVodState(afterNull, TRY_LATER, IN_GRACE)).toEqual(ANSWERED);
  });

  it('preparing + 503 → preparing', () => {
    expect(nextPastVodState(PREPARING, TRY_LATER, IN_GRACE)).toEqual(PREPARING);
  });

  it.each<[string, RewindStatusResult, PastVodClock]>([
    ['404 유예 안', NOT_FOUND, IN_GRACE],
    ['404 유예 뒤', NOT_FOUND, clockAfter(RESUME_WAIT_MS + 1)],
    ['503 류', TRY_LATER, IN_GRACE],
    ['ended', ok('ended', false), IN_GRACE],
  ])('ready + %s → ready', (_label, result, clock) => {
    expect(nextPastVodState(READY, result, clock)).toEqual(READY);
  });
});

describe('nextPastVodState — T1-3 404 는 원점에서 360초 안이면 아직 모름, 지나면 없음', () => {
  it('(a) 원점(endedAt) = 지금 − 100초 · 404 → checking(답 받음)', () => {
    expect(nextPastVodState(NOT_ANSWERED, NOT_FOUND, clockAfter(100_000))).toEqual(ANSWERED);
  });

  it('(b) 원점 + 360초 뒤 404 → none · 바로 앞(1ms 전)은 아직 checking', () => {
    expect(nextPastVodState(ANSWERED, NOT_FOUND, clockAfter(RESUME_WAIT_MS - 1))).toEqual(ANSWERED);
    expect(nextPastVodState(ANSWERED, NOT_FOUND, clockAfter(RESUME_WAIT_MS))).toEqual(NONE);
    expect(nextPastVodState(ANSWERED, NOT_FOUND, clockAfter(RESUME_WAIT_MS + 1))).toEqual(NONE);
  });

  it('(c) 원점(endedAt) = 지금 − 400초 · 첫 404 → 곧바로 none', () => {
    expect(nextPastVodState(NOT_ANSWERED, NOT_FOUND, clockAfter(400_000))).toEqual(NONE);
  });

  it('(d) endedAt 이 없으면 첫 404 시각이 원점 — 그때는 checking, 360초 뒤 404 는 none', () => {
    const firstNotFoundMs = NOW;
    const first = nextPastVodState(NOT_ANSWERED, NOT_FOUND, {
      nowMs: firstNotFoundMs,
      unknownSinceMs: firstNotFoundMs,
    });
    expect(first).toEqual(ANSWERED);
    const midway = nextPastVodState(first, NOT_FOUND, {
      nowMs: firstNotFoundMs + 200_000,
      unknownSinceMs: firstNotFoundMs,
    });
    expect(midway).toEqual(ANSWERED);
    const after = nextPastVodState(midway, NOT_FOUND, {
      nowMs: firstNotFoundMs + RESUME_WAIT_MS,
      unknownSinceMs: firstNotFoundMs,
    });
    expect(after).toEqual(NONE);
  });
});

describe('foldPastVodAnswer — T6-3 404 유예 원점의 우선순위(originMs > 첫 404 > 지금)', () => {
  /** 직전 묶음 — 상태와 첫 404 시각 */
  const hold = (state: PastVodState, firstUnknownMs: number | null): PastVodHold => ({
    state,
    firstUnknownMs,
  });

  it('originMs 가 첫 404 보다 이긴다 — 원점이 유예 밖이면 첫 404 가 유예 안이어도 none', () => {
    const prev = hold(ANSWERED, NOW - 100_000);
    const next = foldPastVodAnswer(prev, NOT_FOUND, NOW, NOW - 400_000);
    expect(next.state).toEqual(NONE);
  });

  it('originMs 가 첫 404 보다 이긴다 — 원점이 유예 안이면 첫 404 가 유예 밖이어도 아직 checking', () => {
    const prev = hold(ANSWERED, NOW - 400_000);
    const next = foldPastVodAnswer(prev, NOT_FOUND, NOW, NOW - 100_000);
    expect(next.state).toEqual(ANSWERED);
  });

  it('originMs 가 없으면 첫 404 시각이 원점이다', () => {
    expect(foldPastVodAnswer(hold(ANSWERED, NOW - 400_000), NOT_FOUND, NOW, null).state).toEqual(
      NONE,
    );
    expect(foldPastVodAnswer(hold(ANSWERED, NOW - 100_000), NOT_FOUND, NOW, null).state).toEqual(
      ANSWERED,
    );
  });

  it('둘 다 없으면 지금이 원점이다 — 첫 404 는 checking(답 받음)이고 그 시각을 첫 404 로 적는다', () => {
    const next = foldPastVodAnswer(hold(NOT_ANSWERED, null), NOT_FOUND, NOW, null);
    expect(next).toEqual(hold(ANSWERED, NOW));
  });

  it('첫 404 시각은 처음 받은 그대로다 — 다음 404 도 · originMs 가 있어도 덮지 않는다', () => {
    const first = foldPastVodAnswer(hold(NOT_ANSWERED, null), NOT_FOUND, NOW, NOW - 100_000);
    expect(first.firstUnknownMs).toBe(NOW);
    const second = foldPastVodAnswer(first, NOT_FOUND, NOW + 10_000, null);
    expect(second.firstUnknownMs).toBe(NOW);
  });

  it.each<[string, RewindStatusResult]>([
    ['503 류', TRY_LATER],
    ['200 mode null', ok(null, false)],
    ['200 ending', ok('ending', false)],
  ])('404 가 아닌 답(%s)은 첫 404 시각을 적지 않는다', (_label, result) => {
    expect(foldPastVodAnswer(hold(NOT_ANSWERED, null), result, NOW, null).firstUnknownMs).toBe(
      null,
    );
  });

  it('상태는 nextPastVodState 그대로다 — ready 는 내려가지 않는다', () => {
    const next = foldPastVodAnswer(hold(READY, null), ok('ended', false), NOW, NOW - 400_000);
    expect(next.state).toEqual(READY);
  });

  it('직전 묶음을 바꾸지 않고 새 묶음을 낸다', () => {
    const prev = hold(NOT_ANSWERED, null);
    const next = foldPastVodAnswer(prev, NOT_FOUND, NOW, null);
    expect(prev).toEqual(hold(NOT_ANSWERED, null));
    expect(next).not.toBe(prev);
  });
});

describe('pastVodNeedsPoll — T1-5 다시 물을 상태', () => {
  it.each<[StateName, boolean]>([
    ['checking(답 없음)', true],
    ['checking(답 받음)', true],
    ['preparing', true],
    ['unavailable', true],
    ['unconfigured', false],
    ['ready', false],
    ['none', false],
  ])('%s → %s', (state, expected) => {
    expect(pastVodNeedsPoll(STATES[state])).toBe(expected);
  });
});

describe('pastCardMode — T1-4 판정표 10줄(위에서부터 처음 맞는 줄)', () => {
  type Input = Parameters<typeof pastCardMode>[0];
  /** 8줄(playable)에 닿는 입력 — 사례마다 한두 칸만 바꾼다 */
  const PLAYABLE_INPUT: Input = {
    enteredAfterEnd: true,
    expired: false,
    vod: READY,
    access: 'ready',
    phase: 'showing',
  };
  const inputWith = (patch: Partial<Input>): Input => ({ ...PLAYABLE_INPUT, ...patch });
  const blocked = (reason: PastBlockReason): PastCardMode => ({ kind: 'blocked', reason });

  it.each<[string, Input, PastCardMode]>([
    [
      '1 끝나기 전에 들어온 화면(라이브 · B)',
      inputWith({ enteredAfterEnd: false }),
      { kind: 'notPast' },
    ],
    ['2 보관 만료', inputWith({ expired: true }), blocked('expired')],
    ['3 unconfigured', inputWith({ vod: UNCONFIGURED }), blocked('unconfigured')],
    ['3 preparing', inputWith({ vod: PREPARING }), blocked('preparing')],
    ['3 none', inputWith({ vod: NONE }), blocked('none')],
    ['3 unavailable', inputWith({ vod: UNAVAILABLE }), blocked('unavailable')],
    ['4 checking(답 없음)', inputWith({ vod: NOT_ANSWERED, phase: null }), { kind: 'waiting' }],
    ['4 checking(답 받음)', inputWith({ vod: ANSWERED, phase: null }), { kind: 'waiting' }],
    ['5 ready · 출입증 실패', inputWith({ access: 'failed' }), blocked('access-failed')],
    ['6 ready · 출입증 답 전', inputWith({ access: 'pending', phase: null }), { kind: 'waiting' }],
    ['7 ready · 재생기 없음', inputWith({ phase: null }), { kind: 'waiting' }],
    ['7 ready · 재생기 loading', inputWith({ phase: 'loading' }), { kind: 'waiting' }],
    ['8 ready · listed', inputWith({ phase: 'listed' }), { kind: 'playable' }],
    ['8 ready · showing', inputWith({ phase: 'showing' }), { kind: 'playable' }],
    ['9 ready · unsupported', inputWith({ phase: 'unsupported' }), blocked('unsupported')],
    ['10 ready · failed', inputWith({ phase: 'failed' }), blocked('failed')],
  ])('%s', (_name, input, expected) => {
    expect(pastCardMode(input)).toEqual(expected);
  });

  describe('경계 — 위 줄이 아래 줄을 이긴다', () => {
    it('끝나기 전에 들어온 화면이면 만료 · 상태 · 국면과 무관하게 notPast', () => {
      const input = inputWith({
        enteredAfterEnd: false,
        expired: true,
        vod: NONE,
        phase: 'failed',
      });
      expect(pastCardMode(input)).toEqual({ kind: 'notPast' });
    });

    it('보관 만료가 vod ready · 재생 중(showing)보다 이긴다', () => {
      expect(pastCardMode(inputWith({ expired: true }))).toEqual(blocked('expired'));
    });

    it.each(NOT_READY_PREVS)('보관 만료가 다시보기 상태 %s 보다 이긴다', (_name, vod) => {
      expect(pastCardMode(inputWith({ expired: true, vod, phase: null }))).toEqual(
        blocked('expired'),
      );
    });

    it('vod 가 ready 가 아니면 출입증 · 국면을 보지 않는다', () => {
      const input = inputWith({ vod: NOT_ANSWERED, access: 'failed', phase: 'failed' });
      expect(pastCardMode(input)).toEqual({ kind: 'waiting' });
      expect(pastCardMode(inputWith({ vod: NONE, phase: 'showing' }))).toEqual(blocked('none'));
    });

    it('출입증 실패가 재생기 국면보다 이긴다', () => {
      expect(pastCardMode(inputWith({ access: 'failed', phase: 'showing' }))).toEqual(
        blocked('access-failed'),
      );
    });

    it.each<VodPhase | null>([null, 'loading', 'listed', 'showing', 'unsupported', 'failed'])(
      '출입증 답 전이면 국면 %s 에서도 waiting',
      (phase) => {
        expect(pastCardMode(inputWith({ access: 'pending', phase }))).toEqual({ kind: 'waiting' });
      },
    );

    it('재생기 없음(null)과 loading 은 둘 다 waiting — 목록 전이라 받아 둔다', () => {
      expect(pastCardMode(inputWith({ phase: null }))).toEqual(
        pastCardMode(inputWith({ phase: 'loading' })),
      );
    });
  });
});
