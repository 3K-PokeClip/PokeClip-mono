import { describe, expect, it } from 'vitest';
import {
  MAX_RANGE_SECONDS,
  ZOOM_LEVELS,
  MIN_RANGE_SECONDS,
  clampTimelineHeight,
  formatDurationTenths,
  formatLengthLabel,
  formatRangeGauge,
  formatTimecodeTenths,
  fractionToSeconds,
  rangeGaugeFraction,
  rangeLengthSeconds,
  resolveRangeEdge,
  rulerLabel,
  rulerTicks,
  secondsFromPointer,
  secondsToFraction,
  zoomedView,
  lowestUsefulZoom,
  zoomSpan,
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

  it('트랜스포트 길이와 게이지가 같은 값을 말한다 — 반올림 규칙이 하나다', () => {
    // 포인터로 고른 구간은 밀리초 단위라 반올림이 갈리면 12.5초 / 0:12.4로 어긋난다
    expect(formatLengthLabel(12.46)).toBe('12.4초');
    expect(formatDurationTenths(12.46)).toBe('0:12.4');
  });

  it('재생 tick이 만든 부동소수 값에서도 없는 시각을 찍지 않는다', () => {
    // 초와 소수를 따로 반올림하면 1:22:14.10이 나온다
    expect(formatTimecodeTenths(4934.999999999999)).toBe('1:22:15.0');
    expect(formatTimecodeTenths(4928.4)).toBe('1:22:08.4');
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

  it('창이 15초보다 좁으면 눈금을 0.1초까지 적는다: 키우면 같은 초가 되풀이돼 보였다(POK-389)', () => {
    expect(rulerLabel(4907.25, 60)).toBe('1:21:47');
    expect(rulerLabel(4907.25, 2)).toBe('1:21:47.2');
    expect(rulerLabel(4907.99, 2)).toBe('1:21:47.9');
  });

  it('100% 이하는 구간 가운데에 두되 원본 밖으로 나가지 않는다', () => {
    const at = (range: { startSeconds: number; endSeconds: number }, max = DURATION, zoom = 100) =>
      zoomedView({
        range,
        playheadSeconds: range.startSeconds,
        zoom,
        minSeconds: 0,
        maxSeconds: max,
        previous: null,
      });
    // 구간 가운데에 기본 75초 창을 두되 원본 밖으로는 안 나간다
    expect(at({ startSeconds: 200, endSeconds: 210 })).toEqual({
      startSeconds: 167.5,
      endSeconds: 242.5,
    });
    expect(at({ startSeconds: 5, endSeconds: 15 }).startSeconds).toBe(0);
    expect(at({ startSeconds: DURATION - 10, endSeconds: DURATION }).endSeconds).toBe(DURATION);
    // 원본이 창보다 짧으면 전체를 보여준다
    expect(at({ startSeconds: 5, endSeconds: 15 }, 30)).toEqual({
      startSeconds: 0,
      endSeconds: 30,
    });
  });
});

describe('줌·높이', () => {
  it('줌은 단계로 움직이고 끝에서는 멈춘다', () => {
    expect(zoomStep(100, 'in')).toBe(200);
    expect(zoomStep(100, 'out')).toBe(50);
    expect(zoomStep(25, 'out')).toBe(25);
    expect(zoomStep(400, 'in')).toBe(400);
  });

  it('100%는 잡을 수 있는 범위 전체다: 짧은 구간이어도 손잡이를 범위 끝까지 끌 수 있다(로컬 리뷰 3라운드, 사용자 결정)', () => {
    // 5초 구간, 잡을 수 있는 범위 19초(카드 창). 100%가 「구간에 맞춤」이면 한 번 끌어 0.7초밖에 못 늘렸다
    const widths = ZOOM_LEVELS.map(
      (zoom) => +zoomSpan({ startSeconds: 107, endSeconds: 112 }, zoom, 100, 119).toFixed(3),
    );
    // 25% · 50%는 범위보다 넓힐 수 없어 100%와 같고(화면은 그 단추를 끈다), 200% · 400%는 100% 폭의 절반 · 4분의 1
    expect(widths).toEqual([19, 19, 19, 9.5, 4.75]);
  });

  it('범위가 넓으면 25% · 50%가 100%보다 넓다(기본 75초 기준, 범위까지만)', () => {
    const widths = ZOOM_LEVELS.map((zoom) =>
      zoomSpan({ startSeconds: 2000, endSeconds: 2010 }, zoom, 0, DURATION),
    );
    expect(widths).toEqual([300, 150, 75, 37.5, 18.75]);
  });

  it('고를 수 있는 가장 낮은 배율: 범위에 막혀 안 넓어지면 100%, 넓힐 수 있으면 25%(PR #341 codex)', () => {
    expect(lowestUsefulZoom({ startSeconds: 107, endSeconds: 112 }, 100, 119)).toBe(100);
    expect(lowestUsefulZoom({ startSeconds: 2000, endSeconds: 2010 }, 0, DURATION)).toBe(25);
  });

  describe('100%를 넘기면 진짜로 키운다: 구간보다 좁아질 수 있고 재생 위치를 따라간다(POK-389)', () => {
    // 1분 구간, 잡을 수 있는 범위 120초. 100%는 기본 75초(구간 × 1.15 = 69초보다 크다), 400%는 18.75초
    const range = { startSeconds: 100, endSeconds: 160 };
    const span = 75 / 4;
    const base = { range, minSeconds: 70, maxSeconds: 190 };

    it('처음 키우면 재생 위치를 가운데에 두고 폭이 배율만큼 줄어든다', () => {
      const view = zoomedView({ ...base, playheadSeconds: 130, zoom: 400, previous: null });
      expect(view).toEqual({ startSeconds: 130 - span / 2, endSeconds: 130 + span / 2 });
    });

    it('창 안에서 재생 위치를 옮기면 창은 그대로다(누를 때마다 출렁이지 않게)', () => {
      const previous = { startSeconds: 130 - span / 2, endSeconds: 130 + span / 2 };
      expect(zoomedView({ ...base, playheadSeconds: 137, zoom: 400, previous })).toEqual(previous);
    });

    it('창 폭이 밀리초로 안 나눠떨어져도 앞 창을 같은 폭으로 본다: 아니면 재생 내내 가운데로 다시 맞춰 흘러간다(PR #341 codex)', () => {
      // 잡을 수 있는 범위 60.001초(카드 창) → 400% 폭 15.00025초, 양 끝을 ms로 반올림하면 폭이 1ms 안쪽으로 달라진다
      const odd = {
        range: { startSeconds: 10, endSeconds: 20 },
        minSeconds: 0,
        maxSeconds: 60.001,
      };
      const first = zoomedView({ ...odd, playheadSeconds: 30, zoom: 400, previous: null });
      expect(zoomedView({ ...odd, playheadSeconds: 31, zoom: 400, previous: first })).toEqual(
        first,
      );
    });

    it('재생 위치가 창을 앞으로 벗어나면 그 자리가 창의 10%에 오게 넘기고, 뒤로 벗어나면 90%에 오게 넘긴다', () => {
      const previous = { startSeconds: 130 - span / 2, endSeconds: 130 + span / 2 };
      const ahead = zoomedView({ ...base, playheadSeconds: 140, zoom: 400, previous });
      expect(ahead.startSeconds).toBeCloseTo(140 - span * 0.1);
      const behind = zoomedView({ ...base, playheadSeconds: 110, zoom: 400, previous });
      expect(behind.startSeconds).toBeCloseTo(110 - span * 0.9);
    });

    it('잡을 수 있는 범위(min~max) 밖으로는 넘기지 않는다', () => {
      const view = zoomedView({ ...base, playheadSeconds: 189, zoom: 400, previous: null });
      expect(view.endSeconds).toBe(190);
      expect(view.endSeconds - view.startSeconds).toBeCloseTo(span);
    });
  });

  it('타임라인 높이는 범위 안으로 자른다', () => {
    expect(clampTimelineHeight(10)).toBe(120);
    expect(clampTimelineHeight(9999)).toBe(460);
    expect(clampTimelineHeight(Number.NaN)).toBe(190);
  });

  it('화면이 잰 상한이 상수 상한보다 먼저 걸린다 — 미리보기를 밀어내지 않는다', () => {
    expect(clampTimelineHeight(400, 300)).toBe(300);
    expect(clampTimelineHeight(250, 300)).toBe(250);
  });

  it('상한이 상수 최대보다 커도 상수 최대를 넘지 않는다', () => {
    expect(clampTimelineHeight(9999, 9999)).toBe(460);
  });

  it('상한이 최소 높이보다 낮아도 최소 높이는 지킨다 — 타임라인이 사라지지 않는다', () => {
    expect(clampTimelineHeight(300, 40)).toBe(120);
  });

  it('상한을 재지 못하면(비유한값) 상수 상한만 쓴다', () => {
    expect(clampTimelineHeight(9999, Number.POSITIVE_INFINITY)).toBe(460);
    expect(clampTimelineHeight(9999, Number.NaN)).toBe(460);
  });
});
