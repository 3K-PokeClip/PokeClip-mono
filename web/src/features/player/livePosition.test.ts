import { describe, expect, it } from 'vitest';
import { nextLivePosition, type LivePosition } from './livePosition';

// 재생기 자리의 다음 값(POK-326) — 같은 실제 초 · 같은 시차면 앞 값(같은 객체)을 둬 리렌더를 건너뛴다

describe('nextLivePosition', () => {
  const prev: LivePosition = { atMs: 10_200, edgeAtMs: 40_000, behindSeconds: 30 };

  it('같은 실제 초 안이고 시차도 같으면 앞 값을 그대로 둔다', () => {
    expect(nextLivePosition(prev, 10_900, 40_500, 30)).toBe(prev);
  });

  it('초가 바뀌면 새 값이다 — 시각은 ms 그대로 반올림한다', () => {
    expect(nextLivePosition(prev, 11_000.4, 40_999.6, 30)).toEqual({
      atMs: 11_000,
      edgeAtMs: 41_000,
      behindSeconds: 30,
    });
  });

  it('같은 초여도 시차가 바뀌면 새 값이다(시크)', () => {
    expect(nextLivePosition(prev, 10_500, 40_000, 20)).toEqual({
      atMs: 10_500,
      edgeAtMs: 40_000,
      behindSeconds: 20,
    });
  });

  it('앞 값이 없으면 새 값이다', () => {
    expect(nextLivePosition(null, 10_200, 40_000, 30)).toEqual(prev);
  });
});
