import { describe, expect, it } from 'vitest';
import { boundaryAction } from '../editorPlayback';
import { zoomedView } from '../timelineMath';
import {
  clipLocateEnd,
  newPathLastFrame,
  type JudgedFrame,
  type JudgeMedia,
  type JudgeRow,
} from '@/test/renderJudge';
import { cutMsOf } from './cutPosition';
import type { PlaylistSegment } from './editorPlaylist';
import type { HeadCache, HeadEntry } from './fileHeadProbe';
import type { RemuxedSegment, RemuxedWindow } from './remuxWindow';
import { placeWindow, type EditorPick } from './windowPlacement';

// 시험 묶음 A 의 창 배치 갈래(설계 prw2 3.2 · 4절 · 11절). 창 Blob 시각 t 의 ③ PTS 는 t + Δ(E3 shiftSeconds)이고, 조각 k
// 안에서 S = (PDT_k − base)/1000 + (p − o_k) 다. 기대값은 설계 반례의 식에서 손으로 옮겼다. 커밋별 검수 입력 셋을 그대로
// 시험으로 둔다: Δ 의 출력 timescale 양자화(약 3.5µs) · 일정하지 않은 표본 길이와 조각 경계 26 틱 겹침 · 원점은 Δ 만 쓴다.

const TICKS = 90_000;
const FRAME_TICKS = 3000;
const BASE = 90_000;

function row(seq: number, pdtMs: number, durationMs: number, timing: 'v2' | null): PlaylistSegment {
  return {
    seq,
    uri: `https://cdn.test/dvr/s/seg/${seq}.m4s`,
    mapUri: 'https://cdn.test/dvr/s/init.mp4',
    pdtMs,
    durationMs,
    timing,
    gap: false,
  };
}

/** ③ 조각 표 — 프레임 시작 틱 목록(PTS 순)과 표본 길이 틱 */
function segment(
  seq: number,
  dtsTicks: number,
  ptsTicks: readonly number[],
  durTicks: number,
): RemuxedSegment {
  const frames = ptsTicks.map((pts) => ({ pts: pts / TICKS, dur: durTicks / TICKS }));
  return {
    seq,
    videoDts: dtsTicks / TICKS,
    frames,
    mediaStart: Math.min(...frames.map((f) => f.pts)),
    mediaEnd: Math.max(...frames.map((f) => f.pts + f.dur)),
  };
}

function evenTicks(startTicks: number, count: number): number[] {
  return Array.from({ length: count }, (_, i) => startTicks + FRAME_TICKS * i);
}

function windowOf(shiftSeconds: number, ...segments: RemuxedSegment[]): RemuxedWindow {
  return { blob: new Blob([]), shiftSeconds, segments };
}

function headsOf(entries: Record<number, HeadEntry> = {}): HeadCache {
  const map = new Map(Object.entries(entries).map(([seq, entry]) => [Number(seq), entry]));
  return { get: (seq) => map.get(seq), subscribe: () => () => {} };
}

describe('placeWindow — 판 NULL 원점은 ② 머리 Hd 다(cx 1 반례)', () => {
  // PDT 100,000 · ③ D3 10.3 · ② D2 0.3 · Hd2 0 → o = 10.0 · ③ 최솟값 10.2 = Δ · 프레임 11.0 은 Blob 0.8
  const k = row(7, 100_000, 4000, null);
  const remuxed = windowOf(10.2, segment(7, 927_000, evenTicks(927_000, 120), FRAME_TICKS));
  const ready = headsOf({ 7: { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } } });

  it('레시피 저장값 101,000 의 Blob 시각은 o + 1.0 − Δ = 0.8 이다', () => {
    const placement = placeWindow(remuxed, [k], BASE, ready);

    expect(placement.blobAtS((101_000 - BASE) / 1000)).toBeCloseTo(0.8, 9);
  });

  it('Blob 0.8 에 보이는 프레임은 PTS 11.0 이고 그 조각의 바뀌지 않는 사실을 든다', () => {
    const pick = placement().pickAtBlob(0.8);

    expect(pick).toEqual({
      seq: 7,
      pts: 990_000 / TICKS,
      dur: expect.closeTo(FRAME_TICKS / TICKS, 12),
      videoDts: 927_000 / TICKS,
      pdtMs: 100_000,
      durationMs: 4000,
      timing: null,
    });
  });

  it('머리가 오기 전에는 근사 자리에 두고 머리가 와서 다시 배치하면 같은 S 가 맞는 자리로 간다', () => {
    const s = (101_000 - BASE) / 1000;
    const early = placeWindow(remuxed, [k], BASE, headsOf({ 7: { state: 'missing' } }));
    const late = placeWindow(remuxed, [k], BASE, ready);

    expect(early.blobAtS(s)).not.toBeCloseTo(0.8, 6);
    expect(late.blobAtS(s)).toBeCloseTo(0.8, 9);
  });

  it('고른 프레임의 표시 초는 저장값과 같은 원점으로 셈한다', () => {
    const pick = placement().pickAtBlob(0.8);

    expect(placement().sAtPick(pick)).toBeCloseTo((101_000 - BASE) / 1000, 9);
    expect(placement().sAtBlob(0.8)).toBeCloseTo((101_000 - BASE) / 1000, 9);
  });

  function placement() {
    return placeWindow(remuxed, [k], BASE, ready);
  }
});

describe('placeWindow — 보이는 프레임 고르기(커밋별 검수 입력)', () => {
  it('Δ 가 출력 timescale 양자화로 3.5µs 작아도 그 시각에 보이는 프레임을 고른다', () => {
    const k = row(1, 0, 4000, 'v2');
    const remuxed = windowOf(
      10.2 - 3.5e-6,
      segment(1, 918_000, evenTicks(918_000, 120), FRAME_TICKS),
    );

    const pick = placeWindow(remuxed, [k], BASE, headsOf()).pickAtBlob(0.8);

    expect(pick.pts).toBe(990_000 / TICKS);
  });

  it('프레임 길이는 표본 길이를 가정하지 않고 같은 조각 표의 다음 PTS 까지다', () => {
    const k = row(1, 0, 4000, 'v2');
    const remuxed = windowOf(0, segment(1, 0, [0, 2997, 6012, 8982], 3000));

    const placement = placeWindow(remuxed, [k], BASE, headsOf());

    expect(placement.pickAtBlob(0).dur).toBeCloseTo(2997 / TICKS, 12);
    expect(placement.pickAtBlob(2997 / TICKS).dur).toBeCloseTo(3015 / TICKS, 12);
  });
});

describe('placeWindow — 조각 마지막 표시 프레임의 길이(렌더 리허설 2026-11-13 · 끝 G+1)', () => {
  // B 프레임 원본은 표본 길이(디코드 순 · 3000 틱)와 표시 간격(다음 PTS − PTS · 2970 틱)이 다르다. 끝 프레임 길이를 표본
  // 길이로 재면 out 이 다음 프레임 PTS 를 넘어 결과 끝에 한 프레임이 더 붙는다(pokeclip-empirical
  // runs/2026-11-13-pdt-r7-prb2-rehearsal). 조각 마지막 프레임도 다음 표시 프레임 PTS 까지로 잰다.

  it('다음 조각이 창에 있으면 앞 조각 마지막 프레임 길이는 다음 조각 첫 표시 PTS 까지다', () => {
    const remuxed = windowOf(
      0,
      segment(1, 0, [0, 2997, 6012, 8982], 3000),
      segment(2, 12_000, [11_997, 15_000], 3000),
    );

    const pick = placeWindow(
      remuxed,
      [row(1, 0, 133, 'v2'), row(2, 133, 4000, 'v2')],
      BASE,
      headsOf(),
    ).pickAtBlob(8982 / TICKS);

    expect(pick.seq).toBe(1);
    expect(pick.dur).toBeCloseTo(3015 / TICKS, 12);
  });

  it('앞 조각 마지막 표시 프레임을 끝으로 고르면 out 은 그 프레임과 다음 조각 첫 프레임 사이 가운데다', () => {
    // 표본 길이 3000 이면 끝 = 14,910 + 3000 = 17,910 틱 = 199.0ms > 다음 PTS 17,880 틱 = 198.67ms. 가운데는
    // 14,910 + 2970/2 = 16,395 틱 = 182.17ms → 182
    const a = row(1, 100_000, 133, 'v2');
    const b = row(2, 100_133, 4000, 'v2');
    const remuxed = windowOf(
      0,
      segment(1, 0, [6000, 8970, 11_940, 14_910], 3000),
      segment(2, 12_000, [17_880, 20_850, 23_820], 3000),
    );
    const placement = placeWindow(remuxed, [a, b], BASE, headsOf());
    const ctx = { base: BASE, heads: headsOf(), list: { runs: [[a, b]], rows: [a, b] } };

    const out = cutMsOf({ kind: 'pick', pick: placement.pickAtBlob(14_910 / TICKS) }, 'end', ctx);

    expect(out).toBe(100_182);
    expect(out).toBeLessThanOrEqual(a.pdtMs + (1000 * 17_880) / TICKS);
  });

  it('창 끝 조각 마지막 프레임은 조각 안 앞 프레임과의 표시 간격이다(표본 길이 아님)', () => {
    const remuxed = windowOf(0, segment(1, 0, [0, 2997, 6012, 8982], 3000));

    const pick = placeWindow(remuxed, [row(1, 0, 4000, 'v2')], BASE, headsOf()).pickAtBlob(
      8982 / TICKS,
    );

    expect(pick.dur).toBeCloseTo(2970 / TICKS, 12);
  });

  it('다음 조각이 이어진 seq 가 아니면 조각 안 표시 간격으로 물러난다', () => {
    const remuxed = windowOf(
      0,
      segment(1, 0, [0, 2997, 6012, 8982], 3000),
      segment(3, 12_000, [11_997, 15_000], 3000),
    );

    const pick = placeWindow(
      remuxed,
      [row(1, 0, 133, 'v2'), row(3, 133, 4000, 'v2')],
      BASE,
      headsOf(),
    ).pickAtBlob(8982 / TICKS);

    expect(pick.dur).toBeCloseTo(2970 / TICKS, 12);
  });

  it('프레임이 하나뿐인 창 끝 조각은 표본 길이다', () => {
    const remuxed = windowOf(0, segment(1, 0, [0], 3000));

    const pick = placeWindow(remuxed, [row(1, 0, 33, 'v2')], BASE, headsOf()).pickAtBlob(0);

    expect(pick.dur).toBeCloseTo(3000 / TICKS, 12);
  });

  it('겹친 이음이면 뒤 조각이 이기므로 이 PTS 뒤 첫 뒤 조각 프레임까지다', () => {
    // 뒤 조각 첫 PTS 8000 ≤ 앞 조각 끝 프레임 9000 — 9000 에는 앞 조각 프레임이 보이고 11,000 에서 뒤 조각이 덮는다
    const remuxed = windowOf(
      0,
      segment(1, 0, [0, 3000, 6000, 9000], 3000),
      segment(2, 8000, [8000, 11_000, 14_000], 3000),
    );

    const pick = placeWindow(
      remuxed,
      [row(1, 0, 100, 'v2'), row(2, 89, 4000, 'v2')],
      BASE,
      headsOf(),
    ).pickAtBlob(9000 / TICKS);

    expect(pick.seq).toBe(1);
    expect(pick.dur).toBeCloseTo(2000 / TICKS, 12);
  });

  it('뒤 조각 프레임이 모두 이 PTS 이하이면 조각 안 표시 간격으로 물러난다', () => {
    const remuxed = windowOf(
      0,
      segment(1, 0, [0, 3000, 6000, 8970], 3000),
      segment(2, 5000, [5000, 8000], 3000),
    );

    const pick = placeWindow(
      remuxed,
      [row(1, 0, 100, 'v2'), row(2, 56, 4000, 'v2')],
      BASE,
      headsOf(),
    ).pickAtBlob(8970 / TICKS);

    expect(pick.seq).toBe(1);
    expect(pick.dur).toBeCloseTo(2970 / TICKS, 12);
  });

  it('앞 조각 끝과 26 틱 겹친 뒤 조각의 첫 프레임 시각에는 뒤 조각 프레임이 보인다', () => {
    const a = row(300, 0, 4000, 'v2');
    const b = row(301, 4000, 4000, 'v2');
    const lastStart = FRAME_TICKS * 119;
    const remuxed = windowOf(
      0,
      segment(300, 0, evenTicks(0, 120), FRAME_TICKS),
      segment(
        301,
        lastStart + FRAME_TICKS - 26,
        evenTicks(lastStart + FRAME_TICKS - 26, 120),
        FRAME_TICKS,
      ),
    );

    const pick = placeWindow(remuxed, [a, b], BASE, headsOf()).pickAtBlob(
      (lastStart + FRAME_TICKS - 26) / TICKS,
    );

    expect(pick.seq).toBe(301);
  });

  it('open GOP 조각은 첫 표본보다 PTS 가 작은 앞 B 프레임을 첫 화면으로 고른다', () => {
    const k = row(1, 0, 4000, 'v2');
    const remuxed = windowOf(0, segment(1, 0, [3000, 6000, 9000, 12_000], FRAME_TICKS));

    const placement = placeWindow(remuxed, [k], BASE, headsOf());

    expect(placement.pickAtBlob(3000 / TICKS).pts).toBe(3000 / TICKS);
    expect(placement.blobAtS((0 - BASE) / 1000)).toBe(3000 / TICKS);
  });
});

describe('placeWindow → cutMsOf — 끝은 G 와 다음 프레임 사이 가운데(리허설 실표본 · r1 반례 둘)', () => {
  // 리허설 판 2 녹화 pcbv2 seq 1–3 의 ffprobe 표본(pokeclip-empirical runs/2026-11-13-pdt-r7-prb2-rehearsal
  // work/ffprobe-src). 조각마다 제 파일 축의 틱을 ③ 축(앞 조각 DTS 끝에 이어 붙인 축)으로 옮겼다 — seq 2 는 +533,790 ·
  // seq 3 은 +892,800. PDT 간격은 5966 · 4000ms 인데 ③ D3 간격은 5966.5 · 4000ms 이고, 조각 첫 프레임은 D3 뒤 33.5ms 다.
  // 끝을 G 의 끝(다음 프레임 PTS)에 두면 ms 내림 · 반 ms 차로 다음 조각 첫 프레임(PTS − D0 = 33.5ms)을 넘는다.
  const rows = [
    row(1, 100_000, 5966, 'v2'),
    row(2, 105_966, 4000, 'v2'),
    row(3, 109_966, 4000, 'v2'),
  ];
  const seconds = (ticks: readonly number[]) => ticks.map((t) => t / TICKS);
  const media = new Map<number, JudgeMedia>([
    [1, { videoDts: 0, m: 0, e: 0, frames: seconds([531_044, 534_015, 537_075]) }],
    [
      2,
      {
        videoDts: 536_985 / TICKS,
        m: 0,
        e: 0,
        frames: seconds([540_000, 542_970, 891_000, 893_970, 897_030]),
      },
    ],
    [3, { videoDts: 896_985 / TICKS, m: 0, e: 0, frames: seconds([900_000, 902_970]) }],
  ]);
  const judgeRows: JudgeRow[] = rows.map((r) => ({ ...r, continuesPrevious: r.seq !== 1 }));
  const list = { runs: [rows], rows };

  function tableOf(seq: number): RemuxedSegment {
    const seg = media.get(seq)!;
    const ticks = seg.frames.map((pts) => Math.round(pts * TICKS));
    return segment(seq, Math.round(seg.videoDts * TICKS), ticks, FRAME_TICKS);
  }

  /**
   * 렌더 끝 프레임 — 새 경로 「PTS − D0 < x_out」(renderJudge). 끝 조각에 남는 프레임이 없으면 렌더는 앞 조각으로 물러나
   * 그 끝 프레임까지 담는다(RenderTimeline 끝 조각 후퇴 — renderJudge 는 옮기지 않아 여기서 잇는다)
   */
  function renderEnd(outMs: number): JudgedFrame | null {
    const last = newPathLastFrame(judgeRows, media, outMs, 'VIDEO_START');
    if (last !== null) return last;
    const place = clipLocateEnd(judgeRows, outMs);
    if (place.kind !== 'media') return null;
    const before = judgeRows[judgeRows.findIndex((r) => r.seq === place.seq) - 1];
    return before === undefined
      ? null
      : { seq: before.seq, pts: media.get(before.seq)!.frames.at(-1)! };
  }

  function outOf(windowSeqs: readonly number[], gTicks: number): number {
    const placement = placeWindow(
      windowOf(0, ...windowSeqs.map(tableOf)),
      rows.filter((r) => windowSeqs.includes(r.seq)),
      BASE,
      headsOf(),
    );
    const out = cutMsOf({ kind: 'pick', pick: placement.pickAtBlob(gTicks / TICKS) }, 'end', {
      base: BASE,
      heads: headsOf(),
      list,
    });
    if (typeof out !== 'number') throw new Error(`컷 값을 짓지 못했다: ${out}`);
    return out;
  }

  it('창 끝 seq 2 의 마지막 프레임(원본 299)을 끝으로 고르면 결과 끝은 그 프레임이고 300 은 빠진다', () => {
    // 앞 간격 3060 틱의 반 → 360,045 + 1530 = 361,575 틱 = 4017.5ms → 4017 · seq 3 의 17ms 로 풀려 첫 프레임 33.5ms 는 빠진다
    const out = outOf([2], 897_030);

    expect(out).toBe(105_966 + 4017);
    expect(renderEnd(out)).toEqual({ seq: 2, pts: 897_030 / TICKS });
    // 앞 간격 전체(옛 r1 · 4034)면 seq 3 의 34ms 라 원본 300 이 든다
    expect(renderEnd(105_966 + 4034)).toEqual({ seq: 3, pts: 900_000 / TICKS });
  });

  it('seq 1 마지막 프레임(원본 179)을 끝으로 고르면 ③ · PDT 반 ms 차가 있어도 결과 끝은 그 프레임이다', () => {
    // 다음 PTS 540,000 까지 2925 틱의 반 → 537,075 + 1462.5 = 538,537.5 틱 = 5983.75ms → 5983 · seq 2 의 17ms
    const out = outOf([1, 2], 537_075);

    expect(out).toBe(100_000 + 5983);
    expect(renderEnd(out)).toEqual({ seq: 1, pts: 537_075 / TICKS });
    // 다음 PTS 까지 전체(6000)면 seq 2 의 34ms 라 원본 180 이 든다
    expect(renderEnd(100_000 + 6000)).toEqual({ seq: 2, pts: 540_000 / TICKS });
  });
});

describe('placeWindow — S ↔ Blob(F 규칙 · 죽은 자리 · 머리)', () => {
  it('판 NULL 죽은 자리(EXTINF 끝과 다음 PDT 사이)는 다음 조각 첫 화면으로 간다', () => {
    const a = row(1, 100_000, 4000, 'v2');
    const b = row(2, 104_200, 4000, 'v2');
    const remuxed = windowOf(
      0,
      segment(1, 0, evenTicks(0, 120), FRAME_TICKS),
      segment(2, 360_000, evenTicks(366_000, 120), FRAME_TICKS),
    );

    const blob = placeWindow(remuxed, [a, b], BASE, headsOf()).blobAtS((104_100 - BASE) / 1000);

    expect(blob).toBe(366_000 / TICKS);
  });

  it('판 NULL 조각 머리 앞섬 구간 [PDT_k, PDT_k + m) 의 값은 그 조각 첫 영상 프레임에 보인다', () => {
    const k = row(7, 100_000, 4000, null);
    const remuxed = windowOf(10.2, segment(7, 927_000, evenTicks(933_000, 120), FRAME_TICKS));
    const heads = headsOf({ 7: { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } } });

    const blob = placeWindow(remuxed, [k], BASE, heads).blobAtS((100_100 - BASE) / 1000);

    expect(blob).toBeCloseTo(933_000 / TICKS - 10.2, 9);
  });

  it('창 밖 S 는 고름이 없고 창 안 S 는 그 자리 프레임을 고른다', () => {
    const k = row(1, 100_000, 4000, 'v2');
    const placement = placeWindow(
      windowOf(0, segment(1, 0, evenTicks(0, 120), FRAME_TICKS)),
      [k],
      BASE,
      headsOf(),
    );

    expect(placement.pickAtS((99_999 - BASE) / 1000)).toBeNull();
    expect(placement.pickAtS((104_000 - BASE) / 1000)).toBeNull();
    expect(placement.pickAtS((101_010 - BASE) / 1000)?.pts).toBe(90_000 / TICKS);
  });

  it('판 2 겹침 경계에서 재생 S 가 0.3초 뒤로 가도 반복을 다시 걸지 않고 확대 보기가 앞으로 따라간다', () => {
    const a = row(1, 100_000, 4000, 'v2');
    const b = row(2, 103_700, 4000, 'v2');
    const placement = placeWindow(
      windowOf(
        0,
        segment(1, 0, evenTicks(0, 120), FRAME_TICKS),
        segment(2, 360_000, evenTicks(360_000, 120), FRAME_TICKS),
      ),
      [a, b],
      BASE,
      headsOf(),
    );
    const bounds = { startSeconds: 12, endSeconds: 20, loop: true };
    const before = placement.sAtBlob(3.99);
    const after = placement.sAtBlob(4.0);
    const view = { startSeconds: 0, endSeconds: 30 };

    expect(before - after).toBeCloseTo(0.29, 9);
    expect(boundaryAction(before, bounds)).toEqual({ kind: 'continue' });
    expect(boundaryAction(after, bounds)).toEqual({ kind: 'continue' });
    expect(
      zoomedView({
        range: { startSeconds: 12, endSeconds: 20 },
        playheadSeconds: after,
        zoom: 200,
        minSeconds: 0,
        maxSeconds: 60,
        previous: view,
      }),
    ).toEqual(view);
  });

  it('다른 창에서 고른 고름(이 창에 없는 seq)도 고름의 PDT · D3 로 표시 초를 낸다', () => {
    const placement = placeWindow(
      windowOf(0, segment(1, 0, evenTicks(0, 120), FRAME_TICKS)),
      [row(1, 100_000, 4000, 'v2')],
      BASE,
      headsOf(),
    );
    const elsewhere: EditorPick = {
      seq: 99,
      pts: 501.5,
      dur: 1 / 30,
      videoDts: 500,
      pdtMs: 600_000,
      durationMs: 4000,
      timing: 'v2',
    };

    expect(placement.sAtPick(elsewhere)).toBeCloseTo((600_000 - BASE) / 1000 + 1.5, 9);
  });

  it('같은 창 안 두 자리의 미디어 거리는 ③ PTS 차이고 창 밖 자리면 null 이다', () => {
    const placement = placeWindow(
      windowOf(
        0,
        segment(1, 0, evenTicks(0, 120), FRAME_TICKS),
        segment(2, 360_000, evenTicks(360_000, 120), FRAME_TICKS),
      ),
      [row(1, 100_000, 4000, 'v2'), row(2, 104_200, 4000, 'v2')],
      BASE,
      headsOf(),
    );

    expect(placement.mediaDistance({ seq: 1, pts: 1 }, { seq: 2, pts: 5.5 })).toBeCloseTo(4.5, 9);
    expect(placement.mediaDistance({ seq: 1, pts: 1 }, { seq: 3, pts: 9 })).toBeNull();
  });
});
