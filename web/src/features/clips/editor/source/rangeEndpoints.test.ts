import { describe, expect, it, vi } from 'vitest';
import { CUT_MAX_MS, CUT_MIN_MS } from '@/api/clipEditor';
import type { CutContext } from './cutPosition';
import type { EditorPlaylist, PlaylistSegment } from './editorPlaylist';
import type { HeadCache, HeadEntry } from './fileHeadProbe';
import {
  cardEndpoints,
  displayRange,
  displaySeconds,
  endpointOf,
  recipeEndpoints,
  savable,
  saveAfterReload,
  saveRefusalMessage,
  type Endpoint,
  type EndpointRange,
} from './rangeEndpoints';
import type { EditorPick } from './windowPlacement';

// 끝점 모델(설계 prw2 3.5 · 계획 P-4′ · 처분 PK1). exact 는 다시 셈하지 않고(왕복 항등), estimate 는 저장 값이 아니며, pick 은
// 저장할 때 그 seq 머리 · 이웃으로 셈한다. 저장 거절 사유는 일곱이다 — 계획의 여섯에 PK1 의 listUnavailable 을 더했다.

const BASE = 1_000_000;

function row(seq: number, pdtMs: number, timing: 'v2' | null): PlaylistSegment {
  return {
    seq,
    uri: `https://cdn.test/dvr/s/seg/${seq}.m4s`,
    mapUri: 'https://cdn.test/dvr/s/init.mp4',
    pdtMs,
    durationMs: 4000,
    timing,
    gap: false,
  };
}

const ROWS = [row(1, 1_000_000, null), row(2, 1_004_000, null), row(3, 1_008_000, null)];
const LIST: EditorPlaylist = { runs: [ROWS], rows: ROWS };

function headsOf(
  entries: Record<number, HeadEntry> = {},
): HeadCache & { set(seq: number, entry: HeadEntry): void } {
  const map = new Map(Object.entries(entries).map(([seq, entry]) => [Number(seq), entry]));
  return {
    get: (seq) => map.get(seq),
    subscribe: () => () => {},
    set: (seq, entry) => {
      map.set(seq, entry);
    },
  };
}

const READY: HeadEntry = { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.2 } };

/** seq 2 의 고름 — ③ D3 50.2 · 판 NULL · m 0.2 면 o = 50.0 */
function pickAt(offsetSeconds: number, seq = 2): EditorPick {
  return {
    seq,
    pts: 50.0 + offsetSeconds,
    dur: 0.04,
    videoDts: 50.2,
    pdtMs: ROWS[seq - 1]?.pdtMs ?? 2_000_000,
    durationMs: 4000,
    timing: null,
  };
}

function ctxOf(heads: HeadCache, list: EditorPlaylist | null = LIST): CutContext {
  return { base: BASE, heads, list };
}

function exact(ms: number): Endpoint {
  return { kind: 'exact', ms };
}

describe('endpointOf', () => {
  it('S 입력은 floor(base + 1000·S) 의 exact · 고름은 pick 그대로다', () => {
    const pick = pickAt(1);

    expect(endpointOf({ kind: 's', s: 6.0009 }, BASE)).toEqual(exact(BASE + 6000));
    expect(endpointOf({ kind: 'pick', pick }, BASE)).toEqual({ kind: 'pick', pick });
  });
});

describe('displaySeconds', () => {
  it('exact · estimate 는 (ms − base)/1000 이고 pick 은 고름의 PDT · 원점으로 셈한다', () => {
    const heads = headsOf({ 2: READY });

    expect(displaySeconds(exact(BASE + 2500), heads, BASE)).toBe(2.5);
    expect(displaySeconds({ kind: 'estimate', ms: BASE + 1500 }, heads, BASE)).toBe(1.5);
    expect(displaySeconds({ kind: 'pick', pick: pickAt(1) }, heads, BASE)).toBeCloseTo(5.0, 9);
  });

  it('고름의 seq 가 목록에서 빠져도 표시 초는 고름이 든 사실로 그린다', () => {
    const gone = pickAt(1, 9);

    expect(displaySeconds({ kind: 'pick', pick: gone }, headsOf(), BASE)).toBeCloseTo(
      (2_000_000 - BASE) / 1000 + 0.8,
      9,
    );
  });
});

describe('savable', () => {
  it('exact 둘은 그대로 저장 값이다(다시 셈하지 않음 — 왕복 항등)', () => {
    const range: EndpointRange = { start: exact(BASE + 1234), end: exact(BASE + 31_234) };

    expect(savable(range, ctxOf(headsOf()))).toEqual({
      ok: true,
      inMs: BASE + 1234,
      outMs: BASE + 31_234,
    });
  });

  it('pick 은 저장할 때 머리로 셈하고 머리가 늦게 오면 같은 범위가 저장 가능해진다', () => {
    const heads = headsOf({ 2: { state: 'missing' } });
    const range: EndpointRange = { start: exact(BASE), end: { kind: 'pick', pick: pickAt(1) } };

    const before = savable(range, ctxOf(heads));
    heads.set(2, READY);

    expect(before).toEqual({ ok: false, reason: 'headMissing' });
    expect(savable(range, ctxOf(heads))).toEqual({ ok: true, inMs: BASE, outMs: 1_004_000 + 1040 });
  });

  it('거절 사유는 estimate · headFailed · outsideList · listUnavailable 이 끝점에서 나온다', () => {
    const heads = headsOf({ 2: { state: 'failed' } });
    const ok = exact(BASE + 30_000);

    expect(savable({ start: { kind: 'estimate', ms: BASE }, end: ok }, ctxOf(heads))).toEqual({
      ok: false,
      reason: 'estimate',
    });
    expect(
      savable({ start: exact(BASE), end: { kind: 'pick', pick: pickAt(1) } }, ctxOf(heads)),
    ).toEqual({
      ok: false,
      reason: 'headFailed',
    });
    expect(
      savable(
        { start: exact(BASE), end: { kind: 'pick', pick: pickAt(1, 9) } },
        ctxOf(headsOf({ 9: READY })),
      ),
    ).toEqual({ ok: false, reason: 'outsideList' });
    expect(
      savable(
        { start: exact(BASE), end: { kind: 'pick', pick: pickAt(1) } },
        ctxOf(headsOf({ 2: READY }), null),
      ),
    ).toEqual({ ok: false, reason: 'listUnavailable' });
  });

  it('길이는 S 축 out − in 으로 5초 미만이면 tooShort · 180초를 넘으면 tooLong 이고 경계값은 저장한다', () => {
    const ctx = ctxOf(headsOf());

    expect(savable({ start: exact(BASE), end: exact(BASE + CUT_MIN_MS - 1) }, ctx)).toEqual({
      ok: false,
      reason: 'tooShort',
    });
    expect(savable({ start: exact(BASE), end: exact(BASE + CUT_MAX_MS + 1) }, ctx)).toEqual({
      ok: false,
      reason: 'tooLong',
    });
    expect(savable({ start: exact(BASE), end: exact(BASE + CUT_MIN_MS) }, ctx).ok).toBe(true);
    expect(savable({ start: exact(BASE), end: exact(BASE + CUT_MAX_MS) }, ctx).ok).toBe(true);
  });

  it('실행취소로 통째 돌아온 범위는 종류와 값이 그대로라 머리가 오면 저장된다', () => {
    const heads = headsOf();
    const earlier: EndpointRange = {
      start: { kind: 'pick', pick: pickAt(0.5) },
      end: exact(BASE + 20_000),
    };
    const restored: EndpointRange = { ...earlier };

    heads.set(2, READY);

    expect(restored.start).toBe(earlier.start);
    expect(savable(restored, ctxOf(heads))).toEqual({
      ok: true,
      inMs: 1_004_000 + 500,
      outMs: BASE + 20_000,
    });
  });
});

describe('cardEndpoints · recipeEndpoints', () => {
  it('카드 범위는 칸 없는 카드의 지금 식(base + 카드 창)이라 estimate 다 — 저장 값이 아니다', () => {
    const range = cardEndpoints({ window: { startMs: 60_000, endMs: 72_400 } }, BASE);

    expect(range).toEqual({
      start: { kind: 'estimate', ms: BASE + 60_000 },
      end: { kind: 'estimate', ms: BASE + 72_400 },
    });
    expect(savable(range, { base: BASE, heads: headsOf(), list: LIST })).toEqual({
      ok: false,
      reason: 'estimate',
    });
  });

  it('레시피 컷은 받은 값 그대로 exact 라 손대지 않고 저장하면 같은 값이다(P-7)', () => {
    const range = recipeEndpoints({ inAtMs: BASE + 61_234, outAtMs: BASE + 71_999 });

    expect(savable(range, { base: BASE, heads: headsOf(), list: null })).toEqual({
      ok: true,
      inMs: BASE + 61_234,
      outMs: BASE + 71_999,
    });
  });
});

describe('displayRange', () => {
  it('끝점 둘의 표시 초를 편집기 구간으로 낸다 — 카드 창이면 지금 식과 같은 초다', () => {
    const range = cardEndpoints({ window: { startMs: 60_000, endMs: 72_400 } }, 1_726_826_400_000);

    expect(displayRange(range, headsOf(), 1_726_826_400_000)).toEqual({
      startSeconds: 60,
      endSeconds: 72.4,
    });
  });

  it('고름은 머리가 오면 표시 초가 머리 원점으로 바뀐다(값은 그대로)', () => {
    const heads = headsOf();
    const range: EndpointRange = {
      start: { kind: 'pick', pick: pickAt(0.5) },
      end: { kind: 'exact', ms: BASE + 12_000 },
    };
    const before = displayRange(range, heads, BASE).startSeconds;

    heads.set(2, READY);

    expect(before).toBeCloseTo(4.3, 9);
    expect(displayRange(range, heads, BASE).startSeconds).toBeCloseTo(4.5, 9);
  });
});

describe('saveAfterReload — 저장 때 목록 다시 받기(PK1)', () => {
  const picked: EndpointRange = {
    start: { kind: 'pick', pick: pickAt(0.5) },
    end: { kind: 'exact', ms: BASE + 12_000 },
  };

  it('저장을 누르면 목록을 한 번 다시 받고 그 목록으로 판정한다', async () => {
    const reloadList = vi.fn(async () => LIST);

    const verdict = await saveAfterReload(picked, BASE, headsOf({ 2: READY }), reloadList);

    expect(reloadList).toHaveBeenCalledOnce();
    expect(verdict).toEqual({ ok: true, inMs: 1_004_500, outMs: BASE + 12_000 });
  });

  it('다시 받기에 실패하면 들고 있는 목록으로 맞추지 않고 listUnavailable 로 막는다', async () => {
    const verdict = await saveAfterReload(picked, BASE, headsOf({ 2: READY }), async () => null);

    expect(verdict).toEqual({ ok: false, reason: 'listUnavailable' });
  });
});

describe('saveRefusalMessage', () => {
  it('사유마다 문구가 있고, 다시 받으면 풀릴 수 있는 사유(머리 · 목록)만 다시 시도 단추를 단다', () => {
    expect(saveRefusalMessage('estimate')).toEqual({
      title: '이 카드는 범위 끝을 직접 정해 주세요',
      retry: false,
    });
    expect(saveRefusalMessage('headMissing')).toEqual({
      title: '저장에 필요한 녹화 정보를 받지 못했어요',
      retry: true,
    });
    expect(saveRefusalMessage('headFailed')).toEqual({
      title: '저장에 필요한 녹화 정보를 받지 못했어요',
      retry: true,
    });
    expect(saveRefusalMessage('outsideList')).toEqual({
      title: '이 끝점은 방송 중 편집 범위(최근 1시간) 밖이 됐어요 · 다시 정해 주세요',
      retry: false,
    });
    expect(saveRefusalMessage('listUnavailable')).toEqual({
      title: '목록을 다시 받지 못했어요',
      retry: true,
    });
    expect(saveRefusalMessage('tooLong')).toEqual({ title: '범위가 180초를 넘어요', retry: false });
    expect(saveRefusalMessage('tooShort')).toEqual({
      title: '범위가 5초보다 짧아요',
      retry: false,
    });
  });
});
