'use client';

import { useQuery } from '@tanstack/react-query';
import { fetchBroadcast, type LibraryEntry } from '@/api/clipEditor';
import { useBroadcastTimelineQuery } from '@/features/broadcast/livenow/useBroadcastTimeline';
import { clipSourceOf, sceneBaseMs, type ClipSource } from './libraryView';

// 상세 패널 원본 방송 카드(POK-511)의 재료. 보관함 목록에는 방송 제목 · 카테고리가 없어(서버 변경 없이 가기로 했다) 패널을 열 때
// 두 창구를 읽는다: 방송 한 줄(제목 · 카테고리 · 플랫폼 · 기준점)과 그 방송의 시점별 기록(장면 때의 제목 · 카테고리 · 포스터).
// 못 읽으면 지어내지 않고 비운다 — 카드는 「10월 17일 방송」으로 물러서고 패널의 나머지는 그대로 동작한다.

/** 방송 중인 방송의 줄을 다시 읽는 간격(대표 플랫폼 · 기준점이 바뀔 수 있다). 끝난 방송의 줄은 안 바뀌어 다시 안 읽는다 */
const LIVE_ROW_MS = 5 * 60_000;

export function useClipSource(
  entry: LibraryEntry | undefined,
  /**
   * 패널이 열려 있는가. 닫힌 패널은 내용만 남아 있어 읽지 않는다 — 그 편집본은 갱신도 안 되어 방송이 끝나도 「방송 중」으로 남으니, 안
   * 막으면 재조회가 영영 돈다. 다시 열면 새로 읽는다
   */
  active = true,
): {
  /** 목업 줄(서버 줄이 없다)이거나 첫 답을 기다리는 중이면 null */
  source: ClipSource | null;
  /** 장면 위치를 재는 기준점(sceneBaseMs). 방송 줄에만 달려 있어 시점별 기록을 기다리지 않는다 */
  baseMs: number | null;
  /** 방송 줄의 첫 답을 기다리는 중 — 그동안 장면 위치 자리는 자리표시다 */
  basePending: boolean;
  /** 첫 답을 기다리는 중 — 그동안 제목 · 카테고리 자리는 자리표시다(대신 쓴 값이 바뀌며 튀지 않게) */
  pending: boolean;
} {
  const streamId = entry?.streamId ?? '';
  const live = entry?.broadcast.status === 'live';

  // 방송 번호로 그 한 줄만 찾는다(찾으면 목록 넘기기를 멈추고, 다른 편집본으로 옮기면 취소한다). 방송이 끝나면 끝난 줄을 한 번 더 읽고,
  // 그 답이 올 때까지 같은 방송의 옛 줄을 둔다: 그사이 비면 보이던 값이 사라졌다가 돌아온다
  const row = useQuery({
    queryKey: ['library-broadcast-row', streamId, live],
    queryFn: ({ signal }) => fetchBroadcast(streamId, signal),
    enabled: active && streamId !== '',
    staleTime: live ? LIVE_ROW_MS : Infinity,
    refetchInterval: live ? LIVE_ROW_MS : false,
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[1] === streamId ? previous : undefined,
  });
  // 방송 줄과 나란히 읽는다(줄을 기다렸다 읽으면 카드가 두 번 늦다). 정보 바와 같은 기록이다: 방송 중이면 주기적으로 다시 읽어, 패널을
  // 연 채 뒤에 만든 편집본을 골라도 옛 끝 구간의 제목 · 카테고리를 쓰지 않고, 끝나면 끝까지 담긴 기록을 한 번 더 읽는다
  const timeline = useBroadcastTimelineQuery(streamId, live ? 'live' : 'ended', active);

  if (entry === undefined)
    return { source: null, baseMs: null, basePending: false, pending: false };
  const broadcastRow = row.data ?? null;
  const base = { baseMs: sceneBaseMs(broadcastRow), basePending: row.isPending };
  // 둘 다 올 때까지 값을 내지 않는다: 방송 줄이 없으면 대표 플랫폼을 몰라, 동시 송출 방송에서 다른 플랫폼 값이 잠깐 떴다가 바뀐다
  if (row.isPending || timeline.isPending) return { source: null, ...base, pending: true };
  // 구간 목록이 없는 답은 못 읽은 것으로 본다 — 그대로 넘기면 구간을 고르다 패널이 통째로 깨진다
  const segmented = Array.isArray(timeline.data?.segments) ? timeline.data : undefined;
  return { source: clipSourceOf(entry, broadcastRow, segmented), ...base, pending: false };
}
