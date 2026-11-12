import { describe, expect, it } from 'vitest';
import type { RewindMode, RewindStatusResult } from '@/api/rewindStatus';
import { RESUME_WAIT_MS } from '@/features/player/sessionShared';
import {
  FRESH_ROW_VOD,
  foldRowVod,
  isRowVodSettled,
  pickRoundAsks,
  type RowVodMemory,
  type VodBadge,
} from './vodRoundBadge';

// 지난 방송 목록 표지의 순수 판정(POK-568 계획 3-1 · 설계 2-3). 행마다 회차 경로 답을 끝난 뒤 들어온 화면(A)과
// 같은 규칙(foldPastVodAnswer)으로 접고, 표지는 ready · none · preparing 이면 그 이름, 모름이면 직전을 둔다.
// 이번 주기에 물을 행은 카드 수 세기와 같은 꼴이다 — 처음 보는 행 · 방금 끝난 행은 모두, 오래된 미결 행만 상한까지.

/** 기준 시각(epoch ms) */
const NOW = 1_800_000_000_000;
const VOD_URL = 'https://media.example/vod/r-A/index.m3u8';

/** 200 답. ready 면 범위가 다 있고, vod 미준비면 url 만 있다(parseRewindStatus 가 받는 꼴) */
function ok(mode: RewindMode | null, ready: boolean): RewindStatusResult {
  return {
    kind: 'ok',
    status: {
      ready,
      mode,
      url: ready || mode === 'vod' ? VOD_URL : null,
      firstPdtMs: ready ? NOW - 7_200_000 : null,
      headPdtMs: ready ? NOW - 900_000 : null,
    },
  };
}

const NOT_FOUND: RewindStatusResult = { kind: 'none' };
/** 503 · 그 밖 응답 · 망 · 형식 오류 · 시한 — 모두 이 꼴로 접힌다 */
const TRY_LATER: RewindStatusResult = { kind: 'unavailable' };

/** 명부 endedAt — 404 유예 안 · 밖 */
const ENDED_IN_GRACE_MS = NOW - 100_000;
const ENDED_PAST_GRACE_MS = NOW - RESUME_WAIT_MS - 40_000;

/** 직전 기억 — 처음 · 준비 중 · ready · none. 처음 말고는 FRESH 에 답 하나를 접어 세운다 */
const EARLIER_MS = NOW - 60_000;
const PREVS = {
  '처음(답 없음)': FRESH_ROW_VOD,
  'preparing 기억': foldRowVod(FRESH_ROW_VOD, ok('ending', false), EARLIER_MS, ENDED_IN_GRACE_MS),
  'ready 기억': foldRowVod(FRESH_ROW_VOD, ok('vod', true), EARLIER_MS, ENDED_IN_GRACE_MS),
  'none 기억': foldRowVod(FRESH_ROW_VOD, ok('ended', false), EARLIER_MS, ENDED_IN_GRACE_MS),
} as const satisfies Record<string, RowVodMemory>;
type PrevName = keyof typeof PREVS;

/** 답 이름표 — 답과 그 답을 받은 때의 명부 endedAt */
const ANSWERS = {
  'vod · ready': [ok('vod', true), ENDED_IN_GRACE_MS],
  ended: [ok('ended', false), ENDED_IN_GRACE_MS],
  'ending · 미준비 vod · live': [ok('ending', false), ENDED_IN_GRACE_MS],
  '유예 밖 404': [NOT_FOUND, ENDED_PAST_GRACE_MS],
  '유예 안 404': [NOT_FOUND, ENDED_IN_GRACE_MS],
  '503 · 망 · 시한': [TRY_LATER, ENDED_IN_GRACE_MS],
  'mode null': [ok(null, false), ENDED_IN_GRACE_MS],
} as const satisfies Record<string, readonly [RewindStatusResult, number | null]>;
type AnswerName = keyof typeof ANSWERS;

describe('FRESH_ROW_VOD — 처음 보는 행의 기억', () => {
  it('아직 아무 답도 없고 표지는 preparing · 물은 적 없음 · 다시 물을 행이다', () => {
    expect(FRESH_ROW_VOD).toEqual({
      state: { kind: 'checking', answered: false },
      firstUnknownMs: null,
      badge: 'preparing',
      askedAt: null,
    });
    expect(isRowVodSettled(FRESH_ROW_VOD)).toBe(false);
  });
});

describe('foldRowVod · isRowVodSettled — T6-1 답 × 직전 표지', () => {
  it('직전 기억은 세운 대로다(표의 열 머리 확인)', () => {
    expect(PREVS['preparing 기억'].badge).toBe('preparing');
    expect(PREVS['ready 기억'].badge).toBe('ready');
    expect(PREVS['none 기억'].badge).toBe('none');
  });

  /** 답 · 직전 · 기대 표지. ready · none 은 다시 묻지 않는 행(settled)이다 */
  const table: ReadonlyArray<[AnswerName, PrevName, VodBadge]> = [
    ['vod · ready', '처음(답 없음)', 'ready'],
    ['vod · ready', 'preparing 기억', 'ready'],
    ['vod · ready', 'ready 기억', 'ready'],
    ['vod · ready', 'none 기억', 'ready'],
    ['ended', '처음(답 없음)', 'none'],
    ['ended', 'preparing 기억', 'none'],
    ['ended', 'ready 기억', 'ready'],
    ['ended', 'none 기억', 'none'],
    ['ending · 미준비 vod · live', '처음(답 없음)', 'preparing'],
    ['ending · 미준비 vod · live', 'preparing 기억', 'preparing'],
    ['ending · 미준비 vod · live', 'ready 기억', 'ready'],
    ['ending · 미준비 vod · live', 'none 기억', 'preparing'],
    ['유예 밖 404', '처음(답 없음)', 'none'],
    ['유예 밖 404', 'preparing 기억', 'none'],
    ['유예 밖 404', 'ready 기억', 'ready'],
    ['유예 밖 404', 'none 기억', 'none'],
    ['유예 안 404', '처음(답 없음)', 'preparing'],
    ['유예 안 404', 'preparing 기억', 'preparing'],
    ['유예 안 404', 'ready 기억', 'ready'],
    ['유예 안 404', 'none 기억', 'none'],
    ['503 · 망 · 시한', '처음(답 없음)', 'preparing'],
    ['503 · 망 · 시한', 'preparing 기억', 'preparing'],
    ['503 · 망 · 시한', 'ready 기억', 'ready'],
    ['503 · 망 · 시한', 'none 기억', 'none'],
    ['mode null', '처음(답 없음)', 'preparing'],
    ['mode null', 'preparing 기억', 'preparing'],
    ['mode null', 'ready 기억', 'ready'],
    ['mode null', 'none 기억', 'none'],
  ];

  it.each(table)('%s · 직전 %s → 표지 %s', (answer, prevName, expected) => {
    const [result, endedAtMs] = ANSWERS[answer];
    const next = foldRowVod(PREVS[prevName], result, NOW, endedAtMs);
    expect(next.badge).toBe(expected);
    expect(isRowVodSettled(next)).toBe(expected === 'ready' || expected === 'none');
  });

  it.each(Object.keys(ANSWERS) as AnswerName[])(
    '%s 도 물은 시각(askedAt)을 넘긴 시각으로 세운다 — 실패도 시도다',
    (answer) => {
      const [result, endedAtMs] = ANSWERS[answer];
      expect(foldRowVod(PREVS['preparing 기억'], result, NOW, endedAtMs).askedAt).toBe(NOW);
    },
  );

  it('ready · none 표지만 다시 묻지 않는다 — preparing 은 다시 묻는다', () => {
    expect(isRowVodSettled(PREVS['ready 기억'])).toBe(true);
    expect(isRowVodSettled(PREVS['none 기억'])).toBe(true);
    expect(isRowVodSettled(PREVS['preparing 기억'])).toBe(false);
  });

  it('endedAt 이 없으면 첫 404 부터 유예를 센다 — 그 안은 직전 표지 · 지나면 none', () => {
    const first = foldRowVod(FRESH_ROW_VOD, NOT_FOUND, NOW, null);
    expect(first.badge).toBe('preparing');
    expect(first.firstUnknownMs).toBe(NOW);
    const midway = foldRowVod(first, NOT_FOUND, NOW + RESUME_WAIT_MS - 1, null);
    expect(midway.badge).toBe('preparing');
    const after = foldRowVod(midway, NOT_FOUND, NOW + RESUME_WAIT_MS, null);
    expect(after.badge).toBe('none');
  });

  it('직전 기억을 바꾸지 않는다 — 함께 쓰는 FRESH_ROW_VOD 가 그대로다', () => {
    const before = structuredClone(FRESH_ROW_VOD);
    foldRowVod(FRESH_ROW_VOD, ok('ended', false), NOW, ENDED_IN_GRACE_MS);
    expect(FRESH_ROW_VOD).toEqual(before);
  });
});

/** pickRoundAsks 후보 한 줄 */
interface Candidate {
  streamId: string;
  justEnded: boolean;
  askedAt: number | null;
}
/** 처음 보는 행(물은 적 없음) */
function firstSeen(streamId: string): Candidate {
  return { streamId, justEnded: false, askedAt: null };
}
/** 방금 끝난 행 — 물은 적이 있어도 매 주기 묻는다 */
function justEnded(streamId: string, askedAt: number): Candidate {
  return { streamId, justEnded: true, askedAt };
}
/** 오래된 미결 행 — 물었는데 아직 ready · none 이 아니다 */
function oldPending(streamId: string, askedAt: number): Candidate {
  return { streamId, justEnded: false, askedAt };
}
function ids(prefix: string, count: number) {
  return Array.from({ length: count }, (_, i) => `${prefix}${i}`);
}
const LIMIT = 20;

describe('pickRoundAsks — T6-2 처음 보는 행 · 방금 끝난 행은 모두, 오래된 미결 행만 상한까지', () => {
  it('① 처음 보는 60행 → 60행 모두(상한 밖)', () => {
    const candidates = ids('new', 60).map(firstSeen);
    expect(pickRoundAsks(candidates, LIMIT)).toEqual(new Set(ids('new', 60)));
  });

  it('② 방금 끝난 3 + 처음 보는 2 + 오래된 미결 25 → 3 + 2 + 오래전에 물은 20', () => {
    // 오래된 미결은 목록 순과 반대로 물었다 — 목록 앞쪽일수록 최근에 물음(old0 이 가장 최근)
    const old = ids('old', 25).map((id, i) => oldPending(id, NOW - i * 1_000));
    const candidates = [
      justEnded('just0', NOW - 1),
      ...old.slice(0, 10),
      firstSeen('new0'),
      justEnded('just1', NOW - 2),
      ...old.slice(10),
      firstSeen('new1'),
      justEnded('just2', NOW - 3),
    ];
    const oldestTwenty = ids('old', 25).slice(5);
    expect(pickRoundAsks(candidates, LIMIT)).toEqual(
      new Set(['just0', 'just1', 'just2', 'new0', 'new1', ...oldestTwenty]),
    );
  });

  it('③ 오래된 미결만 21 → 20(가장 최근에 물은 하나가 다음 주기로)', () => {
    const candidates = ids('old', 21).map((id, i) => oldPending(id, NOW - (21 - i) * 1_000));
    expect(pickRoundAsks(candidates, LIMIT)).toEqual(new Set(ids('old', 20)));
  });

  it('④ 같은 askedAt 은 목록 순 — 목록 끝이 다음 주기로', () => {
    const candidates = ids('old', 21).map((id) => oldPending(id, NOW - 5_000));
    expect(pickRoundAsks(candidates, LIMIT)).toEqual(new Set(ids('old', 20)));
  });

  it('⑤ 한 번 묻다 실패한 행은 askedAt 이 서서 상한 안으로 간다(처음 보는 행이 아니다)', () => {
    const failed = foldRowVod(FRESH_ROW_VOD, TRY_LATER, NOW, null);
    expect(failed.askedAt).toBe(NOW);
    const older = ids('old', LIMIT).map((id, i) => oldPending(id, NOW - (i + 1) * 1_000));
    const candidates = [oldPending('failed', failed.askedAt ?? NOW), ...older];
    expect(pickRoundAsks(candidates, LIMIT)).toEqual(new Set(ids('old', LIMIT)));
  });

  it('후보가 없으면 빈 묶음이다', () => {
    expect(pickRoundAsks([], LIMIT).size).toBe(0);
  });
});
