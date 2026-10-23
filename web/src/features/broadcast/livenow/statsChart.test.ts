import { describe, expect, it } from 'vitest';
import {
  bucketEnd,
  categorySpans,
  clusterPins,
  fmtDuration,
  fmtSeconds,
  layoutFor,
  rangeScale,
  rebucketTo,
  smoothPath,
  timeTicks,
  viewerWindow,
  viewersAt,
  zeroScale,
  type StatsChart,
} from './statsChart';

const T0 = Date.parse('2026-09-26T10:00:00+09:00');

/** 30초 칸 n개짜리 차트(채팅은 칸 번호, 후원은 10칸마다 하나) */
function chart(n: number, bucketSeconds = 30): StatsChart {
  return {
    t0: T0,
    t1: T0 + n * bucketSeconds * 1000,
    bucketSeconds,
    buckets: Array.from({ length: n }, (_, i) => ({
      start: T0 + i * bucketSeconds * 1000,
      chats: i,
      donations: i % 10 === 0 ? 1 : 0,
    })),
  };
}

describe('statsChart: 가로 스크롤 판단(layoutFor)', () => {
  // 넓은 화면(사이드바 펼침)의 그래프 폭 ≈ 1040px
  const WIDE = 1040;

  it('짧은 방송은 폭에 맞춰 늘리고, 막대가 14px 이상 되는 가장 잘은 칸을 고른다', () => {
    const six = layoutFor(chart(12), WIDE); // 6분
    expect(six.scroll).toBe(false);
    expect(six.view.bucketSeconds).toBe(30);
    expect(six.contentW).toBe(WIDE);

    const two = layoutFor(chart(268), WIDE); // 2시간 14분: 30초·1분 칸은 14px이 안 된다
    expect(two.scroll).toBe(false);
    expect(two.view.bucketSeconds).toBe(120);
    expect(WIDE / two.view.buckets.length).toBeGreaterThanOrEqual(14);
  });

  it('2분 칸이 14px보다 좁아지면 스크롤하고, 칸을 2분·14px로 고정한다(1시간 = 420px)', () => {
    const four = layoutFor(chart(504), WIDE); // 4시간 12분
    expect(four.scroll).toBe(true);
    expect(four.view.bucketSeconds).toBe(120);
    expect(four.contentW).toBe(126 * 14);

    // 같은 2시간 14분도 좁은 화면이면 스크롤이다 — 기준은 길이가 아니라 밀도
    expect(layoutFor(chart(268), 630).scroll).toBe(true);
  });

  it('브라우저 글꼴을 키우면(--pc-u 배율) 칸 폭 기준도 같이 커진다', () => {
    expect(layoutFor(chart(268), WIDE, 1).scroll).toBe(false);
    expect(layoutFor(chart(268), WIDE, 1.25).scroll).toBe(true);
  });

  it('서버 칸이 10초면(1시간 이하 지난 방송) 30초로 묶어 그리고, 60초 칸은 30초로 쪼개지 않는다', () => {
    expect(layoutFor(chart(60, 10), WIDE).view.bucketSeconds).toBe(30);
    expect(layoutFor(chart(400, 60), 20_000).view.bucketSeconds).toBe(60);
  });
});

describe('statsChart: 칸 묶기', () => {
  it('묶은 칸은 채팅·후원을 더하고 첫 칸의 시각을 쓴다(합계가 그대로다)', () => {
    const c = chart(9);
    const b = rebucketTo(c, 120);
    expect(b.bucketSeconds).toBe(120);
    expect(b.buckets.map((x) => x.chats)).toEqual([0 + 1 + 2 + 3, 4 + 5 + 6 + 7, 8]);
    expect(b.buckets[1]!.start).toBe(T0 + 4 * 30_000);
    const sum = (ch: StatsChart) => ch.buckets.reduce((a, x) => a + x.chats + x.donations, 0);
    expect(sum(b)).toBe(sum(c));
  });

  it('덜 찬 마지막 묶음의 끝은 t1이다 — 지금보다 뒤까지 그 칸이라고 하지 않는다', () => {
    const b = rebucketTo(chart(9), 120);
    expect(bucketEnd(b, 1)).toBe(T0 + 8 * 30_000);
    expect(bucketEnd(b, 2)).toBe(T0 + 9 * 30_000);
  });

  it('스크롤 폭은 칸 수가 아니라 시간에 비례한다(덜 찬 마지막 칸은 그만큼 짧다)', () => {
    // 4시간 12분 30초 = 2분 칸 126개 + 30초. 칸 수로 재면 127칸 폭이 되어 막대와 시각 눈금이 끝으로 갈수록 어긋났다(PR #316 리뷰)
    const l = layoutFor(chart(505), 1040);
    expect(l.scroll).toBe(true);
    expect(l.view.buckets).toHaveLength(127);
    expect(l.contentW).toBeCloseTo(126.25 * 14);
  });
});

describe('statsChart: 글자', () => {
  it('길이는 분을 먼저 반올림한다 — 「1시간 60분」이 나오지 않는다', () => {
    expect(fmtDuration(119.5)).toBe('2시간 0분');
    expect(fmtDuration(59.6)).toBe('1시간 0분');
    expect(fmtDuration(38)).toBe('38분');
    expect(fmtDuration(96.4)).toBe('1시간 36분');
  });

  it('칸 길이는 나머지 초까지 쓴다', () => {
    expect(fmtSeconds(30)).toBe('30초');
    expect(fmtSeconds(120)).toBe('2분');
    expect(fmtSeconds(90)).toBe('1분 30초');
  });
});

describe('statsChart: 눈금', () => {
  it('막대 눈금은 0에서 시작하고 최고값을 덮는다', () => {
    const s = zeroScale(65, 3);
    expect(s.ticks[0]).toBe(0);
    expect(s.max).toBeGreaterThanOrEqual(65);
    expect(s.ticks).toEqual([0, 25, 50, 75]);
  });

  it('시청자 눈금은 값이 있는 범위만 잡는다', () => {
    const s = rangeScale(1200, 1300, 2);
    expect(s.min).toBeGreaterThan(0);
    expect(s.min).toBeLessThanOrEqual(1200);
    expect(s.max).toBeGreaterThanOrEqual(1300);
  });

  it('방송 중이면 오른쪽 끝이 「지금」이고 그 근처 시각 눈금은 뺀다', () => {
    const ticks = timeTicks(chart(268), 1040, true);
    expect(ticks.at(-1)).toMatchObject({ label: '지금', x: 1040, edge: true });
    expect(ticks.slice(0, -1).every((t) => 1040 - t.x >= 44)).toBe(true);
    expect(timeTicks(chart(268), 1040, false).some((t) => t.label === '지금')).toBe(false);
  });

  it('짧은 방송에서도 눈금 열쇠(시각)가 겹치지 않는다', () => {
    const ticks = timeTicks(chart(4), 1040, false); // 2분
    expect(new Set(ticks.map((t) => t.t)).size).toBe(ticks.length);
  });
});

describe('statsChart: 선', () => {
  it('부드러운 선은 점을 넘어 튀지 않는다(없는 최고·최저를 그리지 않는다)', () => {
    const pts = [
      [0, 50],
      [10, 10],
      [20, 10],
      [30, 90],
      [40, 85],
    ] as const;
    const ys = smoothPath(pts)
      .split(/[MC ]/)
      .filter(Boolean)
      .map((pair) => Number(pair.split(',')[1]));
    expect(Math.min(...ys)).toBeGreaterThanOrEqual(10);
    expect(Math.max(...ys)).toBeLessThanOrEqual(90);
  });

  it('창 앞 관측 하나를 붙여 선이 왼쪽 끝에서 시작한다', () => {
    const v = [
      { t: T0 - 120_000, v: 1 },
      { t: T0 - 60_000, v: 2 },
      { t: T0 + 60_000, v: 3 },
    ];
    expect(viewerWindow(v, T0, T0 + 600_000).map((p) => p.v)).toEqual([2, 3]);
    expect(viewersAt(v, T0)).toBe(2);
    expect(viewersAt(v, T0 - 200_000)).toBeNull();
  });
});

describe('statsChart: 카테고리·핀', () => {
  it('창 앞에서 시작한 카테고리는 창 왼쪽 끝에서 자르고, 창 밖 구간은 뺀다', () => {
    const marks = [
      { t: T0 - 3_600_000, name: '저스트 채팅' },
      { t: T0 + 600_000, name: '리그 오브 레전드' },
      { t: T0 + 9_000_000, name: '발로란트' },
    ];
    expect(categorySpans(marks, T0, T0 + 1_200_000)).toEqual([
      { from: T0, to: T0 + 600_000, name: '저스트 채팅' },
      { from: T0 + 600_000, to: T0 + 1_200_000, name: '리그 오브 레전드' },
    ]);
  });

  it('가까운 핀은 앞 핀 하나로 묶고 몇 장 더 있는지 센다', () => {
    const pins = clusterPins([{ x: 100 }, { x: 10 }, { x: 120 }, { x: 300 }], 44);
    expect(pins.map((p) => [p.x, p.more])).toEqual([
      [10, 0],
      [100, 1],
      [300, 0],
    ]);
  });
});
