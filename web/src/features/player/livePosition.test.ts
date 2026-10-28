import { describe, expect, it } from 'vitest';
import type { SeekableLike } from './dvrWindow';
import { nextLivePosition, playedRunMs, type LivePosition } from './livePosition';

// 재생기 자리의 다음 값(POK-326) — 같은 실제 초 · 같은 시차면 앞 값(같은 객체)을 둬 리렌더를 건너뛴다

describe('nextLivePosition', () => {
  const prev: LivePosition = {
    atMs: 10_200,
    edgeAtMs: 40_000,
    behindSeconds: 30,
    playing: true,
    runMs: 4_000,
  };

  it('같은 실제 초 안이고 시차도 같으면 앞 값을 그대로 둔다', () => {
    expect(nextLivePosition(prev, 10_900, 40_500, 30, true, 4_700)).toBe(prev);
  });

  it('초가 바뀌면 새 값이다 — 시각은 ms 그대로 반올림한다', () => {
    expect(nextLivePosition(prev, 11_000.4, 40_999.6, 30, true, 4_800.4)).toEqual({
      atMs: 11_000,
      edgeAtMs: 41_000,
      behindSeconds: 30,
      playing: true,
      runMs: 4_800,
    });
  });

  it('같은 초여도 시차가 바뀌면 새 값이다(시크)', () => {
    expect(nextLivePosition(prev, 10_500, 40_000, 20, true, 0)).toEqual({
      atMs: 10_500,
      edgeAtMs: 40_000,
      behindSeconds: 20,
      playing: true,
      runMs: 0,
    });
  });

  it('같은 초여도 재생이 멈추면 새 값을 한 번 낸다 — 사람의 일시정지(POK-200 c13)', () => {
    const paused = nextLivePosition(prev, 10_400, 40_000, 30, false, 4_200);
    expect(paused).toEqual({ ...prev, atMs: 10_400, playing: false, runMs: 4_200 });
    expect(nextLivePosition(paused, 10_600, 40_000, 30, false, 4_200)).toBe(paused);
  });

  it('같은 초 안에서 연속 길이만 바뀌면 앞 값을 둔다 — 다음 초의 값이 드러낸다', () => {
    expect(nextLivePosition(prev, 10_300, 40_000, 30, true, 0)).toBe(prev);
  });

  it('앞 값이 없으면 새 값이다', () => {
    expect(nextLivePosition(null, 10_200, 40_000, 30, true, 4_000)).toEqual(prev);
  });
});

/** [시작, 끝] 쌍들로 만든 played 흉내 */
function played(...ranges: [number, number][]): SeekableLike {
  return {
    length: ranges.length,
    start: (i) => ranges[i]![0],
    end: (i) => ranges[i]![1],
  };
}

describe('playedRunMs — 지금 위치를 담은 played 범위의 길이(T24)', () => {
  it('범위 하나 [0, 10] · 위치 10 → 10000', () => {
    expect(playedRunMs(played([0, 10]), 10)).toBe(10_000);
  });

  it('끊긴 두 범위에서는 지금 위치를 담은 범위만 센다 — [5.1, 8] · 위치 8 → 2900', () => {
    expect(playedRunMs(played([0, 5], [5.1, 8]), 8)).toBe(2_900);
  });

  it('맞닿아 합쳐진 범위는 통째로 센다 — [0, 8] · 위치 8 → 8000', () => {
    expect(playedRunMs(played([0, 8]), 8)).toBe(8_000);
  });

  it('지금 위치를 담은 범위가 없으면 0', () => {
    expect(playedRunMs(played(), 3)).toBe(0);
    expect(playedRunMs(played([0, 5]), 7)).toBe(0);
  });

  it('범위 끝에서 0.05초 안이면 그 범위다 — 재생 중 played 끝이 위치보다 조금 늦다', () => {
    expect(playedRunMs(played([0, 10]), 10.04)).toBe(10_000);
    expect(playedRunMs(played([0, 10]), 10.06)).toBe(0);
  });
});
