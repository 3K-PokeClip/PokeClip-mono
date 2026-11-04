'use client';

import { useQuery, type QueryClient } from '@tanstack/react-query';
import { fetchBroadcastTimeline, type BroadcastTimeline } from '@/api/clipEditor';
import type { LiveBroadcastStatus } from './liveDataStore';

/** 방송 중 시점별 기록을 다시 읽는 간격. 카테고리를 바꾸면 1분 안에 그림이 바뀐다(POK-467 완료 조건) */
export const TIMELINE_LIVE_MS = 30_000;

/**
 * 기록에 카테고리 사진이 아직 없을 때 다시 읽는 간격(POK-552). 방송을 켜면 수집기의 첫 관측까지 사진이 없는데, 30초 주기면 관측이
 * 기록에 적힌 뒤에도 다음 차례까지 최대 30초를 더 기다렸다. 통로(SSE)가 끊겨 있을 때도 첫 사진이 늦지 않게 짧게 묻는다
 */
export const TIMELINE_FIRST_IMAGE_MS = 5_000;

/**
 * 그 짧은 간격으로 읽는 최대 횟수(5초 × 36 = 3분). 사진을 끝내 못 받는 방송이 있다: 치지직 목록에서 못 찾은 0명 방송은 관측 자체가
 * 없고, 설정이 거부된 치지직 회차 · 이름이 같은 치지직 카테고리가 없는 SOOP은 사진 칸이 빈다. 이런 방송이 5초로 계속 묻지 않게 한다
 */
export const TIMELINE_FIRST_IMAGE_TRIES = 36;

/** 기록에 카테고리 사진이 하나라도 있나. 기록을 못 읽었으면(undefined) 없다 */
export function hasCategoryImage(timeline: BroadcastTimeline | undefined): boolean {
  return timeline?.segments.some((s) => s.categoryImageUrl !== null) ?? false;
}

/**
 * 방송 중 기록을 다시 읽는 간격. 첫 사진 전에는 TIMELINE_FIRST_IMAGE_MS, 사진이 생기거나 짧은 간격을 tries번 다 쓰면 TIMELINE_LIVE_MS다.
 * tries는 그 기록을 읽은 횟수(성공 + 실패)다
 */
export function liveTimelineIntervalMs(timeline: BroadcastTimeline | undefined, tries: number) {
  return hasCategoryImage(timeline) || tries >= TIMELINE_FIRST_IMAGE_TRIES
    ? TIMELINE_LIVE_MS
    : TIMELINE_FIRST_IMAGE_MS;
}

const timelineKey = (streamId: string, status: LiveBroadcastStatus) =>
  ['broadcast-timeline', streamId, status] as const;

/**
 * 통로(SSE broadcast-info)가 카테고리 사진을 싣고 왔는데 방송 중 기록에 아직 사진이 없으면 기록을 바로 다시 읽는다(POK-552). 수집기는
 * 관측을 기록에 적은 뒤 통로로 밀어서(치지직 · SOOP 모두), 이때 읽으면 사진이 있다. 첫 사진만 앞당긴다: 기록에 사진이 생긴 뒤 바뀐
 * 카테고리는 지금처럼 30초 주기로 따라온다. 사진을 안 실은 관측이면 다시 읽어도 사진이 없으니 그대로 둔다
 */
export function refetchTimelineForFirstImage(
  queryClient: QueryClient,
  streamId: string,
  categoryImageUrl: string | null | undefined,
): void {
  if (!categoryImageUrl || streamId === '') return;
  const queryKey = timelineKey(streamId, 'live');
  if (hasCategoryImage(queryClient.getQueryData<BroadcastTimeline>(queryKey))) return;
  // 읽는 중이면 그 읽기를 버리고 새로 읽는다. 그냥 다시 읽기만 걸면 첫 답을 기다리는 쿼리는 이미 떠난 읽기를 그대로 돌려준다
  // (react-query는 데이터가 있을 때만 떠난 읽기를 끊는다). 그 읽기는 관측이 적히기 전에 떠났을 수 있어 사진이 없다
  void queryClient
    .cancelQueries({ queryKey, exact: true })
    .then(() => queryClient.invalidateQueries({ queryKey, exact: true }));
}

/**
 * 방송 하나의 시점별 기록(POK-462 구간 창구). 정보 바의 카테고리 사진(POK-467)과 보는 시점의 제목 · 카테고리 · 태그(POK-462)가 같은
 * 기록을 쓴다: 같은 열쇠라 요청은 한 번이다. 방송 중이면 카테고리를 바꾼 것이 1분 안에 보이게 30초마다 다시 읽고(첫 카테고리 사진
 * 전에는 5초, POK-552), 끝난 방송은 한 번 읽는다(방송 중에서 끝으로 바뀌면 끝까지 담긴 기록을 한 번 더). 못 읽으면 undefined다.
 * 상태를 아는 뒤에만 읽고 상태마다 따로 둔다: 모를 때 읽은 기록을 끝난 방송과 함께 두면 화면을 열 때 읽은 옛 기록이 끝난 뒤 다시
 * 쓰였다(POK-467 로컬 리뷰 3라운드)
 */
export function useBroadcastTimeline(
  streamId: string,
  status: LiveBroadcastStatus,
): BroadcastTimeline | undefined {
  return useBroadcastTimelineQuery(streamId, status).data;
}

/**
 * 같은 기록의 쿼리 전체 — 첫 답을 기다리는 중인지까지 봐야 하는 화면(보관함 원본 방송 카드)이 쓴다. active가 거짓이면 읽지 않는다.
 * 방송이 끝나 열쇠가 바뀌는 동안은 같은 방송의 옛 기록을 그대로 둔다: 그사이 비면 보이던 값이 사라졌다가 돌아온다
 */
export function useBroadcastTimelineQuery(
  streamId: string,
  status: LiveBroadcastStatus,
  active = true,
) {
  const live = status === 'live';
  return useQuery({
    queryKey: timelineKey(streamId, status),
    queryFn: () => fetchBroadcastTimeline(streamId),
    enabled: active && streamId !== '' && (live || status === 'ended'),
    staleTime: live ? TIMELINE_LIVE_MS : Infinity,
    // 간격은 읽을 때마다 다시 정해진다(react-query가 쿼리가 바뀔 때마다 타이머를 다시 건다): 첫 사진이 오는 순간 30초로 돌아간다
    refetchInterval: live
      ? (query) =>
          liveTimelineIntervalMs(
            query.state.data,
            query.state.dataUpdateCount + query.state.errorUpdateCount,
          )
      : false,
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[1] === streamId ? previous : undefined,
  });
}
