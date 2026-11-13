import { describe, expect, it, vi } from 'vitest';
import { CUT_MAX_MS, CUT_MIN_MS } from '@/api/clipEditor';
import type { CutContext } from './cutPosition';
import type { EditorPlaylist, PlaylistSegment } from './editorPlaylist';
import type { HeadCache, HeadEntry } from './fileHeadProbe';
import {
  cardDraftVerdict,
  cardEndpoints,
  cardHighlightMs,
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
import type { RemuxedWindow } from './remuxWindow';
import { placeWindow, type EditorPick } from './windowPlacement';

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
    // 끝 = 고른 프레임(머리 원점에서 1.0초)과 다음 프레임 사이 가운데 — 길이 0.04 의 반(kty 결정 (다) 2026-11-13)
    expect(savable(range, ctxOf(heads))).toEqual({ ok: true, inMs: BASE, outMs: 1_004_000 + 1020 });
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

  it('clip 이 창 두 끝(playerWindow)을 주면 그 값 그대로 exact 다 — 카드 창(base + window)과 수 초 달라도 다시 셈하지 않는다', () => {
    const card = {
      window: { startMs: 60_000, endMs: 72_400 },
      playerWindow: { startMs: BASE + 63_217, endMs: BASE + 75_901 },
    };

    const range = cardEndpoints(card, BASE);

    expect(range).toEqual({ start: exact(BASE + 63_217), end: exact(BASE + 75_901) });
    expect(savable(range, { base: BASE, heads: headsOf(), list: null })).toEqual({
      ok: true,
      inMs: BASE + 63_217,
      outMs: BASE + 75_901,
    });
  });

  it.each([
    ['null(장면 없음)', null],
    ['칸 없음(옛 clip)', undefined],
    ['끝 < 시작(믿을 수 없음)', { startMs: BASE + 9_000, endMs: BASE + 1_000 }],
  ])('playerWindow %s — 카드 창의 지금 식(estimate)이다', (_label, playerWindow) => {
    const card = { window: { startMs: 60_000, endMs: 72_400 }, playerWindow };

    expect(cardEndpoints(card, BASE)).toEqual({
      start: { kind: 'estimate', ms: BASE + 60_000 },
      end: { kind: 'estimate', ms: BASE + 72_400 },
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

describe('cardDraftVerdict — 카드 바로 저장(편집기를 열지 않음)', () => {
  const window = { startMs: 60_000, endMs: 72_400 };

  it('playerWindow 두 끝 그대로가 저장 값이다', () => {
    const card = { window, playerWindow: { startMs: BASE + 63_217, endMs: BASE + 75_901 } };

    expect(cardDraftVerdict(card)).toEqual({ ok: true, inMs: BASE + 63_217, outMs: BASE + 75_901 });
  });

  it.each([
    ['null', null],
    ['칸 없음', undefined],
  ])(
    'playerWindow %s — estimate 로 막는다(카드 창으로 대신 저장하지 않는다)',
    (_label, playerWindow) => {
      const card = { window, playerWindow };

      expect(cardDraftVerdict(card)).toEqual({ ok: false, reason: 'estimate' });
    },
  );

  it('길이가 5–180초 밖이면 편집기 저장과 같은 사유로 막는다 — 몰래 늘리거나 자르지 않는다', () => {
    const of = (length: number) => ({
      window,
      playerWindow: { startMs: BASE, endMs: BASE + length },
    });

    expect(cardDraftVerdict(of(CUT_MIN_MS - 1))).toEqual({ ok: false, reason: 'tooShort' });
    expect(cardDraftVerdict(of(CUT_MAX_MS + 1))).toEqual({ ok: false, reason: 'tooLong' });
    expect(cardDraftVerdict(of(CUT_MIN_MS)).ok).toBe(true);
    expect(cardDraftVerdict(of(CUT_MAX_MS)).ok).toBe(true);
  });
});

describe('cardHighlightMs — 카드 하이라이트 자리(목록 PDT 축 ms)', () => {
  /** 끝난 방송의 base 는 vod 목록 첫 PDT 다(서버 기준점이 없을 때) — 칸 없는 카드는 그 base 에 카드 ms 를 더한다 */
  const VOD_FIRST_PDT = 1_726_826_432_000;

  it('playerAtMs 가 값이면 그 값이다', () => {
    expect(cardHighlightMs({ streamTimestampMs: 65_000, playerAtMs: BASE + 67_250 }, BASE)).toBe(
      BASE + 67_250,
    );
  });

  it('playerAtMs 가 null(장면 없음)이면 하이라이트가 없다', () => {
    expect(cardHighlightMs({ streamTimestampMs: 65_000, playerAtMs: null }, BASE)).toBeNull();
  });

  it('칸이 없으면(옛 clip) base + 카드 ms 다 — 끝난 방송이면 vod 첫 PDT 가 base 다', () => {
    expect(cardHighlightMs({ streamTimestampMs: 65_000 }, VOD_FIRST_PDT)).toBe(
      VOD_FIRST_PDT + 65_000,
    );
  });
});

describe('시험 묶음 D — 다시 열기 · 카드 범위 반례', () => {
  it('R-a 없음 반례: 칸 없는 카드 창은 손대지 않으면 저장이 막히고, 끝점을 정하면 저장된다(r3 cx 1)', () => {
    // start_pts = 0 · 4,000 · 8,000 · PDT = 100,000 · 110,000 · 114,000 — 카드 창 [1,000, 9,000] 의 지금 식 끝(109,000)은
    // 조각 0 끝(104,000)과 조각 1 PDT(110,000) 사이 죽은 자리라 본 적 없는 장면이다
    const base = 100_000;
    const rows = [row(0, 100_000, null), row(1, 110_000, null), row(2, 114_000, null)];
    const ctx = { base, heads: headsOf(), list: { runs: [rows], rows } };
    const opened = cardEndpoints({ window: { startMs: 1_000, endMs: 9_000 } }, base);

    const untouched = savable(opened, ctx);
    const set: EndpointRange = {
      start: endpointOf({ kind: 's', s: 1 }, base),
      end: endpointOf({ kind: 's', s: 12 }, base),
    };

    expect(untouched).toEqual({ ok: false, reason: 'estimate' });
    expect(savable(set, ctx)).toEqual({ ok: true, inMs: 101_000, outMs: 112_000 });
  });

  it('런을 건너는 컷(판 2 seq 3 → 끊김 → seq 4)도 저장하고, 다시 열어 손대지 않고 저장하면 같은 값이다', () => {
    const t0 = 1_726_826_400_000;
    const seq3 = row(3, t0 + 8_000, 'v2');
    const seq4 = row(4, t0 + 20_300, 'v2');
    const list: EditorPlaylist = { runs: [[seq3], [seq4]], rows: [seq3, seq4] };
    const pickIn: EditorPick = {
      seq: 3,
      pts: 32.0,
      dur: 0.04,
      videoDts: 30.0,
      pdtMs: seq3.pdtMs,
      durationMs: 4000,
      timing: 'v2',
    };
    const pickOut: EditorPick = {
      seq: 4,
      pts: 42.96,
      dur: 0.04,
      videoDts: 40.0,
      pdtMs: seq4.pdtMs,
      durationMs: 4000,
      timing: 'v2',
    };
    const ctx = { base: t0, heads: headsOf(), list };
    const picked: EndpointRange = {
      start: { kind: 'pick', pick: pickIn },
      end: { kind: 'pick', pick: pickOut },
    };

    const saved = savable(picked, ctx);
    if (!saved.ok) throw new Error(`저장이 막혔다: ${saved.reason}`);
    const reopened = recipeEndpoints({ inAtMs: saved.inMs, outAtMs: saved.outMs });

    expect(saved).toEqual({ ok: true, inMs: t0 + 10_000, outMs: t0 + 23_280 });
    expect(savable(reopened, ctx)).toEqual(saved);
    // 다시 연 화면은 저장 값의 표시 초다 — 고른 끝(프레임 시작 23.26초)이 아니라 그 프레임과 다음 프레임 사이 가운데
    // (23.28초)다(kty 결정 (다) 2026-11-13 — 옛 결정 2 「그 프레임 끝」 을 대체)
    expect(displayRange(reopened, ctx.heads, t0)).toEqual({ startSeconds: 10, endSeconds: 23.28 });
  });

  it('판 NULL 런을 건너는 컷은 양쪽 seq 의 ② 머리로 원점을 옮겨 저장하고, 다시 열어 그대로 저장하면 같은 값이다', () => {
    // seq 3: ③ D3 30.0 · ② D2 0.2 · Hd 0 → o = 29.8 · seq 4: D3 40.0 · D2 0.3 → o = 39.7(e 0)
    //   in  = PDT_3 + ceil(1000·(32.0 − 29.8)) = + 2,200 · out = PDT_4 + floor(1000·(42.96 + 0.02 − 39.7)) = + 3,280
    const t0 = 1_726_826_400_000;
    const seq3 = row(3, t0 + 8_000, null);
    const seq4 = row(4, t0 + 20_300, null);
    const list: EditorPlaylist = { runs: [[seq3], [seq4]], rows: [seq3, seq4] };
    const heads = headsOf({
      3: { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.2 } },
      4: { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } },
    });
    const pickOf = (seg: PlaylistSegment, pts: number, videoDts: number): EditorPick => ({
      seq: seg.seq,
      pts,
      dur: 0.04,
      videoDts,
      pdtMs: seg.pdtMs,
      durationMs: 4000,
      timing: null,
    });
    const ctx = { base: t0, heads, list };
    const picked: EndpointRange = {
      start: { kind: 'pick', pick: pickOf(seq3, 32.0, 30.0) },
      end: { kind: 'pick', pick: pickOf(seq4, 42.96, 40.0) },
    };

    const saved = savable(picked, ctx);
    if (!saved.ok) throw new Error(`저장이 막혔다: ${saved.reason}`);

    expect(saved).toEqual({ ok: true, inMs: t0 + 10_200, outMs: t0 + 23_580 });
    expect(savable(recipeEndpoints({ inAtMs: saved.inMs, outAtMs: saved.outMs }), ctx)).toEqual(
      saved,
    );
  });

  describe('레시피 다시 열기 — 판 NULL 머리 전후(설계 5절 8 · cx 1 반례)', () => {
    // 조각 7: PDT 100,000 · ③ D3 10.3 · ② D2 0.3 · Hd 0 → o = 10.0 · 창 Δ 10.2(③ PTS 최솟값). 레시피 컷 101,000 은 Blob
    // o + 1.0 − Δ = 0.8 이다 — 머리 전에는 o 를 D3 로 근사해 1.1 에 두고, 머리가 오면 같은 S 를 다시 배치해 0.8 로 간다
    const base = 90_000;
    const ticks = 90_000;
    const seg7 = row(7, 100_000, null);
    const frames = Array.from({ length: 120 }, (_, i) => ({
      pts: (927_000 + 3000 * i) / ticks,
      dur: 3000 / ticks,
    }));
    const remuxed: RemuxedWindow = {
      blob: new Blob([]),
      shiftSeconds: 10.2,
      segments: [{ seq: 7, videoDts: 927_000 / ticks, frames, mediaStart: 10.3, mediaEnd: 14.3 }],
    };
    const cut = { inAtMs: 101_000, outAtMs: 106_000 };

    it('저장 값은 머리 전후 같고(받은 값 · 다시 셈하지 않음) 시크는 머리가 오면 맞는 자리로 간다', () => {
      const heads = headsOf({ 7: { state: 'missing' } });
      const reopened = recipeEndpoints(cut);
      const s = displayRange(reopened, heads, base).startSeconds;

      const savedBefore = savable(reopened, { base, heads, list: LIST });
      const seekBefore = placeWindow(remuxed, [seg7], base, heads).blobAtS(s);
      heads.set(7, { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } });
      const seekAfter = placeWindow(remuxed, [seg7], base, heads).blobAtS(s);

      expect(s).toBe(11);
      expect(savedBefore).toEqual({ ok: true, inMs: 101_000, outMs: 106_000 });
      expect(savable(reopened, { base, heads, list: LIST })).toEqual(savedBefore);
      expect(seekBefore).toBeCloseTo(1.1, 9);
      expect(seekAfter).toBeCloseTo(0.8, 9);
    });

    it('고른 프레임으로 저장한 in 을 다시 열면 그 자리에 보이는 프레임이 고른 프레임이다(in 올림 · kty 결정 2 (다))', () => {
      // 소수 ms PTS(30fps 의 1/3 · 2/3 ms)도 in 을 내리면 o + in 이 F 보다 1ms 미만 앞이라 F 앞 프레임이 보였다. 조각 끝
      // 4.0초에 닿거나 넘는 꼬리 프레임(i ≥ 111)은 K2 가 조각 끝 앞으로 내려 F 앞이라 뺀다
      const heads = headsOf({ 7: { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } } });
      const placement = placeWindow(remuxed, [seg7], base, heads);
      const ctx = { base, heads, list: { runs: [[seg7]], rows: [seg7] } };

      const shown = frames.slice(0, 111).map(({ pts }) => {
        const pick = placement.pickAtBlob(pts - remuxed.shiftSeconds);
        const saved = recipeEndpoints({ inAtMs: savedIn(pick, ctx), outAtMs: 106_000 });
        const blob = placement.blobAtS(displaySeconds(saved.start, heads, base));
        return placement.pickAtBlob(blob).pts;
      });

      expect(shown).toEqual(frames.slice(0, 111).map(({ pts }) => pts));
    });
  });
});

/** 고름의 in 저장 값 — 짓지 못하면 시험을 멈춘다 */
function savedIn(pick: EditorPick, ctx: CutContext): number {
  const range: EndpointRange = { start: { kind: 'pick', pick }, end: exact(pick.pdtMs + 10_000) };
  const verdict = savable(range, ctx);
  if (!verdict.ok) throw new Error(`저장이 막혔다: ${verdict.reason}`);
  return verdict.inMs;
}

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
