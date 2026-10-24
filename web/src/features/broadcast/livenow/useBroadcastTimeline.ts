'use client';

import { useQuery } from '@tanstack/react-query';
import { fetchBroadcastTimeline, type BroadcastTimeline } from '@/api/clipEditor';
import type { LiveBroadcastStatus } from './liveDataStore';

/** 방송 중 시점별 기록을 다시 읽는 간격. 카테고리를 바꾸면 1분 안에 그림이 바뀐다(POK-467 완료 조건) */
const TIMELINE_LIVE_MS = 30_000;

/**
 * 방송 하나의 시점별 기록(POK-462 구간 창구). 정보 바의 카테고리 사진(POK-467)과 보는 시점의 제목 · 카테고리 · 태그(POK-462)가 같은
 * 기록을 쓴다: 같은 열쇠라 요청은 한 번이다. 방송 중이면 카테고리를 바꾼 것이 1분 안에 보이게 30초마다 다시 읽고, 끝난 방송은 한 번
 * 읽는다(방송 중에서 끝으로 바뀌면 끝까지 담긴 기록을 한 번 더). 못 읽으면 undefined다.
 * 상태를 아는 뒤에만 읽고 상태마다 따로 둔다: 모를 때 읽은 기록을 끝난 방송과 함께 두면 화면을 열 때 읽은 옛 기록이 끝난 뒤 다시
 * 쓰였다(POK-467 로컬 리뷰 3라운드)
 */
export function useBroadcastTimeline(
  streamId: string,
  status: LiveBroadcastStatus,
): BroadcastTimeline | undefined {
  const live = status === 'live';
  return useQuery({
    queryKey: ['broadcast-timeline', streamId, status],
    queryFn: () => fetchBroadcastTimeline(streamId),
    enabled: streamId !== '' && (live || status === 'ended'),
    staleTime: live ? TIMELINE_LIVE_MS : Infinity,
    refetchInterval: live ? TIMELINE_LIVE_MS : false,
  }).data;
}
