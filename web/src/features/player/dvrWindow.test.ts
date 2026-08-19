import { describe, expect, it } from 'vitest';
import {
  behindFromCurrentTime,
  dvrRange,
  liveEdgePosition,
  rewindWindowSeconds,
  type SeekableLike,
} from './dvrWindow';
import { LIVE_EDGE_BACKOFF_SECONDS, LIVE_WINDOW_SECONDS, isAtEdge } from './playerMath';

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
    // 스텁 VOD에서 hls.js가 주는 값: HOLD-BACK이 없어 liveSyncDurationCount(3) x
    // targetduration(4) = 12초 뒤. 캡(edge - targetduration = 296)은 걸리지 않는다.
    expect(liveEdgePosition(range, 288)).toBe(288);
    // LL-HLS에서 PART-HOLD-BACK만큼만 뒤인 경우
    expect(liveEdgePosition(range, 298.5)).toBe(298.5);
  });

  it('null이거나 유한하지 않으면 백오프 지점으로 물러난다', () => {
    const fallback = range.end - LIVE_EDGE_BACKOFF_SECONDS;
    expect(liveEdgePosition(range, null)).toBe(fallback);
    expect(liveEdgePosition(range, NaN)).toBe(fallback);
    expect(liveEdgePosition(range, Infinity)).toBe(fallback);
  });

  it('창 밖 값은 창 안으로 클램프한다', () => {
    expect(liveEdgePosition(range, 5000)).toBe(300);
    // 창이 지연폭보다 짧으면 range.start로 붕괴한다 (방송 시작 직후)
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

  it('임계값 미만도 실제 값을 그대로 보고한다 — 뭉개면 seekBy가 그만큼 어긋난다', () => {
    expect(behindFromCurrentTime(range, 295.5, 297)).toBe(2);
    expect(behindFromCurrentTime(range, 294.4, 297)).toBe(3);
  });

  it('DVR 창 상한으로 자른다 — 시크바 ARIA 범위 밖으로 나가지 않게', () => {
    // 1시간 넘게 일시정지하면 currentTime은 멈춘 채 라이브 지점만 계속 전진한다
    const long = { start: 0, end: 90_000 };
    expect(behindFromCurrentTime(long, 0, 80_000)).toBe(LIVE_WINDOW_SECONDS);
  });
});

// POK-31 리뷰 회귀 — "LIVE 복귀 후 실시간 표기가 안 뜬다"는 스냅 지점과 시차 기준점이
// 어긋나서 생겼다. 두 함수를 합성해 스냅 → 재칠 경로를 지나간다.
// 다만 이 합성만으로는 "호출부가 syncPosition을 안 넘기는" 진짜 회귀를 못 잡는다 —
// 그건 syncPosition을 필수 인자로 둬서 타입 검사가 막는다 (dvrWindow.ts 주석 참조).
describe('엣지 스냅 후 재칠해도 엣지로 남는다', () => {
  const range = { start: 0, end: 300 };

  function snapThenRepaint(sync: number | null): number {
    const snapped = liveEdgePosition(range, sync); // seekToBehind(0)이 앉는 지점
    return behindFromCurrentTime(range, snapped, sync); // paint()가 다시 칠하는 시차
  }

  it('liveSyncPosition이 없을 때 (Safari 네이티브)', () => {
    expect(snapThenRepaint(null)).toBe(0);
    expect(isAtEdge(snapThenRepaint(null))).toBe(true);
  });

  it('liveSyncPosition이 임계값보다 훨씬 뒤일 때 (스텁 VOD·일반 HLS·스톨 누적)', () => {
    // 이 경로가 리뷰의 critical — 엣지에서 4초든 12초든 뒤에 앉아도 그게 이 플레이어의 라이브다
    for (const sync of [296, 288, 296.5]) {
      expect(snapThenRepaint(sync)).toBe(0);
      expect(isAtEdge(snapThenRepaint(sync))).toBe(true);
    }
  });

  it('되감기 지점도 같은 기준이라 왕복이 어긋나지 않는다', () => {
    const sync = 288;
    const live = liveEdgePosition(range, sync);
    expect(behindFromCurrentTime(range, live - 60, sync)).toBe(60);
  });
});

describe('rewindWindowSeconds', () => {
  it('방송이 창보다 짧으면 방송 길이가 곧 되감기 폭이다', () => {
    // seekable 0..600, 라이브 지점 590 → 되감을 수 있는 건 590초뿐
    expect(rewindWindowSeconds({ start: 0, end: 600 }, 590)).toBe(590);
  });

  it('창을 넘으면 계약 상한 1시간으로 자른다', () => {
    expect(rewindWindowSeconds({ start: 0, end: 7200 }, 7100)).toBe(LIVE_WINDOW_SECONDS);
  });

  it('라이브 지점이 창 시작으로 붕괴하면 0이다 — 방송 시작 직후', () => {
    // 창이 라이브 지연폭보다 짧으면 liveEdgePosition이 range.start로 클램프된다
    expect(rewindWindowSeconds({ start: 100, end: 102 }, 100)).toBe(0);
  });

  it('syncPosition이 없으면 백오프 지점 기준이다 (Safari 네이티브)', () => {
    expect(rewindWindowSeconds({ start: 0, end: 600 }, null)).toBe(
      600 - LIVE_EDGE_BACKOFF_SECONDS,
    );
  });
});
