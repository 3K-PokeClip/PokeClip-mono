'use client';

import {
  createContext,
  Fragment,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { meQueryOptions } from '@/api/auth';
import { delegationsAsEditorQueryOptions, type StreamerDelegation } from '@/api/editors';
import { useMe } from '@/features/auth/useSession';
import { isListSeenWhenSelected, useChannelStore } from '@/stores/channel';
import { useToast } from '@/ui';
import { useSwitchCurtain } from './switchCurtain';
import { asEditorList, useActiveChannel } from './useActiveChannel';

// 독 화면들의 채널 범위(POK-390). 하는 일 넷:
// 1) 지금 채널이 서기 전에는 자식 대신 fallback(본문 스켈레톤)을 그린다: 홈·지난 방송 훅은 마운트 때 한 번만 목록을
//    부르므로, 새로고침 뒤 as-editor가 늦게 오면 내 번호로 먼저 불러 버린다(계획 검증 M4). 범위는 화면 그룹마다 본문만
//    감싼다(POK-442): 사이드바·홈 헤더는 범위 밖이라 me를 기다리지 않고 바로 선다. 그래서 채널이 바뀌어도 그 둘은 다시
//    서지 않는다(열린 계정 전환 창은 ChannelSwitcher가 스스로 닫는다).
// 2) 채널이 바뀌면 자식을 통째로 다시 세운다(key): 훅마다 「채널이 바뀌었나」를 보게 하지 않는다. 앞 채널의 늦은 응답은
//    각 훅의 정리 플래그가 버린다.
// 3) 위임 끊김의 부수효과(저장값 지우기·토스트·as-editor 다시 읽기)를 여기 한 자리에서 한다. useActiveChannel은 여러
//    자리에서 쓰여 거기 두면 쓰는 수만큼 돈다.
// 4) 방송 하나를 보던 화면(지난 방송 상세 · ?stream=)에서 바꾸면 홈으로: 그 방송은 앞 채널 것이다.

/** 내보내는 것은 화면 훅 시험이 번호를 직접 내려 주기 위해서다. 화면 코드는 ChannelScope만 쓴다. */
export const ChannelStreamerIdContext = createContext<number | null>(null);

/**
 * 지금 채널에서 나의 관계(POK-390 태스크 5). 남의 채널이면 연동·편집자·플러그인 같은 「채널 주인의 일」을 막는 데 쓴다.
 * ChannelScope 밖(시험·편집기)이거나 me를 못 읽었으면 null = 모름 → 막지 않는다. react-query 없이 읽게 컨텍스트로 둔다:
 * 홈 화면 시험은 QueryClient 없이 렌더한다(계획 검증 m9).
 */
export const ChannelRelationContext = createContext<'OWNER' | 'EDITOR' | null>(null);

export function useChannelRelation(): 'OWNER' | 'EDITOR' | null {
  return useContext(ChannelRelationContext);
}

/** 화면 훅이 목록에 실을 채널 번호. ChannelScope 밖(시험·편집기)이면 null = 섞어서(옛 호출). */
export function useChannelStreamerId(): number | null {
  return useContext(ChannelStreamerIdContext);
}

/** 방송 하나를 못 박은 화면인가(지난 방송 상세 · ?stream=). 그 방송은 앞 채널 것이라 채널을 바꾸면 홈으로 간다. */
function pinnedToBroadcast(pathname: string | null): boolean {
  if ((pathname ?? '').startsWith('/broadcast/vod/')) return true;
  // ?stream=은 useSearchParams 대신 주소창에서 읽는다: 레이아웃에서 그것을 쓰면 Suspense 경계가 필요하고,
  // 채널은 주소 이동 없이 바뀌므로 주소창이 지금 주소다.
  return typeof window !== 'undefined' && new URLSearchParams(window.location.search).has('stream');
}

export function ChannelScope({
  children,
  fallback = null,
}: {
  children: ReactNode;
  /** 채널이 서기 전·홈으로 떠나는 동안 자식 대신 그린다. 기본은 아무것도 안 그림 */
  fallback?: ReactNode;
}) {
  const active = useActiveChannel();
  const meQuery = useMe();
  const meFailed = meQuery.isError;
  const meId = meQuery.data?.id ?? null;
  const select = useChannelStore((s) => s.select);
  const goneSignal = useChannelStore((s) => s.goneSignal);
  const storeUserId = useChannelStore((s) => s.userId);
  const selected = useChannelStore((s) => s.selected);
  const selectedAt = useChannelStore((s) => s.selectedAt);
  const selectedAgainst = useChannelStore((s) => s.selectedAgainst);
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!active.ready || !active.gone) return;
    const goneId = useChannelStore.getState().selected;
    // 내 채널로 풀린 상태라 streamerId가 내 번호다. 지우면 gone이 꺼져 이 효과가 다시 안 돈다(토스트 한 번).
    select(active.streamerId, null);
    // 방금 스스로 나간 채널이면 경고하지 않는다: 나가기가 이미 「나왔어요」와 전환 화면으로 알렸다. 덮는 동안(내 채널로
    // 바꾸기 전) 그 채널 요청이 404를 받으면 여기로 온다(PR #342 리뷰 4)
    if (goneId !== null && useSwitchCurtain.getState().curtain?.leftStreamerId === goneId) return;
    toast({
      tone: 'warning',
      title: '편집 권한이 없어져 내 채널로 돌아왔어요',
      dedupeKey: 'channel-gone',
    });
  }, [active.ready, active.gone, active.streamerId, select, toast]);

  // 목록 함수가 남긴 404 신호: 정말 끊겼는지는 as-editor를 다시 읽어 위 효과가 가린다. 첫 값은 지난 신호라 건너뛴다.
  const seenSignal = useRef(goneSignal);
  // 내 채널 404로 me를 다시 읽은 회원 번호. 같은 me로 또 404면 더 읽지 않는다: 홈·라이브의 갱신 주기마다 me를 두드리지 않게
  const meRecheckedFor = useRef<number | null>(null);
  useEffect(() => {
    if (seenSignal.current === goneSignal) return;
    seenSignal.current = goneSignal;
    const goneFor = useChannelStore.getState().goneFor;
    if (goneFor !== null && goneFor === meId) {
      // 내 채널이 404면 위임 끊김이 아니다. 이 탭이 쥔 me가 토큰과 어긋났다(다른 탭에서 다른 계정으로 로그인했는데 이 탭이
      // 신호를 못 받음). me를 다시 읽으면 다른 계정일 때 hydrate가 그 계정으로 다시 세운다. 한 번만 읽는다
      if (meRecheckedFor.current === meId) return;
      meRecheckedFor.current = meId;
      void queryClient.invalidateQueries({ queryKey: meQueryOptions.queryKey });
      return;
    }
    void queryClient.invalidateQueries({ queryKey: ['editor-delegations'] });
  }, [goneSignal, meId, queryClient]);

  // 고른 채널이 이미 받아 둔 as-editor에 없고 그 목록이 고르기 전 것이면 새로 읽힌다(감사 2회차 지적 1). 다른 탭이 방금
  // 수락하고 고른 채널은 이 탭의 낡은 목록에 아직 없다: 그것으로 끊김을 단정하면 저장값을 지워 고른 탭까지 되돌린다.
  // 기다리는 동안 useActiveChannel은 준비 안 됨이다.
  useEffect(() => {
    if (selected === null || selected === storeUserId) return;
    const state = queryClient.getQueryState<StreamerDelegation[]>(
      delegationsAsEditorQueryOptions.queryKey,
    );
    // 고를 때 본 목록이 아니면(그때 목록이 없었거나, 그 뒤 새로 받았거나, 쿼리가 지워졌다 다시 생겼으면) 기다릴 것이 없다.
    // 시각이 아니라 쿼리 객체와 갱신 횟수로 본다
    if (state?.data === undefined) return;
    if (!isListSeenWhenSelected(selectedAgainst, asEditorList(queryClient))) return;
    if (state.data.some((d) => d.streamerId === selected)) return;
    void queryClient.refetchQueries({ queryKey: delegationsAsEditorQueryOptions.queryKey });
  }, [selected, selectedAt, selectedAgainst, storeUserId, queryClient]);

  // 방송을 못 박은 화면에서 채널이 바뀌면 홈으로 가고, 가는 동안 자식을 새 채널로 세우지 않는다(감사 2회차 지적 3):
  // 세우면 앞 채널의 방송을 새 채널 이름 아래에서 한 번 더 읽는다. 렌더 중에 가른다: 효과에서 가르면 이미 섰다.
  // 앞 번호는 준비 안 됨(null)을 건너 기억한다: 다른 탭 선택은 7 → 준비 안 됨 → 3으로 온다.
  const streamerId = active.ready ? active.streamerId : null;
  const pinned = pinnedToBroadcast(pathname);
  const [shownId, setShownId] = useState<number | null>(streamerId);
  const [leaving, setLeaving] = useState(false);
  if (streamerId !== null && streamerId !== shownId) {
    setShownId(streamerId);
    if (shownId !== null && pinned) setLeaving(true);
  }
  if (leaving && !pinned) setLeaving(false);
  useEffect(() => {
    if (leaving) router.replace('/home');
  }, [leaving, router]);

  // 나를 못 읽으면 채널을 정할 수 없다: 화면을 통째로 막지 않고 번호 없이(섞어서) 그린다(이 카드 전과 같은 동작).
  if (!active.ready && meFailed) {
    return (
      <ChannelStreamerIdContext.Provider value={null}>{children}</ChannelStreamerIdContext.Provider>
    );
  }
  if (!active.ready || leaving) return <>{fallback}</>;
  return (
    <ChannelStreamerIdContext.Provider value={active.streamerId}>
      <ChannelRelationContext.Provider value={active.relation}>
        <Fragment key={active.streamerId}>{children}</Fragment>
      </ChannelRelationContext.Provider>
    </ChannelStreamerIdContext.Provider>
  );
}
