import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  newPathFirstFrame,
  newPathLastFrame,
  oldPathFirstFrame,
  oldPathLastFrame,
  oldPathRange,
  type JudgeMedia,
  type JudgeRow,
} from '@/test/renderJudge';
import { cutMsOf, type CutContext } from './cutPosition';
import type { EditorPlaylist, PlaylistSegment } from './editorPlaylist';
import type { FileHead, HeadCache, HeadEntry } from './fileHeadProbe';
import type { EditorPick } from './windowPlacement';

// 시험 묶음 A 의 컷 값 갈래(설계 prw2 3.2 결정 2 · 11절 · 처분 K1′ · K2 · PK1). 기대 T 는 설계 반례의 식에서 손으로 옮겼고,
// 그 T 가 담는 프레임은 시험 쪽 판정 함수(@/test/renderJudge — clip · 렌더 규칙을 옮긴 것)로 본다. 첫 프레임은 새 경로 = F
// (K2 · e > 0 은 F 앞) · 옛 경로 ∈ {F, F 다음}, 끝 프레임은 두 경로 모두 F 로 기대한다. 프레임 시각은 실물처럼 90 kHz 틱에서
// 만든다. in 은 올림 · out 은 F 와 다음 프레임 사이 가운데의 내림이다(kty 결정 2 (다) · 결정 (다) 2026-11-13) — 기대 in 은
// ceil(p_F − o − e), 기대 out 은 floor(p_F + dur_F/2 − o) 에서 옮겼다.

const TICKS = 90_000;
const FRAME_TICKS = 3000;
const BASE = 50_000;

function frameTimes(d0Ticks: number, cTicks: number, count: number): number[] {
  return Array.from({ length: count }, (_, i) => (d0Ticks + cTicks + FRAME_TICKS * i) / TICKS);
}

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

/** 런 하나짜리 목록(끊김은 판정 입력으로만 준다 — 웹은 목록 이어짐을 모른다) */
function listOf(...rows: PlaylistSegment[]): EditorPlaylist {
  return { runs: [rows], rows };
}

/** 머리 캐시 — 시험이 값을 바꿔 「늦게 온 머리」 를 흉내 낸다 */
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

function ready(head: FileHead): HeadEntry {
  return { state: 'ready', head };
}

/** 조각 k 의 프레임 i 를 고른 것 — 길이는 같은 표의 다음 PTS 까지(마지막이면 한 프레임) */
function pickOf(
  seg: PlaylistSegment,
  videoDts: number,
  frames: readonly number[],
  i: number,
): EditorPick {
  const pts = frames[i]!;
  const next = frames[i + 1];
  return {
    seq: seg.seq,
    pts,
    dur: next === undefined ? FRAME_TICKS / TICKS : next - pts,
    videoDts,
    pdtMs: seg.pdtMs,
    durationMs: seg.durationMs,
    timing: seg.timing,
  };
}

function ctxOf(list: EditorPlaylist | null, heads: HeadCache = headsOf()): CutContext {
  return { base: BASE, heads, list };
}

function judgeRows(rows: readonly PlaylistSegment[], continues: readonly boolean[]): JudgeRow[] {
  return rows.map((r, i) => ({
    seq: r.seq,
    pdtMs: r.pdtMs,
    durationMs: r.durationMs,
    continuesPrevious: continues[i] ?? false,
  }));
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('cutMsOf — 판 NULL 고름은 ② 머리 Hd 를 원점으로 셈한다(cc H1 반례)', () => {
  // PDT 100,000 · ③ D3 10.3 · ② D2 0.3 · Hd2 0 · 프레임 PTS 11.0 · 30fps → o = 10.0 · out T = 101,016(1.0 + 1/60)
  const k = row(7, 100_000, 4000, null);
  const next = row(8, 104_000, 4000, null);
  const frames = frameTimes(927_000, 0, 120);
  const pick = pickOf(k, 10.3, frames, 21);
  const head = ready({ hd: 0, hp: 0, videoDts: 0.3 });
  const judge = new Map<number, JudgeMedia>([[7, { videoDts: 10.3, m: 0.3, e: 0, frames }]]);

  it('고른 프레임과 다음 프레임 사이 가운데를 원점 o = D3 − m 에서 재어 101,016 을 내고 렌더 끝 프레임이 그 프레임이다', () => {
    const outMs = cutMsOf(
      { kind: 'pick', pick },
      'end',
      ctxOf(listOf(k, next), headsOf({ 7: head })),
    );

    expect(pick.pts).toBeCloseTo(11.0, 9);
    expect(outMs).toBe(101_016);
    const last = newPathLastFrame(
      judgeRows([k, next], [false, true]),
      judge,
      outMs as number,
      'FILE_HEAD',
    );
    expect(last).toEqual({ seq: 7, pts: pick.pts });
  });

  it('머리가 없으면 headMissing · 판독이 실패했으면 headFailed · 아직 묻지 않았으면 headMissing 이다', () => {
    const list = listOf(k, next);

    expect(
      cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(list, headsOf({ 7: { state: 'missing' } }))),
    ).toBe('headMissing');
    expect(
      cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(list, headsOf({ 7: { state: 'failed' } }))),
    ).toBe('headFailed');
    expect(cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(list, headsOf()))).toBe('headMissing');
  });

  it('머리가 늦게 와도 같은 고름이 같은 값으로 저장 가능해진다(진실원 하나 — 머리 캐시)', () => {
    const heads = headsOf({ 7: { state: 'missing' } });
    const ctx = ctxOf(listOf(k, next), heads);
    const before = cutMsOf({ kind: 'pick', pick }, 'end', ctx);

    heads.set(7, head);

    expect(before).toBe('headMissing');
    expect(cutMsOf({ kind: 'pick', pick }, 'end', ctx)).toBe(101_016);
  });

  it('판 2 고름은 머리 없이 영상 첫 DTS 를 원점으로 셈한다', () => {
    const v2 = row(7, 100_000, 4000, 'v2');
    const v2Pick = pickOf(v2, 10.3, frames, 21);

    expect(
      cutMsOf(
        { kind: 'pick', pick: v2Pick },
        'end',
        ctxOf(listOf(v2, row(8, 104_000, 4000, 'v2'))),
      ),
    ).toBe(100_716);
  });
});

describe('cutMsOf — 반올림은 in 올림 · out 내림이다', () => {
  // 판 NULL · ② 영상 첫 DTS 0 · 머리 앞섬 m 0 · e 0 · ③ 축 = ② 축
  const k = row(3, 200_000, 4000, null);
  const next = row(4, 204_000, 4000, null);
  const frames = frameTimes(0, 0, 120);
  const heads = headsOf({ 3: ready({ hd: 0, hp: 0, videoDts: 0 }) });
  const seg: JudgeMedia = { videoDts: 0, m: 0, e: 0, frames };
  const judge = new Map([[3, seg]]);
  const rows = judgeRows([k, next], [false, true]);

  it('in 프레임 PTS 1.033333 은 1,034 로 올려 새 경로는 F 부터 · 옛 경로는 F 다음부터 담는다', () => {
    const pick = pickOf(k, 0, frames, 31);

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctxOf(listOf(k, next), heads)) as number;

    expect(inMs).toBe(201_034);
    expect(newPathFirstFrame(rows, judge, inMs, 'FILE_HEAD')?.pts).toBe(pick.pts);
    // 옛 경로 「in 이후 첫 프레임」 은 F 를 빠뜨린다 — 웹과 clip PR-B2 를 같은 배포 창에 붙여 그 공백을 줄인다(kty 결정 1 (가))
    expect(oldPathFirstFrame(seg, k.pdtMs, inMs)).toBe(frames[32]);
  });

  it('정수 ms 에 떨어지는 in(4.2 − 4.0 = 0.200)은 부동소수 오차로 1ms 위로 올라가지 않아 두 경로 모두 F 부터다', () => {
    // 판 2 · D3 4.0 · 프레임 PTS 4.2 — 1000·(4.2 − 4.0) 은 부동소수로 200.00000000000017 이다
    const v2 = row(12, 700_000, 4000, 'v2');
    const v2Frames = frameTimes(360_000, 0, 120);
    const pick = pickOf(v2, 4.0, v2Frames, 6);
    const v2Seg: JudgeMedia = { videoDts: 4.0, m: 0, e: 0, frames: v2Frames };

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctxOf(listOf(v2))) as number;

    expect(inMs).toBe(700_200);
    expect(
      newPathFirstFrame(judgeRows([v2], [false]), new Map([[12, v2Seg]]), inMs, 'VIDEO_START')?.pts,
    ).toBe(pick.pts);
    expect(oldPathFirstFrame(v2Seg, v2.pdtMs, inMs)).toBe(pick.pts);
  });

  it('한 틱(1/90 ms) 소수도 올린다 — 1000.0111 은 1,001 이고 1,000 이면 새 경로가 F 앞부터다', () => {
    // 판 2 · D3 0 · 프레임이 한 틱 늦게 선다(PTS 1 + 1/90000초)
    const v2 = row(13, 800_000, 4000, 'v2');
    const v2Frames = frameTimes(0, 1, 120);
    const pick = pickOf(v2, 0, v2Frames, 30);
    const v2Judge = new Map([[13, { videoDts: 0, m: 0, e: 0, frames: v2Frames }]]);
    const v2Rows = judgeRows([v2], [false]);

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctxOf(listOf(v2))) as number;

    expect(inMs).toBe(801_001);
    expect(newPathFirstFrame(v2Rows, v2Judge, inMs, 'VIDEO_START')?.pts).toBe(pick.pts);
    expect(newPathFirstFrame(v2Rows, v2Judge, inMs - 1, 'VIDEO_START')?.pts).toBe(v2Frames[29]);
  });

  // 판 NULL 원점 o = D3 − (D2 − Hd) 는 영상 틱(1/90000초)과 소리 틱의 차를 담는다. 그 차의 가장 작은 소수는
  // 1/lcm(90000, 소리 rate)초 — 96kHz 1/1440 ms · 44.1kHz 1/4410 ms 로 부동소수 잡음보다 훨씬 크다(r1 cx · cc 지적)
  it.each([
    { rate: '96kHz', d2Ticks: 1, hd: 1 / 96_000 },
    { rate: '44.1kHz', d2Ticks: 49, hd: 24 / 44_100 },
  ])('혼합 틱($rate) 소수 ms 도 올려 새 경로 첫 프레임이 F 다', ({ d2Ticks, hd }) => {
    // ③ 축 = ② 축 · D2 = D3 = d2Ticks/90000 · Hd = Hp(e 0) · 프레임 PTS = D3 + 1초
    const d3 = d2Ticks / TICKS;
    const nk = row(14, 900_000, 4000, null);
    const nFrames = frameTimes(d2Ticks, 0, 120);
    const pick = pickOf(nk, d3, nFrames, 30);
    const nHeads = headsOf({ 14: ready({ hd, hp: hd, videoDts: d3 }) });
    const nJudge = new Map([[14, { videoDts: d3, m: d3 - hd, e: 0, frames: nFrames }]]);

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctxOf(listOf(nk), nHeads)) as number;

    expect(pick.pts - d3).toBeCloseTo(1, 9);
    expect(inMs).toBe(901_001);
    expect(newPathFirstFrame(judgeRows([nk], [false]), nJudge, inMs, 'FILE_HEAD')?.pts).toBe(
      pick.pts,
    );
  });

  it('out 은 F 와 G 사이 가운데 1.05 를 1,050 으로 내려 두 경로 모두 F 는 남고 G 는 빠진다', () => {
    const pick = pickOf(k, 0, frames, 31);

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next), heads)) as number;

    expect(outMs).toBe(201_050);
    expect(newPathLastFrame(rows, judge, outMs, 'FILE_HEAD')?.pts).toBe(pick.pts);
    expect(oldPathLastFrame(seg, k.pdtMs, outMs)).toBe(pick.pts);
  });

  it('정수 ms 에 떨어지는 끝(0.900)은 부동소수 오차로 1ms 앞으로 내려가지 않는다', () => {
    // 반 프레임(1500 틱) 늦게 선 프레임이라 프레임 0.883333 과 다음 프레임 사이 가운데가 0.900 인데, 1000·(p + dur/2) 는
    // 부동소수로 899.9999999999999 다
    const pick = pickOf(k, 0, frameTimes(0, 1500, 120), 26);

    expect(1000 * (pick.pts + pick.dur / 2)).toBeLessThan(900);
    expect(cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next), heads))).toBe(200_900);
  });

  it('혼합 틱 소수 ms 의 끝도 내린다 — 999.99931 은 999 이고 1,000 이면 G 쪽으로 다가간다', () => {
    // ③ D3 0 · ② D2 14/90000 · Hd = Hp 15/96000 → o = D3 − (D2 − Hd) = 1/1440000초(6.9e-4 ms). 가운데 1.000 에서 o 를 빼
    // 999.99931ms — 여유가 1e-3ms 만 돼도 그 소수를 지워 1,000 으로 올린다
    const d2 = 14 / TICKS;
    const hd = 15 / 96_000;
    const nk = row(15, 950_000, 4000, null);
    const nFrames = frameTimes(0, 1500, 120);
    const pick = pickOf(nk, 0, nFrames, 29);
    const nHeads = headsOf({ 15: ready({ hd, hp: hd, videoDts: d2 }) });
    const nJudge = new Map([[15, { videoDts: 0, m: d2 - hd, e: 0, frames: nFrames }]]);

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(nk), nHeads)) as number;

    expect(pick.pts + pick.dur / 2).toBeCloseTo(1, 9);
    expect(outMs).toBe(950_999);
    expect(newPathLastFrame(judgeRows([nk], [false]), nJudge, outMs, 'FILE_HEAD')?.pts).toBe(
      pick.pts,
    );
  });
});

describe('cutMsOf — 판 NULL in 은 e 를 빼 옛 경로 0점(Hp)과 맞춘다(r3 cx 2 반례)', () => {
  // ② 영상 첫 DTS 0 · 첫 PTS 66.667ms · 모든 소리 첫 PTS 40ms → Hd 0 · Hp 0.040 · m 0 · e 0.040
  const k = row(5, 300_000, 4000, null);
  const next = row(6, 304_000, 4000, null);
  const frames = frameTimes(0, 6000, 118);
  const heads = headsOf({ 5: ready({ hd: 0, hp: 0.04, videoDts: 0 }) });
  const seg: JudgeMedia = { videoDts: 0, m: 0, e: 0.04, frames };
  const judge = new Map([[5, seg]]);
  const rows = judgeRows([k, next], [false, true]);
  const ctx = ctxOf(listOf(k, next), heads);

  it('in 프레임 1.000 은 PDT + 960 이고 옛 경로 첫 프레임이 F 다(정수 ms 라 올림 · 내림이 같다)', () => {
    const pick = pickOf(k, 0, frames, 28);

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctx) as number;

    expect(pick.pts).toBeCloseTo(1.0, 9);
    expect(inMs).toBe(300_960);
    expect(oldPathFirstFrame(seg, k.pdtMs, inMs)).toBe(pick.pts);
  });

  it('in 프레임 1.033333 은 1.033333 − 0.04 를 올려 PDT + 994 · 새 경로는 F − e 안 · 옛 경로는 F 다음부터다', () => {
    const pick = pickOf(k, 0, frames, 29);

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctx) as number;

    expect(pick.pts).toBeCloseTo(1.0 + 1 / 30, 9);
    expect(inMs).toBe(300_994);
    const newFirst = newPathFirstFrame(rows, judge, inMs, 'FILE_HEAD')!.pts;
    expect(newFirst).toBeLessThan(pick.pts);
    expect(newFirst).toBeGreaterThanOrEqual(pick.pts - 0.04 - FRAME_TICKS / TICKS);
    expect(oldPathFirstFrame(seg, k.pdtMs, inMs)).toBe(frames[30]);
  });

  it('e > 0 이면 새 경로 머리는 F − e − 한 프레임 안에서 더 들고 옛 경로 끝은 F 를 넘어 더 들 수 있으나 누락은 없다', () => {
    const pick = pickOf(k, 0, frames, 58);
    const frame = FRAME_TICKS / TICKS;

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctx) as number;
    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctx) as number;

    const newFirst = newPathFirstFrame(rows, judge, inMs, 'FILE_HEAD')!.pts;
    expect(newFirst).toBeLessThanOrEqual(pick.pts);
    expect(newFirst).toBeGreaterThanOrEqual(pick.pts - 0.04 - frame);
    expect(oldPathFirstFrame(seg, k.pdtMs, inMs)).toBe(pick.pts);
    expect(newPathLastFrame(rows, judge, outMs, 'FILE_HEAD')?.pts).toBe(pick.pts);
    const oldLast = oldPathLastFrame(seg, k.pdtMs, outMs)!;
    expect(oldLast).toBeGreaterThanOrEqual(pick.pts);
    expect(oldLast).toBeLessThan(pick.pts + pick.dur + 0.04);
  });
});

describe('cutMsOf — K2: in 이 장부 틈에 떨어지면 조각 끝 앞으로 내린다', () => {
  // 판 2 · PDT 104,000 · EXTINF 4,000 · 프레임 PTS 4.033333(DTS 원점 — 구성 시차 때문에 EXTINF 밖)
  const k = row(10, 104_000, 4000, 'v2');
  const frames = frameTimes(0, 0, 122);
  const pick = pickOf(k, 0, frames, 121);
  const judge = new Map<number, JudgeMedia>([
    [10, { videoDts: 0, m: 0, e: 0, frames }],
    [11, { videoDts: 4.0, m: 0, e: 0, frames: frameTimes(360_000, 0, 120) }],
  ]);

  it('이어진 이웃 앞 틈의 108,034 는 107,999 가 되어 렌더 시작이 그 조각 안이다', () => {
    const next = row(11, 111_000, 4000, 'v2');

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctxOf(listOf(k, next))) as number;

    expect(inMs).toBe(107_999);
    const first = newPathFirstFrame(
      judgeRows([k, next], [false, true]),
      judge,
      inMs,
      'VIDEO_START',
    );
    expect(first?.seq).toBe(10);
    expect(first!.pts).toBeLessThanOrEqual(pick.pts);
  });

  it('끊긴 이웃 앞 틈에서도 같은 값이라 렌더가 뒤 조각부터 시작하지 않는다', () => {
    const next = row(11, 108_500, 4000, 'v2');

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctxOf(listOf(k, next))) as number;

    expect(inMs).toBe(107_999);
    expect(
      newPathFirstFrame(judgeRows([k, next], [false, false]), judge, inMs, 'VIDEO_START')?.seq,
    ).toBe(10);
  });

  it('목록 마지막 조각이면 PDT_k + EXTINF_k − 1 로 내려 clip 이 AfterLast 로 거절하지 않는다', () => {
    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctxOf(listOf(k))) as number;

    expect(inMs).toBe(107_999);
    expect(newPathFirstFrame(judgeRows([k], [false]), judge, inMs, 'VIDEO_START')?.seq).toBe(10);
  });

  it('판 NULL 머리 앞섬 m 꼬리(영상 DTS [0.2, 4.2] · PTS 4.1)도 PDT_k + EXTINF_k − 1 로 내린다', () => {
    const v1 = row(20, 400_000, 4000, null);
    const v1Next = row(21, 404_000, 4000, null);
    const v1Frames = frameTimes(18_000, 0, 120);
    const v1Pick = pickOf(v1, 0.2, v1Frames, 117);
    const heads = headsOf({ 20: ready({ hd: 0, hp: 0, videoDts: 0.2 }) });
    const v1Judge = new Map([[20, { videoDts: 0.2, m: 0.2, e: 0, frames: v1Frames }]]);

    const inMs = cutMsOf(
      { kind: 'pick', pick: v1Pick },
      'start',
      ctxOf(listOf(v1, v1Next), heads),
    ) as number;

    expect(v1Pick.pts).toBeCloseTo(4.1, 9);
    expect(inMs).toBe(403_999);
    const first = newPathFirstFrame(
      judgeRows([v1, v1Next], [false, true]),
      v1Judge,
      inMs,
      'FILE_HEAD',
    );
    expect(first?.seq).toBe(20);
    expect(first!.pts).toBeLessThanOrEqual(v1Pick.pts);
  });

  it('올림이 조각 끝(PTS 3.999989 → 108,000)에 닿아도 PDT_k + EXTINF_k − 1 로 내려 렌더가 이웃 조각부터 시작하지 않는다', () => {
    // 프레임이 359,999 틱에 서는 판 2 조각 — 1000·3.999989 를 올리면 4,000 = EXTINF 라 clip 은 이어진 이웃 seq 11 머리로 푼다
    const edgeFrames = frameTimes(0, 2999, 120);
    const edgePick = pickOf(k, 0, edgeFrames, 119);
    const next = row(11, 108_000, 4000, 'v2');
    const edgeJudge = new Map<number, JudgeMedia>([
      [10, { videoDts: 0, m: 0, e: 0, frames: edgeFrames }],
      [11, { videoDts: 4.0, m: 0, e: 0, frames: frameTimes(360_000, 0, 120) }],
    ]);
    const rows = judgeRows([k, next], [false, true]);

    const inMs = cutMsOf(
      { kind: 'pick', pick: edgePick },
      'start',
      ctxOf(listOf(k, next)),
    ) as number;

    expect(edgePick.pts).toBeCloseTo(359_999 / TICKS, 9);
    expect(inMs).toBe(107_999);
    const first = newPathFirstFrame(rows, edgeJudge, inMs, 'VIDEO_START');
    expect(first?.seq).toBe(10);
    expect(first!.pts).toBeLessThanOrEqual(edgePick.pts);
    expect(newPathFirstFrame(rows, edgeJudge, 108_000, 'VIDEO_START')?.seq).toBe(11);
  });

  it('판 2 겹침 꼬리(다음 PDT 가 EXTINF 끝보다 이름)는 PDT_{k+1} − 1 로 내리고 out 은 그대로 둔다', () => {
    const overlapK = row(30, 500_000, 4000, 'v2');
    const overlapNext = row(31, 503_800, 4000, 'v2');
    const tailPick = pickOf(overlapK, 0, frames, 117);
    const ctx = ctxOf(listOf(overlapK, overlapNext));

    const inMs = cutMsOf({ kind: 'pick', pick: tailPick }, 'start', ctx) as number;

    expect(tailPick.pts).toBeCloseTo(3.9, 9);
    expect(inMs).toBe(503_799);
    expect(cutMsOf({ kind: 'pick', pick: tailPick }, 'end', ctx)).toBe(503_916);
    const overlapJudge = new Map([
      [30, { videoDts: 0, m: 0, e: 0, frames }],
      [31, { videoDts: 4.0, m: 0, e: 0, frames: frameTimes(360_000, 0, 120) }],
    ]);
    const first = newPathFirstFrame(
      judgeRows([overlapK, overlapNext], [false, true]),
      overlapJudge,
      inMs,
      'VIDEO_START',
    );
    expect(first?.seq).toBe(30);
    expect(first!.pts).toBeLessThanOrEqual(tailPick.pts);
  });
});

describe('cutMsOf — K1′: out 은 장부 틈 안에서 맞추지 않고 목록 마지막 조각일 때만 조각 끝으로 맞춘다', () => {
  // 판 2 · PDT 104,000 · EXTINF 4,000 · 마지막 프레임 PTS 4.033333 · 다음 프레임과의 가운데 4.05 → floor 값 108,050
  const k = row(10, 104_000, 4000, 'v2');
  const frames = frameTimes(0, 0, 122);
  const pick = pickOf(k, 0, frames, 121);
  const judge = new Map<number, JudgeMedia>([[10, { videoDts: 0, m: 0, e: 0, frames }]]);

  it('이어진 틈이면 108,050 그대로 · clip 이 (k, EXTINF) 로 접어 그 프레임은 빠진다(한계 R-h)', () => {
    const next = row(11, 111_000, 4000, 'v2');

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next))) as number;

    expect(outMs).toBe(108_050);
    const last = newPathLastFrame(judgeRows([k, next], [false, true]), judge, outMs, 'VIDEO_START');
    expect(last?.pts).toBe(frames[119]);
  });

  it('끊긴 틈(DISCONTINUITY 로 런이 갈림)이면 그대로 두어 clip 이 조각 끝까지 담는다', () => {
    const next = row(11, 111_000, 4000, 'v2');
    const list: EditorPlaylist = { runs: [[k], [next]], rows: [k, next] };

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(list)) as number;

    expect(outMs).toBe(108_050);
    expect(
      newPathLastFrame(judgeRows([k, next], [false, false]), judge, outMs, 'VIDEO_START')?.pts,
    ).toBe(pick.pts);
  });

  it('회차 안 H8 끊김(표지 없는 PDT 건너뜀)도 끊긴 틈과 같다', () => {
    const next = row(11, 111_000, 4000, 'v2');

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next))) as number;

    expect(outMs).toBe(108_050);
    expect(
      newPathLastFrame(judgeRows([k, next], [false, false]), judge, outMs, 'VIDEO_START')?.pts,
    ).toBe(pick.pts);
  });

  it('목록 마지막 조각이면 108,000 으로 맞추고 알린다 — clip 이 AfterLast 로 거절하지 않는다', () => {
    const info = vi.spyOn(console, 'info').mockImplementation(() => {});

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k))) as number;

    expect(outMs).toBe(108_000);
    expect(info).toHaveBeenCalledTimes(1);
    expect(newPathLastFrame(judgeRows([k], [false]), judge, outMs, 'VIDEO_START')).not.toBeNull();
    expect(newPathLastFrame(judgeRows([k], [false]), judge, 108_050, 'VIDEO_START')).toBeNull();
  });

  it('옛 경로에서는 틈 안 out 넘침이 100ms 를 넘으면 SOURCE_RANGE 다(한계 — 맞춤 없이 그대로 둠)', () => {
    const next = row(11, 111_000, 4000, 'v2');
    const wide = frameTimes(0, 0, 126);
    const late = pickOf(k, 0, wide, 125);
    const rows = judgeRows([k, next], [false, true]);

    const small = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next))) as number;
    const large = cutMsOf({ kind: 'pick', pick: late }, 'end', ctxOf(listOf(k, next))) as number;

    expect(large).toBe(108_183);
    expect(oldPathRange(rows, 105_000, small)).toBe('ok');
    expect(oldPathRange(rows, 105_000, large)).toBe('SOURCE_RANGE');
  });

  it('판 NULL 목록 마지막 조각의 손실은 m + 영상 길이 + c − EXTINF 다', () => {
    // m 0.2 · 영상 길이 3.8(114 프레임) · c 2 프레임 · EXTINF 4.0 → 손실 0.0667(2 프레임)
    vi.spyOn(console, 'info').mockImplementation(() => {});
    const v1 = row(40, 600_000, 4000, null);
    const v1Frames = frameTimes(18_000, 6000, 114);
    const lastPick = pickOf(v1, 0.2, v1Frames, 113);
    const heads = headsOf({ 40: ready({ hd: 0, hp: 0, videoDts: 0.2 }) });
    const v1Judge = new Map([[40, { videoDts: 0.2, m: 0.2, e: 0, frames: v1Frames }]]);

    const outMs = cutMsOf(
      { kind: 'pick', pick: lastPick },
      'end',
      ctxOf(listOf(v1), heads),
    ) as number;

    expect(outMs).toBe(604_000);
    const kept = newPathLastFrame(judgeRows([v1], [false]), v1Judge, outMs, 'FILE_HEAD')!.pts;
    const lostSeconds = lastPick.pts + lastPick.dur - (kept + FRAME_TICKS / TICKS);
    expect(lostSeconds).toBeCloseTo(Math.max(0, 0.2 + 3.8 + 6000 / TICKS - 4.0), 9);
  });
});

describe('cutMsOf — 고름 수명(계획 prw2 P-4′ · 처분 PK1)', () => {
  const prev = row(9, 100_000, 4000, 'v2');
  const k = row(10, 104_000, 4000, 'v2');
  const next = row(11, 108_000, 4000, 'v2');
  const frames = frameTimes(0, 0, 122);
  const inPick = pickOf(k, 0, frames, 30);
  const outPick = pickOf(k, 0, frames, 121);

  it('목록이 미끄러져 앞 조각이 빠져도 k 가 남으면 같은 값이다', () => {
    const before = ctxOf(listOf(prev, k, next));
    const after = ctxOf(listOf(k, next));

    expect(cutMsOf({ kind: 'pick', pick: inPick }, 'start', after)).toBe(
      cutMsOf({ kind: 'pick', pick: inPick }, 'start', before),
    );
    expect(cutMsOf({ kind: 'pick', pick: outPick }, 'end', after)).toBe(
      cutMsOf({ kind: 'pick', pick: outPick }, 'end', before),
    );
  });

  it('k 가 최신 목록에서 빠지면 outsideList 다(실행취소로 돌아온 고름도 같다)', () => {
    const slid = ctxOf(listOf(next));

    expect(cutMsOf({ kind: 'pick', pick: inPick }, 'start', slid)).toBe('outsideList');
    expect(cutMsOf({ kind: 'pick', pick: outPick }, 'end', slid)).toBe('outsideList');
  });

  it('저장 때 다시 받은 목록에 다음 행이 새로 생기면 목록 마지막 맞춤이 풀린다', () => {
    vi.spyOn(console, 'info').mockImplementation(() => {});
    const gapNext = row(11, 111_000, 4000, 'v2');

    expect(cutMsOf({ kind: 'pick', pick: outPick }, 'end', ctxOf(listOf(k)))).toBe(108_000);
    expect(cutMsOf({ kind: 'pick', pick: outPick }, 'end', ctxOf(listOf(k, gapNext)))).toBe(
      108_050,
    );
  });

  it('저장 때 목록을 다시 받지 못했으면 들고 있는 목록으로 맞추지 않고 listUnavailable 이다', () => {
    expect(cutMsOf({ kind: 'pick', pick: outPick }, 'end', ctxOf(null))).toBe('listUnavailable');
    expect(cutMsOf({ kind: 'pick', pick: inPick }, 'start', ctxOf(null))).toBe('listUnavailable');
  });

  it('S 입력(고름 없음)은 목록 · 머리 없이 floor(base + 1000·S) 다 — in 도 올리지 않는다', () => {
    expect(cutMsOf({ kind: 's', s: 12.3456 }, 'start', ctxOf(null))).toBe(BASE + 12_345);
    expect(cutMsOf({ kind: 's', s: 12.3456 }, 'end', ctxOf(null))).toBe(BASE + 12_345);
  });

  it('S 입력이 정수 ms 에 떨어지면(1.005 — 1000·S 는 부동소수로 1004.9999999999999) 1ms 앞으로 내려가지 않는다', () => {
    expect(cutMsOf({ kind: 's', s: 1.005 }, 'start', ctxOf(null))).toBe(BASE + 1005);
  });
});
