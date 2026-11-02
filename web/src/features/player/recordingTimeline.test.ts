import { describe, expect, it } from 'vitest';
import {
  playableBackFrom,
  playableFrom,
  rebaseTimeline,
  recordingTimeline,
} from './recordingTimeline';

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

  it('같은 스트림키의 앞 방송 녹화는 뺀다 — 방송 시작보다 먼저 끝난 구간', () => {
    const started = T0 + 3_600_000;
    const t = recordingTimeline([span(0, 60), span(3_600, 30)], started, null)!;
    expect(t.startMs).toBe(started);
    expect(t.pieces).toEqual([{ fromSeconds: 0, toSeconds: 30 }]);
  });

  it.each([5 * 60, 30])(
    '종료 %i초 뒤 같은 키로 켠 다음 방송은 뺀다 — 다시보기가 다음 방송으로 이어지면 안 된다(PR #205 codex)',
    (after) => {
      const t = recordingTimeline([span(0, 60), span(60 + after, 30)], T0, T0 + 60_000)!;
      expect(t.pieces).toEqual([{ fromSeconds: 0, toSeconds: 60 }]);
    },
  );

  it.each([90, 30])(
    '시작 %i초 앞에 끝난 앞 방송은 뺀다 — 0초가 앞 방송으로 밀리면 안 된다(PR #205 codex)',
    (before) => {
      const started = T0 + 200_000;
      const t = recordingTimeline([span(0, 200 - before), span(200, 30)], started, null)!;
      expect(t.startMs).toBe(started);
      expect(t.pieces).toHaveLength(1);
    },
  );

  it('녹화기가 시작 편지보다 먼저 서고 종료 뒤에 닫힌 구간은 제 방송이다 — 방송 시간과 겹친다', () => {
    const t = recordingTimeline([span(0, 400)], T0 + 40_000, T0 + 100_000)!;
    expect(t.pieces).toEqual([{ fromSeconds: 0, toSeconds: 400 }]);
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

describe('playableBackFrom — 뒤로 갈 때', () => {
  const t = recordingTimeline([span(0, 14), span(24, 14)], null, null)!;

  it('구간 안이면 그 자리', () => {
    expect(playableBackFrom(t, 30)).toBe(30);
    expect(playableBackFrom(t, 5)).toBe(5);
  });

  it('틈 안이면 앞 구간 끝 1초 전 — 다음 구간 시작으로 올리면 앞 구간에 영영 못 간다(PR #205 codex)', () => {
    expect(playableBackFrom(t, 19)).toBe(13);
    expect(playableBackFrom(t, 14)).toBe(13);
  });

  it('첫 구간 앞이면 첫 구간 시작', () => {
    expect(playableBackFrom(t, -3)).toBe(0);
  });
});

describe('rebaseTimeline — 서버 기준점 축으로', () => {
  it('녹화가 기준점보다 3초 늦게 섰으면 구간이 3초씩 뒤로 간다 — 카드를 누른 자리와 영상이 맞게', () => {
    const t = recordingTimeline([span(0, 14), span(24, 14)], null, null)!;
    const r = rebaseTimeline(t, T0 - 3_000);
    expect(r.startMs).toBe(T0 - 3_000);
    expect(r.pieces).toEqual([
      { fromSeconds: 3, toSeconds: 17 },
      { fromSeconds: 27, toSeconds: 41 },
    ]);
    expect(r.durationSeconds).toBe(41);
  });
});
