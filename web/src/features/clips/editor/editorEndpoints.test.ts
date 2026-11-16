import { describe, expect, it } from 'vitest';
import {
  editRangeEdge,
  inputAtS,
  playheadInput,
  rangeEdgeLimits,
  thumbnailSecondsOf,
  withDisplayRange,
  type EndpointContext,
} from './editorEndpoints';
import type { HeadCache, HeadEntry } from './source/fileHeadProbe';
import { savable, type EndpointRange } from './source/rangeEndpoints';
import type { EditorPick } from './source/windowPlacement';

// 편집기 상태의 끝점 · 고름 입력(설계 prw2 3.3 몸짓 표 · 4절 상태 훅 입력 꼴 · 계획 5절 E4b-3). 몸짓 규칙은 하나다 — 창 안 자리면
// 고름, 창 밖 · 목업이면 S. 그래서 창 안 끌기는 판 NULL 머리를 읽어야 저장된다(처분 K3 — 판독 없이 저장되는 우회가 없다).

const BASE = 1_000_000;

/** 머리 캐시 — 시험이 값을 넣는다 */
function headsOf(entries: Record<number, HeadEntry> = {}): HeadCache & {
  set(seq: number, entry: HeadEntry): void;
} {
  const map = new Map(Object.entries(entries).map(([seq, entry]) => [Number(seq), entry]));
  return {
    get: (seq) => map.get(seq),
    subscribe: () => () => {},
    set: (seq, entry) => map.set(seq, entry),
  };
}

/** seq k(4초 조각 · PDT = BASE + (k − 1)·4초 · 판 NULL · D3 = 10·k)에서 조각 안 offset 초의 고름 */
function pickAt(seq: number, offset: number): EditorPick {
  return {
    seq,
    pts: 10 * seq + offset,
    dur: 0.5,
    videoDts: 10 * seq,
    pdtMs: BASE + (seq - 1) * 4_000,
    durationMs: 4_000,
    timing: null,
  };
}

/** 창 = 조각 3–5(편집기 8–20초). 0.5초 프레임 */
function windowPickAtS(s: number): EditorPick | null {
  if (s < 8 || s >= 20) return null;
  const seq = Math.floor(s / 4) + 1;
  return pickAt(seq, Math.floor((s - (seq - 1) * 4) * 2) / 2);
}

const LIMITS = { minSeconds: 0, durationSeconds: 60 };
const ctxOf = (heads: HeadCache = headsOf()): EndpointContext => ({ base: BASE, heads });

const ESTIMATE: EndpointRange = {
  start: { kind: 'estimate', ms: BASE + 10_000 },
  end: { kind: 'estimate', ms: BASE + 18_000 },
};
const current = { range: { startSeconds: 10, endSeconds: 18 }, endpoints: ESTIMATE };

describe('inputAtS · playheadInput — 창 안이면 고름, 밖이면 S', () => {
  it('창 안 S 는 그 자리에 보이는 프레임의 고름이고, 창 밖이면 S 그대로다', () => {
    expect(inputAtS(9.3, windowPickAtS)).toEqual({ kind: 'pick', pick: pickAt(3, 1) });
    expect(inputAtS(30, windowPickAtS)).toEqual({ kind: 's', s: 30 });
  });

  it('재생 위치 찍기는 보이는 프레임이 있으면 고름, 없으면(목업 · 창 없음) 그 초다', () => {
    expect(playheadInput(pickAt(4, 0.5), 12.5)).toEqual({ kind: 'pick', pick: pickAt(4, 0.5) });
    expect(playheadInput(null, 12.5)).toEqual({ kind: 's', s: 12.5 });
  });
});

describe('editRangeEdge', () => {
  it('K3 — 창 안 끌기 · 화살표(S)는 고름이 된다: 판 NULL 머리 없이는 저장되지 않는다', () => {
    const next = editRangeEdge(
      'end',
      { kind: 's', s: 16.2 },
      current,
      LIMITS,
      ctxOf(),
      windowPickAtS,
    );

    expect(next?.endpoints?.end).toEqual({ kind: 'pick', pick: pickAt(5, 0) });
    expect(next?.endpoints?.start).toBe(ESTIMATE.start);
  });

  it('창 밖 끌기는 S 입력이라 그 자리 floor 값의 exact 다(설계가 받아들인 동작)', () => {
    const next = editRangeEdge(
      'end',
      { kind: 's', s: 25.0004 },
      current,
      LIMITS,
      ctxOf(),
      windowPickAtS,
    );

    expect(next?.endpoints?.end).toEqual({ kind: 'exact', ms: BASE + 25_000 });
    expect(next?.range).toEqual({ startSeconds: 10, endSeconds: 25 });
  });

  it('고름의 표시 초로 구간을 그린다 — 머리 전에는 영상 첫 DTS 원점의 근사 자리다', () => {
    const next = editRangeEdge(
      'start',
      { kind: 'pick', pick: pickAt(3, 1.5) },
      current,
      LIMITS,
      ctxOf(),
      windowPickAtS,
    );

    expect(next?.endpoints?.start).toEqual({ kind: 'pick', pick: pickAt(3, 1.5) });
    expect(next?.range.startSeconds).toBeCloseTo(9.5, 9);
  });

  it('왼쪽 한계 밖으로 끌면 한계 자리에서 다시 고른다: 잘린 자리도 창 안이면 고름이다', () => {
    const next = editRangeEdge(
      'start',
      { kind: 's', s: 5 },
      current,
      { minSeconds: 9, durationSeconds: 60 },
      ctxOf(),
      windowPickAtS,
    );

    expect(next?.endpoints?.start).toEqual({ kind: 'pick', pick: pickAt(3, 1) });
  });

  it('오른쪽 한계 밖으로 끌면 한계 자리에서 다시 고른다: 잘린 자리도 창 안이면 고름이다', () => {
    const next = editRangeEdge(
      'end',
      { kind: 's', s: 25 },
      current,
      { minSeconds: 0, durationSeconds: 19 },
      ctxOf(),
      windowPickAtS,
    );

    expect(next?.endpoints?.end).toEqual({ kind: 'pick', pick: pickAt(5, 3) });
  });

  it('길이가 5초 미만이 되거나 180초를 넘으면 바꾸지 않는다', () => {
    expect(
      editRangeEdge('end', { kind: 's', s: 12 }, current, LIMITS, ctxOf(), windowPickAtS),
    ).toBeNull();
  });

  it('카드의 estimate 끝은 같은 초를 다시 정해도 바뀐다(사용자가 한 번 정해야 저장된다) — 이미 같은 끝점이면 그대로다', () => {
    const at10 = { kind: 'exact', ms: BASE + 10_000 } as const;
    const same = editRangeEdge(
      'start',
      { kind: 's', s: 10 },
      { range: current.range, endpoints: { ...ESTIMATE, start: at10 } },
      LIMITS,
      ctxOf(),
      () => null,
    );
    const fromEstimate = editRangeEdge(
      'start',
      { kind: 's', s: 10 },
      current,
      LIMITS,
      ctxOf(),
      () => null,
    );

    expect(same).toBeNull();
    expect(fromEstimate?.endpoints?.start).toEqual(at10);
  });

  // 손잡이의 5초–3분 판정은 저장 규칙 ms 로 본다(inceil cx 반례) — 표시 초는 고름의 프레임 시작이라 저장 값(in 올림 ·
  // out 가운데 내림)과 1ms 미만 · 반 프레임만큼 갈린다. 화면이 받은 범위는 저장도 받는다
  describe('길이 판정은 저장 규칙 ms 다', () => {
    const wide = { minSeconds: 0, durationSeconds: 400 };
    /** 판 2 조각(PDT = BASE + pdtOffsetMs · D3 0)의 프레임 pts 고름 */
    const v2Pick = (pdtOffsetMs: number, pts: number, dur: number): EditorPick => ({
      seq: 1 + pdtOffsetMs / 4_000,
      pts,
      dur,
      videoDts: 0,
      pdtMs: BASE + pdtOffsetMs,
      durationMs: 4_000,
      timing: 'v2',
    });
    /** 저장 판정용 목록 — 고름이 든 seq 와 그다음 행(목록 마지막 맞춤이 끼지 않게) */
    const listFor = (pick: EditorPick) => {
      const rows = [pick.seq, pick.seq + 1].map((seq) => ({
        seq,
        uri: `https://cdn.test/dvr/s/seg/${seq}.m4s`,
        mapUri: 'https://cdn.test/dvr/s/init.mp4',
        pdtMs: BASE + (seq - 1) * 4_000,
        durationMs: 4_000,
        timing: 'v2' as const,
        gap: false,
      }));
      return { runs: [rows], rows };
    };
    const exactRange = (startMs: number, endMs: number) => ({
      range: { startSeconds: startMs / 1000, endSeconds: endMs / 1000 },
      endpoints: {
        start: { kind: 'exact', ms: BASE + startMs },
        end: { kind: 'exact', ms: BASE + endMs },
      } satisfies EndpointRange,
    });

    it('끝 exact 6.033 · 시작 고름 1.0333 은 화면 5초여도 저장 4,999ms 라 받지 않는다', () => {
      const start = v2Pick(0, 31 / 30, 1 / 30);

      const next = editRangeEdge(
        'start',
        { kind: 'pick', pick: start },
        exactRange(0, 6_033),
        wide,
        ctxOf(),
        () => null,
      );

      expect(next).toBeNull();
    });

    it('끝 exact 6.034 면 받고 저장도 5,000ms 로 받는다', () => {
      const start = v2Pick(0, 31 / 30, 1 / 30);

      const next = editRangeEdge(
        'start',
        { kind: 'pick', pick: start },
        exactRange(0, 6_034),
        wide,
        ctxOf(),
        () => null,
      );

      expect(next?.endpoints?.start).toEqual({ kind: 'pick', pick: start });
      expect(savable(next!.endpoints!, { ...ctxOf(), list: listFor(start) })).toEqual({
        ok: true,
        inMs: BASE + 1_034,
        outMs: BASE + 6_034,
      });
    });

    it('끝 고름이 화면 179.995초여도 저장 out(가운데 내림)이 180,005ms 면 받지 않는다', () => {
      // 조각 PDT 176초 · 프레임 3.995 · 다음 프레임까지 0.02 → out = 176,000 + floor(4,005)
      const end = v2Pick(176_000, 3.995, 0.02);

      const next = editRangeEdge(
        'end',
        { kind: 'pick', pick: end },
        exactRange(0, 10_000),
        wide,
        ctxOf(),
        () => null,
      );

      expect(
        savable(
          { start: exactRange(0, 10_000).endpoints.start, end: { kind: 'pick', pick: end } },
          {
            ...ctxOf(),
            list: listFor(end),
          },
        ),
      ).toEqual({ ok: false, reason: 'tooLong' });
      expect(next).toBeNull();
    });

    it('끝 고름이 화면 4.99초여도 저장 out 이 5,000ms 면 받는다', () => {
      // 조각 PDT 4초 · 프레임 0.99 · 다음 프레임까지 0.02 → out = 4,000 + floor(1,000)
      const end = v2Pick(4_000, 0.99, 0.02);

      const next = editRangeEdge(
        'end',
        { kind: 'pick', pick: end },
        exactRange(0, 10_000),
        wide,
        ctxOf(),
        () => null,
      );

      expect(next?.range.endSeconds).toBeCloseTo(4.99, 9);
      expect(savable(next!.endpoints!, { ...ctxOf(), list: listFor(end) })).toEqual({
        ok: true,
        inMs: BASE,
        outMs: BASE + 5_000,
      });
    });
  });

  it('목업(끝점 없음)은 지금처럼 구간 초만 바꾼다', () => {
    const next = editRangeEdge(
      'end',
      { kind: 's', s: 16.2 },
      { range: current.range },
      LIMITS,
      null,
      () => null,
    );

    expect(next).toEqual({ range: { startSeconds: 10, endSeconds: 16.2 } });
  });
});

describe('withDisplayRange', () => {
  it('머리가 오면 고름 끝점의 표시 구간이 머리 원점으로 바뀌고, 그대로면 같은 객체를 돌려준다', () => {
    const heads = headsOf();
    const recipe = {
      range: { startSeconds: 0, endSeconds: 0 },
      endpoints: {
        start: { kind: 'pick', pick: pickAt(3, 1) },
        end: { kind: 'exact', ms: BASE + 18_000 },
      } satisfies EndpointRange,
    };
    const before = withDisplayRange(recipe, ctxOf(heads));
    heads.set(3, { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.25 } });
    const after = withDisplayRange(before, ctxOf(heads));

    expect(before.range).toEqual({ startSeconds: 9, endSeconds: 18 });
    expect(after.range.startSeconds).toBeCloseTo(9.25, 9);
    expect(withDisplayRange(after, ctxOf(heads))).toBe(after);
    expect(withDisplayRange(recipe, null)).toBe(recipe);
  });
});

describe('thumbnailSecondsOf', () => {
  it('고른 썸네일 장면의 표시 초 — 고름이면 고름의 자리, S 면 그 초', () => {
    expect(thumbnailSecondsOf({ kind: 'pick', pick: pickAt(4, 0.5) }, ctxOf())).toBeCloseTo(
      12.5,
      9,
    );
    expect(thumbnailSecondsOf({ kind: 's', s: 7.25 }, null)).toBe(7.25);
  });
});

// 5초 언저리 카드(kty 2026-11-12 23:42 (나)). 범위가 5초 + 한 프레임보다 짧으면 고를 수 있는 길이가 5초에 못 미쳐 저장할 길이
// 없다 — 끝 한계만 범위 끝 뒤로 5초 넓혀(편집기 창 여유 10초 안) 사용자가 끝을 직접 끌어 정하게 한다. 끝을 옮겨 주지는 않는다
describe('rangeEdgeLimits', () => {
  it('5초 이상인 범위는 그 범위가 곧 손잡이 한계다', () => {
    expect(rangeEdgeLimits({ startMs: 60_000, endMs: 72_400 }, 3_600, true)).toEqual({
      minSeconds: 60,
      durationSeconds: 72.4,
    });
  });

  it('5초보다 짧은 카드는 끝 한계를 범위 끝 뒤로 5초 넓힌다', () => {
    expect(rangeEdgeLimits({ startMs: 60_000, endMs: 62_000 }, 3_600, true)).toEqual({
      minSeconds: 60,
      durationSeconds: 67,
    });
  });

  it('정확히 5초인 범위도 넓힌다 — 첫 영상 PTS 가 시작보다 늦으면 고를 수 있는 길이가 5초에 못 미친다', () => {
    expect(rangeEdgeLimits({ startMs: 60_000, endMs: 65_000 }, 3_600, true).durationSeconds).toBe(
      70,
    );
  });

  it('저장된 편집본은 5초보다 짧아도 넓히지 않는다 — 저장된 구간이 곧 한계다(사용자 확정 2026-09-17)', () => {
    expect(rangeEdgeLimits({ startMs: 60_000, endMs: 65_000 }, 3_600, false)).toEqual({
      minSeconds: 60,
      durationSeconds: 65,
    });
  });

  it('넓힌 끝도 방송 길이를 넘지 않는다', () => {
    expect(rangeEdgeLimits({ startMs: 60_000, endMs: 62_000 }, 64, true).durationSeconds).toBe(64);
  });

  it('정확히 5초 카드 + 첫 영상 PTS 10ms 늦음 — 넓힌 한계로 끝을 끌면 시작을 고름으로 정해도 5초를 넘는다', () => {
    const late: EditorPick = { ...pickAt(16, 0), pts: 160.01, videoDts: 160 };
    const pickAtS = (s: number) => (s < 60.01 ? late : null);
    // 머리 전 근사 원점(영상 첫 DTS 160) — 표시 초 60.01
    const ctx = ctxOf();
    const card: EndpointRange = {
      start: { kind: 'estimate', ms: BASE + 60_000 },
      end: { kind: 'estimate', ms: BASE + 65_000 },
    };
    const limits = rangeEdgeLimits({ startMs: 60_000, endMs: 65_000 }, 3_600, true);
    const opened = { range: { startSeconds: 60, endSeconds: 65 }, endpoints: card };

    const ended = editRangeEdge('end', { kind: 's', s: 66 }, opened, limits, ctx, pickAtS);
    const started = editRangeEdge('start', { kind: 's', s: 60 }, ended!, limits, ctx, pickAtS);

    expect(ended?.endpoints?.end).toEqual({ kind: 'exact', ms: BASE + 66_000 });
    expect(started?.range.startSeconds).toBeCloseTo(60.01, 6);
    expect(started?.endpoints?.start).toEqual({ kind: 'pick', pick: late });
  });
});
