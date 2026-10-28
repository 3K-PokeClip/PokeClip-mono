'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { chzzkLinkQueryOptions, type ChzzkLinkState } from '@/api/chzzkLink';
import { soopLinkQueryOptions, type SoopLinkState } from '@/api/soopLink';
import { useLiveData } from './liveDataStore';
import type { BroadcastStatus } from './useBroadcastClock';

// 방송은 들어오는데 방송 채널(치지직 · SOOP)을 하나도 연동하지 않았으면 채팅 · 통계는 올 곳이 없다: 수집기가 연동에서 채널을 찾아
// 채팅 · 시청자 수를 걷는다(chat-collector ChzzkLinkClient · SoopLinkClient). 빈 패널에 「수집 중」을 띄우는 대신 잠그고 채널 연동
// 화면으로 가는 길을 준다.

/** 잠근 까닭: 연동이 없다(한 적 없음 · 해제) · 연동이 끊겼다(BROKEN, 다시 연결해야 한다). 잠그지 않으면 null */
export type LinkLock = 'unlinked' | 'broken' | null;

/**
 * 잠금이 빈칸에 쓰는 말. 채팅 배지와 정보 바 · 영상 위의 빈칸(「제목 연동 필요」)이 같은 말을 쓴다: 연동이 없으면 제목 · 카테고리 ·
 * 태그 · 시청자 수도 수집기가 걷는 값이라 오지 않는다. 「수집 전」이라 하면 기다리면 올 것처럼 읽힌다
 */
export const LINK_LOCK_WORD: Record<Exclude<LinkLock, null>, string> = {
  unlinked: '연동 필요',
  broken: '연동 끊김',
};

/**
 * 채팅 · 통계를 잠글지 고른다. 모르면 잠그지 않는다: 멀쩡히 걷히는 방송을 가리는 쪽이 빈 패널보다 나쁘다.
 * - 방송 중일 때만이다. 끝난 방송은 그때 걷힌 기록이 남아 있을 수 있다(그 뒤에 해제했어도)
 * - 내 방송일 때만이다. 연동 조회(/api/chzzk-link · /api/soop-link)는 보는 사람 자신의 연동을 답한다. 위임받은 방송의 스트리머
 *   연동은 방송 목록의 채널 이름(POK-430)으로만 비치는데, 서버가 이름을 못 물었을 때도 두 칸 null로 와 「연동 없음」과 가를 수 없다
 * - 두 조회가 다 답해야 한다. 하나라도 연동(linked: ACTIVE · EXPIRED)이면 잠그지 않는다. 수집기와 같은 기준이다: 만료는 auth가
 *   갱신해 걷고, 없음 · 해제 · BROKEN은 영구 거절이라 못 걷는다
 */
export function linkLockOf({
  status,
  relation,
  chzzk,
  soop,
}: {
  status: BroadcastStatus;
  relation: string | null;
  chzzk: ChzzkLinkState | undefined;
  soop: SoopLinkState | undefined;
}): LinkLock {
  if (status !== 'live' || relation !== 'OWNER') return null;
  if (chzzk === undefined || soop === undefined) return null;
  if (chzzk.linked || soop.linked) return null;
  return chzzk.status === 'BROKEN' || soop.status === 'BROKEN' ? 'broken' : 'unlinked';
}

/**
 * 잠긴 동안 두 연동을 다시 묻는 간격. 다른 탭 · 다른 기기에서 연동하고 돌아오면 이 탭의 캐시는 그대로다: 연동 콜백이 쿼리를 지우는
 * 것은 그 탭의 캐시뿐이고, 전역 staleTime(60초)만으로는 탭에 머무는 동안 다시 묻지 않아 연동했는데도 잠긴 채였다(PR #453 리뷰).
 * 편집자 목록 자동 갱신(POK-481)과 같은 간격이다. 숨은 탭에서는 묻지 않는다(refetchIntervalInBackground 기본값)
 */
export const LINK_RECHECK_MS = 20_000;

/**
 * 지금 보는 방송의 채팅 · 통계 잠금. 방송 상태 · 관계는 저장소의 것이다(방송 시계가 올린다): 방송 정보를 읽는 훅(useLiveMockState)도
 * 같은 판정으로 차트 읽기를 멈추므로 화면의 시계 값을 따로 넘기지 않는다. 연동 조회는 그 훅과 같은 쿼리라 요청이 늘지 않는다
 */
export function useLinkLock(): LinkLock {
  const { status, relation } = useLiveData();
  const queryClient = useQueryClient();
  // 주기는 두 연동을 같이 보고 정한다: 한 쿼리의 주기를 그 쿼리 값만으로 정하면 다른 쪽이 연동돼 잠금이 풀린 뒤에도 미연동 쪽을
  // 계속 묻는다. 옵션은 그릴 때마다 다시 넘어가므로 다른 쪽 답이 오면 다음 그리기에서 주기가 맞춰진다
  const recheck = () =>
    linkLockOf({
      status,
      relation,
      chzzk: queryClient.getQueryData<ChzzkLinkState>(chzzkLinkQueryOptions.queryKey),
      soop: queryClient.getQueryData<SoopLinkState>(soopLinkQueryOptions.queryKey),
    }) === null
      ? false
      : LINK_RECHECK_MS;
  const { data: chzzk } = useQuery({ ...chzzkLinkQueryOptions, refetchInterval: recheck });
  const { data: soop } = useQuery({ ...soopLinkQueryOptions, refetchInterval: recheck });
  return linkLockOf({ status, relation, chzzk, soop });
}
