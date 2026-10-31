import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { vodListQueryOptions } from '@/features/broadcast/vod/useVodListMockState';
import { homeQueryOptions } from '@/features/home/useHomeMockState';
import { forgetCardCount } from './cardCountMemory';

// 키가 어긋나면 지우기가 조용히 빗나간다: 두 화면의 진짜 쿼리 키로 시험한다

const row = { cardCount: 7, countedAt: 1, recorded: true, missing: false };

function seeded() {
  const client = new QueryClient();
  const vodKey = vodListQueryOptions(3).queryKey;
  const homeKey = homeQueryOptions(3).queryKey;
  client.setQueryData(vodKey, {
    rows: [],
    visuals: {},
    memory: { target: row, other: row },
  } as never);
  client.setQueryData(homeKey, {
    memory: {
      pastAll: [],
      pastReadAt: 0,
      liveIds: [],
      cardCounts: { target: { count: 7, at: 1 }, other: { count: 2, at: 1 } },
    },
  } as never);
  return { client, vodKey, homeKey };
}

describe('forgetCardCount', () => {
  it('그 방송의 기억만 지운다: 지난 방송 목록은 다시 세는 값으로, 홈은 칸을 없앤다', () => {
    const { client, vodKey, homeKey } = seeded();

    forgetCardCount(client, 'target');

    const vod = client.getQueryData(vodKey) as unknown as {
      memory: Record<string, { cardCount: number | null; recorded: boolean }>;
    };
    expect(vod.memory.target).toMatchObject({ cardCount: null, recorded: true });
    expect(vod.memory.other?.cardCount).toBe(7);
    const home = client.getQueryData(homeKey) as unknown as {
      memory: { cardCounts: Record<string, unknown> };
    };
    expect(home.memory.cardCounts.target).toBeUndefined();
    expect(home.memory.cardCounts.other).toEqual({ count: 2, at: 1 });
  });

  it('두 목록을 낡은 것으로 표시한다: staleTime 안에 돌아와도 다시 읽는다', () => {
    const { client, vodKey, homeKey } = seeded();

    forgetCardCount(client, 'target');

    expect(client.getQueryState(vodKey)?.isInvalidated).toBe(true);
    expect(client.getQueryState(homeKey)?.isInvalidated).toBe(true);
  });

  it('기억에 없는 방송이어도 터지지 않는다', () => {
    const client = new QueryClient();

    expect(() => forgetCardCount(client, 'nothing')).not.toThrow();
  });
});
