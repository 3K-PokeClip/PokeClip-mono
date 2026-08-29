import { describe, expect, it } from 'vitest';
import type { VodBroadcast, VodDownloadState } from './useVodListMockState';
import {
  dateLabel,
  ddayFor,
  durationLabel,
  excludeLive,
  filterByPeriod,
  isOpenable,
  qualityOptionsFor,
  rowViewFor,
} from './vodListView';

const NOW = new Date('2026-08-24T21:00:00+09:00');
const DAY_MS = 24 * 60 * 60 * 1000;

function iso(offsetMs: number): string {
  return new Date(NOW.getTime() + offsetMs).toISOString();
}

function broadcast(patch: Partial<VodBroadcast> = {}): VodBroadcast {
  return {
    streamId: 'stream-1',
    status: 'vod_ready',
    relation: 'OWNER',
    startedAt: iso(-3 * DAY_MS),
    endedAt: iso(-3 * DAY_MS),
    vodExpiresAt: iso(57 * DAY_MS),
    ...patch,
  };
}

describe('vodListView — ddayFor', () => {
  it('정확히 3일 남으면 D-3이고 만료 임박이다', () => {
    expect(ddayFor(iso(3 * DAY_MS), NOW)).toEqual({ kind: 'active', label: 'D-3', urgent: true });
  });

  it('3일에서 1ms만 더 남아도 D-4로 올림해 임박에서 빠진다', () => {
    expect(ddayFor(iso(3 * DAY_MS + 1), NOW)).toEqual({
      kind: 'active',
      label: 'D-4',
      urgent: false,
    });
  });

  it('1ms라도 남았으면 D-1이다 — 오늘까지는 아직 볼 수 있다', () => {
    expect(ddayFor(iso(1), NOW)).toEqual({ kind: 'active', label: 'D-1', urgent: true });
  });

  it('만료 시각이 지났으면 expired다 — 기한이 지난 방송도 목록에 남는다', () => {
    expect(ddayFor(iso(0), NOW)).toEqual({ kind: 'expired' });
    expect(ddayFor(iso(-DAY_MS), NOW)).toEqual({ kind: 'expired' });
  });

  it('만료 시각을 모르면 unknown이다 — 준비 중이라 기한이 아직 없다', () => {
    expect(ddayFor(null, NOW)).toEqual({ kind: 'unknown' });
    expect(ddayFor('언젠가', NOW)).toEqual({ kind: 'unknown' });
  });
});

describe('vodListView — dateLabel', () => {
  it('끝난 시각을 「7월 26일」로 적는다', () => {
    expect(dateLabel(broadcast({ endedAt: '2026-07-26T23:10:00+09:00' }))).toBe('7월 26일');
  });

  it('종료 알림이 아직 없으면 시작 시각으로 물러선다', () => {
    const item = broadcast({ endedAt: null, startedAt: '2026-07-24T19:00:00+09:00' });
    expect(dateLabel(item)).toBe('7월 24일');
  });

  it('두 시각이 모두 없으면 null이다 — 화면이 「방송일 미상」을 그린다', () => {
    expect(dateLabel(broadcast({ endedAt: null, startedAt: null }))).toBeNull();
  });
});

describe('vodListView — durationLabel', () => {
  it('시간이 있으면 h:mm:ss로 적는다', () => {
    expect(durationLabel(21690)).toBe('6:01:30');
    expect(durationLabel(15128)).toBe('4:12:08');
  });

  it('한 시간이 안 되면 m:ss다', () => {
    expect(durationLabel(605)).toBe('10:05');
  });

  it('길이를 모르면 null이다 — 준비 중 행이 그렇다', () => {
    expect(durationLabel(null)).toBeNull();
  });
});

describe('vodListView — rowViewFor', () => {
  const idle: VodDownloadState = { kind: 'idle' };

  it('방금 끝난 방송은 준비 중이다', () => {
    expect(rowViewFor(broadcast({ status: 'ended' }), idle, NOW)).toEqual({ kind: 'preparing' });
  });

  it('받는 중이면 진행률을 그대로 싣는다', () => {
    expect(rowViewFor(broadcast(), { kind: 'downloading', progress: 46 }, NOW)).toEqual({
      kind: 'downloading',
      progress: 46,
    });
  });

  it('진행률 0%도 받는 중이다 — 시작하자마자의 상태다', () => {
    expect(rowViewFor(broadcast(), { kind: 'downloading', progress: 0 }, NOW)).toEqual({
      kind: 'downloading',
      progress: 0,
    });
  });

  it('다 받아 뒀으면 완료다', () => {
    expect(rowViewFor(broadcast(), { kind: 'done' }, NOW)).toEqual({ kind: 'done' });
  });

  it('VOD가 준비됐고 받는 중도 아니면 볼 수 있는 행이다', () => {
    expect(rowViewFor(broadcast(), idle, NOW)).toEqual({ kind: 'ready' });
  });

  it('보관 기한이 지났으면 받기 상태와 무관하게 만료다 — 열 것이 없다', () => {
    const gone = broadcast({ vodExpiresAt: iso(-DAY_MS) });
    expect(rowViewFor(gone, idle, NOW)).toEqual({ kind: 'expired' });
    expect(rowViewFor(gone, { kind: 'downloading', progress: 46 }, NOW)).toEqual({
      kind: 'expired',
    });
    expect(rowViewFor(gone, { kind: 'done' }, NOW)).toEqual({ kind: 'expired' });
  });

  it('기한을 모르는 vod_ready는 만료로 접지 않는다 — 모르는 것과 지난 것은 다르다', () => {
    expect(rowViewFor(broadcast({ vodExpiresAt: null }), idle, NOW)).toEqual({ kind: 'ready' });
  });
});

describe('vodListView — isOpenable', () => {
  it('준비 중·만료 행만 뷰어로 못 들어간다 — 받는 중에도 VOD는 이미 있다', () => {
    expect(isOpenable({ kind: 'preparing' })).toBe(false);
    expect(isOpenable({ kind: 'expired' })).toBe(false);
    expect(isOpenable({ kind: 'ready' })).toBe(true);
    expect(isOpenable({ kind: 'downloading', progress: 46 })).toBe(true);
    expect(isOpenable({ kind: 'done' })).toBe(true);
  });
});

describe('vodListView — qualityOptionsFor', () => {
  it('시안 표기값을 그대로 낸다 — 4:12:08이면 6.2GB · 2.4GB · 180MB', () => {
    expect(qualityOptionsFor(15128).map((q) => `${q.label} ${q.size}`)).toEqual([
      '원본 화질 · 1080p60 6.2GB',
      '720p30 2.4GB',
      '오디오만 · MP3 180MB',
    ]);
  });

  it('짧은 방송은 더 작게 적힌다 — 길이와 무관한 고정값이 아니다', () => {
    expect(qualityOptionsFor(3600)[0]!.size).toBe('1.5GB');
  });

  it('길이를 모르면 크기도 모른다 — 지어내지 않는다', () => {
    expect(qualityOptionsFor(null).map((q) => q.size)).toEqual([
      '크기 미상',
      '크기 미상',
      '크기 미상',
    ]);
  });
});

describe('vodListView — excludeLive', () => {
  it('방송 중인 것은 「지난 방송」에 넣지 않는다', () => {
    const items = [
      broadcast({ streamId: 'live', status: 'live' }),
      broadcast({ streamId: 'past' }),
    ];
    expect(excludeLive(items).map((item) => item.streamId)).toEqual(['past']);
  });
});

describe('vodListView — filterByPeriod', () => {
  const items = [
    broadcast({ streamId: 'd1', endedAt: iso(-1 * DAY_MS) }),
    broadcast({ streamId: 'd7-경계', endedAt: iso(-7 * DAY_MS) }),
    broadcast({ streamId: 'd20', endedAt: iso(-20 * DAY_MS) }),
    broadcast({ streamId: 'd45', endedAt: iso(-45 * DAY_MS) }),
    broadcast({ streamId: '시각없음', endedAt: null, startedAt: null }),
  ];
  const noRange = { from: null, to: null };

  it('전체는 시각을 모르는 방송까지 전부 보여준다', () => {
    expect(filterByPeriod(items, 'all', noRange, NOW)).toHaveLength(5);
  });

  it('7일은 경계 시각을 포함한다', () => {
    expect(filterByPeriod(items, '7d', noRange, NOW).map((item) => item.streamId)).toEqual([
      'd1',
      'd7-경계',
    ]);
  });

  it('7일 경계를 1ms라도 넘기면 빠진다', () => {
    const justOut = [broadcast({ streamId: 'out', endedAt: iso(-7 * DAY_MS - 1) })];
    expect(filterByPeriod(justOut, '7d', noRange, NOW)).toHaveLength(0);
  });

  it('30일은 그 안의 방송만 남긴다', () => {
    expect(filterByPeriod(items, '30d', noRange, NOW).map((item) => item.streamId)).toEqual([
      'd1',
      'd7-경계',
      'd20',
    ]);
  });

  it('시각을 모르는 방송은 기간 필터에 안 걸린다 — 전체에서만 보인다', () => {
    for (const filter of ['7d', '30d'] as const) {
      expect(
        filterByPeriod(items, filter, noRange, NOW).map((item) => item.streamId),
      ).not.toContain('시각없음');
    }
  });

  it('기간 지정을 비워 두면 제약이 없다 — 반쯤 입력하는 동안 목록이 비지 않는다', () => {
    expect(filterByPeriod(items, 'custom', noRange, NOW)).toHaveLength(5);
  });

  it('기간 지정은 한쪽만 채워도 그 조건만 건다', () => {
    const onlyFrom = filterByPeriod(items, 'custom', { from: '2026-08-01', to: null }, NOW);
    expect(onlyFrom.map((item) => item.streamId)).toEqual(['d1', 'd7-경계', 'd20']);

    const onlyTo = filterByPeriod(items, 'custom', { from: null, to: '2026-08-01' }, NOW);
    expect(onlyTo.map((item) => item.streamId)).toEqual(['d45']);
  });

  it('기간 지정은 종료일 그 날의 끝까지 포함한다', () => {
    const endedThatDay = [broadcast({ streamId: '그날밤', endedAt: '2026-08-10T23:50:00+09:00' })];
    const range = { from: '2026-08-10', to: '2026-08-10' };
    expect(filterByPeriod(endedThatDay, 'custom', range, NOW)).toHaveLength(1);
  });

  it('시작일이 종료일보다 뒤면 결과가 없다 — 화면이 그 사실을 문장으로 말한다', () => {
    expect(filterByPeriod(items, 'custom', { from: '2026-08-20', to: '2026-08-01' }, NOW)).toEqual(
      [],
    );
  });

  it('못 읽는 날짜는 안 준 것으로 접는다', () => {
    expect(filterByPeriod(items, 'custom', { from: '어제', to: null }, NOW)).toHaveLength(5);
  });
});
