'use client';

import { useQuery } from '@tanstack/react-query';
import { fetchBroadcast, type LibraryEntry } from '@/api/clipEditor';
import { useBroadcastTimelineQuery } from '@/features/broadcast/livenow/useBroadcastTimeline';
import { clipSourceOf, type ClipSource } from './libraryView';

// 상세 패널 원본 방송 카드(POK-511)의 재료. 보관함 목록에는 방송 제목 · 카테고리가 없어(서버 변경 없이 가기로 했다) 패널을 열 때
// 두 창구를 읽는다: 방송 한 줄(제목 · 카테고리 · 플랫폼 · 기준점)과 그 방송의 시점별 기록(장면 때의 제목 · 카테고리 · 포스터).
// 못 읽으면 지어내지 않고 비운다 — 카드는 「10월 17일 방송」으로 물러서고 패널의 나머지는 그대로 동작한다.

/** 방송 중인 방송의 줄을 다시 읽는 간격. 제목 · 카테고리는 시점별 기록이 말하고, 끝난 방송의 줄은 안 바뀌어 다시 안 읽는다 */
const LIVE_ROW_STALE_MS = 5 * 60_000;

export function useClipSource(entry: LibraryEntry | undefined): {
  /** 목업 줄(서버 줄이 없다)이거나 첫 답을 기다리는 중이면 null */
  source: ClipSource | null;
  /** 첫 답을 기다리는 중 — 그동안 제목 · 카테고리 자리는 자리표시다(대신 쓴 값이 바뀌며 튀지 않게) */
  pending: boolean;
} {
  const streamId = entry?.streamId ?? '';
  const live = entry?.broadcast.status === 'live';

  // 방송 번호로 그 한 줄만 찾는다(찾으면 목록 넘기기를 멈춘다). 방송 중에서 끝으로 바뀌면 끝난 줄을 한 번 더 읽는다
  const row = useQuery({
    queryKey: ['library-broadcast-row', streamId, live],
    queryFn: () => fetchBroadcast(streamId),
    enabled: streamId !== '',
    staleTime: live ? LIVE_ROW_STALE_MS : Infinity,
  });
  // 방송 줄과 나란히 읽는다(줄을 기다렸다 읽으면 카드가 두 번 늦다). 정보 바와 같은 기록이다: 방송 중이면 주기적으로 다시 읽어, 패널을
  // 연 채 뒤에 만든 편집본을 골라도 옛 끝 구간의 제목 · 카테고리를 쓰지 않고, 끝나면 끝까지 담긴 기록을 한 번 더 읽는다
  const timeline = useBroadcastTimelineQuery(streamId, live ? 'live' : 'ended');

  if (entry === undefined) return { source: null, pending: false };
  // 둘 다 올 때까지 값을 내지 않는다: 방송 줄이 없으면 대표 플랫폼을 몰라, 동시 송출 방송에서 다른 플랫폼 값이 잠깐 떴다가 바뀐다
  if (row.isPending || timeline.isPending) return { source: null, pending: true };
  // 구간 목록이 없는 답은 못 읽은 것으로 본다 — 그대로 넘기면 구간을 고르다 패널이 통째로 깨진다
  const segmented = Array.isArray(timeline.data?.segments) ? timeline.data : undefined;
  return { source: clipSourceOf(entry, row.data ?? null, segmented), pending: false };
}
