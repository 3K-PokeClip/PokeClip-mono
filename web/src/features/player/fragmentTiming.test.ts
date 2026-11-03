import { describe, expect, it } from 'vitest';
import { mediaTimeAt, programDateAt, type DatedFragment } from './dvrWindow';
import {
  LIVE_PTS_CORRECTION,
  TIMING_TAG,
  datedFragments,
  datedFragmentsWith,
  type HlsFragmentLike,
  type StartOf,
} from './fragmentTiming';
import { loadedList } from './playbackEdge';

// hls.js 조각 → 환산용 조각(설계 r7 3.2 · 10절 c4 · r6 4.3 표 · 5.1 cx-5 반례). 판 2 조각은 영상 축(start = v_k ·
// duration = 원래 EXTINF), 판 NULL · 태그 없는 목록 · 라이브는 지금 식 글자 그대로다. 조각은 흉내 조각이고 v_k 는
// 시험이 sn 별로 준다(videoStarts 의 기록 몫).

const T = Date.parse('2026-11-03T05:00:00.000Z');

interface FragSpec {
  sn: number;
  /** hls.js 의 frag.start(해체 뒤면 기준 트랙 시작 · 앞이면 추정) */
  start: number;
  /** hls.js 의 frag.duration(해체 뒤면 모든 트랙 끝까지) */
  duration?: number;
  /** 원래 EXTINF(초) — 없으면 INF 태그를 싣지 않는다 */
  inf?: number;
  pdt: number | null;
  cc?: number;
  /** 이 조각에 실린 판 태그 값 */
  timing?: string;
  gap?: boolean;
}

function frag(spec: FragSpec): HlsFragmentLike {
  const tagList: string[][] = [];
  if (spec.timing !== undefined) tagList.push([TIMING_TAG, spec.timing]);
  if (spec.inf !== undefined) tagList.push(['INF', spec.inf.toFixed(3)]);
  return {
    sn: spec.sn,
    cc: spec.cc ?? 0,
    start: spec.start,
    duration: spec.duration ?? spec.inf ?? 4,
    programDateTime: spec.pdt,
    gap: spec.gap,
    tagList,
  };
}

/** sn → v_k(초). 표에 없으면 그 조각을 아직 해체하지 않았다 */
function startsFrom(table: Record<number, number>): StartOf {
  return (f) => {
    const videoStartS = typeof f.sn === 'number' ? table[f.sn] : undefined;
    return videoStartS === undefined ? null : { videoStartS, firstCtoS: 1 / 15 };
  };
}

const NO_STARTS = startsFrom({});

/** 환산이 읽는 칸만 */
function timing(fragments: readonly DatedFragment[]) {
  return fragments.map(({ start, duration, programDateTime, gap, videoAxis }) => ({
    start,
    duration,
    programDateTime,
    gap,
    videoAxis,
  }));
}

describe('datedFragments — 판 NULL · 태그 없는 목록은 hls.js 조각 값 그대로(출시 전제)', () => {
  // 해체된 조각이라 hls.js 가 start · duration 을 소리 기준 · 모든 트랙 끝으로 덮어 EXTINF 와 다르다
  const untagged = [
    frag({ sn: 7, start: 0, duration: 4.3, inf: 4, pdt: T }),
    frag({ sn: 8, start: 4.3, duration: 4.2, inf: 4, pdt: T + 4_000, gap: true }),
    frag({ sn: 9, start: 8.5, duration: 3.9, inf: 4, pdt: null }),
  ];
  // 바이트는 읽었어도(v_k 를 알아도) 판 NULL 이면 쓰지 않는다
  const known = startsFrom({ 7: 0.2, 8: 4.4, 9: 8.6 });
  const asIs = untagged.map(({ start, duration, programDateTime, gap }) => ({
    start,
    duration,
    programDateTime,
    gap,
    videoAxis: undefined,
  }));

  it.each(['rewind', 'vod', 'live'] as const)('%s: 태그 없는 목록', (kind) => {
    expect(timing(datedFragments(untagged, kind, known))).toEqual(asIs);
  });

  it('태그 값 0(판 NULL 로 바뀐 조각)부터도 그대로다', () => {
    const tagged = [
      frag({ sn: 7, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '0' }),
      ...untagged.slice(1),
    ];
    expect(timing(datedFragments(tagged, 'rewind', known))).toEqual(asIs);
  });

  it('빈 목록은 빈 목록이다', () => {
    expect(datedFragments([], 'rewind', known)).toEqual([]);
  });
});

describe('datedFragments — 판 2 해체된 조각은 영상 축(v_k · 원래 EXTINF)', () => {
  it('머리 앞섬(v_k − frag.start)이 조각마다 달라도 조각마다 제 v_k 다', () => {
    const fragments = [
      frag({ sn: 0, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '2' }),
      frag({ sn: 1, start: 4.3, duration: 4.05, inf: 4.1, pdt: T + 4_000 }),
      frag({ sn: 2, start: 8.35, duration: 4.4, inf: 3.9, pdt: T + 8_100, gap: true }),
    ];
    const starts = startsFrom({ 0: 0.2, 1: 4.25, 2: 8.6 });
    expect(timing(datedFragments(fragments, 'rewind', starts))).toEqual([
      { start: 0.2, duration: 4, programDateTime: T, gap: undefined, videoAxis: true },
      { start: 4.25, duration: 4.1, programDateTime: T + 4_000, gap: undefined, videoAxis: true },
      { start: 8.6, duration: 3.9, programDateTime: T + 8_100, gap: true, videoAxis: true },
    ]);
  });

  it('vod 도 같다', () => {
    const fragments = [frag({ sn: 0, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '2' })];
    expect(timing(datedFragments(fragments, 'vod', startsFrom({ 0: 0.2 })))).toEqual([
      { start: 0.2, duration: 4, programDateTime: T, gap: undefined, videoAxis: true },
    ]);
  });

  it('라이브는 지금 식이다(구성 시차 스위치 기본 OFF) — 판 태그 · v_k 가 있어도 쓰지 않는다', () => {
    const fragments = [frag({ sn: 0, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '2' })];
    expect(timing(datedFragments(fragments, 'live', startsFrom({ 0: 0.2 })))).toEqual([
      { start: 0, duration: 4.3, programDateTime: T, gap: undefined, videoAxis: undefined },
    ]);
  });

  it('r6 cx-5 반례: 재접속 틈 400ms — 3900 · 4.2 · 영상 끝', () => {
    // 앞 조각 PDT 0 · v 0.2 · EXTINF 4.0, 뒤 조각 PDT 4.4 · frag.start 4.0 · v 4.2(③ 영상은 이어짐)
    const fragments = datedFragments(
      [
        frag({ sn: 0, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '2' }),
        frag({ sn: 1, start: 4, duration: 4.3, inf: 4, pdt: T + 4_400 }),
      ],
      'rewind',
      startsFrom({ 0: 0.2, 1: 4.2 }),
    );
    expect(programDateAt(fragments, 4.1)).toBeCloseTo(T + 3_900, 6);
    expect(mediaTimeAt(fragments, T + 4_200)).toBe(4.2);
    // hls.js 가 덮은 frag.duration(소리 꼬리까지 4.3)이 아니라 원래 EXTINF 로 끝을 잰다
    expect(loadedList(fragments, 4)).toMatchObject({ lastEndMs: T + 8_400, headSpanMs: 8_000 });
  });
});

describe('datedFragments — 판 2 해체 전 조각(r6 4.3 표 셋째 · 넷째 줄)', () => {
  const INFS = [4, 3.9, 4.1, 4, 4.2];
  function fiveFragments(cc: (sn: number) => number = () => 0) {
    return INFS.map((inf, sn) =>
      frag({
        sn,
        cc: cc(sn),
        start: sn * 4,
        inf,
        pdt: T + sn * 4_000,
        timing: sn === 0 ? '2' : undefined,
      }),
    );
  }

  it('같은 cc 의 해체 조각에서 EXTINF 합으로 앞뒤를 잇는다', () => {
    const starts = timing(datedFragments(fiveFragments(), 'rewind', startsFrom({ 2: 8.3 })));
    expect(starts.map((f) => f.start)).toEqual([
      expect.closeTo(8.3 - 3.9 - 4, 9),
      expect.closeTo(8.3 - 3.9, 9),
      8.3,
      expect.closeTo(8.3 + 4.1, 9),
      expect.closeTo(8.3 + 4.1 + 4, 9),
    ]);
    expect(starts.map((f) => f.duration)).toEqual(INFS);
    expect(starts.every((f) => f.videoAxis === true)).toBe(true);
  });

  it('가장 가까운 해체 조각을 쓴다 — 거리가 같으면 앞 조각이다', () => {
    const starts = timing(
      datedFragments(fiveFragments(), 'rewind', startsFrom({ 0: 0.1, 4: 16.5 })),
    ).map((f) => f.start);
    expect(starts[1]).toBeCloseTo(0.1 + 4, 9);
    expect(starts[2]).toBeCloseTo(0.1 + 4 + 3.9, 9);
    expect(starts[3]).toBeCloseTo(16.5 - 4, 9);
  });

  it('그 cc 를 하나도 해체하지 않았으면 frag.start(hls.js 추정) · 원래 EXTINF 다', () => {
    // 0 · 1 은 cc 0(0 해체), 2 부터 cc 1(해체 없음) — cc 를 넘어 잇지 않는다
    const fragments = fiveFragments((sn) => (sn < 2 ? 0 : 1));
    const starts = timing(datedFragments(fragments, 'rewind', startsFrom({ 0: 0.1 })));
    expect(starts.map((f) => f.start)).toEqual([0.1, expect.closeTo(4.1, 9), 8, 12, 16]);
    expect(starts.map((f) => f.duration)).toEqual(INFS);
  });

  it('아무 조각도 해체하지 않은 첫 목록은 hls.js 첫 값과 같다(시작 자리 — hlsStart 무변경의 근거)', () => {
    const starts = timing(datedFragments(fiveFragments(), 'rewind', NO_STARTS));
    expect(starts.map((f) => f.start)).toEqual([0, 4, 8, 12, 16]);
    expect(starts.map((f) => f.duration)).toEqual(INFS);
  });
});

describe('datedFragments — 판 섞임', () => {
  it('판 NULL 조각은 그대로 넘기고, 같은 cc 추정은 판 NULL 조각에서 끊긴다', () => {
    const fragments = [
      frag({ sn: 0, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '2' }),
      frag({ sn: 1, start: 4.3, duration: 4.2, inf: 4, pdt: T + 4_000, timing: '0' }),
      frag({ sn: 2, start: 8.5, duration: 4.1, inf: 4, pdt: T + 8_000, timing: '2' }),
    ];
    expect(timing(datedFragments(fragments, 'rewind', startsFrom({ 0: 0.2 })))).toEqual([
      { start: 0.2, duration: 4, programDateTime: T, gap: undefined, videoAxis: true },
      {
        start: 4.3,
        duration: 4.2,
        programDateTime: T + 4_000,
        gap: undefined,
        videoAxis: undefined,
      },
      { start: 8.5, duration: 4, programDateTime: T + 8_000, gap: undefined, videoAxis: true },
    ]);
  });

  it('판 2 인데 원래 EXTINF 가 없는 조각은 판 NULL 처럼 지금 식이고 추정도 끊긴다', () => {
    const fragments = [
      frag({ sn: 0, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '2' }),
      frag({ sn: 1, start: 4.3, duration: 4.2, pdt: T + 4_000 }),
      frag({ sn: 2, start: 8.5, duration: 4.1, inf: 4, pdt: T + 8_000 }),
    ];
    expect(timing(datedFragments(fragments, 'rewind', startsFrom({ 0: 0.2 })))).toEqual([
      { start: 0.2, duration: 4, programDateTime: T, gap: undefined, videoAxis: true },
      {
        start: 4.3,
        duration: 4.2,
        programDateTime: T + 4_000,
        gap: undefined,
        videoAxis: undefined,
      },
      { start: 8.5, duration: 4, programDateTime: T + 8_000, gap: undefined, videoAxis: true },
    ]);
  });
});

describe('datedFragments — 겹침(B8) 목록', () => {
  // 새 장의 PDT 가 앞 조각 끝보다 500ms 이르다(1초 미만 부분 겹침). 영상은 이어진다
  const fragments = datedFragments(
    [
      frag({ sn: 0, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '2' }),
      frag({ sn: 1, start: 4.3, duration: 4.3, inf: 4, pdt: T + 3_500 }),
      frag({ sn: 2, start: 8.6, duration: 4.3, inf: 4, pdt: T + 7_500 }),
    ],
    'rewind',
    startsFrom({ 0: 0, 1: 4, 2: 8 }),
  );

  it('시각 → 자리는 나중 조각이다', () => {
    expect(mediaTimeAt(fragments, T + 3_700)).toBeCloseTo(4.2, 9);
  });

  it('목록 끝 · 창 하한 여유는 원래 EXTINF 로 잰다', () => {
    expect(loadedList(fragments, 4)).toEqual({
      firstMs: T,
      lastEndMs: T + 11_500,
      targetDurationS: 4,
      headSpanMs: 12_000,
    });
  });
});

/** sn → [v_k, c_k](초). 표에 없으면 그 조각을 아직 해체하지 않았다 */
function startsWithCto(table: Record<number, [number, number]>): StartOf {
  return (f) => {
    const entry = typeof f.sn === 'number' ? table[f.sn] : undefined;
    return entry === undefined ? null : { videoStartS: entry[0], firstCtoS: entry[1] };
  };
}

describe('datedFragmentsWith — 라이브 구성 시차 스위치(설계 r7 5.4 · 10절 c5)', () => {
  // 라이브 목록(MediaMTX)은 판 태그가 없다. hls.js 의 frag.start 는 v_k(DTS 자리 — T-3b)다
  function liveFragments(cc: (sn: number) => number = () => 0) {
    return [0, 1, 2, 3, 4].map((sn) =>
      frag({ sn, cc: cc(sn), start: sn * 2, duration: 2, inf: 2, pdt: T + sn * 2_000 }),
    );
  }
  const C = 1 / 15;

  it('기본값은 OFF 다(지금 prod 동작)', () => {
    expect(LIVE_PTS_CORRECTION).toBe(false);
  });

  it('OFF 는 받은 배열 그대로다 — v_k · c 를 알아도(지금 식과 바이트 단위로 같음)', () => {
    const fragments = liveFragments();
    const starts = startsWithCto({ 0: [0, C], 1: [2, C] });
    expect(datedFragmentsWith(fragments, 'live', starts, false)).toBe(fragments);
    expect(datedFragments(fragments, 'live', starts)).toBe(fragments);
  });

  it('ON: 해체된 조각은 start = v_k + c_k 다 · 길이 · 시각 · 틈 표시는 hls.js 조각 그대로', () => {
    const fragments = [
      frag({ sn: 0, start: 0.001, duration: 2.1, inf: 2, pdt: T }),
      frag({ sn: 1, start: 2.1, duration: 1.9, inf: 2, pdt: T + 2_000, gap: true }),
    ];
    const starts = startsWithCto({ 0: [0, C], 1: [2, 0.1] });
    expect(timing(datedFragmentsWith(fragments, 'live', starts, true))).toEqual([
      { start: C, duration: 2.1, programDateTime: T, gap: undefined, videoAxis: undefined },
      { start: 2.1, duration: 1.9, programDateTime: T + 2_000, gap: true, videoAxis: undefined },
    ]);
  });

  it('ON: 재생 위치 t 의 시각은 PDT + (t − (v_k + c_k)) 다(5.1 표)', () => {
    const fragments = datedFragmentsWith(
      liveFragments(),
      'live',
      startsWithCto({ 1: [2, C] }),
      true,
    );
    expect(programDateAt(fragments, 2.5)).toBeCloseTo(T + 2_000 + (0.5 - C) * 1000, 6);
    expect(mediaTimeAt(fragments, T + 2_000)).toBeCloseTo(2 + C, 9);
  });

  it('ON: 해체 전 조각은 같은 cc 의 가장 가까운 해체 조각 c 를 frag.start 에 더한다 — 거리가 같으면 앞 조각', () => {
    const starts = startsWithCto({ 1: [2, 0.05], 3: [6, 0.1] });
    const out = datedFragmentsWith(liveFragments(), 'live', starts, true);
    expect(out.map((f) => f.start)).toEqual([
      expect.closeTo(0.05, 9),
      expect.closeTo(2.05, 9),
      expect.closeTo(4.05, 9),
      expect.closeTo(6.1, 9),
      expect.closeTo(8.1, 9),
    ]);
  });

  it('ON: 같은 cc 에 해체 조각이 없으면 frag.start 다 — cc 를 넘어 빌리지 않는다', () => {
    // 0 · 1 은 cc 0(해체 없음), 2 부터 cc 1(3 해체)
    const fragments = liveFragments((sn) => (sn < 2 ? 0 : 1));
    const out = datedFragmentsWith(fragments, 'live', startsWithCto({ 3: [6, 0.1] }), true);
    expect(out.map((f) => f.start)).toEqual([
      0,
      2,
      expect.closeTo(4.1, 9),
      expect.closeTo(6.1, 9),
      expect.closeTo(8.1, 9),
    ]);
  });

  it('ON: 아무 조각도 해체하지 않은 첫 목록은 hls.js 값 그대로다(시작 자리 몫 — 5.3 표)', () => {
    const fragments = liveFragments();
    expect(timing(datedFragmentsWith(fragments, 'live', NO_STARTS, true))).toEqual(
      timing(fragments),
    );
  });

  it('ON 이어도 되감기 · 다시보기는 판 2 갈래 그대로다 — c 를 더하지 않는다', () => {
    const fragments = [frag({ sn: 0, start: 0, duration: 4.3, inf: 4, pdt: T, timing: '2' })];
    const starts = startsWithCto({ 0: [0.2, C] });
    for (const kind of ['rewind', 'vod'] as const) {
      expect(timing(datedFragmentsWith(fragments, kind, starts, true))).toEqual([
        { start: 0.2, duration: 4, programDateTime: T, gap: undefined, videoAxis: true },
      ]);
    }
  });
});
