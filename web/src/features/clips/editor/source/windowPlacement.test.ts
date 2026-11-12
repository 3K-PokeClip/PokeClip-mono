import { describe, expect, it } from 'vitest';
import { boundaryAction } from '../editorPlayback';
import { zoomedView } from '../timelineMath';
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
    expect(placement.pickAtBlob(8982 / TICKS).dur).toBeCloseTo(3000 / TICKS, 12);
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
