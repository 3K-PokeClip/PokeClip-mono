import { describe, expect, it } from 'vitest';
import { playableFrom, recordingTimeline } from './recordingTimeline';

// 녹화 구간 여럿 → 한 시간축(POK-253). 재생 서버는 틈을 건너 주지 않으므로(실측) 한 구간씩 튼다.

const T0 = Date.parse('2026-09-30T01:50:00Z');
const span = (offsetSeconds: number, durationSeconds: number) => ({
  startMs: T0 + offsetSeconds * 1000,
  durationSeconds,
});

describe('recordingTimeline', () => {
  it('틈이 있어도 축은 첫 구간 시작부터 마지막 구간 끝까지다 — 틈 뒤 카드가 어긋나지 않게', () => {
    const t = recordingTimeline([span(0, 14), span(24, 14)], T0, T0 + 40_000)!;
    expect(t.startMs).toBe(T0);
    expect(t.durationSeconds).toBe(38);
    expect(t.pieces).toEqual([
      { fromSeconds: 0, toSeconds: 14 },
      { fromSeconds: 24, toSeconds: 38 },
    ]);
  });

  it('순서가 섞여 와도 시간순으로 놓는다', () => {
    const t = recordingTimeline([span(24, 14), span(0, 14)], null, null)!;
    expect(t.pieces.map((p) => p.fromSeconds)).toEqual([0, 24]);
  });

  it('겹치거나 맞닿은 구간은 하나로 잇는다', () => {
    const t = recordingTimeline([span(0, 10), span(10, 5), span(12, 10)], null, null)!;
    expect(t.pieces).toEqual([{ fromSeconds: 0, toSeconds: 22 }]);
  });

  it('같은 스트림키의 앞 방송 녹화는 뺀다 — 방송 시작 2분 앞보다 먼저 끝난 구간', () => {
    const started = T0 + 3_600_000;
    const t = recordingTimeline([span(0, 60), span(3_600, 30)], started, null)!;
    expect(t.startMs).toBe(started);
    expect(t.pieces).toEqual([{ fromSeconds: 0, toSeconds: 30 }]);
  });

  it('방송 종료 10분 뒤보다 늦게 시작한 구간(다음 방송)은 뺀다', () => {
    const t = recordingTimeline([span(0, 60), span(60 + 11 * 60, 30)], T0, T0 + 60_000)!;
    expect(t.pieces).toHaveLength(1);
  });

  it('남는 구간이 없으면 null', () => {
    expect(recordingTimeline([], null, null)).toBeNull();
    expect(recordingTimeline([span(0, 10)], T0 + 3_600_000, null)).toBeNull();
  });
});

describe('playableFrom', () => {
  const t = recordingTimeline([span(0, 14), span(24, 14)], null, null)!;

  it('구간 안이면 그 자리부터 그 구간 끝까지 — 틈을 건너 달라 하지 않는다', () => {
    expect(playableFrom(t, 5)).toEqual({ fromSeconds: 5, toSeconds: 14 });
  });

  it('틈 안이면 다음 구간 시작부터 — 틈에서 시작하면 재생 서버가 404다', () => {
    expect(playableFrom(t, 18)).toEqual({ fromSeconds: 24, toSeconds: 38 });
  });

  it('구간 끝에서 부르면 다음 구간이다 — 한 구간이 끝나면 이어 튼다', () => {
    expect(playableFrom(t, 14)).toEqual({ fromSeconds: 24, toSeconds: 38 });
  });

  it('녹화 끝을 지나면 null', () => {
    expect(playableFrom(t, 38)).toBeNull();
    expect(playableFrom(t, 99)).toBeNull();
  });
});
