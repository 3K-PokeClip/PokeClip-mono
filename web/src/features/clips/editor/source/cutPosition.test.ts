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
// 그 T 가 담는 프레임은 시험 쪽 판정 함수(@/test/renderJudge — clip · 렌더 규칙을 옮긴 것)로 본다. 첫 프레임은 새 경로 ∈
// {F, F 앞} · 옛 경로 = F, 끝 프레임은 두 경로 모두 F 로 기대한다. 프레임 시각은 실물처럼 90 kHz 틱에서 만든다.

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
  // PDT 100,000 · ③ D3 10.3 · ② D2 0.3 · Hd2 0 · 프레임 PTS 11.0 · 30fps → o = 10.0 · out T = 101,033
  const k = row(7, 100_000, 4000, null);
  const next = row(8, 104_000, 4000, null);
  const frames = frameTimes(927_000, 0, 120);
  const pick = pickOf(k, 10.3, frames, 21);
  const head = ready({ hd: 0, hp: 0, videoDts: 0.3 });
  const judge = new Map<number, JudgeMedia>([[7, { videoDts: 10.3, m: 0.3, e: 0, frames }]]);

  it('고른 프레임의 끝을 원점 o = D3 − m 에서 재어 101,033 을 내고 렌더 끝 프레임이 그 프레임이다', () => {
    const outMs = cutMsOf(
      { kind: 'pick', pick },
      'end',
      ctxOf(listOf(k, next), headsOf({ 7: head })),
    );

    expect(pick.pts).toBeCloseTo(11.0, 9);
    expect(outMs).toBe(101_033);
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
    expect(cutMsOf({ kind: 'pick', pick }, 'end', ctx)).toBe(101_033);
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
    ).toBe(100_733);
  });
});

describe('cutMsOf — 반올림은 in · out 모두 floor 다', () => {
  // 판 NULL · ② 영상 첫 DTS 0 · 머리 앞섬 m 0 · e 0 · ③ 축 = ② 축
  const k = row(3, 200_000, 4000, null);
  const next = row(4, 204_000, 4000, null);
  const frames = frameTimes(0, 0, 120);
  const heads = headsOf({ 3: ready({ hd: 0, hp: 0, videoDts: 0 }) });
  const seg: JudgeMedia = { videoDts: 0, m: 0, e: 0, frames };
  const judge = new Map([[3, seg]]);
  const rows = judgeRows([k, next], [false, true]);

  it('in 프레임 PTS 1.033333 은 1,033 으로 내려 새 경로는 F 앞부터 · 옛 경로는 F 부터 담는다', () => {
    const pick = pickOf(k, 0, frames, 31);

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctxOf(listOf(k, next), heads)) as number;

    expect(inMs).toBe(201_033);
    expect(newPathFirstFrame(rows, judge, inMs, 'FILE_HEAD')?.pts).toBeCloseTo(frames[30]!, 9);
    expect(oldPathFirstFrame(seg, k.pdtMs, inMs)).toBe(pick.pts);
  });

  it('out 은 F 의 끝 1.066667 을 1,066 으로 내려 두 경로 모두 F 는 남고 G 는 빠진다', () => {
    const pick = pickOf(k, 0, frames, 31);

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next), heads)) as number;

    expect(outMs).toBe(201_066);
    expect(newPathLastFrame(rows, judge, outMs, 'FILE_HEAD')?.pts).toBe(pick.pts);
    expect(oldPathLastFrame(seg, k.pdtMs, outMs)).toBe(pick.pts);
  });

  it('정수 ms 에 떨어지는 끝(4.000)은 부동소수 오차로 1ms 앞으로 내려가지 않는다', () => {
    const pick = pickOf(k, 0, frames, 119);

    expect(cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next), heads))).toBe(204_000);
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

  it('in 프레임 1.000 은 PDT + 960 이고 옛 경로 첫 프레임이 F 다', () => {
    const pick = pickOf(k, 0, frames, 28);

    const inMs = cutMsOf({ kind: 'pick', pick }, 'start', ctx) as number;

    expect(pick.pts).toBeCloseTo(1.0, 9);
    expect(inMs).toBe(300_960);
    expect(oldPathFirstFrame(seg, k.pdtMs, inMs)).toBe(pick.pts);
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

  it('이어진 이웃 앞 틈의 108,033 은 107,999 가 되어 렌더 시작이 그 조각 안이다', () => {
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

  it('판 2 겹침 꼬리(다음 PDT 가 EXTINF 끝보다 이름)는 PDT_{k+1} − 1 로 내리고 out 은 그대로 둔다', () => {
    const overlapK = row(30, 500_000, 4000, 'v2');
    const overlapNext = row(31, 503_800, 4000, 'v2');
    const tailPick = pickOf(overlapK, 0, frames, 117);
    const ctx = ctxOf(listOf(overlapK, overlapNext));

    const inMs = cutMsOf({ kind: 'pick', pick: tailPick }, 'start', ctx) as number;

    expect(tailPick.pts).toBeCloseTo(3.9, 9);
    expect(inMs).toBe(503_799);
    expect(cutMsOf({ kind: 'pick', pick: tailPick }, 'end', ctx)).toBe(503_933);
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
  // 판 2 · PDT 104,000 · EXTINF 4,000 · 마지막 프레임 PTS 4.033333 · 끝 4.066667 → floor 값 108,066
  const k = row(10, 104_000, 4000, 'v2');
  const frames = frameTimes(0, 0, 122);
  const pick = pickOf(k, 0, frames, 121);
  const judge = new Map<number, JudgeMedia>([[10, { videoDts: 0, m: 0, e: 0, frames }]]);

  it('이어진 틈이면 108,066 그대로 · clip 이 (k, EXTINF) 로 접어 그 프레임은 빠진다(한계 R-h)', () => {
    const next = row(11, 111_000, 4000, 'v2');

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next))) as number;

    expect(outMs).toBe(108_066);
    const last = newPathLastFrame(judgeRows([k, next], [false, true]), judge, outMs, 'VIDEO_START');
    expect(last?.pts).toBe(frames[119]);
  });

  it('끊긴 틈(DISCONTINUITY 로 런이 갈림)이면 그대로 두어 clip 이 조각 끝까지 담는다', () => {
    const next = row(11, 111_000, 4000, 'v2');
    const list: EditorPlaylist = { runs: [[k], [next]], rows: [k, next] };

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(list)) as number;

    expect(outMs).toBe(108_066);
    expect(
      newPathLastFrame(judgeRows([k, next], [false, false]), judge, outMs, 'VIDEO_START')?.pts,
    ).toBe(pick.pts);
  });

  it('회차 안 H8 끊김(표지 없는 PDT 건너뜀)도 끊긴 틈과 같다', () => {
    const next = row(11, 111_000, 4000, 'v2');

    const outMs = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next))) as number;

    expect(outMs).toBe(108_066);
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
    expect(newPathLastFrame(judgeRows([k], [false]), judge, 108_066, 'VIDEO_START')).toBeNull();
  });

  it('옛 경로에서는 틈 안 out 넘침이 100ms 를 넘으면 SOURCE_RANGE 다(한계 — 맞춤 없이 그대로 둠)', () => {
    const next = row(11, 111_000, 4000, 'v2');
    const wide = frameTimes(0, 0, 126);
    const late = pickOf(k, 0, wide, 125);
    const rows = judgeRows([k, next], [false, true]);

    const small = cutMsOf({ kind: 'pick', pick }, 'end', ctxOf(listOf(k, next))) as number;
    const large = cutMsOf({ kind: 'pick', pick: late }, 'end', ctxOf(listOf(k, next))) as number;

    expect(large).toBe(108_200);
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
      108_066,
    );
  });

  it('저장 때 목록을 다시 받지 못했으면 들고 있는 목록으로 맞추지 않고 listUnavailable 이다', () => {
    expect(cutMsOf({ kind: 'pick', pick: outPick }, 'end', ctxOf(null))).toBe('listUnavailable');
    expect(cutMsOf({ kind: 'pick', pick: inPick }, 'start', ctxOf(null))).toBe('listUnavailable');
  });

  it('S 입력(고름 없음)은 목록 · 머리 없이 floor(base + 1000·S) 다', () => {
    expect(cutMsOf({ kind: 's', s: 12.3456 }, 'start', ctxOf(null))).toBe(BASE + 12_345);
    expect(cutMsOf({ kind: 's', s: 12.3456 }, 'end', ctxOf(null))).toBe(BASE + 12_345);
  });
});
