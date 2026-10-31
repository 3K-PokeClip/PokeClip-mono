import type { QueryClient } from '@tanstack/react-query';

// 홈과 지난 방송 목록은 방송별 카드 수를 쿼리 데이터에 기억한다(끝난 방송을 주기마다 다시 세지 않으려고). 그래서 카드를 만들거나 가린
// 뒤에는 그 방송의 기억을 지워야 한다: 안 지우면 끝난 지 오래된 방송의 숫자가 기억 기한(5~10분)까지 옛 값으로 남는다.
// 두 화면의 모듈을 가져오면 라이브 화면이 그 코드까지 받으므로 쿼리 데이터의 필요한 칸만 여기서 본다.

interface VodListMemory {
  memory: Record<string, { cardCount: number | null }>;
}

interface HomeMemory {
  memory: { cardCounts: Record<string, unknown> };
}

/** 이 방송의 카드 수 기억을 지우고 두 목록을 낡은 것으로 표시한다. 다음에 들어오거나 다음 주기에 다시 센다 */
export function forgetCardCount(queryClient: QueryClient, streamId: string): void {
  queryClient.setQueriesData<VodListMemory>({ queryKey: ['vod-list'] }, (data) => {
    const known = data?.memory[streamId];
    if (data === undefined || known === undefined) return data;
    return {
      ...data,
      memory: { ...data.memory, [streamId]: { ...known, cardCount: null } },
    };
  });
  queryClient.setQueriesData<HomeMemory>({ queryKey: ['home'] }, (data) => {
    if (data === undefined || data.memory.cardCounts[streamId] === undefined) return data;
    const { [streamId]: _forgotten, ...cardCounts } = data.memory.cardCounts;
    return { ...data, memory: { ...data.memory, cardCounts } };
  });
  void queryClient.invalidateQueries({ queryKey: ['vod-list'], refetchType: 'none' });
  void queryClient.invalidateQueries({ queryKey: ['home'], refetchType: 'none' });
}
