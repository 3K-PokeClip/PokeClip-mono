import { describe, expect, it } from 'vitest';
import {
  MAX_RANGE_SECONDS,
  MIN_RANGE_SECONDS,
  clampTimelineHeight,
  formatDurationTenths,
  formatRangeGauge,
  formatTimecodeTenths,
  fractionToSeconds,
  rangeGaugeFraction,
  rangeLengthSeconds,
  resolveRangeEdge,
  rulerTicks,
  secondsFromPointer,
  secondsToFraction,
  viewWindow,
  zoomStep,
} from './timelineMath';

const DURATION = 5043; // 시안의 1:24:03 방송
const RANGE = { startSeconds: 4928.4, endSeconds: 4940.8 }; // 1:22:08.4 – 1:22:20.8 = 12.4초
// 시안 구간은 방송 끝까지 115초뿐이라 끝 핸들로는 3분에 닿지 못한다 —
// 경계 자체를 재는 케이스는 앞뒤로 여유가 있는 구간을 쓴다.
const ROOMY = { startSeconds: 600, endSeconds: 612.4 };

describe('resolveRangeEdge', () => {
  it('구간 안쪽 이동은 그대로 적용한다', () => {
    const result = resolveRangeEdge('end', 4950, RANGE, DURATION);
    expect(result.rejection).toBeNull();
    expect(result.range.endSeconds).toBe(4950);
  });

  it('4초로 줄이려 하면 거부하고 구간을 그대로 둔다', () => {
    const result = resolveRangeEdge('end', ROOMY.startSeconds + 4, ROOMY, DURATION);
    expect(result.rejection).toBe('tooShort');
    expect(result.range).toEqual(ROOMY);
  });

  it('3분 1초로 늘리려 하면 거부하고 구간을 그대로 둔다', () => {
    const result = resolveRangeEdge('end', ROOMY.startSeconds + 181, ROOMY, DURATION);
    expect(result.rejection).toBe('tooLong');
    expect(result.range).toEqual(ROOMY);
  });

  it('정확히 5초·3분은 통과한다 — 경계는 열려 있다', () => {
    const min = resolveRangeEdge('end', ROOMY.startSeconds + MIN_RANGE_SECONDS, ROOMY, DURATION);
    expect(min.rejection).toBeNull();
    expect(rangeLengthSeconds(min.range)).toBe(MIN_RANGE_SECONDS);

    const max = resolveRangeEdge('end', ROOMY.startSeconds + MAX_RANGE_SECONDS, ROOMY, DURATION);
    expect(max.rejection).toBeNull();
    expect(rangeLengthSeconds(max.range)).toBe(MAX_RANGE_SECONDS);
  });

  it('시작 핸들도 같은 경계를 쓴다 — 시안 구간에서도 뒤로는 3분까지 닿는다', () => {
    expect(resolveRangeEdge('start', RANGE.endSeconds - 4, RANGE, DURATION).rejection).toBe(
      'tooShort',
    );
    expect(resolveRangeEdge('start', RANGE.endSeconds - 181, RANGE, DURATION).rejection).toBe(
      'tooLong',
    );
  });

  it('원본 밖으로 끌면 길이 위반이 아니라 원본 경계로 자른다', () => {
    const nearHead = { startSeconds: 30, endSeconds: 60 };
    const result = resolveRangeEdge('start', -30, nearHead, DURATION);
    expect(result.rejection).toBeNull();
    expect(result.range.startSeconds).toBe(0);
  });

  it('원본 끝에 막혀 짧아진 구간은 거부가 아니다 — 늘릴 곳이 없을 뿐이다', () => {
    const result = resolveRangeEdge('end', DURATION + 500, RANGE, DURATION);
    expect(result.rejection).toBeNull();
    expect(result.range.endSeconds).toBe(DURATION);
  });
});

describe('표기', () => {
  it('구간 길이를 0:12.4로 적는다', () => {
    expect(formatDurationTenths(12.4)).toBe('0:12.4');
    expect(formatDurationTenths(180)).toBe('3:00.0');
  });

  it('절대 시각을 1:22:08.4로 적는다', () => {
    expect(formatTimecodeTenths(4928.4)).toBe('1:22:08.4');
    expect(formatTimecodeTenths(4940.8)).toBe('1:22:20.8');
  });

  it('게이지 문구에 상한을 함께 적는다', () => {
    expect(formatRangeGauge(12.4)).toBe('0:12.4 / 최대 3:00');
  });

  it('게이지 비율은 상한에서 1로 포화한다', () => {
    expect(rangeGaugeFraction(90)).toBeCloseTo(0.5);
    expect(rangeGaugeFraction(300)).toBe(1);
  });
});

describe('창 환산', () => {
  const view = { startSeconds: 4900, endSeconds: 4975 };

  it('초 ↔ 비율이 왕복한다', () => {
    const fraction = secondsToFraction(4937.5, view);
    expect(fraction).toBeCloseTo(0.5);
    expect(fractionToSeconds(fraction, view)).toBeCloseTo(4937.5);
  });

  it('창 밖의 값은 0..1로 자른다', () => {
    expect(secondsToFraction(4800, view)).toBe(0);
    expect(secondsToFraction(5000, view)).toBe(1);
  });

  it('폭이 0인 창은 NaN 대신 0을 준다', () => {
    expect(secondsToFraction(10, { startSeconds: 10, endSeconds: 10 })).toBe(0);
  });

  it('포인터 좌표를 초로 옮긴다 — 폭이 0이면 계산 불가라 null', () => {
    expect(secondsFromPointer({ left: 0, width: 300 }, 150, view)).toBeCloseTo(4937.5);
    expect(secondsFromPointer({ left: 0, width: 0 }, 150, view)).toBeNull();
  });

  it('눈금은 창을 균등 분할한다 — 줌을 바꾸면 함께 움직인다', () => {
    expect(rulerTicks(view, 6)).toEqual([4900, 4915, 4930, 4945, 4960, 4975]);
  });

  it('보이는 창은 중심을 따라가되 원본 밖으로 나가지 않는다', () => {
    expect(viewWindow(10, 100, DURATION).startSeconds).toBe(0);
    const end = viewWindow(DURATION, 100, DURATION);
    expect(end.endSeconds).toBe(DURATION);
    // 원본이 창보다 짧으면 전체를 보여준다
    expect(viewWindow(5, 100, 30)).toEqual({ startSeconds: 0, endSeconds: 30 });
  });
});

describe('줌·높이', () => {
  it('줌은 단계로 움직이고 끝에서는 멈춘다', () => {
    expect(zoomStep(100, 'in')).toBe(200);
    expect(zoomStep(100, 'out')).toBe(50);
    expect(zoomStep(50, 'out')).toBe(50);
    expect(zoomStep(400, 'in')).toBe(400);
  });

  it('타임라인 높이는 범위 안으로 자른다', () => {
    expect(clampTimelineHeight(10)).toBe(150);
    expect(clampTimelineHeight(9999)).toBe(460);
    expect(clampTimelineHeight(Number.NaN)).toBe(232);
  });
});
