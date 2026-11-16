import { describe, expect, it } from 'vitest';
import type { EditorPlaylist, PlaylistSegment } from './editorPlaylist';
import type { HeadCache } from './fileHeadProbe';
import type { RemuxedSegment } from './remuxWindow';
import { sceneOffsetFromPlayhead } from '../uploadInfo';
import { sceneOffsetMs, uploadSceneOffsetMs, type SceneMedia } from './sceneOffset';
import { placeWindow, type EditorPick, type WindowPlacement } from './windowPlacement';

// 업로드 장면 offset(설계 prw2 3.3 표 「업로드 장면 offset」 · 11절 c12′ · 처분 H3). offset 은 결과 영상 ms 다(경계 답 N9 —
// 렌더는 조각 미디어를 이어 붙이고 장부 틈은 시간이 없다). 그래서 PDT 차가 아니라 in 부터 장면까지의 미디어 거리를 보낸다.
//   같은 창이면 Blob 시각 차(= ③ PTS 차) · 창 밖 · 창 없음이면 조각별 길이(EXTINF) 합 · GAP 줄과 런 사이 틈은 0
// 픽스처: 판 NULL 60초 남짓 컷. ③ 영상은 조각마다 4초(30fps · 120 프레임)로 이어지는데 PDT 는 래칫으로 경계마다 0.2초씩
// 앞서 「죽은 자리」 0.2초가 생긴다. ② 머리는 Hd = Hp = D2 = 0 이라 조각 원점 o_k = D3_k 다. 기대값은 아래 식에서 손으로 셈했다.

const TICKS = 90_000;
const FRAME_TICKS = 3000;
const BASE = 1_000_000;
/** 조각 i(seq 10 + i)의 ③ 영상 첫 DTS(눈금) — 100초부터 4초씩 이어진다 */
const dtsTicks = (i: number) => 9_000_000 + 360_000 * i;
/** 판 NULL 래칫 — 조각 PDT 는 4.2초씩 서고 EXTINF 는 4초라 경계마다 0.2초 죽은 자리 */
const PDT_STEP_MS = 4200;
const pdtOf = (i: number) => BASE + PDT_STEP_MS * i;

function row(seq: number, pdtMs: number, extra: Partial<PlaylistSegment> = {}): PlaylistSegment {
  return {
    seq,
    uri: `https://cdn.test/dvr/s/seg/${seq}.m4s`,
    mapUri: 'https://cdn.test/dvr/s/init.mp4',
    pdtMs,
    durationMs: 4000,
    timing: null,
    gap: false,
    ...extra,
  };
}

/** 조각 i 의 ③ 영상 표 — 4초 안에 드는 프레임만(59.94fps 면 239 장 · 다음 조각과 겹치지 않는다) */
function segment(i: number, frameTicks = FRAME_TICKS): RemuxedSegment {
  const frames = Array.from({ length: Math.floor(360_000 / frameTicks) }, (_, f) => ({
    pts: (dtsTicks(i) + frameTicks * f) / TICKS,
    dur: frameTicks / TICKS,
  }));
  return {
    seq: 10 + i,
    videoDts: dtsTicks(i) / TICKS,
    frames,
    mediaStart: frames[0]!.pts,
    mediaEnd: frames.at(-1)!.pts + frames.at(-1)!.dur,
  };
}

/** 모든 seq 의 ② 머리가 Hd = Hp = D2 = 0 — 원점 o_k = D3_k · e_k = 0 */
const heads: HeadCache = {
  get: () => ({ state: 'ready', head: { hd: 0, hp: 0, videoDts: 0 } }),
  subscribe: () => () => {},
};

/** 한 런 목록 — seq 10 + i, i = 0 … count − 1 */
function listOf(count: number): EditorPlaylist {
  const rows = Array.from({ length: count }, (_, i) => row(10 + i, pdtOf(i)));
  return { runs: [rows], rows };
}

/** 목록 조각 i = from … to 의 창 배치 — Δ = 그 창 첫 프레임 PTS */
function windowOver(
  list: EditorPlaylist,
  from: number,
  to: number,
  frameTicks = FRAME_TICKS,
): WindowPlacement {
  const segments = Array.from({ length: to - from + 1 }, (_, n) => segment(from + n, frameTicks));
  const rows = list.rows.filter((r) => segments.some((s) => s.seq === r.seq));
  const remuxed = { blob: new Blob([]), shiftSeconds: segments[0]!.mediaStart, segments };
  return placeWindow(remuxed, rows, BASE, heads);
}

/** 장면 offset 재료 — 편집기 0초 BASE · 머리는 모두 0 */
function mediaOf(placement: WindowPlacement | null, list: EditorPlaylist | null): SceneMedia {
  return { placement, list, base: BASE, heads };
}

/** 그 ③ PTS(초)에 보이는 프레임 */
function pickAt(placement: WindowPlacement, firstPts: number, pts: number): EditorPick {
  return placement.pickAtBlob(pts - firstPts);
}

describe('sceneOffsetMs — 판 NULL 경계마다 죽은 자리 0.2초인 60초 컷', () => {
  const list = listOf(16);
  const windowA = windowOver(list, 0, 10);
  // in = 조각 10 의 0.5초 프레임(③ PTS 100.5) · S = 0.5
  const inPick = pickAt(windowA, 100, 100.5);
  const inEnd = { kind: 'pick', pick: inPick } as const;

  it('같은 창이면 Blob 시각 차다: 조각 18 의 134.0 프레임은 33.5초(PDT 차 35.1초가 아니다)', () => {
    const scene = pickAt(windowA, 100, 134.0);

    expect(sceneOffsetMs(inEnd, { kind: 'pick', pick: scene }, mediaOf(windowA, list))).toBe(
      33_500,
    );
    // 같은 두 프레임의 표시 초 차 — 죽은 자리 여덟 개(1.6초)가 더 든다
    expect(Math.round(1000 * (windowA.sAtPick(scene) - windowA.sAtPick(inPick)))).toBe(35_100);
  });

  it('같은 창의 거리도 ms 로 내리되 정수 ms 에 떨어지는 거리는 1ms 내려가지 않는다', () => {
    // in 100.0 프레임부터 100.0667(66.67ms → 66) · 100.1(100ms) · 100.3(300ms)
    const start = { kind: 'pick', pick: pickAt(windowA, 100, 100) } as const;
    const offsetTo = (pts: number) =>
      sceneOffsetMs(
        start,
        { kind: 'pick', pick: pickAt(windowA, 100, pts) },
        mediaOf(windowA, list),
      );

    expect([100 + 2 / 30, 100.1, 100.3].map(offsetTo)).toEqual([66, 100, 300]);
  });

  it('in 이 받은 값(exact)이면 그 자리 프레임의 나머지까지 뺀다', () => {
    // S 0.51 = 조각 10 PDT 뒤 0.51초 → ③ 100.51 · 보이는 프레임 100.5 + 나머지 0.01
    const exactIn = { kind: 'exact', ms: BASE + 510 } as const;
    const scene = pickAt(windowA, 100, 134.0);

    expect(sceneOffsetMs(exactIn, { kind: 'pick', pick: scene }, mediaOf(windowA, list))).toBe(
      33_490,
    );
  });

  it('같은 창이면 목록 길이가 아니라 Blob 차다: EXTINF 가 미디어보다 길어도 33.5초', () => {
    const longRows = list.rows.map((r) => ({ ...r, durationMs: 4100 }));
    const longList = { runs: [longRows], rows: longRows };
    const placement = windowOver(longList, 0, 10);
    const scene = pickAt(placement, 100, 134.0);
    const start = { kind: 'pick', pick: pickAt(placement, 100, 100.5) } as const;

    expect(sceneOffsetMs(start, { kind: 'pick', pick: scene }, mediaOf(placement, longList))).toBe(
      33_500,
    );
  });

  it('다른 창에서 고른 장면이면 조각별 길이 합이다: 조각 24 의 159.0 프레임은 58.5초', () => {
    // 창 B(조각 21 … 25)에서 고른 프레임 — 지금 창 A 에는 없다
    const windowB = windowOver(list, 11, 15);
    const scene = pickAt(windowB, 144, 159.0);

    // (4000 − 500) + 조각 11 … 23 의 13 × 4000 + 3000
    expect(sceneOffsetMs(inEnd, { kind: 'pick', pick: scene }, mediaOf(windowA, list))).toBe(
      58_500,
    );
  });

  it('창 밖 초(S)도 목록의 F 규칙으로 조각을 찾아 길이 합으로 셈한다', () => {
    // 조각 23(i 13) PDT 뒤 1.0초 — 창 A 밖
    const s = (pdtOf(13) - BASE) / 1000 + 1.0;

    // (4000 − 500) + 조각 11 … 22 의 12 × 4000 + 1000
    expect(sceneOffsetMs(inEnd, { kind: 's', s }, mediaOf(windowA, list))).toBe(52_500);
  });

  it('GAP 줄과 런 사이 틈은 0 으로 이어 붙인다', () => {
    // 조각 10 … 20 · GAP 줄 21 · 10초 회차 틈 · 조각 22 … 25
    const before = list.rows.slice(0, 11);
    const gapRow = row(21, pdtOf(11), { gap: true });
    const restStart = pdtOf(11) + 4000 + 10_000;
    const after = [22, 23, 24, 25].map((seq, n) => row(seq, restStart + PDT_STEP_MS * n));
    const gapped = { runs: [before, after], rows: [...before, gapRow, ...after] };
    const placement = windowOver(gapped, 0, 10);
    const start = { kind: 'pick', pick: pickAt(placement, 100, 100.5) } as const;
    const s = (restStart + PDT_STEP_MS - BASE) / 1000 + 1.0;

    // (4000 − 500) + 조각 11 … 20 의 10 × 4000 + GAP 0 + 조각 22 의 4000 + 1000
    expect(sceneOffsetMs(start, { kind: 's', s }, mediaOf(placement, gapped))).toBe(48_500);
  });

  // codex r1 반례: 조각 10 미디어 [0, 4)초 · GAP 줄 11 [4.2, 8.2)초 · 다음 런 조각 12 는 20초부터. 결과 영상에서 GAP 은 길이 0 의
  // 한 점이다(렌더 N9 「InGap 한 점」) — GAP 안 자리는 그 줄 안 위치를 남기지 않는다
  describe('GAP 줄 안 자리는 한 점(0)이다', () => {
    const before = row(10, BASE);
    const gapRow = row(11, BASE + 4200, { gap: true });
    const next = row(12, BASE + 20_000);
    const gapped = { runs: [[before], [next]], rows: [before, gapRow, next] };
    const placement = windowOver({ runs: [[before]], rows: [before] }, 0, 0);
    const start = { kind: 'pick', pick: pickAt(placement, 100, 100.5) } as const;

    it('장면이 GAP 안이면 in 조각 나머지까지다: in 0.5 · 장면 6.2 → 3,500', () => {
      expect(sceneOffsetMs(start, { kind: 's', s: 6.2 }, mediaOf(placement, gapped))).toBe(3_500);
    });

    it('장면이 GAP 안이면 다음 런 첫 자리와 같은 값이다', () => {
      const inGap = sceneOffsetMs(start, { kind: 's', s: 6.2 }, mediaOf(placement, gapped));

      expect(sceneOffsetMs(start, { kind: 's', s: 20 }, mediaOf(placement, gapped))).toBe(inGap);
    });

    it('in 이 GAP 안(exact)이면 다음 런부터 센다: in 6.2 · 장면 21 → 1,000', () => {
      const exactIn = { kind: 'exact', ms: BASE + 6200 } as const;

      expect(sceneOffsetMs(exactIn, { kind: 's', s: 21 }, mediaOf(placement, gapped))).toBe(1_000);
    });
  });

  // kty c12′ 결정 1 (가): 창(배치)을 받기 전 · 받지 못했어도 목록이 있으면 목록 경로로 센다
  describe('창이 없으면 목록으로 센다', () => {
    /** 모든 seq 의 ② 머리가 D2 − Hd = 0.3초 — 원점 o_k = D3_k − 0.3 */
    const leadHeads: HeadCache = {
      get: () => ({ state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } }),
      subscribe: () => () => {},
    };

    it('두 자리가 값이면 F 규칙 · 길이 합이다', () => {
      const s = (pdtOf(13) - BASE) / 1000 + 1.0;
      const exactIn = { kind: 'exact', ms: pdtOf(0) + 500 } as const;

      // (4000 − 500) + 조각 11 … 22 의 12 × 4000 + 1000 — 창이 있을 때(52,500)와 같다
      expect(sceneOffsetMs(exactIn, { kind: 's', s }, mediaOf(null, list))).toBe(52_500);
    });

    it('창 없이 든 고름도 그 조각 원점(머리)으로 조각 안 자리를 읽어 센다', () => {
      const scene = { kind: 'pick', pick: pickAt(windowA, 100, 134.0) } as const;

      // 같은 창일 때의 Blob 차와 같다: (4000 − 500) + 조각 11 … 17 의 7 × 4000 + 2000
      expect(sceneOffsetMs(inEnd, scene, mediaOf(null, list))).toBe(33_500);
    });

    it('판 NULL 고름은 ② 머리로 원점을 옮긴다: D2 − Hd 0.3초면 조각 안 자리가 0.3초 뒤라 4,200', () => {
      // o = D3 − (D2 − Hd) = D3 − 0.3. in 은 조각 10 PDT 뒤 0.5초(값) · 장면은 조각 11 의 D3 + 0.4 프레임이라 조각 안 0.7초
      // → (4000 − 500) + 700. 원점을 D3 로 두면 조각 안 0.4초라 3,900 이다
      const exactIn = { kind: 'exact', ms: pdtOf(0) + 500 } as const;
      const scene = { kind: 'pick', pick: pickAt(windowA, 100, 104.4) } as const;

      expect(sceneOffsetMs(exactIn, scene, { ...mediaOf(null, list), heads: leadHeads })).toBe(
        4_200,
      );
    });

    it('미디어 거리는 ms 로 내린다 — 정수 ms 에 떨어지는 거리는 부동소수 잡음으로 1ms 내려가지 않는다', () => {
      // 원점 D3 − 0.3 · in 은 조각 10 PDT(조각 안 0). 장면 100.0667 은 366.67ms(올리면 다음 프레임 자리 367) · 100.1 은
      // 400ms 인데 1000·(100.1 − 99.7) 은 부동소수로 399.99999999999 다
      const exactIn = { kind: 'exact', ms: pdtOf(0) } as const;
      const media = { ...mediaOf(null, list), heads: leadHeads };
      const sceneAt = (pts: number) => ({ kind: 'pick', pick: pickAt(windowA, 100, pts) }) as const;

      expect(sceneOffsetMs(exactIn, sceneAt(100 + 2 / 30), media)).toBe(366);
      expect(sceneOffsetMs(exactIn, sceneAt(100.1), media)).toBe(400);
    });
  });

  it('셀 수 없으면 null: 어림 끝점 · 목록 밖 자리 · 목록 없음 · 머리 없는 판 NULL 고름', () => {
    const scene = { kind: 'pick', pick: pickAt(windowA, 100, 134.0) } as const;
    const noHeads: HeadCache = { get: () => undefined, subscribe: () => () => {} };
    const listWithoutIn = { runs: [list.rows.slice(1)], rows: list.rows.slice(1) };

    expect(sceneOffsetMs({ kind: 'estimate', ms: BASE }, scene, mediaOf(windowA, list))).toBeNull();
    expect(sceneOffsetMs(inEnd, { kind: 's', s: 500 }, mediaOf(windowA, list))).toBeNull();
    expect(sceneOffsetMs(inEnd, { kind: 's', s: 60 }, mediaOf(windowA, null))).toBeNull();
    expect(sceneOffsetMs(inEnd, { kind: 's', s: 35.6 }, mediaOf(null, null))).toBeNull();
    expect(sceneOffsetMs(inEnd, scene, mediaOf(null, listWithoutIn))).toBeNull();
    expect(sceneOffsetMs(inEnd, scene, { ...mediaOf(null, list), heads: noHeads })).toBeNull();
  });
});

describe('uploadSceneOffsetMs — 업로드 창의 장면(연 구간 시작부터 편집기 초 차) → 결과 영상 offset', () => {
  const list = listOf(16);
  const windowA = windowOver(list, 0, 10);
  const inPick = pickAt(windowA, 100, 100.5);
  const scene = {
    rangeStartSeconds: windowA.sAtPick(inPick),
    start: { kind: 'pick', pick: inPick } as const,
    placement: windowA,
    list,
    base: BASE,
    heads,
  };

  it('그 자리에 보이는 프레임(미리보기와 같은 프레임)까지의 미디어 거리다', () => {
    // 134.0 프레임의 S 는 35.6 — 창 값 35,110 은 그 프레임 안
    expect(uploadSceneOffsetMs(35_110, scene)).toEqual({ offsetMs: 33_500, approximate: false });
  });

  it('끝점이 없으면(목업) 창의 값 그대로다 — 어긋남이 아니다', () => {
    expect(uploadSceneOffsetMs(35_110, { ...scene, start: undefined })).toEqual({
      offsetMs: 35_110,
      approximate: false,
    });
  });

  it('통합 시험 꼴: 판 NULL 4.2초 PDT · 4초 EXTINF 목록 · 창 없음 · 받은 값(exact) 끝점이면 미디어 거리 5,000', () => {
    // 조각 seq = i · PDT = BASE + 4.2·i. in 59.8초 = 조각 14(PDT 58.8) 안 1.0초, 장면 65.0초 = 조각 15(PDT 63.0) 안 2.0초.
    // 창의 값은 편집기 초 차 5.2초, 결과 영상 안 거리는 (4000 − 1000) + 2000 = 5.0초
    const rows = Array.from({ length: 20 }, (_, i) => row(i, BASE + PDT_STEP_MS * i));
    const ratchet = { runs: [rows], rows };
    const exactScene = {
      rangeStartSeconds: 59.8,
      start: { kind: 'exact', ms: BASE + 59_800 } as const,
      placement: null,
      list: ratchet,
      base: BASE,
      heads,
    };

    expect(uploadSceneOffsetMs(5_200, exactScene)).toEqual({ offsetMs: 5_000, approximate: false });
  });

  it('셀 수 없으면 창의 값을 그대로 보내고 어긋날 수 있다고 표시한다(목록 밖 고름)', () => {
    const listWithoutIn = { runs: [list.rows.slice(1)], rows: list.rows.slice(1) };

    expect(uploadSceneOffsetMs(35_110, { ...scene, placement: null, list: listWithoutIn })).toEqual(
      { offsetMs: 35_110, approximate: true },
    );
  });
});

/**
 * 업로드 일꾼이 offset 에서 뽑는 프레임 — 결과 영상에서 「pts ≥ t」 인 첫 프레임(SceneExtractor.java:126-128 의 입력 -ss ·
 * ffmpeg 9.0.2 실측). 결과 영상 0 초는 in 프레임이다
 */
function extractedAt(frames: readonly { pts: number }[], inPts: number, offsetMs: number) {
  return frames.find((frame) => 1000 * (frame.pts - inPts) >= offsetMs - 1e-6) ?? null;
}

// 편집기에서 고른 썸네일 프레임 → 업로드 창 값(withPickedScene 의 sceneOffsetFromPlayhead) → 업로드 offset 이 같은 프레임을
// 가리켜야 한다(kty c12′ 결정 2 (나)). 고른 S 는 그 프레임 시작이라 ms 로 반올림하면 3번에 1번(30fps) 프레임 시작 앞에 서서
// 업로드 창이 pickAtS 로 다시 고를 때 앞 프레임이 나왔다(cc r1 M2 실측 100/300). 결과 영상 거리도 반올림하면 프레임 시작이
// .67 꼴인 자리에서 업로드 일꾼이 다음 프레임을 뽑는다 — 내린다(경계 답 2026-11-13 렌더)
describe('편집기에서 고른 썸네일 프레임 = 업로드 offset 이 가리키는 프레임', () => {
  const list = listOf(16);
  const cutLengthMs = 60_000;

  it.each([
    ['30fps', 3000],
    ['25fps', 3600],
    ['59.94fps', 1501.5],
  ])('%s 고름 300건이 모두 그 프레임이다', (_, frameTicks) => {
    const placement = windowOver(list, 0, 10, frameTicks);
    const inPick = pickAt(placement, 100, 100.5);
    const rangeStart = placement.sAtPick(inPick);
    const scene = {
      rangeStartSeconds: rangeStart,
      start: { kind: 'pick', pick: inPick } as const,
      ...mediaOf(placement, list),
    };
    // in 프레임부터 300장 — 조각 경계 둘을 건넌다
    const frames = Array.from({ length: 11 }, (_, i) => segment(i, frameTicks).frames)
      .flat()
      .filter((frame) => frame.pts >= inPick.pts);
    const picks = frames.slice(0, 300).map((frame) => pickAt(placement, 100, frame.pts));

    const missed = picks.filter((pick) => {
      const dialogMs = sceneOffsetFromPlayhead(placement.sAtPick(pick), rangeStart, cutLengthMs)!;
      const landed = placement.pickAtS(rangeStart + dialogMs / 1000);
      const offset = uploadSceneOffsetMs(dialogMs, scene).offsetMs;
      return landed?.pts !== pick.pts || extractedAt(frames, inPick.pts, offset)?.pts !== pick.pts;
    });

    expect(picks).toHaveLength(300);
    expect(missed.map((pick) => pick.pts)).toEqual([]);
  });
});
