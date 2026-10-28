'use client';

import { useQuery } from '@tanstack/react-query';
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

/** 지금 보는 방송의 채팅 · 통계 잠금. 연동 조회는 방송 정보(useLiveMockState)와 같은 쿼리라 요청이 늘지 않는다 */
export function useLinkLock(status: BroadcastStatus): LinkLock {
  const { relation } = useLiveData();
  const { data: chzzk } = useQuery(chzzkLinkQueryOptions);
  const { data: soop } = useQuery(soopLinkQueryOptions);
  return linkLockOf({ status, relation, chzzk, soop });
}
