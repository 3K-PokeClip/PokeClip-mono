import { describe, expect, it } from 'vitest';
import {
  behindFromCurrentTime,
  dvrRange,
  liveEdgePosition,
  type SeekableLike,
} from './dvrWindow';
import { LIVE_EDGE_BACKOFF_SECONDS, isAtEdge } from './playerMath';

// TimeRanges 호환 plain object — jsdom 미디어 구현 없이 검증한다
function seekable(...ranges: Array<[number, number]>): SeekableLike {
  return {
    length: ranges.length,
    start: (i) => ranges[i]![0],
    end: (i) => ranges[i]![1],
  };
}

describe('dvrRange', () => {
  it('seekable이 비어 있으면 null — 매니페스트 로드 전', () => {
    expect(dvrRange(seekable())).toBeNull();
  });

  it('폭이 0인 범위도 null — 시크할 곳이 없다', () => {
    expect(dvrRange(seekable([120, 120]))).toBeNull();
  });

  it('창보다 좁으면 seekable 그대로 — 스텁(VOD 5분)·방송 초반', () => {
    expect(dvrRange(seekable([0, 300]))).toEqual({ start: 0, end: 300 });
  });

  it('창보다 넓으면 끝에서 1시간으로 자른다 (계약3 4절 4번)', () => {
    expect(dvrRange(seekable([0, 5000]))).toEqual({ start: 1400, end: 5000 });
  });

  it('여러 구간이면 첫 시작·마지막 끝을 쓴다', () => {
    expect(dvrRange(seekable([100, 200], [250, 900]))).toEqual({ start: 100, end: 900 });
  });

  it('창 인자를 줄이면 그만큼만 시크 가능하다', () => {
    expect(dvrRange(seekable([0, 5000]), 600)).toEqual({ start: 4400, end: 5000 });
  });
});

describe('liveEdgePosition', () => {
  const range = { start: 0, end: 300 };

  it('liveSyncPosition이 있으면 그 지점 — 엣지에서 얼마나 뒤든 그대로 쓴다', () => {
    // hls.js는 스텁 VOD에서도 값을 준다: targetduration(4) 캡 → 296
    expect(liveEdgePosition(range, 296)).toBe(296);
    // 일반 HLS의 holdBack(3 x 4s)만큼 뒤인 경우
    expect(liveEdgePosition(range, 288)).toBe(288);
  });

  it('값이 없거나 유한하지 않으면 백오프 지점으로 물러난다', () => {
    const fallback = range.end - LIVE_EDGE_BACKOFF_SECONDS;
    expect(liveEdgePosition(range)).toBe(fallback);
    expect(liveEdgePosition(range, null)).toBe(fallback);
    expect(liveEdgePosition(range, NaN)).toBe(fallback);
    expect(liveEdgePosition(range, Infinity)).toBe(fallback);
  });

  it('창 밖 값은 창 안으로 클램프한다', () => {
    expect(liveEdgePosition(range, 5000)).toBe(300);
    expect(liveEdgePosition({ start: 100, end: 300 }, 50)).toBe(100);
  });
});

describe('behindFromCurrentTime', () => {
  const range = { start: 0, end: 300 };

  it('시차를 정수 초로 반올림한다 — 기준은 라이브 지점', () => {
    expect(behindFromCurrentTime(range, 237, 297)).toBe(60);
    expect(behindFromCurrentTime(range, 236.4, 297)).toBe(61);
  });

  it('라이브 지점을 넘어선 위치는 0으로 클램프한다', () => {
    expect(behindFromCurrentTime(range, 300, 297)).toBe(0);
  });

  it('임계값 미만은 0으로 스냅한다 — 반올림 톱니로 엣지 판정이 깜빡이지 않게', () => {
    expect(behindFromCurrentTime(range, 295.5, 297)).toBe(0);
    expect(behindFromCurrentTime(range, 294.4, 297)).toBe(3);
  });
});

// POK-31 리뷰 회귀 — "LIVE 복귀 후 실시간 표기가 안 뜬다"는 증상은 스냅 지점과
// 시차 기준점이 어긋나서 생겼다. 두 모듈을 합성해 스냅 → 재칠 경로를 그대로 지나간다.
describe('엣지 스냅 후 재칠해도 엣지로 남는다', () => {
  const range = { start: 0, end: 300 };

  function snapThenRepaint(sync?: number | null): number {
    const snapped = liveEdgePosition(range, sync); // seekToBehind(0)이 앉는 지점
    return behindFromCurrentTime(range, snapped, sync); // paint()가 다시 칠하는 시차
  }

  it('liveSyncPosition이 없을 때 (Safari 네이티브)', () => {
    expect(snapThenRepaint()).toBe(0);
    expect(isAtEdge(snapThenRepaint())).toBe(true);
  });

  it('liveSyncPosition이 임계값보다 훨씬 뒤일 때 (스텁 VOD·일반 HLS·스톨 누적)', () => {
    // 이 경로가 리뷰의 critical — 엣지에서 4초든 12초든 뒤에 앉아도 그게 이 플레이어의 라이브다
    for (const sync of [296, 288, 296.5]) {
      expect(snapThenRepaint(sync)).toBe(0);
      expect(isAtEdge(snapThenRepaint(sync))).toBe(true);
    }
  });

  it('스냅 뒤 seekable.end만 파트 단위로 전진해도 엣지를 유지한다', () => {
    const snapped = liveEdgePosition(range, 297);
    // 0.5초짜리 파트가 두 번 붙어 창이 밀린 상황 (currentTime은 재생으로 함께 흐르지 않은 최악)
    const advanced = { start: 0, end: 301 };
    expect(isAtEdge(behindFromCurrentTime(advanced, snapped, 298))).toBe(true);
  });

  it('되감기 지점도 같은 기준이라 왕복이 어긋나지 않는다', () => {
    const sync = 296;
    const live = liveEdgePosition(range, sync);
    expect(behindFromCurrentTime(range, live - 60, sync)).toBe(60);
  });
});
