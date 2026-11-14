import { describe, expect, it } from 'vitest';
import { playableFrom } from './recordingTimeline';

// 미디어가 있는 구간 여럿 → 한 시간축(POK-253). 틈에는 미디어가 없으므로 한 구간씩 튼다.

describe('playableFrom', () => {
  const t = {
    pieces: [
      { fromSeconds: 0, toSeconds: 14 },
      { fromSeconds: 24, toSeconds: 38 },
    ],
  };

  it('구간 안이면 그 자리부터 그 구간 끝까지 — 틈을 건너 달라 하지 않는다', () => {
    expect(playableFrom(t, 5)).toEqual({ fromSeconds: 5, toSeconds: 14 });
  });

  it('틈 안이면 다음 구간 시작부터 — 틈에는 미디어가 없다', () => {
    expect(playableFrom(t, 18)).toEqual({ fromSeconds: 24, toSeconds: 38 });
  });

  it('구간 끝에서 부르면 다음 구간이다 — 한 구간이 끝나면 이어 튼다', () => {
    expect(playableFrom(t, 14)).toEqual({ fromSeconds: 24, toSeconds: 38 });
  });

  it('마지막 구간 끝을 지나면 null', () => {
    expect(playableFrom(t, 38)).toBeNull();
    expect(playableFrom(t, 99)).toBeNull();
  });
});
